package org.koitharu.kotatsu.parsers.site.madara.fr

import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("SCANHENTAIMENU", "X-MANGA", "fr", ContentType.HENTAI)
internal class ScanHentaiMenu(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.SCANHENTAIMENU, "x-manga.org") {

	override val listUrl = "manga/"
	override val datePattern = "MMMM d, yyyy"
	override val selectPage = "div.page-break, div.reading-box"
	override val selectTestAsync = "#manga-chapters-holder li.wp-manga-chapter"

	private val chapterDateFormatFr = ThreadLocal.withInitial { SimpleDateFormat(datePattern, sourceLocale) }
	private val chapterDateFormatEn = ThreadLocal.withInitial { SimpleDateFormat(datePattern, Locale.ENGLISH) }

	private val chaptersCache = object : LinkedHashMap<String, List<MangaChapter>>(32, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaChapter>>?): Boolean {
			return size > CHAPTERS_CACHE_SIZE
		}
	}

	private val pagesCache = object : LinkedHashMap<String, List<MangaPage>>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaPage>>?): Boolean {
			return size > PAGES_CACHE_SIZE
		}
	}

	private val mangaIdCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
			return size > MANGA_ID_CACHE_SIZE
		}
	}
	private val availableTagsCache = suspendLazy { fetchAvailableTagsImpl() }

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val cacheKey = normalizeMangaUrl(manga.url)
		synchronized(chaptersCache) {
			chaptersCache[cacheKey]?.let { return it }
		}
		cacheMangaPostIdFromDocument(cacheKey, doc)
		val chapters = parseChapterList(doc.body().select(selectChapter), sourceOrderFallback = true)
		if (chapters.isNotEmpty()) {
			synchronized(chaptersCache) {
				chaptersCache[cacheKey] = chapters
			}
			return chapters
		}
		return loadChapters(manga.url, doc)
	}

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val cacheKey = normalizeMangaUrl(mangaUrl)
		synchronized(chaptersCache) {
			chaptersCache[cacheKey]?.let { return it }
		}
		cacheMangaPostIdFromDocument(cacheKey, document)
		val chaptersFromAsync = requestAsyncChapters(mangaUrl, document)
		val chapters = if (chaptersFromAsync.isNotEmpty()) {
			chaptersFromAsync
		} else {
			parseChapterList(document.body().select(selectChapter), sourceOrderFallback = true)
		}
		if (chapters.isNotEmpty()) {
			synchronized(chaptersCache) {
				chaptersCache[cacheKey] = chapters
			}
		}
		return chapters
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val cacheKey = chapter.url.substringBefore('?')
		synchronized(pagesCache) {
			pagesCache[cacheKey]?.let { return it }
		}
		val pages = loadPagesViaAjax(chapter).ifEmpty { super.getPages(chapter) }
		if (pages.isNotEmpty()) {
			synchronized(pagesCache) {
				pagesCache[cacheKey] = pages
			}
		}
		return pages
	}

	private suspend fun loadPagesViaAjax(chapter: MangaChapter): List<MangaPage> {
		val route = CHAPTER_ROUTE.find(chapter.url) ?: return emptyList()
		val mangaSlug = route.groupValues.getOrNull(1).orEmpty()
		val chapterSlug = route.groupValues.getOrNull(2).orEmpty()
		if (mangaSlug.isEmpty() || chapterSlug.isEmpty()) return emptyList()

		val postId = getMangaPostId(mangaSlug) ?: return emptyList()
		val ajaxUrl =
			"https://$domain/wp-admin/admin-ajax.php?postID=$postId&chapter=$chapterSlug&manga-paged=1&style=list&action=chapter_navigate_page"
		val payload = webClient.httpGet(ajaxUrl).parseJson()
		if (!payload.optBoolean("success")) return emptyList()

		val dataNode = payload.optJSONObject("data")
		val contentHtml = dataNode
			?.optJSONObject("data")
			?.getStringOrNull("content")
			?: dataNode?.getStringOrNull("content")
			?.trim()
			.orEmpty()
		if (contentHtml.isEmpty()) return emptyList()

		val doc = Jsoup.parse(contentHtml, "https://$domain")
		return doc.select("div.reading-content img, div.reading-box img").mapNotNull { img ->
			val src = img.src()?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") } ?: return@mapNotNull null
			val url = src.toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}.distinctBy { it.url }
	}

	private suspend fun requestAsyncChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
		val ajaxDoc = webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
		val ajaxChapters = parseChapterList(ajaxDoc.select(selectChapter), sourceOrderFallback = true)
		if (ajaxChapters.isNotEmpty()) {
			return ajaxChapters
		}
		val mangaId = document.select("div#manga-chapters-holder").attr("data-id").trim()
		if (mangaId.isNotEmpty()) {
			val adminAjaxUrl = "https://$domain/wp-admin/admin-ajax.php"
			val postData = postDataReq + mangaId
			val adminAjaxDoc = webClient.httpPost(adminAjaxUrl, postData).parseHtml()
			val adminAjaxChapters = parseChapterList(adminAjaxDoc.select(selectChapter), sourceOrderFallback = true)
			if (adminAjaxChapters.isNotEmpty()) {
				return adminAjaxChapters
			}
		}
		return emptyList()
	}

	private suspend fun getMangaPostId(mangaSlug: String): String? {
		synchronized(mangaIdCache) {
			mangaIdCache[mangaSlug]?.let { return it }
		}
		val doc = webClient.httpGet("/manga/$mangaSlug/".toAbsoluteUrl(domain)).parseHtml()
		val postId = doc.selectFirst("#manga-chapters-holder")
			?.attr("data-id")
			?.trim()
			?.ifEmpty { null }
			?: MANGA_ID_REGEX.find(doc.html())?.groupValues?.getOrNull(1)
		cacheMangaPostId(mangaSlug, postId)
		return postId
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> = availableTagsCache.get()

	private suspend fun fetchAvailableTagsImpl(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/?s=&post_type=wp-manga").parseHtml()
		return doc.select("form.search-advanced-form input[type=checkbox][name='genre[]']").mapNotNullToSet { input ->
			val key = input.attr("value").trim()
			val title = input.nextElementSibling()?.text()?.trim().orEmpty()
			if (key.isEmpty() || title.isEmpty()) {
				return@mapNotNullToSet null
			}
			MangaTag(
				key = key,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	private fun parseChapterList(items: List<Element>, sourceOrderFallback: Boolean): List<MangaChapter> {
		return items.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val chapterTitle = (a.selectFirst("p")?.text() ?: a.ownText()).trim().ifEmpty { null }
			val chapterTitleLower = chapterTitle?.lowercase(Locale.ROOT).orEmpty()
			val hrefLower = href.lowercase(Locale.ROOT)
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
				?: li.selectFirst(selectDate)?.text()

			MangaChapter(
				id = generateUid(href),
				title = chapterTitle,
				number = parseChapterNumber(
					title = chapterTitle,
					href = href,
					titleLower = chapterTitleLower,
					hrefLower = hrefLower,
					fallback = if (sourceOrderFallback) i + 1f else 0f,
				),
				volume = 0,
				url = href + stylePage,
				uploadDate = parseUploadDate(dateText),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	private fun parseUploadDate(raw: String?): Long {
		val normalizedRaw = raw?.trim()?.replace('\u00a0', ' ')?.ifEmpty { return 0L } ?: return 0L
		val frDate = parseChapterDate(chapterDateFormatFr.get(), normalizedRaw)
		if (frDate != 0L) return frDate
		return parseChapterDate(chapterDateFormatEn.get(), normalizedRaw)
	}

	private fun parseChapterNumber(
		title: String?,
		href: String,
		titleLower: String,
		hrefLower: String,
		fallback: Float,
	): Float {
		title?.let {
			CHAPTER_TITLE_NUMBER.find(it)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()?.let { n ->
				return n
			}
		}
		if ("oneshot" in titleLower) return 1f
		CHAPTER_URL_NUMBER.find(href)?.groupValues?.getOrNull(1)?.let { raw ->
			val value = raw.replace('-', '.')
			value.toFloatOrNull()?.let { return it }
		}
		if ("/oneshot" in hrefLower) return 1f
		return fallback
	}

	private fun normalizeMangaUrl(url: String): String {
		return url.substringBefore('?').removeSuffix("/")
	}

	private fun cacheMangaPostIdFromDocument(mangaUrl: String, document: Document) {
		val slug = extractMangaSlug(mangaUrl)
		if (slug.isEmpty()) return
		val postId = document.selectFirst("#manga-chapters-holder")
			?.attr("data-id")
			?.trim()
			?.ifEmpty { null }
			?: MANGA_ID_REGEX.find(document.html())?.groupValues?.getOrNull(1)
		cacheMangaPostId(slug, postId)
	}

	private fun cacheMangaPostId(mangaSlug: String, postId: String?) {
		if (mangaSlug.isBlank() || postId.isNullOrBlank()) return
		synchronized(mangaIdCache) {
			mangaIdCache[mangaSlug] = postId
		}
	}

	private fun extractMangaSlug(url: String): String {
		return url.substringAfter("/manga/").substringBefore('/').substringBefore('?').substringBefore('#').trim()
	}

	private companion object {
		private const val CHAPTERS_CACHE_SIZE = 100
		private const val PAGES_CACHE_SIZE = 200
		private const val MANGA_ID_CACHE_SIZE = 128

		private val CHAPTER_TITLE_NUMBER = Regex("(?i)\\bchap(?:itre|ter)?\\.?\\s*([0-9]+(?:[.,][0-9]+)?)")
		private val CHAPTER_URL_NUMBER = Regex("(?i)/(?:chapitre|chapter)-([0-9]+(?:-[0-9]+)?)(?:[^0-9]|$)")
		private val CHAPTER_ROUTE = Regex("(?i)/manga/([^/]+)/([^/?#]+)")
		private val MANGA_ID_REGEX = Regex("\"manga_id\"\\s*:\\s*\"?(\\d+)\"?")
	}
}
