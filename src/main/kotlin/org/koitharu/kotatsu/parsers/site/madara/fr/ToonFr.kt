package org.koitharu.kotatsu.parsers.site.madara.fr

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@MangaSourceParser("TOONFR", "ToonFr", "fr", ContentType.HENTAI)
internal class ToonFr(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TOONFR, "toonfr.com") {

	override val tagPrefix = "webtoon-genre/"
	override val listUrl = "webtoon/"
	override val datePattern = "MMM d, yyyy"
	override val selectTestAsync = "#manga-chapters-holder li.wp-manga-chapter"
	override val selectChapter = "li.wp-manga-chapter, div.chapter-item"

	private val chapterDateFormat = ThreadLocal.withInitial { SimpleDateFormat(datePattern, sourceLocale) }
	private val absoluteDateRegex = Regex("""^([a-zA-ZÀ-ÿ.]+)\s+(\d{1,2})(?:,\s*(\d{4}))?$""")
	private val chaptersCache = object : LinkedHashMap<String, List<MangaChapter>>(32, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaChapter>>?): Boolean {
			return size > CHAPTERS_CACHE_SIZE
		}
	}

	private val monthAliases = mapOf(
		"jan" to "janv.",
		"janv" to "janv.",
		"january" to "janv.",
		"feb" to "févr.",
		"fev" to "févr.",
		"fév" to "févr.",
		"fevr" to "févr.",
		"févr" to "févr.",
		"february" to "févr.",
		"mar" to "mars",
		"mars" to "mars",
		"march" to "mars",
		"apr" to "avr.",
		"avr" to "avr.",
		"april" to "avr.",
		"may" to "mai",
		"mai" to "mai",
		"jun" to "juin",
		"juin" to "juin",
		"june" to "juin",
		"jul" to "juil.",
		"juil" to "juil.",
		"july" to "juil.",
		"aug" to "août",
		"aout" to "août",
		"août" to "août",
		"august" to "août",
		"sep" to "sept.",
		"sept" to "sept.",
		"september" to "sept.",
		"oct" to "oct.",
		"october" to "oct.",
		"nov" to "nov.",
		"november" to "nov.",
		"dec" to "déc.",
		"déc" to "déc.",
		"december" to "déc.",
		"décembre" to "déc.",
	)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val cacheKey = normalizeMangaUrl(manga.url)
		synchronized(chaptersCache) {
			chaptersCache[cacheKey]?.let { return it }
		}
		val chapters = parseChapters(doc)
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
			parseChapters(document)
		}
		if (chapters.isNotEmpty()) {
			synchronized(chaptersCache) {
				chaptersCache[cacheKey] = chapters
			}
		}
		return chapters
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val dateFormat = chapterDateFormat.get()
		val currentYear = Calendar.getInstance().get(Calendar.YEAR).toString()
		return doc.select(selectChapter).mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
				?: li.selectFirst("span.chapter-release-date i")?.text()
			MangaChapter(
				id = generateUid(href),
				url = href + stylePage,
				title = a.text(),
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(dateFormat, normalizeDate(dateText, currentYear)),
				scanlator = null,
				source = source,
			)
		}
	}

	private suspend fun requestAsyncChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
		val ajaxDoc = webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
		val ajaxChapters = parseChapters(ajaxDoc)
		if (ajaxChapters.isNotEmpty()) {
			return ajaxChapters
		}
		val mangaId = document.select("div#manga-chapters-holder").attr("data-id").trim()
		if (mangaId.isNotEmpty()) {
			val url = "https://$domain/wp-admin/admin-ajax.php"
			val postData = postDataReq + mangaId
			val adminAjaxDoc = webClient.httpPost(url, postData).parseHtml()
			val adminAjaxChapters = parseChapters(adminAjaxDoc)
			if (adminAjaxChapters.isNotEmpty()) {
				return adminAjaxChapters
			}
		}
		return emptyList()
	}

	private fun normalizeDate(date: String?, currentYear: String): String? {
		val text = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val lower = text.lowercase(Locale.ROOT)
		val match = absoluteDateRegex.matchEntire(lower) ?: return lower
		val rawMonth = match.groupValues[1].trim().trim('.')
		val day = match.groupValues[2]
		val year = match.groupValues[3].ifEmpty { currentYear }
		val month = monthAliases[rawMonth] ?: rawMonth
		return "$month $day, $year"
	}

	private fun normalizeMangaUrl(url: String): String {
		return url.substringBefore('?').removeSuffix("/")
	}

	private companion object {
		private const val CHAPTERS_CACHE_SIZE = 100
	}
}
