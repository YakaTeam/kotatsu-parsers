package org.koitharu.kotatsu.parsers.site.madtheme.en

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madtheme.MadthemeParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("MANGAJINX", "MangaJinx", "en")
internal class MangaJinx(context: MangaLoaderContext) :
	MadthemeParser(context, MangaParserSource.MANGAJINX, "mgjinx.com") {
	override val listUrl = "search"

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override suspend fun getChapters(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val id = doc.selectFirstOrThrow("script:containsData(bookId)").data().substringAfter("bookId = ")
			.substringBefore(";")
		val docChapter = webClient.httpGet("https://$domain/service/backend/chaplist/?manga_id=$id").parseHtml()
		return docChapter.select(selectChapter).mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val dateText = li.selectFirst(selectDate)?.text()
			MangaChapter(
				id = generateUid(href),
				title = li.selectFirstOrThrow(".chapter-title").text(),
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	// MangaJinx images are served from a signed CDN (acc=<token>&expires=<unix>) with a
	// ~12 h token lifetime. The base parser bakes those URLs into MangaPage#url at fetch
	// time, so any chapter opened more than ~12 h after the list was populated 404s and
	// surfaces as "failed to create image decoder". Store the per-page ref only and
	// resolve the current signed URL lazily in getPageUrl.
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val chapterId = doc.selectFirstOrThrow("script:containsData(chapterId)").data()
			.substringAfter("chapterId = ").substringBefore(";").trim()
		val count = doc.select("script").firstNotNullOfOrNull { script ->
			CHAP_IMAGES_REGEX.find(script.html())?.groupValues?.getOrNull(1)
		}?.split(',')?.size
			?: throw ParseException("chapImages not found", chapter.url)
		val endpoint = "/service/backend/chapterServer/?server_id=1&chapter_id=$chapterId"
		return (0 until count).map { i ->
			val pageRef = "$endpoint#$i"
			MangaPage(
				id = generateUid("${chapter.url}#$i"),
				url = pageRef,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val endpoint = page.url.substringBeforeLast('#')
		val index = page.url.substringAfterLast('#').toIntOrNull()
			?: throw ParseException("Malformed page ref: ${page.url}", page.url)
		val doc = webClient.httpGet(endpoint.toAbsoluteUrl(domain)).parseHtml()
		val items = doc.select("div.chapter-image")
		val src = items.getOrNull(index)?.attr("data-src")?.nullIfEmpty()
			?: throw ParseException(
				"Page $index of ${items.size} missing data-src at chapterServer",
				page.url,
			)
		return src
	}

	companion object {
		private val CHAP_IMAGES_REGEX = Regex("""chapImages\s*=\s*['"](.*?)['"]""")
	}
}
