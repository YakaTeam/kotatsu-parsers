package org.koitharu.kotatsu.parsers.site.mangabox

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.splitByWhitespace
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

internal abstract class MangaboxParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	pageSize: Int = 48,
) : PagedMangaParser(context, source, pageSize) {

	private val headers = Headers.headersOf("User-Agent", UserAgents.KOTATSU)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED, // latest
		SortOrder.POPULARITY, // top read
		SortOrder.NEWEST, // newest
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
	)

	@JvmField
	protected val ongoing: Set<String> = setOf("ongoing")

	@JvmField
	protected val finished: Set<String> = setOf("completed")

	protected open val listUrl = "/genre"
	protected open val searchUrl = "/search/story/"
	protected open val itemSelector = "div.search-story-item, a.list-story-item"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()

		if (!filter.query.isNullOrEmpty()) {
			val query = filter.query.filterQuery()
			url.addPathSegment("search")
			url.addPathSegment("story")
			url.addPathSegment(query)
		} else {
			url.addPathSegment("genre")
			if (filter.tags.isEmpty()) {
				url.addPathSegment("all")
			} else {
				val tags = filter.tags.first()
				url.addPathSegment(tags.key)
			}
		}

		when (order) {
			SortOrder.NEWEST -> {
				if (filter.states.isEmpty()) {
					url.addQueryParameter("filter", 1.toString())
				} else {
					val state = filter.states.oneOrThrowIfMany()
					when (state) {
						MangaState.FINISHED -> url.addQueryParameter("filter", 2.toString())
						MangaState.ONGOING -> url.addQueryParameter("filter", 3.toString())
						else -> url.addQueryParameter("filter", 1.toString())
					}
				}
			}
			SortOrder.POPULARITY -> {
				if (filter.states.isEmpty()) {
					url.addQueryParameter("filter", 7.toString())
				} else {
					val state = filter.states.oneOrThrowIfMany()
					when (state) {
						MangaState.FINISHED -> url.addQueryParameter("filter", 8.toString())
						MangaState.ONGOING -> url.addQueryParameter("filter", 9.toString())
						else -> url.addQueryParameter("filter", 7.toString())
					}
				}
			}
			else -> {
				if (filter.states.isEmpty()) {
					url.addQueryParameter("filter", 4.toString())
				} else {
					val state = filter.states.oneOrThrowIfMany()
					when (state) {
						MangaState.FINISHED -> url.addQueryParameter("filter", 5.toString())
						MangaState.ONGOING -> url.addQueryParameter("filter", 6.toString())
						else -> url.addQueryParameter("filter", 4.toString())
					}
				}
			}
		}

		val doc = webClient.httpGet(url.build()).parseHtml()

		return doc.select(itemSelector).map { el ->
			val a = if (el.tagName() == "a") el else el.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = a.absUrl("href"),
				coverUrl = el.selectFirst("img")?.src(),
				title = a.attr("title").ifEmpty { el.selectFirst("h3")?.text().orEmpty() },
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	protected open val selectDesc = "#contentBox, div#noidungm, div#panel-story-info-description"
	protected open val selectState = ".info-status, li:contains(status), td:containsOwn(status) + td"
	protected open val selectAut = "a[href*='/author/'], li:contains(author) a, td:contains(author) + td a"
	protected open val selectTag = ".manga-info-content ul.manga-info-text li.genres a"
	protected open val selectRating = ".comic-info-wrap .info-wrap .rating"

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val slug = manga.url.removeSuffix("/").substringAfterLast("/")
		val chaptersDeferred = async { fetchChapters(slug) }

		// Description
		val desc = doc.selectFirst(selectDesc)?.html()
			?.replace(Regex("<[^>]*>"), "")
			?.replace("&amp;", "&")
			?.substringAfter("summary:")
			?.trim()

		// State
		val stateText = doc.selectFirst(selectState)?.text()?.lowercase()
		val state = when {
			stateText?.contains("ongoing") == true -> MangaState.ONGOING
			stateText?.contains("completed") == true -> MangaState.FINISHED
			else -> null
		}

		// Tags
		val tags = doc.select(selectTag).mapNotNullToSet { a ->
			val href = a.attr("href")
			val key = href.substringAfterLast("/genre/")
				.substringBefore("?")
				.substringBefore("/")
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = a.text().toTitleCase(),
				source = source,
			)
		}

		manga.copy(
			description = desc,
			state = state ?: manga.state,
			tags = tags,
			authors = doc.select(selectAut).mapToSet { it.text() },
			chapters = chaptersDeferred.await(),
			rating = doc.select(selectRating).attr("data-default")
				.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN
		)
	}

	protected open suspend fun fetchChapters(slug: String): List<MangaChapter> {
		val url = urlBuilder().addPathSegment("api").addPathSegment("manga")
			.addPathSegment(slug).addPathSegment("chapters")
			.addQueryParameter("limit", 9999.toString())
			.addQueryParameter("offset", 0.toString())
		val json = webClient.httpGet(url.build(), headers).parseJson()

		if (!json.optBoolean("success", false)) {
			return emptyList()
		}

		val chapsArr = json.getJSONObject("data").getJSONArray("chapters")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)
			dateFormat.timeZone = TimeZone.getTimeZone("UTC")

		return chapsArr.mapChapters(reversed = true) { i, item ->
			val chapterSlug = item.getString("chapter_slug")
			val chapterNum = item.getFloatOrDefault("chapter_num", (i + 1).toFloat())
			val chapterName = item.optString("chapter_name", "Chapter $chapterNum")
			val updatedAt = item.optString("updated_at", "")

			MangaChapter(
				id = generateUid(chapterSlug),
				title = chapterName,
				number = chapterNum,
				volume = 0,
				url = "/manga/$slug/$chapterSlug",
				uploadDate = dateFormat.parseSafe(updatedAt),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	protected open val selectPage = ".container-chapter-reader img, div#vungdoc img"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select(selectPage).map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("a[href*='/genre/']").filterNot { a ->
			a.attr("href").contains("?type") ||
				a.attr("href").contains("all")
		}.mapNotNullToSet { a ->
			val href = a.attr("href")
			val key = href.substringAfterLast("/genre/")
				.substringBefore("?")
				.substringBefore("/")
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = a.text().toTitleCase(),
				source = source,
			)
		}
	}

	// fetch from page
	private fun String.filterQuery(): String {
		return lowercase()
			.replace(Regex("Ã |Ã¡|áº¡|áº£|Ã£|Ã¢|áº§|áº¥|áº­|áº©|áº«|Äƒ|áº±|áº¯|áº·|áº³|áºµ"), "a")
			.replace(Regex("Ã¨|Ã©|áº¹|áº»|áº½|Ãª|á»|áº¿|á»‡|á»ƒ|á»…"), "e")
			.replace(Regex("Ã¬|Ã­|á»‹|á»‰|Ä©"), "i")
			.replace(Regex("Ã²|Ã³|á»|á»|Ãµ|Ã´|á»“|á»‘|á»™|á»•|á»—|Æ¡|á»|á»›|á»£|á»Ÿ|á»¡"), "o")
			.replace(Regex("Ã¹|Ãº|á»¥|á»§|Å©|Æ°|á»«|á»©|á»±|á»­|á»¯"), "u")
			.replace(Regex("á»³|Ã½|á»µ|á»·|á»¹"), "y")
			.replace(Regex("Ä‘"), "d")
			.replace(Regex("[!@%^*()+=<>?/,.:'\"&#\\[\\]~\\-$ _]+"), "_")
			.replace(Regex("_+"), "_")
			.replace(Regex("^_+|_+$"), "")
			// force split + joinToString
			.splitByWhitespace().joinToString("_") { it }
	}
}
