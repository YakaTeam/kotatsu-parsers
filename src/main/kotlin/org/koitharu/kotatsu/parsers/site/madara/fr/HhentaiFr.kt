package org.koitharu.kotatsu.parsers.site.madara.fr

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
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("HHENTAIFR", "Histoire d'Hentai", "fr", ContentType.HENTAI)
internal class HhentaiFr(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HHENTAIFR, "hhentai.fr") {

	override val listUrl = "manga/"
	override val datePattern = "MMMM d, yyyy"
	override val selectTestAsync = "#manga-chapters-holder li.wp-manga-chapter"
	override val selectChapter = "li.wp-manga-chapter, div.chapter-item"

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

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	init {
		context.cookieJar.insertCookies(
			domain,
			"age_gate=32;",
		)
	}

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

		val asyncDoc = requestAsyncChapters(mangaUrl, document)
		val chaptersFromAsync = parseChapterList(asyncDoc.select(selectChapter), sourceOrderFallback = true)
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

	private suspend fun requestAsyncChapters(mangaUrl: String, document: Document): Document {
		val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
		val ajaxDoc = webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
		if (ajaxDoc.select(selectChapter).isNotEmpty()) {
			return ajaxDoc
		}

		val mangaId = document.select("div#manga-chapters-holder").attr("data-id").trim()
		if (mangaId.isNotEmpty()) {
			val url = "https://$domain/wp-admin/admin-ajax.php"
			val postData = postDataReq + mangaId
			val adminAjaxDoc = webClient.httpPost(url, postData).parseHtml()
			if (adminAjaxDoc.select(selectChapter).isNotEmpty()) {
				return adminAjaxDoc
			}
		}
		return ajaxDoc
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
		return items.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val chapterTitle = (a.selectFirst("p")?.text() ?: a.ownText()).trim().ifEmpty { null }
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
				?: li.selectFirst(selectDate)?.text()

			MangaChapter(
				id = generateUid(href),
				title = chapterTitle,
				number = parseChapterNumber(chapterTitle, href, fallback = if (sourceOrderFallback) i + 1f else 0f),
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

	private fun parseChapterNumber(title: String?, href: String, fallback: Float): Float {
		title?.let {
			CHAPTER_TITLE_NUMBER.find(it)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()?.let { n ->
				return n
			}
			if ("oneshot" in it.lowercase(Locale.ROOT)) {
				return 1f
			}
		}
		CHAPTER_URL_NUMBER.find(href)?.groupValues?.getOrNull(1)?.let { raw ->
			val value = raw.replace('-', '.')
			value.toFloatOrNull()?.let { return it }
		}
		if ("/oneshot" in href.lowercase(Locale.ROOT)) {
			return 1f
		}
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
