package org.koitharu.kotatsu.parsers.site.vi

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.splitByWhitespace
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlBuilder
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("BFANGTEAM", "Moè Truyện", "vi")
internal class BFANGTeam (context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.BFANGTEAM, 24) {

	override val configKeyDomain = ConfigKey.Domain("moetruyen.net")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = availableTags(),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegment("manga")

		if (!filter.query.isNullOrEmpty()) {
			url.addEncodedQueryParameter("q", filter.query)
		}

		// todo: need to use data-genre (num) instead of genre name
		if (filter.tags.isNotEmpty()) {
			filter.tags.forEach {
				url.addQueryParameter("include", it.key)
			}
		}

		// todo: need to use data-genre (num) instead of genre name
		if (filter.tagsExclude.isNotEmpty()) {
			filter.tagsExclude.forEach {
				url.addQueryParameter("exclude", it.key)
			}
		}

		if (!filter.author.isNullOrEmpty()) {
			url.addQueryParameter("q",
				filter.author.splitByWhitespace().joinToString(separator = "+") { it }
			)
		}

		// paging
		if (page > 1) {
			url.addQueryParameter("page", page.toString())
		}

		val request = webClient.httpGet(url.build()).parseHtml()
		return request.select("article.manga-card").map { ar ->
			val href = ar.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = ar.select(".manga-body h3").text(),
				altTitles = emptySet(),
				coverUrl = ar.select(".cover img").attr("src").toAbsoluteUrl(domain),
				largeCoverUrl = null,
				authors = emptySet(),
				tags = emptySet(),
				state = when (ar.select(".meta-row span.tag").text()) {
					"Hoàn thành" -> MangaState.FINISHED
					"Còn tiếp" -> MangaState.ONGOING
					"Tạm dừng" -> MangaState.PAUSED
					else -> null
				},
				description = null,
				contentRating = null,
				source = source,
				rating = RATING_UNKNOWN,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val div = doc.select(".detail-info.reveal")
		val isNsfw = div.select(".chips a.chip").any { it.text() == "Adult" }

		return manga.copy(
			authors = div.select("p.manga-author a.inline-link").map { it.text() }.toSet(),
			altTitles = div.select("p.note").map { it.text().substringAfter("Tên khác: ") }.toSet(),
			description = div.select(".manga-description p span").text(),
			contentRating = if (isNsfw) ContentRating.ADULT else null,
			tags = div.select(".chips a.chip").map {
				MangaTag(title = it.text(), key = it.text().urlEncoded(), source = source)
			}.toSet(),
			chapters = fetchAllChapters(manga.url.toAbsoluteUrl(domain), doc),
		)
	}

	private suspend fun fetchAllChapters(startUrl: String, firstPage: Document): List<MangaChapter> {
		val chapters = mutableListOf<MangaChapter>()
		val visited = mutableSetOf(startUrl)
		var doc = firstPage

		while (true) {
			chapters += parseChapters(doc)
			val nextUrl = doc.selectFirst("nav[aria-label*='Phân trang chương'] a[aria-label='Trang chương sau']:not(.is-disabled)")
				?.absUrl("href")?.takeIf { it.isNotEmpty() && it != "#" && visited.add(it) }
				?: break
			doc = webClient.httpGet(nextUrl).parseHtml()
		}

		return chapters.distinctBy { it.number }
	}

	private fun parseChapters(doc: Document): List<MangaChapter> =
		doc.select("li.chapter").mapChapters(true) { i, li ->
			val row = li.select("a .chapter-main .chapter-title-row")
			val title = row.select("span.chapter-title").text()
			MangaChapter(
				id = generateUid(title),
				title = title,
				number = row.select("span.chapter-num").text().substringAfter("Ch. ").toFloatOrNull() ?: (i + 1).toFloat(),
				volume = 0,
				url = li.select("a").attr("href").toAbsoluteUrl(domain),
				scanlator = li.selectFirst("span.chapter-sub-text")?.text(),
				uploadDate = parseChapterDate(li.select("span.chapter-time").text()),
				branch = null,
				source = source,
			)
		}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val response = webClient.httpGet(chapter.url).parseHtml()
		return response.select(".page-card img").mapNotNull { div ->
			val img = div.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
				?: div.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
				?: return@mapNotNull null

			val url = if (img.startsWith("http")) img else "https://i.$domain$img"
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChapterDate(dateText: String?): Long {
		if (dateText == null) return 0

		val relativeTimePattern = Regex("(\\d+)\\s*(phút|giờ|ngày|tuần) trước")
		val absoluteTimePattern = Regex("(\\d{2}-\\d{2}-\\d{4})")

		return when {
			dateText.contains("phút trước") -> {
				val match = relativeTimePattern.find(dateText)
				val minutes = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - minutes * 60 * 1000
			}

			dateText.contains("giờ trước") -> {
				val match = relativeTimePattern.find(dateText)
				val hours = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - hours * 3600 * 1000
			}

			dateText.contains("ngày trước") -> {
				val match = relativeTimePattern.find(dateText)
				val days = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - days * 86400 * 1000
			}

			dateText.contains("tuần trước") -> {
				val match = relativeTimePattern.find(dateText)
				val weeks = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - weeks * 7 * 86400 * 1000
			}

			absoluteTimePattern.matches(dateText) -> {
				val formatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
				formatter.parseSafe(dateText)
			}

			else -> 0L
		}
	}

	private suspend fun availableTags(): Set<MangaTag> {
		val url = urlBuilder().addPathSegment("manga").build()
		val response = webClient.httpGet(url).parseHtml()
		return response.select(".filter-options button").map {
			val span = it.select("span.filter-name")
			val title = span.text()
			MangaTag(
				title = title,
				key = title.urlEncoded(),
				source = source,
			)
		}.toSet()
	}
}
