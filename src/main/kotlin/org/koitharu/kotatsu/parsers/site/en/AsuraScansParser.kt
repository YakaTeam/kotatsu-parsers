package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import java.time.Instant
import java.util.*
import kotlin.collections.emptySet

@MangaSourceParser("ASURASCANS", "AsuraScans", "en")
internal class AsuraScansParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ASURASCANS, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("asurascans.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getOrCreateTagMap().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/browse?page=")
			append(page)

			filter.query?.let {
				append("&q=")
				append(filter.query.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&genres=")
				append(filter.tags.joinToString(separator = ",") { it.key })
			}

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "dropped"
						MangaState.PAUSED -> "hiatus"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			filter.types.oneOrThrowIfMany()?.let {
				append("&types=")
				append(
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> ""
					},
				)
			}

			if (!filter.author.isNullOrEmpty()) {
				append("&author=")
				append(filter.author.urlEncoded())
			}

			append(
				when (order) {
					SortOrder.UPDATED_ASC -> "&order=asc"
					SortOrder.POPULARITY -> "&sort=popular"
					SortOrder.POPULARITY_ASC -> "&sort=popular&order=asc"
					SortOrder.RATING -> "&sort=rating"
					SortOrder.RATING_ASC -> "&sort=rating&order=asc"
					SortOrder.ALPHABETICAL_DESC -> "&sort=name"
					SortOrder.ALPHABETICAL -> "&sort=name&order=asc"
					SortOrder.NEWEST -> "&sort=newest"
					SortOrder.NEWEST_ASC -> "&sort=newest&order=asc"
					else -> "" // SortOrder.UPDATED is the site default
				},
			)
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div#series-grid > div.series-card").mapNotNull  { card ->
			val a = card.selectFirst("a[href]") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = a.selectFirst("img")?.src(),
				title = card.selectFirst("h3")?.text().orEmpty(),
				altTitles = emptySet(),
				rating = a.selectFirst("div > span")?.text()?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = when (card.selectLast("span.capitalize")?.text()?.lowercase()) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					"dropped" -> MangaState.ABANDONED
					else -> null
				},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	private var tagCache: ArrayMap<String, MangaTag>? = null
	private val mutex = Mutex()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val tagMap = ArrayMap<String, MangaTag>()
		val json =
			webClient.httpGet("https://api.$domain/api/genres").parseJson().getJSONArray("data")
				.asTypedList<JSONObject>()
		for (el in json) {
			if (el.getString("name").isEmpty()) continue
			tagMap[el.getString("name")] = MangaTag(
				key = el.getString("slug"),
				title = el.getString("name"),
				source = source,
			)
		}
		tagCache = tagMap
		tagMap
	}

	// Need refactor
	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val props = doc.selectFirst("astro-island[component-url*='DescriptionModal']")
			?.attr("props")?.let { JSONObject(it) }

		val tagMap = getOrCreateTagMap()
		val tags = props?.optJSONArray("genres")?.optJSONArray(1)?.let { arr ->
			(0 until arr.length()).mapNotNull { i ->
				tagMap[arr.getJSONArray(i).getJSONObject(1).str("name")]
			}.toSet()
		} ?: emptySet()

		val islandProps = doc.selectFirst("astro-island[component-url*='ChapterList']")
			?.attr("props").orEmpty()
		val chapterDates = if (islandProps.isEmpty()) emptyMap() else {
			val chapters = JSONObject(islandProps).getJSONArray("chapters").getJSONArray(1)
			(0 until chapters.length()).associate { i ->
				val obj = chapters.getJSONArray(i).getJSONObject(1)
				obj.getJSONArray("number").get(1).toString() to
					Instant.parse(obj.getJSONArray("published_at").getString(1)).toEpochMilli()
			}
		}

		return manga.copy(
			description = doc.getElementById("description-text")?.text().orEmpty(),
			tags = tags,
			authors = props?.str("author")?.takeIf { it.isNotEmpty() }
				?.let { setOf(it) } ?: emptySet(),
			state = when (props?.str("status")) {
				"completed" -> MangaState.FINISHED
				"hiatus"    -> MangaState.PAUSED
				"dropped"   -> MangaState.ABANDONED
				else        -> MangaState.ONGOING
			},
			rating = props?.dbl("rating")?.toFloat()?.div(10f) ?: RATING_UNKNOWN,
			chapters = doc.select("div.divide-y > a[href*='/chapter/']").mapChapters(reversed = true) { i, a ->
				val urlRelative = a.attr("href")
				val urlParts = urlRelative.split("/chapter/")
				val chapterNum = urlParts.lastOrNull().orEmpty()
				val slug = urlParts.firstOrNull()
					?.substringAfter("/comics/")?.substringBeforeLast("-").orEmpty()
				val stableUrl = if (slug.isNotEmpty() && chapterNum.isNotEmpty())
					"/comics/$slug/chapter/$chapterNum"
				else throw ParseException("Can't find valid url for chapter", urlRelative)

				MangaChapter(
					id = generateUid(stableUrl),
					title = a.selectFirst("span.block")?.text()?.takeIf { it.isNotEmpty() }
						?: a.selectFirst("span.font-medium")?.text()?.takeIf { it.isNotEmpty() },
					number = i + 1f,
					volume = 0,
					url = urlRelative.toAbsoluteUrl(domain),
					scanlator = null,
					uploadDate = chapterDates[chapterNum] ?: 0L,
					branch = null,
					source = source,
				)
			},
		)
	}

	// Need refactor
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val propsRaw = doc.selectFirst("astro-island[component-url*='ChapterReader']")
			?.attr("props")
			?: throw ParseException("Could not find astro-island props", chapter.url)

		val props = JSONObject(propsRaw)

		val pagesOuter = props.getJSONArray("pages")
		val pagesArray = pagesOuter.getJSONArray(1)

		return (0 until pagesArray.length()).mapNotNull { i ->
			val entry = pagesArray.getJSONArray(i)
			val obj = entry.getJSONObject(1)
			val url = obj.getJSONArray("url").getString(1)

			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	/* Helpers */
	private fun JSONObject.str(key: String) = optJSONArray(key)?.optString(1).orEmpty()
	private fun JSONObject.dbl(key: String): Double? = optJSONArray(key)?.optDouble(1)
}
