package org.koitharu.kotatsu.parsers.site.madara.fr

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@MangaSourceParser("MANGASORIGINES", "MangasOrigines.fr", "fr")
internal class MangasOrigines(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGASORIGINES, "mangas-origines.fr") {
	override val datePattern = "MMMM d, yyyy"
	override val tagPrefix = "manga-genres/"
	override val listUrl = "catalogues/"
	private val pagesCache = object : LinkedHashMap<String, List<MangaPage>>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaPage>>?): Boolean {
			return size > PAGES_CACHE_SIZE
		}
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return parseChapterList(doc.body().select(selectChapter), sourceOrderFallback = true)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		synchronized(pagesCache) {
			pagesCache[chapter.url]?.let { return it }
		}
		val pages = super.getPages(chapter)
		if (pages.isNotEmpty()) {
			synchronized(pagesCache) {
				pagesCache[chapter.url] = pages
			}
		}
		return pages
	}

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val doc = if (postReq) {
			val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
			val url = "https://$domain/wp-admin/admin-ajax.php"
			val postData = postDataReq + mangaId
			webClient.httpPost(url, postData).parseHtml()
		} else {
			val url = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
			webClient.httpPost(url, emptyMap()).parseHtml()
		}
		return parseChapterList(doc.select(selectChapter), sourceOrderFallback = true)
	}

	private fun parseChapterList(items: List<org.jsoup.nodes.Element>, sourceOrderFallback: Boolean): List<MangaChapter> {
		val absoluteFrDate = SimpleDateFormat(datePattern, sourceLocale)
		val absoluteEnDate = SimpleDateFormat(datePattern, Locale.ENGLISH)
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
				uploadDate = parseUploadDate(dateText, absoluteFrDate, absoluteEnDate),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	private fun parseUploadDate(raw: String?, frDate: DateFormat, enDate: DateFormat): Long {
		val normalizedRaw = raw?.trim()?.replace('\u00a0', ' ')?.ifEmpty { return 0L } ?: return 0L
		val date = normalizedRaw.lowercase(Locale.ROOT)
		val number = DATE_NUMBER.find(date)?.groupValues?.getOrNull(1)?.toIntOrNull()
		if (number != null) {
			val cal = Calendar.getInstance()
			when {
				DATE_SECONDS.containsMatchIn(date) -> return cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
				DATE_MINUTES.containsMatchIn(date) -> return cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
				DATE_HOURS.containsMatchIn(date) -> return cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
				DATE_DAYS.containsMatchIn(date) -> return cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
				DATE_WEEKS.containsMatchIn(date) -> return cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
				DATE_MONTHS.containsMatchIn(date) -> return cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
				DATE_YEARS.containsMatchIn(date) -> return cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			}
		}
		if ("hier" in date) {
			return Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -1)
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis
		}
		if ("aujourd" in date || "today" == date) {
			return Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis
		}
		val parsedFr = frDate.parseSafe(normalizedRaw)
		return if (parsedFr != 0L) parsedFr else enDate.parseSafe(normalizedRaw)
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

	private companion object {
		private const val PAGES_CACHE_SIZE = 200

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
