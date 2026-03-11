package org.koitharu.kotatsu.parsers.site.madara.en

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("MANHWACLUB", "Manhwaclub", "en", ContentType.HENTAI)
internal class ManhwaClub(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHWACLUB, "manhwaclub.net") {

	override val listUrl = "manga/"
	override val datePattern = "MMMM d, yyyy"
	override val postReq = true
	override val selectTestAsync = "#manga-chapters-holder li.wp-manga-chapter"
	override val selectChapter = "li.wp-manga-chapter, div.chapter-item"

	private val chapterDateFormat = ThreadLocal.withInitial { SimpleDateFormat(datePattern, sourceLocale) }

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

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val cacheKey = normalizeMangaUrl(manga.url)
		synchronized(chaptersCache) {
			chaptersCache[cacheKey]?.let { return it }
		}
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

	private suspend fun requestAsyncChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val mangaId = document.select("div#manga-chapters-holder").attr("data-id").trim()
		if (postReq && mangaId.isNotEmpty()) {
			val adminAjaxUrl = "https://$domain/wp-admin/admin-ajax.php"
			val postData = postDataReq + mangaId
			val adminAjaxDoc = webClient.httpPost(adminAjaxUrl, postData).parseHtml()
			val adminAjaxChapters = parseChapterList(adminAjaxDoc.select(selectChapter), sourceOrderFallback = true)
			if (adminAjaxChapters.isNotEmpty()) {
				return adminAjaxChapters
			}
		}
		val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
		val ajaxDoc = webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
		return parseChapterList(ajaxDoc.select(selectChapter), sourceOrderFallback = true)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val cacheKey = chapter.url.substringBefore('?')
		synchronized(pagesCache) {
			pagesCache[cacheKey]?.let { return it }
		}
		val pages = super.getPages(chapter)
		if (pages.isNotEmpty()) {
			synchronized(pagesCache) {
				pagesCache[cacheKey] = pages
			}
		}
		return pages
	}

	private fun parseChapterList(items: List<Element>, sourceOrderFallback: Boolean): List<MangaChapter> {
		val dateFormat = chapterDateFormat.get()
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
				uploadDate = parseChapterDate(dateFormat, dateText),
				source = source,
				scanlator = null,
				branch = parseChapterBranch(chapterTitleLower, hrefLower),
			)
		}
	}

	private fun parseChapterBranch(titleLower: String, hrefLower: String): String? {
		return if ("raw" in titleLower || "-raw/" in hrefLower) "RAW" else null
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

	private companion object {
		private const val CHAPTERS_CACHE_SIZE = 100
		private const val PAGES_CACHE_SIZE = 200

		private val CHAPTER_TITLE_NUMBER = Regex("(?i)\\bchap(?:itre|ter)?\\.?\\s*([0-9]+(?:[.,][0-9]+)?)")
		private val CHAPTER_URL_NUMBER = Regex("(?i)/(?:chapitre|chapter)-([0-9]+(?:-[0-9]+)?)(?:[^0-9]|$)")
	}
}
