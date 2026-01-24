package org.koitharu.kotatsu.parsers.site.mangabox

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONIndexed
import java.text.SimpleDateFormat
import java.util.*

internal abstract class MangaboxParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	pageSize: Int = 48,
) : PagedMangaParser(context, source, pageSize) {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	@JvmField
	protected val ongoing: Set<String> = setOf("ongoing")

	@JvmField
	protected val finished: Set<String> = setOf("completed")

	protected open val listUrl = "/genre"
	protected open val searchUrl = "/search/story/"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url: String
		val itemSelector: String

		if (!filter.query.isNullOrEmpty()) {
			// Search mode
			val query = filter.query.replace(" ", "_")
			url = "https://$domain$searchUrl$query?page=$page"
			itemSelector = "div.search-story-item"
		} else {
			// Browse mode
			val tag = filter.tags.oneOrThrowIfMany()
			val state = filter.states.oneOrThrowIfMany()

			val sortParam = when (order) {
				SortOrder.POPULARITY -> "topview"
				SortOrder.NEWEST -> "newest"
				else -> "latest"
			}

			val stateParam = when (state) {
				MangaState.ONGOING -> "ongoing"
				MangaState.FINISHED -> "completed"
				else -> "all"
			}

			val tagSlug = tag?.key ?: "all"
			url = "https://$domain$listUrl/$tagSlug?type=$sortParam&state=$stateParam&page=$page"
			itemSelector = "a.list-story-item"
		}

		val doc = webClient.httpGet(url).parseHtml()

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

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("a[href*='/genre/']").mapNotNullToSet { a ->
			val href = a.attr("href")
			val key = href.substringAfterLast("/genre/").substringBefore("?").substringBefore("/")
			if (key.isEmpty() || key == "all") return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = a.text().trim().toTitleCase(),
				source = source,
			)
		}
	}

	protected open val selectDesc = "#contentBox, div#noidungm, div#panel-story-info-description"
	protected open val selectState = ".info-status, li:contains(status), td:containsOwn(status) + td"
	protected open val selectAut = "a[href*='/author/'], li:contains(author) a, td:contains(author) + td a"
	protected open val selectTag = "a[href*='/genre/']"

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
			if (!href.contains("/genre/")) return@mapNotNullToSet null
			val key = href.substringAfterLast("/genre/").substringBefore("?").substringBefore("/")
			if (key.isEmpty() || key == "all") return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = a.text().trim().toTitleCase(),
				source = source,
			)
		}

		// Authors
		val authors = doc.select(selectAut).mapToSet { it.text().trim() }

		manga.copy(
			description = desc,
			state = state ?: manga.state,
			tags = tags,
			authors = authors,
			chapters = chaptersDeferred.await(),
		)
	}

	protected open suspend fun fetchChapters(slug: String): List<MangaChapter> {
		val url = "https://$domain/api/manga/$slug/chapters"
		val json = webClient.httpGet(url).parseJson()

		if (!json.optBoolean("success", false)) {
			return emptyList()
		}

		val data = json.getJSONObject("data")
		val chaptersArray = data.getJSONArray("chapters")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)
		dateFormat.timeZone = TimeZone.getTimeZone("UTC")

		return chaptersArray.mapJSONIndexed { i, item ->
			val chapterSlug = item.getString("chapter_slug")
			val chapterNum = item.optDouble("chapter_num", (i + 1).toDouble()).toFloat()
			val chapterName = item.getStringOrNull("chapter_name") ?: "Chapter $chapterNum"
			val updatedAt = item.getStringOrNull("updated_at") ?: ""

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
		}.reversed() // API returns newest first, we want oldest first
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
}
