package org.koitharu.kotatsu.parsers.site.madtheme.en

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.madtheme.MadthemeParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGABUDDY", "MangaBuddy Custom", "en")
internal class MangaBuddy(context: MangaLoaderContext) :
	MadthemeParser(context, MangaParserSource.MANGABUDDY, "mangak.io") {

	override val listUrl = "manga-list"

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	// 1. RE-ENGINEERED DYNAMIC ENGINE INTEGRATING PAGING, SEARCH AND FILTERS
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			
			// Detect active text queries to route to the search endpoint
			if (!filter.query.isNullOrBlank()) {
				append("/search/?search=")
				append(filter.query!!.urlEncoded())
				append("&page=")
				append(page.toString())
			} else {
				// Native layout routing sequence matching mangak.io
				val sortingPath = when (order) {
					SortOrder.POPULARITY -> "manga-list"
					SortOrder.UPDATED -> "latest"
					else -> "manga-list"
				}
				append("/")
				append(sortingPath)
				append("?page=")
				append(page.toString())
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		
		// Target structural list elements directly
		return doc.select(".manga-list .item, .story-item, div.bs, div.manga-box").map { element ->
			val link = element.selectFirstOrThrow("a")
			val href = link.attrAsRelativeUrl("href")
			val title = element.select(".title, h3, h4, .tt, .manga-name").text().trim()

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = element.selectFirst("img")?.attr("src")?.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null
			)
		}.filter { it.url.isNotEmpty() && it.title.isNotEmpty() }
	}

	// 2. UNIFIED DETAILS AND CHAPTER RE-BUILD EXTRACTION ENGINE
	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val title = doc.selectFirst(".detail h1, h1.title, .post-title h1")?.text()?.trim() ?: manga.title
		val description = doc.select(".summary .content, #manga-description, .description-summary").text().trim()

		// Extraction sequence parsing layout row entries
		val chapters = doc.select(".chapters-list li, .chapter-item, .wp-manga-chapter, .chbox, li.chapter").mapChapters(reversed = true) 
		{ index, element ->
			val link = element.selectFirst("a") ?: return@mapChapters null
			val href = link.attrAsRelativeUrl("href")
			val chapterTitle = element.select(".chapter-title, span").text().trim().ifEmpty { link.text().trim() }

			MangaChapter(
				id = generateUid(href),
				url = href,
				title = chapterTitle,
				uploadDate = 0L,
				source = source,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				branch = null
			)
		}

		return manga.copy(
			title = title,
			description = description,
			chapters = chapters,
			contentRating = ContentRating.SAFE
		)
	}

	// 3. TARGET DIRECT READER VIEWER CONTAINER PAGES
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		val pageElements = doc.select(".page-break img, .reading-content img, .wp-manga-chapter-img, img.chapter-img, #readerarea img")
		
		if (pageElements.isEmpty()) {
			throw org.koitharu.kotatsu.parsers.exception.ParseException("Empty reading layout view block caught", fullUrl)
		}

		return pageElements.mapIndexed { index, element ->
			val imageUrl = element.attr("data-src").takeIf { it.isNotEmpty() }
				?: element.attr("data-lazy-src").takeIf { it.isNotEmpty() }
				?: element.attr("src")

			MangaPage(
				id = generateUid("${chapter.url}#$index"),
				url = imageUrl.trim().toAbsoluteUrl(domain),
				preview = null,
				source = source
			)
		}
	}
}
