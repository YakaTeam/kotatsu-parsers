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
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.jsoup.nodes.Document

@MangaSourceParser("MANGABUDDY", "MangaBuddy Custom", "en")
internal class MangaBuddy(context: MangaLoaderContext) :
	MangaSourceParserBase(context, MangaParserSource.MANGABUDDY) {

	private val domain = "mangak.io"

	override fun getFilters(): MangaFilters {
		return MangaFilters(
			listOf(
				MangaFilter.Select(
					id = "sort_by",
					name = "Sort By",
					options = listOf(
						"Latest Update" to "latest",
						"Trending / Popular" to "manga-list"
					),
					defaultValue = "latest"
				)
			)
		)
	}

	override suspend fun getList(page: Int, filter: Any?): List<Manga> {
		val sortBy = (filter as? MangaFilters)?.getSelectValue("sort_by") ?: "latest"
		val url = "https://$domain/$sortBy?page=$page"
		
		val doc = context.httpClient.httpGet(url).parseHtml()
		
		return doc.select(".manga-list .item, .story-item, div.bs, div.manga-box").map { element ->
			val linkElement = element.select("a").firstOrNull()
			val mangaUrl = linkElement?.attr("href")?.toAbsoluteUrl(domain).orEmpty()
			
			Manga(
				id = context.generateUid(mangaUrl),
				title = element.select(".title, h3, h4, .tt, .manga-name").text().trim(),
				url = mangaUrl,
				cover = element.select("img").attr("src").toAbsoluteUrl(domain),
				source = source
			)
		}.filter { it.url.isNotEmpty() && it.title.isNotEmpty() }
	}

	override suspend fun search(query: String, page: Int): List<Manga> {
		val url = "https://$domain/search/?search=$query&page=$page"
		val doc = context.httpClient.httpGet(url).parseHtml()
		
		return doc.select(".story-item, div.manga-box, div.bs, .item").map { element ->
			val linkElement = element.select("a").firstOrNull()
			val mangaUrl = linkElement?.attr("href")?.toAbsoluteUrl(domain).orEmpty()
			Manga(
				id = context.generateUid(mangaUrl),
				title = element.select(".title, h3, .tt, .manga-name").text().trim(),
				url = mangaUrl,
				cover = element.select("img").attr("src").toAbsoluteUrl(domain),
				source = source
			)
		}.filter { it.url.isNotEmpty() }
	}

	override suspend fun getChapters(manga: Manga): List<MangaChapter> {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = context.httpClient.httpGet(fullUrl).parseHtml()
		
		return doc.select(".chapters-list li, .chapter-item, .wp-manga-chapter, .chbox, li.chapter").mapIndexed { index, element ->
			val link = element.select("a").firstOrNull()
			val chapterUrl = link?.attr("href")?.toAbsoluteUrl(domain).orEmpty()
			
			MangaChapter(
				id = context.generateUid(chapterUrl),
				title = link?.text()?.trim() ?: "Chapter ${index + 1}",
				url = chapterUrl,
				number = (index + 1).toFloat(),
				volume = 0,
				uploadDate = 0L,
				branch = ""
			)
		}.filter { it.url.isNotEmpty() }.reversed()
	}

	override fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = context.httpClient.httpGet(fullUrl).parseHtml()
		
		val pageElements = doc.select(".page-break img, .reading-content img, .wp-manga-chapter-img, img.chapter-img, #readerarea img")
		
		if (pageElements.isNotEmpty()) {
			return pageElements.mapIndexed { index, element ->
				val imageUrl = element.attr("data-src").takeIf { it.isNotEmpty() }
					?: element.attr("data-lazy-src").takeIf { it.isNotEmpty() }
					?: element.attr("src")

				MangaPage(
					id = context.generateUid(imageUrl),
					url = imageUrl.trim().toAbsoluteUrl(domain),
					preview = null
				)
			}
		}
		return emptyList()
	}
	}
	
