package org.koitharu.kotatsu.parsers.site.madtheme.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.MangaSourceParserBase
import org.koitharu.kotatsu.parsers.model.filter.MangaFilter
import org.koitharu.kotatsu.parsers.model.filter.MangaFilters
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.jsoup.nodes.Document

@MangaSourceParser("MANGABUDDY", "MangaBuddy Custom", "en")
internal class MangaBuddy(context: MangaLoaderContext) :
	MangaSourceParserBase(context, MangaParserSource.MANGABUDDY) {

	private val domain = "mangak.io"

	// 1. DEFINE USER CONTROLS FOR THE APP UI
	override fun getFilters(): MangaFilters {
		return MangaFilters(
			listOf(
				MangaFilter.Select(
					id = "sort_by",
					name = "Sort By",
					options = listOf(
						"Latest Update" to "latest",
						"Trending / Popular" to "popular"
					),
					defaultValue = "latest"
				)
			)
		)
	}

	// 2. DYNAMIC SORTING LIST ENGINE
	override suspend fun getList(page: Int, filter: Any?): List<Manga> {
		// Extract user selection safely or fallback to default
		val sortBy = (filter as? MangaFilters)?.getSelectValue("sort_by") ?: "latest"
		
		// Map options directly to the site's distinct URL endpoint structures
		val path = if (sortBy == "popular") "manga-list" else "latest"
		val url = "https://$domain/$path?page=$page"
		
		val doc = webClient.httpGet(url).parseHtml()
		
		// Parse the targeted layout items safely
		return doc.select("div.story-item, div.manga-box, .page-item, div.bs").map { element ->
			val linkElement = element.select("a").firstOrNull()
			val mangaUrl = linkElement?.attr("href")?.toAbsoluteUrl(domain).orEmpty()
			
			Manga(
				id = generateUid(mangaUrl),
				title = element.select(".title, h3, h4, .tt").text().trim(),
				url = mangaUrl,
				cover = element.select("img").attr("src").toAbsoluteUrl(domain),
				source = source
			)
		}.filter { it.url.isNotEmpty() }
	}

	// 3. SEARCH ENGINE
	override suspend fun search(query: String, page: Int): List<Manga> {
		val url = "https://$domain/search/?search=$query&page=$page"
		val doc = webClient.httpGet(url).parseHtml()
		
		return doc.select("div.story-item, div.manga-box, div.bs").map { element ->
			val mangaUrl = element.select("a").attr("href").toAbsoluteUrl(domain)
			Manga(
				id = generateUid(mangaUrl),
				title = element.select(".title, h3, .tt").text().trim(),
				url = mangaUrl,
				cover = element.select("img").attr("src").toAbsoluteUrl(domain),
				source = source
			)
		}
	}

	// 4. CHAPTER EXTRACTION
	override suspend fun getChapters(manga: Manga): List<MangaChapter> {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		return doc.select("ul.chapters-list li, div.chapter-item, .wp-manga-chapter, .chbox").mapIndexed { index, element ->
			val link = element.select("a").firstOrNull()
			val chapterUrl = link?.attr("href")?.toAbsoluteUrl(domain).orEmpty()
			
			MangaChapter(
				id = generateUid(chapterUrl),
				name = link?.text()?.trim() ?: "Chapter ${index + 1}",
				url = chapterUrl,
				scanlator = null,
				date = null,
				source = source
			)
		}.filter { it.url.isNotEmpty() }.reversed()
	}

	// 5. HIGH-RESOLUTION PAGE VIEWER IMAGES
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		// Extracted from common theme image targets
		val pageElements = doc.select("div.page-break img, div.reading-content img, .wp-manga-chapter-img, img.chapter-img, #readerarea img")
		
		if (pageElements.isNotEmpty()) {
			return pageElements.mapIndexed { index, element ->
				val imageUrl = element.attr("data-src").takeIf { it.isNotEmpty() }
					?: element.attr("data-lazy-src").takeIf { it.isNotEmpty() }
					?: element.attr("src")

				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl.trim().toAbsoluteUrl(domain),
					preview = null,
					source = source
				)
			}
		}
		return emptyList()
	}
}
