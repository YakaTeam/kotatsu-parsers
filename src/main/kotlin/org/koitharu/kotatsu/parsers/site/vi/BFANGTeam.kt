package org.koitharu.kotatsu.parsers.site.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
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

@MangaSourceParser("BFANGTEAM", "BFANG Team (Động Mòe)", "vi")
internal class BFANGTeam (context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.BFANGTEAM) {

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

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegment("manga")

		if (!filter.query.isNullOrEmpty()) {
			url.addQueryParameter("q",
				filter.query.splitByWhitespace().joinToString(separator = "+") { it }
			)
		}

		if (filter.tags.isNotEmpty()) {
			filter.tags.forEach {
				url.addQueryParameter("include", it.key)
			}
		}

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

		val request = webClient.httpGet(url.build()).parseHtml()
		return request.select("article.manga-card.reveal").map { ar ->
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
		val response = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val div = response.select(".detail-info.reveal")
		val author = div.select("p.manga-author a.inline-link").map { it.text() }.toSet()
		val altTitles = div.select("p.note").map { it.text().substringAfter("Tên khác: ") }.toSet()
		val description = div.select(".manga-description p span").text()
		val isNsfw = div.select(".chips a.chip").any { it.text() == "Adult" }
		val tags = div.select(".chips a.chip").map {
			val tag = it.text()
			MangaTag(
				title = tag,
				key = tag.urlEncoded(),
				source = source,
			)
		}.toSet()

		return manga.copy(
			authors = author,
			altTitles = altTitles,
			chapters = response.select("li.chapter").mapChapters(true) { i, li ->
				val chapterMain = li.select("a .chapter-main .chapter-title-row")
				val chapNum = chapterMain.select("span.chapter-num")
					.text().substringAfter("Ch. ").toFloatOrNull() ?: (i + 1).toFloat()
				val chapTitle = chapterMain.select("span.chapter-title").text()
				MangaChapter(
					id = generateUid(chapTitle),
					title = chapTitle,
					number = chapNum,
					volume = 0,
					url = li.select("a").attr("href").toAbsoluteUrl(domain),
					scanlator = li.select("span.chapter-sub-text").text(),
					uploadDate = parseChapterDate(li.select("span.chapter-time").text()),
					branch = null,
					source = source,
				)
			},
			contentRating = if (isNsfw) ContentRating.ADULT else null,
			description = description,
			tags = tags,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val response = webClient.httpGet(chapter.url).parseHtml()
		return response.select(".page-card img").mapNotNull { div ->
			val img = div.attrAsRelativeUrlOrNull("src")
				?: div.attrAsRelativeUrlOrNull("data-src")
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(img),
				url = img,
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
