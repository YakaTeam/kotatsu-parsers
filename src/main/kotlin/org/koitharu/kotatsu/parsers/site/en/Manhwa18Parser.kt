package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.ArrayMap
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.ParseException
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWA18", "Manhwa18.net", "en", type = ContentType.HENTAI)
internal class Manhwa18Parser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWA18, pageSize = 18, searchPageSize = 18) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("manhwa18.net")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.ALPHABETICAL,
			SortOrder.NEWEST,
			SortOrder.RATING,
		)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsMap.get().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://$domain/uploads/logos/logo-mini.png", 92, null),
			),
			domain,
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem?page=")
			append(page.toString())

			filter.query?.let {
				append("&q=")
				append(it.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&accept_genres=")
				append(filter.tags.joinToString(",") { it.key })
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&reject_genres=")
				append(filter.tagsExclude.joinToString(",") { it.key })
			}

			append("&sort=")
			append(
				when (order) {
					SortOrder.ALPHABETICAL -> "az"
					SortOrder.ALPHABETICAL_DESC -> "za"
					SortOrder.POPULARITY -> "top"
					SortOrder.UPDATED -> "update"
					SortOrder.NEWEST -> "new"
					SortOrder.RATING -> "like"
					else -> "update"
				},
			)

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "1"
						MangaState.FINISHED -> "3"
						MangaState.PAUSED -> "2"
						else -> ""
					},
				)
			}
		}

		val page = webClient.httpGet(url).parseHtml().inertiaPage()
		val mangas = page.getJSONObject("props").getJSONObject("mangas").getJSONArray("data")
		return mangas.mapJSON { obj -> parseMangaCard(obj) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val page = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml().inertiaPage()
		val props = page.getJSONObject("props")
		val m = props.getJSONObject("manga")
		val tags = m.optJSONArray("genres")?.mapJSONNotNullToSet { g ->
			val id = g.optInt("id", -1).takeIf { it >= 0 } ?: return@mapJSONNotNullToSet null
			val name = g.optString("name").ifEmpty { return@mapJSONNotNullToSet null }
			MangaTag(title = name.toTitleCase(Locale.ENGLISH), key = id.toString(), source = source)
		}.orEmpty()
		val artists = m.optJSONArray("artists")?.mapJSONNotNull { a ->
			a.optString("name").ifEmpty { null }
		}.orEmpty().toSet()
		val description = m.optString("pilot").ifEmpty { null }
			?.let { html -> Jsoup.parseBodyFragment(html).body().html() }
		val chapters = props.optJSONArray("chapters")?.mapJSON { c ->
			parseChapter(manga.url.removeSuffix("/"), c)
		}.orEmpty().asReversed()

		return manga.copy(
			altTitles = setOfNotNull(m.optString("other_name").ifEmpty { null }),
			authors = artists,
			description = description,
			tags = tags,
			state = when (m.optInt("status_id", -1)) {
				0 -> MangaState.ONGOING
				1 -> MangaState.PAUSED
				2 -> MangaState.FINISHED
				else -> null
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val page = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml().inertiaPage()
		val contentHtml = page.getJSONObject("props").optString("chapterContent")
		if (contentHtml.isEmpty()) {
			throw ParseException("chapterContent not found", chapter.url)
		}
		return Jsoup.parseBodyFragment(contentHtml).select("img").mapNotNull {
			val url = it.src() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = MangaParserSource.MANHWA18,
			)
		}
	}

	private fun parseMangaCard(obj: JSONObject): Manga {
		val slug = obj.getString("slug")
		val relUrl = "/manga/$slug"
		val title = obj.optString("name").ifEmpty { slug }
		val coverUrl = obj.optString("thumb_url").ifEmpty {
			obj.optString("cover_url").ifEmpty { null }
		}
		val rating = obj.optDouble("rating_average", -1.0)
			.takeIf { it in 0.0..5.0 }
			?.let { (it / 5.0).toFloat() }
			?: RATING_UNKNOWN
		val state = when (obj.optInt("status_id", -1)) {
			0 -> MangaState.ONGOING
			1 -> MangaState.PAUSED
			2 -> MangaState.FINISHED
			else -> null
		}
		val contentRating = if (obj.optInt("is_adult_content", 0) == 1) {
			ContentRating.ADULT
		} else {
			// The site itself is an 18+ portal; downgrading to SUGGESTIVE
			// would over-claim safety, so the whole source stays ADULT.
			ContentRating.ADULT
		}
		return Manga(
			id = generateUid(relUrl),
			title = title,
			altTitles = setOfNotNull(obj.optString("other_name").ifEmpty { null }),
			url = relUrl,
			publicUrl = relUrl.toAbsoluteUrl(domain),
			rating = rating,
			contentRating = contentRating,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = state,
			authors = emptySet(),
			largeCoverUrl = obj.optString("cover_url").ifEmpty { null },
			description = null,
			source = MangaParserSource.MANHWA18,
		)
	}

	private fun parseChapter(mangaRelUrlNoSlash: String, obj: JSONObject): MangaChapter {
		val slug = obj.getString("slug")
		val chapterRelUrl = "$mangaRelUrlNoSlash/$slug"
		val name = obj.optString("name").ifEmpty { slug }
		val number = CHAPTER_NUMBER.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()
			?: obj.optInt("order", 0).toFloat().takeIf { it > 0f }
			?: -1f
		val uploadDate = ISO_DATE_FORMAT.parseSafe(obj.optString("created_at").replace("T", " ").substringBefore('.'))
		return MangaChapter(
			id = generateUid(chapterRelUrl),
			title = name,
			number = number,
			volume = 0,
			url = chapterRelUrl,
			scanlator = null,
			uploadDate = uploadDate,
			branch = null,
			source = MangaParserSource.MANHWA18,
		)
	}

	private fun Document.inertiaPage(): JSONObject {
		val raw = getElementById("app")?.attr("data-page")
			?.takeIf { it.isNotEmpty() }
			?: throw ParseException("Inertia page payload not found", domain)
		return JSONObject(raw)
	}

	private val tagsMap = suspendLazy(initializer = ::parseTags)

	private suspend fun parseTags(): Map<String, MangaTag> {
		val page = webClient.httpGet("https://$domain/tim-kiem").parseHtml().inertiaPage()
		val genres = page.getJSONObject("props").optJSONArray("genres") ?: return emptyMap()
		val out = ArrayMap<String, MangaTag>(genres.length())
		for (i in 0 until genres.length()) {
			val g = genres.getJSONObject(i)
			val id = g.optInt("id", -1).takeIf { it >= 0 } ?: continue
			val name = g.optString("name").ifEmpty { continue }
			out[name.lowercase(Locale.ENGLISH)] = MangaTag(
				title = name.toTitleCase(Locale.ENGLISH),
				key = id.toString(),
				source = source,
			)
		}
		return out
	}

	private companion object {
		private val CHAPTER_NUMBER = Regex("""(\d+(?:\.\d+)?)""")
		private val ISO_DATE_FORMAT by lazy {
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
				timeZone = TimeZone.getTimeZone("UTC")
			}
		}
	}
}
