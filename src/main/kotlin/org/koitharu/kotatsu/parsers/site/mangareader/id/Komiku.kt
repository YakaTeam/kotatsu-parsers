package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet

/* Need some refactors */
@MangaSourceParser("KOMIKU", "Komiku", "id")
internal class Komiku(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKU, "komiku.org", 10, 10) {

	private val apiDomain = "api.komiku.org"
	override val datePattern = "dd/MM/yyyy"
	override val selectPage = "#Baca_Komik img"
	override val selectTestScript = "script:containsData(thisIsNeverFound)"
	override val listUrl = "/manga/"
	override val selectMangaList = "div.bge"
	override val selectMangaListImg = "div.bgei img"
	override val selectMangaListTitle = "div.kan h3"
	override val selectChapter = "#Daftar_Chapter tr:has(td.judulseries)"
	override val detailsDescriptionSelector = "#Sinopsis > p"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val url = buildString {
			append("https://")
			append(apiDomain)
			if (query.isNotEmpty()) {
				append("/?post_type=manga&s=")
				append(query.urlEncoded())
				if (page > 1) {
					append("&page=")
					append(page)
				}
			} else {
				append("/manga/page/")
				append(page)
				append("/")
				val params = mutableListOf<String>()
				val genres = filter.tags.map { it.key }
				genres.getOrNull(0)?.let { params += "genre=$it" }
				genres.getOrNull(1)?.let { params += "genre2=$it" }
				val orderParam = when (order) {
					SortOrder.UPDATED -> "modified"
					SortOrder.NEWEST -> "date"
					SortOrder.POPULARITY -> "meta_value_num"
					SortOrder.ALPHABETICAL -> "title"
					else -> null
				}
				if (orderParam != null) params += "orderby=$orderParam"
				filter.types.oneOrThrowIfMany()?.let {
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> null
					}
				}?.let { params += "tipe=$it" }
				filter.states.oneOrThrowIfMany()?.let {
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "end"
						else -> null
					}
				}?.let { params += "status=$it" }
				if (params.isNotEmpty()) {
					append("?")
					append(params.joinToString("&"))
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(selectMangaList).mapNotNull { element ->
			val a = element.selectFirst("div.bgei a[href*=/manga/]")
				?: element.selectFirst("a[href*=/manga/]")
				?: return@mapNotNull null

			val href = a.attr("href")
			val relativeUrl = href.toRelativeUrl(domain)

			val thumbnailUrl = element.selectFirst(selectMangaListImg)?.let { img ->
				img.attr("data-src").ifBlank { img.attr("src") }
			}?.substringBeforeLast("?")

			val title = element.selectFirst(selectMangaListTitle)?.text()
				?: return@mapNotNull null

			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = title,
				altTitles = emptySet(),
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = thumbnailUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
				description = element.selectFirst("div.kan p")?.text(),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
			val a = element.selectFirst("td.judulseries a") ?: return@mapChapters null
			val url = a.attrAsRelativeUrl("href")
			val dateText = element.selectFirst("td.tanggalseries")?.text()
			MangaChapter(
				id = generateUid(url),
				title = a.selectFirst("span")?.text() ?: a.text(),
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}

		return parseInfo(docs, manga, chapters)
	}

	override suspend fun parseInfo(docs: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
		val tags = docs.select("ul.genre li.genre a, table.inftable a[href*=/genre/]").mapNotNullToSet { element ->
			val href = element.attr("href")
			val genreKey = href.substringAfter("/genre/").substringBefore("/").ifBlank { return@mapNotNullToSet null }
			val genreTitle = element.selectFirst("span[itemprop='genre']")?.text() ?: element.text()
			MangaTag(
				key = genreKey,
				title = genreTitle.toTitleCase(sourceLocale),
				source = source,
			)
		}

		val statusText = docs.selectFirst("table.inftable tr > td:contains(Status) + td")?.text().orEmpty()
		val state = when {
			statusText.contains("Ongoing", true) -> MangaState.ONGOING
			statusText.contains("Completed", true) -> MangaState.FINISHED
			statusText.contains("Tamat", true) -> MangaState.FINISHED
			statusText.contains("End", true) -> MangaState.FINISHED
			else -> null
		}

		val author = docs.selectFirst("table.inftable tr:has(td:contains(Pengarang)) td:last-child")?.text()
		val altTitle = docs.selectFirst("table.inftable tr:has(td:contains(Judul Indonesia)) td:last-child")?.text()

		val thumbnail = docs.selectFirst("div.ims > img")?.let { img ->
			img.attr("data-src").ifBlank { img.attr("src") }
		}?.substringBeforeLast("?")

		return manga.copy(
			altTitles = setOfNotNull(altTitle),
			description = docs.selectFirst(detailsDescriptionSelector)?.text() ?: manga.description,
			state = state ?: manga.state,
			authors = setOfNotNull(author),
			tags = tags,
			chapters = chapters,
			coverUrl = thumbnail ?: manga.coverUrl,
		)
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/pustaka/").parseHtml()
		val tags = mutableSetOf<MangaTag>()

		doc.select("select[name='genre'] option").forEach { option ->
			val value = option.attr("value").trim()
			val title = option.text().substringBefore("(").trim()

			if (value.isNotBlank() && !title.equals("Genre", true)) {
				tags.add(
					MangaTag(
						key = value,
						title = title,
						source = source,
					),
				)
			}
		}
		return tags
	}
}
