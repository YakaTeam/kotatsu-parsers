package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
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
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("THEMANGA", "TheManga", "id")
internal class TheManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.THEMANGA, 20) {

	override val configKeyDomain = ConfigKey.Domain("themanga.site")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return when {
			!filter.query.isNullOrEmpty() -> searchManga(filter.query)
			filter.tags.isNotEmpty() -> getMangaListByGenre(filter.tags.first().key, page, filter.tags)
			else -> getAllManga(page, order)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val heroMeta = doc.selectFirstOrThrow(".hero-meta-grid")
		val author = heroMeta.selectFirst(".meta-item-value")?.text()
		val genres = doc.select(".meta-pill").mapToSet { span ->
			MangaTag(
				key = span.text().lowercase().replace(" ", "-"),
				title = span.text().toTitleCase(sourceLocale),
				source = source,
			)
		}

		val statusBadge = doc.selectFirst(".hero-status-badge")
		val status = when {
			statusBadge?.hasClass("is-completed") == true -> MangaState.FINISHED
			statusBadge?.hasClass("is-hiatus") == true -> MangaState.PAUSED
			statusBadge?.hasClass("is-canceled") == true -> MangaState.ABANDONED
			statusBadge != null -> MangaState.ONGOING
			else -> null
		}

		return manga.copy(
			title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
			description = doc.selectFirst(".hero-description")?.textOrNull(),
			coverUrl = doc.selectFirst(".hero-image img, .hero-cover img")?.src() ?: manga.coverUrl,
			tags = genres,
			authors = setOfNotNull(author),
			state = status,
			chapters = parseChapters(doc),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val pages = doc.select("img[alt^=Page]")
		if (pages.isEmpty()) {
			val wrapPages = doc.select("#page-wrap img")
			if (wrapPages.isNotEmpty()) {
				return parsePageUrls(wrapPages)
			}
			throw ParseException("No images found on page", fullUrl)
		}

		return parsePageUrls(pages)
	}

	/* Utilities, may need some refactors */

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		val regex = Regex("""\?genre=(\w+)""")

		return doc.select("a.genre-shelf-link[href*='?genre=']").mapNotNull { element ->
			regex.find(element.attr("href"))?.groupValues?.get(1)
		}.distinct().map { genre ->
			MangaTag(
				key = genre,
				title = genre.toTitleCase(sourceLocale),
				source = source
			)
		}.toSet()
	}

	private fun getSortParam(order: SortOrder): String {
		return when (order) {
			SortOrder.POPULARITY -> "popular"
			SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "latest_update"
			SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "latest_update"
			SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "title"
			SortOrder.RATING, SortOrder.RATING_ASC -> "rating"
			else -> "popular"
		}
	}

