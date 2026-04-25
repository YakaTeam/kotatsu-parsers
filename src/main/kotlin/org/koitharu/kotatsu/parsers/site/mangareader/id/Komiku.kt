package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("KOMIKU", "Komiku", "id")
internal class Komiku(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKU, "komiku.org", pageSize = 60, searchPageSize = 60) {

	override val datePattern = "dd/MM/yyyy"
	override val selectPage = "#Baca_Komik img"
	override val selectTestScript = "script:containsData(thisIsNeverFound)"
	override val listUrl = "/daftar-komik/"
	override val selectMangaList = "article.manga-card"
	override val selectMangaListImg = "img"
	override val selectMangaListTitle = "h4"
	override val selectChapter = "#Daftar_Chapter tr:has(td.judulseries)"
	override val detailsDescriptionSelector = "#Sinopsis > p"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// komiku.org's /pustaka/, /?s=, and /genre/ pages are htmx shells whose data is
		// fetched client-side from api.komiku.org. With direct (no-JS) HTML scraping we
		// can only reliably read /daftar-komik/, which is fully server-rendered.
		// We therefore base listing on /daftar-komik/ and apply best-effort client-side
		// filtering for query / state / type. Genre filter and ordering are not exposed
		// by /daftar-komik/ and are silently ignored.
		val tipe = filter.types.oneOrThrowIfMany()?.let {
			when (it) {
				ContentType.MANGA -> "manga"
				ContentType.MANHWA -> "manhwa"
				ContentType.MANHUA -> "manhua"
				else -> null
			}
		}

		val url = buildString {
			append("https://")
			append(domain)
			append(listUrl)
			if (page > 1) {
				append("page/")
				append(page.toString())
				append("/")
			}
			if (tipe != null) {
				append("?tipe=")
				append(tipe)
			}
		}

		val all = parseMangaList(webClient.httpGet(url).parseHtml())

		// Best-effort client-side filtering for the fields /daftar-komik/ does not
		// support natively. State is rendered inline in p.meta as "Status: Ongoing/End".
		val state = filter.states.oneOrThrowIfMany()
		val query = filter.query?.trim().orEmpty()
		return all.filter { manga ->
			val matchesQuery = query.isEmpty() ||
				manga.title.contains(query, ignoreCase = true) ||
				manga.altTitles.any { it.contains(query, ignoreCase = true) }
			val matchesState = state == null || manga.state == state
			matchesQuery && matchesState
		}
	}

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(selectMangaList).mapNotNull { element ->
			val a = element.selectFirst("a[href*=/manga/]") ?: return@mapNotNull null
			val relativeUrl = a.attrAsRelativeUrl("href").toRelativeUrl(domain)

			val thumbnailUrl = element.selectFirst(selectMangaListImg)?.let { img ->
				img.attr("data-src").ifBlank { img.attr("src") }
			}?.substringBeforeLast("?")

			val meta = element.selectFirst("p.meta")?.text().orEmpty()
			val state = when {
				meta.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
				meta.contains("End", ignoreCase = true) -> MangaState.FINISHED
				meta.contains("Tamat", ignoreCase = true) -> MangaState.FINISHED
				else -> null
			}

			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = element.selectFirst(selectMangaListTitle)?.text()?.trim() ?: return@mapNotNull null,
				altTitles = emptySet(),
				publicUrl = a.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = thumbnailUrl,
				tags = emptySet(),
				state = state,
				authors = emptySet(),
				source = source,
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
				title = a.selectFirst("span")?.text()?.trim() ?: a.text().trim(),
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
			val genreTitle = element.selectFirst("span[itemprop='genre']")?.text()?.trim()
				?: element.text().trim()

			MangaTag(
				key = genreKey,
				title = genreTitle.toTitleCase(sourceLocale),
				source = source,
			)
		}

		val statusText = docs.selectFirst("table.inftable tr > td:contains(Status) + td")?.text()
		val state = when {
			statusText == null -> null
			statusText.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("Completed", ignoreCase = true) -> MangaState.FINISHED
			statusText.contains("Tamat", ignoreCase = true) -> MangaState.FINISHED
			statusText.contains("End", ignoreCase = true) -> MangaState.FINISHED
			else -> null
		}

		val author = docs.selectFirst("table.inftable tr:has(td:contains(Pengarang)) td:last-child")?.text()?.trim()

		val altTitle =
			docs.selectFirst("table.inftable tr:has(td:contains(Judul Indonesia)) td:last-child")?.text()?.trim()
		val altTitles = if (!altTitle.isNullOrBlank()) setOf(altTitle) else emptySet()

		val thumbnail = docs.selectFirst("div.ims > img")?.let { img ->
			img.attr("data-src").ifBlank { img.attr("src") }
		}?.substringBeforeLast("?")

		return manga.copy(
			altTitles = altTitles,
			description = docs.selectFirst(detailsDescriptionSelector)?.text()?.trim(),
			state = state ?: manga.state,
			authors = setOfNotNull(author),
			contentRating = if (manga.contentRating == ContentRating.ADULT) ContentRating.ADULT else ContentRating.SAFE,
			tags = tags,
			chapters = chapters,
			coverUrl = thumbnail ?: manga.coverUrl,
		)
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		// The full genre <select> is rendered server-side on the /pustaka/ shell page.
		// We only need the option keys/titles, not the htmx-loaded results below it.
		val doc = webClient.httpGet("https://$domain/pustaka/").parseHtml()
		val tags = mutableSetOf<MangaTag>()

		doc.select("select[name='genre'] option").forEach { option ->
			val value = option.attr("value").trim()
			val title = option.text().substringBefore("(").trim()

			if (value.isNotBlank() && !title.equals("Genre", ignoreCase = true)) {
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
