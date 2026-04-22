package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("HENTAI2READ", "Hentai2Read", "en", ContentType.HENTAI)
internal class Hentai2Read(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.HENTAI2READ, pageSize = 48) {

	override val configKeyDomain = ConfigKey.Domain("hentai2read.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val sortSegment = when (order) {
			SortOrder.NEWEST -> "last-added"
			SortOrder.UPDATED -> "last-updated"
			SortOrder.POPULARITY -> "most-popular"
			SortOrder.RATING -> "highest-rating"
			SortOrder.ALPHABETICAL_DESC -> "name-za"
			else -> "name-az"
		}
		val query = filter.query?.trim().orEmpty()
		val doc = if (query.isEmpty()) {
			val url = "https://$domain/hentai-list/all/any/all/$sortSegment/$page/"
			webClient.httpGet(url).parseHtml()
		} else {
			val url = "https://$domain/hentai-list/search/any/all/$sortSegment/$page/"
			webClient.httpPost(
				url,
				mapOf(
					"cmd_wpm_wgt_mng_sch_sbm" to "Search",
					"txt_wpm_wgt_mng_sch_nme" to query,
				),
			).parseHtml()
		}
		return doc.select("div.book-grid-item-container").mapNotNull { card ->
			val link = card.selectFirst("a.title") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val cover = card.selectFirst("picture img")?.attrAsAbsoluteUrl("src")
			val title = card.selectFirst("a.title span.title-text")?.text()?.trim().orEmpty()
			val statusClass = card.selectFirst("div.book-grid-item")?.className().orEmpty()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = cover,
				tags = emptySet(),
				state = parseStatusFromClass(statusClass),
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val infoList = doc.select("ul.list.list-simple-mini > li")
		val altTitle = infoList.firstOrNull { it.hasClass("text-muted") }?.text()?.trim()
		var status: MangaState? = null
		val authors = HashSet<String>()
		val tags = HashSet<MangaTag>()
		for (li in infoList) {
			val label = li.selectFirst("b")?.text()?.trim().orEmpty()
			when (label) {
				"Status" -> status = when (li.selectFirst("a.tagButton")?.text()?.trim()) {
					"Ongoing" -> MangaState.ONGOING
					"Completed" -> MangaState.FINISHED
					else -> null
				}

				"Author", "Artist" -> {
					li.select("a.tagButton").forEach { a ->
						val name = a.text().trim()
						if (name.isNotEmpty() && name != "-") {
							authors.add(name)
						}
					}
				}

				"Category" -> {
					li.select("a.tagButton").forEach { a ->
						val name = a.text().trim()
						if (name.isNotEmpty()) {
							tags.add(
								MangaTag(
									key = a.attr("href").substringAfter("/category/").trimEnd('/'),
									title = name.toTitleCase(sourceLocale),
									source = source,
								),
							)
						}
					}
				}
			}
		}
		val rating = doc.selectFirst("div.js-raty")?.attr("data-score")?.toFloatOrNull()?.let { it / 5f }
			?: RATING_UNKNOWN
		val chapters = doc.select("ul.nav-chapters > li").let { items ->
			items.mapChapters(reversed = true) { i, li ->
				val a = li.selectFirstOrThrow("a.pull-left")
				val href = a.attrAsRelativeUrl("href")
				val name = a.ownText().trim().ifEmpty { "Chapter ${i + 1}" }
				val dateText = li.selectFirst("div.text-muted small")?.text()
				MangaChapter(
					id = generateUid(href),
					title = name,
					number = (i + 1).toFloat(),
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseChapterDate(dateText),
					branch = null,
					source = source,
				)
			}
		}
		return manga.copy(
			altTitles = setOfNotNull(altTitle?.takeIf { it.isNotEmpty() && it != manga.title }),
			state = status,
			tags = tags,
			authors = authors,
			rating = rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val script = doc.selectFirstOrThrow("script:containsData(var gData)").data()
		val imagesRaw = script.substringAfter("'images'").substringAfter('[').substringBefore(']')
		val json = JSONArray("[$imagesRaw]")
		val result = ArrayList<MangaPage>(json.length())
		for (i in 0 until json.length()) {
			val path = json.getString(i)
			val url = "https://static.hentai.direct/hentai$path"
			result.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				),
			)
		}
		return result
	}

	private fun parseStatusFromClass(classNames: String): MangaState? = when {
		classNames.contains("status-completed") -> MangaState.FINISHED
		classNames.contains("status-ongoing") -> MangaState.ONGOING
		else -> null
	}

	private fun parseChapterDate(raw: String?): Long {
		if (raw.isNullOrBlank()) return 0L
		val text = raw.lowercase(Locale.ROOT)
		val match = DATE_REGEX.find(text) ?: return 0L
		val value = match.groupValues[1].toIntOrNull() ?: return 0L
		val unit = match.groupValues[2]
		val now = System.currentTimeMillis()
		return now - value * when {
			unit.startsWith("second") -> 1_000L
			unit.startsWith("minute") -> 60_000L
			unit.startsWith("hour") -> 3_600_000L
			unit.startsWith("day") -> 86_400_000L
			unit.startsWith("week") -> 7 * 86_400_000L
			unit.startsWith("month") -> 30 * 86_400_000L
			unit.startsWith("year") -> 365 * 86_400_000L
			else -> return 0L
		}
	}

	private companion object {
		val DATE_REGEX = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?""")
	}
}
