package org.koitharu.kotatsu.parsers.site.madara.fr

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@MangaSourceParser("MANGASORIGINES", "MangasOrigines.fr", "fr")
internal class MangasOrigines(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGASORIGINES, "mangas-origines.fr") {
	override val datePattern = "MMMM d, yyyy"
	override val tagPrefix = "manga-genres/"
	override val listUrl = "catalogues/"
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
		.set(CommonHeaders.REFERER, "https://$domain/")
		.set(CommonHeaders.ORIGIN, "https://$domain")
		.build()

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val cacheKey = normalizeMangaUrl(manga.url)
		synchronized(chaptersCache) {
			chaptersCache[cacheKey]?.let { return it }
		}
		val chapters = parseChapterList(selectChapterItems(doc), sourceOrderFallback = true)
		if (chapters.isNotEmpty()) {
			synchronized(chaptersCache) {
				chaptersCache[cacheKey] = chapters
			}
			return chapters
		}
		return loadChapters(manga.url, doc)
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

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val cacheKey = normalizeMangaUrl(mangaUrl)
		synchronized(chaptersCache) {
			chaptersCache[cacheKey]?.let { return it }
		}
		val chaptersFromAsync = requestAsyncChapters(mangaUrl)
		val chapters = if (chaptersFromAsync.isNotEmpty()) {
			chaptersFromAsync
		} else {
			parseChapterList(selectChapterItems(document), sourceOrderFallback = true)
		}
		if (chapters.isNotEmpty()) {
			synchronized(chaptersCache) {
				chaptersCache[cacheKey] = chapters
			}
		}
		return chapters
	}

	private suspend fun requestAsyncChapters(mangaUrl: String): List<MangaChapter> {
		val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
		val ajaxDoc = webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
		return parseChapterList(selectChapterItems(ajaxDoc), sourceOrderFallback = true)
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
		val date = normalizedRaw.lowercase(Locale.ROOT)
		val number = DATE_NUMBER.find(date)?.groupValues?.getOrNull(1)?.toIntOrNull()
		if (number != null) {
			val now = System.currentTimeMillis()
			when {
				DATE_SECONDS.containsMatchIn(date) -> return now - number * SECOND_MILLIS
				DATE_MINUTES.containsMatchIn(date) -> return now - number * MINUTE_MILLIS
				DATE_HOURS.containsMatchIn(date) -> return now - number * HOUR_MILLIS
				DATE_DAYS.containsMatchIn(date) -> return now - number * DAY_MILLIS
				DATE_WEEKS.containsMatchIn(date) -> return now - number * WEEK_MILLIS
				DATE_MONTHS.containsMatchIn(date) -> return Calendar.getInstance().apply { add(Calendar.MONTH, -number) }.timeInMillis
				DATE_YEARS.containsMatchIn(date) -> return Calendar.getInstance().apply { add(Calendar.YEAR, -number) }.timeInMillis
			}
		}
		if ("hier" in date) {
			return startOfDay(daysAgo = 1)
		}
		if ("aujourd" in date || "today" == date) {
			return startOfDay(daysAgo = 0)
		}
		val parsedFr = chapterDateFormatFr.get().parseSafe(normalizedRaw)
		return if (parsedFr != 0L) parsedFr else chapterDateFormatEn.get().parseSafe(normalizedRaw)
	}

	private fun startOfDay(daysAgo: Int): Long {
		return Calendar.getInstance().apply {
			if (daysAgo != 0) {
				add(Calendar.DAY_OF_MONTH, -daysAgo)
			}
			set(Calendar.HOUR_OF_DAY, 0)
			set(Calendar.MINUTE, 0)
			set(Calendar.SECOND, 0)
			set(Calendar.MILLISECOND, 0)
		}.timeInMillis
	}

	private fun parseChapterNumber(title: String?, href: String, fallback: Float): Float {
		title?.let {
			CHAPTER_TITLE_NUMBER.find(it)?.groupValues?.getOrNull(1)?.normalizeChapterNumber()?.let { n ->
				return n
			}
		}
		CHAPTER_URL_NUMBER.find(href)?.groupValues?.getOrNull(1)?.normalizeChapterNumberFromSlug()?.let { n ->
			return n
		}
		return fallback
	}

	private fun String.normalizeChapterNumber(): Float? {
		val cleaned = trim().replace(',', '.')
		return cleaned.toFloatOrNull()
	}

	private fun String.normalizeChapterNumberFromSlug(): Float? {
		val token = trim().lowercase(Locale.ROOT)
		val decimalToken = token.substringBefore("-va").substringBefore("-vf")
		if (DECIMAL_SLUG.matches(decimalToken)) {
			return decimalToken.replace('-', '.').toFloatOrNull()
		}
		return decimalToken.toFloatOrNull()
	}

	private fun normalizeMangaUrl(url: String): String {
		return url.substringBefore('?').removeSuffix("/")
	}

	private fun selectChapterItems(document: Document): List<Element> {
		return document.selectFirst(CHAPTERS_HOLDER_SELECTOR)?.select(selectChapter)
			?: document.select(selectChapter)
	}

	private companion object {
		private const val CHAPTERS_CACHE_SIZE = 100
		private const val PAGES_CACHE_SIZE = 200
		private const val CHAPTERS_HOLDER_SELECTOR = "div#manga-chapters-holder"
		private const val SECOND_MILLIS = 1_000L
		private const val MINUTE_MILLIS = 60 * SECOND_MILLIS
		private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
		private const val DAY_MILLIS = 24 * HOUR_MILLIS
		private const val WEEK_MILLIS = 7 * DAY_MILLIS

		private val CHAPTER_TITLE_NUMBER = Regex("(?i)\\bchap(?:itre|ter)?\\.?\\s*([0-9]+(?:[.,][0-9]+)?)")
		private val CHAPTER_URL_NUMBER = Regex("(?i)/(?:chapitre|chapter)-([0-9]+(?:-[0-9]+)?)(?:-|/|$)")
		private val DECIMAL_SLUG = Regex("^[0-9]+-[0-9]+$")

		private val DATE_NUMBER = Regex("(\\d+)")
		private val DATE_SECONDS = Regex("\\b(seconde|secondes|sec)\\b")
		private val DATE_MINUTES = Regex("\\b(minute|minutes|min)\\b")
		private val DATE_HOURS = Regex("\\b(heure|heures|h)\\b")
		private val DATE_DAYS = Regex("\\b(jour|jours|j)\\b")
		private val DATE_WEEKS = Regex("\\b(semaine|semaines|sem)\\b")
		private val DATE_MONTHS = Regex("\\b(mois)\\b")
		private val DATE_YEARS = Regex("\\b(an|ans|année|années|year|years)\\b")
	}
}