	private suspend fun searchManga(query: String): List<Manga> {
		val url = "https://$domain/search/quick?q=${query.urlEncoded()}"
		val json = webClient.httpGet(url).parseJson()
		val results = json.getJSONArray("results")

		return results.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				url = "/manga/$slug",
				publicUrl = "https://$domain/manga/$slug",
				title = jo.getString("title"),
				altTitles = emptySet(),
				coverUrl = jo.optString("cover_url").nullIfEmpty(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
				contentRating = null,
			)
		}
	}

	private suspend fun getAllManga(page: Int, order: SortOrder): List<Manga> {
		val result = mutableListOf<Manga>()
		val sortParam = getSortParam(order)

		// Fetch popular page with sort
		val popularJson = webClient.httpGet("https://$domain/home/lazy/popular").parseJson()
		val popularHtml = popularJson.getString("html")
		val popularDoc = Jsoup.parse(popularHtml, "https://$domain/")

		result.addAll(popularDoc.select("a.popular-feature-card").map { a ->
			val href = a.attrAsRelativeUrl("href")
			val slug = href.removePrefix("/manga/")
			Manga(
				id = generateUid(slug),
				url = "/manga/$slug",
				publicUrl = "https://$domain$href",
				title = a.selectFirstOrThrow(".popular-feature-title").text(),
				altTitles = emptySet(),
				coverUrl = a.selectFirst("img")?.src(),
				rating = a.selectFirst(".popular-feature-rating span")?.ownText()?.toFloatOrNull()?.div(2f) ?: RATING_UNKNOWN,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
				contentRating = null,
			)
		})

		// Fetch genre-based listings with sort
		val genres = listOf("action", "fantasy", "romance", "isekai", "adventure", "comedy", "drama")
		for (genre in genres) {
			if (result.size >= 50) break
			try {
				val genreUrl = if (page == 0) {
					"https://$domain?genre=$genre&sort=$sortParam"
				} else {
					"https://$domain?genre=$genre&sort=$sortParam&page=${page + 1}"
				}
				val genreDoc = webClient.httpGet(genreUrl).parseHtml()
				genreDoc.select("a.card").forEach { a ->
					val href = a.attrAsRelativeUrl("href")
					val slug = href.removePrefix("/manga/")
					val statusBadge = a.selectFirst(".status-badge")
					val coverImg = a.selectFirst("img")
					val manga = Manga(
						id = generateUid(slug),
						url = "/manga/$slug",
						publicUrl = "https://$domain$href",
						title = coverImg?.attr("alt")?.nullIfEmpty() ?: a.text(),
						altTitles = emptySet(),
						coverUrl = coverImg?.src(),
						rating = RATING_UNKNOWN,
						tags = emptySet(),
						state = when {
							statusBadge?.hasClass("is-completed") == true -> MangaState.FINISHED
							statusBadge?.hasClass("is-hiatus") == true -> MangaState.PAUSED
							statusBadge?.hasClass("is-canceled") == true -> MangaState.ABANDONED
							statusBadge != null -> MangaState.ONGOING
							else -> null
						},
						authors = emptySet(),
						source = source,
						contentRating = null,
					)
					if (result.none { it.id == manga.id }) {
						result.add(manga)
					}
				}
			} catch (_: Exception) {
				// Skip if genre fails
			}
		}

		return result
	}

	private suspend fun getMangaListByGenre(genre: String, page: Int, tags: Set<MangaTag>): List<Manga> {
		val sortParam = getSortParam(defaultSortOrder)
		val urlBuilder = StringBuilder("https://$domain?genre=$genre&sort=$sortParam")
		if (page > 0) {
			urlBuilder.append("&page=${page + 1}")
		}

		val doc = webClient.httpGet(urlBuilder.toString()).parseHtml()
		return doc.select("a.card").map { a ->
			val href = a.attrAsRelativeUrl("href")
			val slug = href.removePrefix("/manga/")
			val statusBadge = a.selectFirst(".status-badge")
			val coverImg = a.selectFirst("img")
			Manga(
				id = generateUid(slug),
				url = "/manga/$slug",
				publicUrl = "https://$domain$href",
				title = coverImg?.attr("alt")?.nullIfEmpty() ?: a.text(),
				altTitles = emptySet(),
				coverUrl = coverImg?.src(),
				rating = RATING_UNKNOWN,
				tags = tags,
				state = when {
					statusBadge?.hasClass("is-completed") == true -> MangaState.FINISHED
					statusBadge?.hasClass("is-hiatus") == true -> MangaState.PAUSED
					statusBadge?.hasClass("is-canceled") == true -> MangaState.ABANDONED
					statusBadge != null -> MangaState.ONGOING
					else -> null
				},
				authors = emptySet(),
				source = source,
				contentRating = null,
			)
		}
	}

	private fun parsePageUrls(pages: Elements): List<MangaPage> {
		return pages.mapNotNull { img ->
			img.src()
				?.takeIf { it.isNotBlank() && it.startsWith("http") && it.contains("cdn") }
				?.let { url -> MangaPage(generateUid(url), url, null, source) }
		}
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val chapterRows = doc.select(".chapter-row[data-href]")
		if (chapterRows.isEmpty()) return emptyList()
		return chapterRows.mapChapters(reversed = true) { i, row ->
			val href = row.attrAsRelativeUrl("data-href")
			val badge = row.selectFirst(".chapter-badge")?.text()
			val title = row.selectFirst(".chapter-title")?.ownText()
			val dateText = row.selectFirst(".chapter-meta")?.textOrNull()
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = title ?: "Chapter ${badge ?: (i + 1)}",
				number = badge?.toFloatOrNull() ?: (i + 1).toFloat(),
				volume = 0,
				uploadDate = parseChapterDate(dateText),
				scanlator = null,
				branch = null,
				source = source,
			)
		}
	}

	private fun parseChapterDate(dateText: String?): Long {
		if (dateText.isNullOrBlank()) return 0L
		val date = dateText.lowercase(Locale.US)
		val now = Calendar.getInstance()
		return when {
			date.contains("menit") || date.contains("minute") || date.contains("min") -> {
				val num = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
				now.apply { add(Calendar.MINUTE, -num) }.timeInMillis
			}
			date.contains("jam") || date.contains("hour") || date.contains("h ") -> {
				val num = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
				now.apply { add(Calendar.HOUR, -num) }.timeInMillis
			}
			date.contains("hari") || date.contains("day") -> {
				val num = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
				now.apply { add(Calendar.DAY_OF_MONTH, -num) }.timeInMillis
			}
			date.contains("minggu") || date.contains("week") -> {
				val num = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
				now.apply { add(Calendar.DAY_OF_MONTH, -num * 7) }.timeInMillis
			}
			date.contains("bulan") || date.contains("month") -> {
				val num = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
				now.apply { add(Calendar.MONTH, -num) }.timeInMillis
			}
			date.contains("tahun") || date.contains("year") -> {
				val num = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
				now.apply { add(Calendar.YEAR, -num) }.timeInMillis
			}
			date.contains("kemarin") || date.contains("yesterday") -> {
				now.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
			}
			date.contains("baru") || date.contains(" baru") -> {
				now.timeInMillis
			}
			else -> {
				try {
					val formats = listOf(
						SimpleDateFormat("dd MMM yyyy", sourceLocale),
						SimpleDateFormat("dd MMMM yyyy", sourceLocale),
						SimpleDateFormat("MMM dd, yyyy", Locale.US),
						SimpleDateFormat("MMMM dd, yyyy", Locale.US),
						SimpleDateFormat("yyyy-MM-dd", Locale.US),
					)
					for (format in formats) {
						try {
							return format.parse(dateText)?.time ?: 0L
						} catch (_: Exception) {
							// Try next format
						}
					}
				} catch (_: Exception) {
					// Ignore parsing errors
				}
				0L
			}
		}
	}

	private fun String.toTitleCase(locale: Locale): String {
		return split(" ", "-")
			.joinToString(" ") { word ->
				word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
			}
	}
}
