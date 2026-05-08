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
import org.koitharu.kotatsu.parsers.util.attrOrNull
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
			isSearchWithFiltersSupported = false,
			isMultipleTagsSupported = false,
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
			!filter.query.isNullOrEmpty() -> if (page <= 1) searchManga(filter.query!!) else emptyList()
			filter.tags.isNotEmpty() -> getMangaListByGenre(filter.tags.first().key, page, order, filter.tags)
			else -> getAllManga(page, order)
		}
	}

	private fun parseListCard(a: org.jsoup.nodes.Element, presetTags: Set<MangaTag> = emptySet()): Manga {
		val href = a.attrAsRelativeUrl("href")
		val slug = href.substringAfterLast("/manga/").trimEnd('/')
		val coverImg = a.selectFirst(".card-cover img, img")
		val titleEl = a.selectFirst(".card-title")
		val statusBadge = a.selectFirst(".status-badge")
		val ratingText = a.selectFirst(".card-rating span, .rating span")?.ownText()
		return Manga(
			id = generateUid(slug),
			url = "/manga/$slug",
			publicUrl = "https://$domain/manga/$slug",
			title = titleEl?.textOrNull() ?: coverImg?.attrOrNull("alt") ?: a.text(),
			altTitles = emptySet(),
			coverUrl = coverImg?.src(),
			rating = ratingText?.toFloatOrNull()?.div(2f) ?: RATING_UNKNOWN,
			tags = presetTags,
			state = parseStateBadge(statusBadge),
			authors = emptySet(),
			source = source,
			contentRating = null,
		)
	}

	private fun parseStateBadge(badge: org.jsoup.nodes.Element?): MangaState? = when {
		badge == null -> null
		badge.hasClass("is-completed") || badge.hasClass("is-finished") -> MangaState.FINISHED
		badge.hasClass("is-hiatus") || badge.hasClass("is-paused") -> MangaState.PAUSED
		badge.hasClass("is-canceled") || badge.hasClass("is-cancelled") || badge.hasClass("is-dropped") -> MangaState.ABANDONED
		badge.hasClass("is-ongoing") -> MangaState.ONGOING
		else -> {
			when (badge.text().trim().lowercase(Locale.ROOT)) {
				"completed", "tamat", "finished" -> MangaState.FINISHED
				"hiatus", "paused" -> MangaState.PAUSED
				"dropped", "canceled", "cancelled" -> MangaState.ABANDONED
				"ongoing", "berjalan" -> MangaState.ONGOING
				else -> null
			}
		}
	}

	private fun parseStateText(text: String?): MangaState? = when (text?.trim()?.lowercase(Locale.ROOT)) {
		null, "", "-", "—" -> null
		"completed", "tamat", "finished" -> MangaState.FINISHED
		"hiatus", "paused" -> MangaState.PAUSED
		"dropped", "canceled", "cancelled" -> MangaState.ABANDONED
		"ongoing", "berjalan" -> MangaState.ONGOING
		else -> null
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val hero = doc.selectFirstOrThrow(".hero")
		val heroMeta = hero.selectFirst(".hero-meta-grid")
		val metaMap = mutableMapOf<String, String>()
		heroMeta?.children()?.forEach { item ->
			val label = item.selectFirst(".meta-item-label")?.text()?.trim()?.lowercase(Locale.ROOT)
			val value = item.selectFirst(".meta-item-value")?.text()?.trim()
			if (!label.isNullOrEmpty() && !value.isNullOrEmpty() && value != "—") {
				metaMap[label] = value
			}
		}
		val author = metaMap["author"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
		val genres = doc.select(".meta-pill, .genre-pill, a.genre-shelf-link").mapToSet { el ->
			val text = el.text().trim()
			val key = el.attr("href").substringAfter("?genre=", "").substringBefore('&')
				.ifEmpty { text.lowercase(Locale.ROOT).replace(' ', '-') }
			MangaTag(
				key = key,
				title = text.toTitleCase(sourceLocale),
				source = source,
			)
		}.filter { it.title.isNotEmpty() }.toSet()

		val status = parseStateText(metaMap["status"])
		val description = doc.selectFirst(".synopsis-text, .hero-description, .synopsis-content")?.textOrNull()
			?: doc.selectFirst("meta[name=description]")?.attr("content")?.nullIfEmpty()
		val title = hero.selectFirst(".hero-title, h1")?.textOrNull() ?: manga.title
		val altTitle = hero.selectFirst(".hero-alt-title")?.textOrNull()
		val cover = doc.selectFirst(".hero-cover img, .hero-image img, meta[property=og:image]")
			?.let { it.src() ?: it.attr("content").nullIfEmpty() }
		val rating = hero.selectFirst(".hero-rating-badge span")?.ownText()?.toFloatOrNull()
			?.takeIf { it > 0f }?.div(5f) ?: manga.rating

		return manga.copy(
			title = title,
			altTitles = setOfNotNull(altTitle),
			description = description,
			coverUrl = cover ?: manga.coverUrl,
			tags = if (genres.isNotEmpty()) genres else manga.tags,
			authors = author,
			state = status ?: manga.state,
			rating = rating,
			chapters = parseAllChapters(manga.url),
		)
	}

	private suspend fun parseAllChapters(mangaRelUrl: String): List<MangaChapter> {
		val seen = mutableSetOf<String>()
		val rows = mutableListOf<org.jsoup.nodes.Element>()
		var pageNum = 1
		while (pageNum <= 50) {
			val url = "https://$domain$mangaRelUrl?order=oldest&page=$pageNum".toAbsoluteUrl(domain)
			val doc = try {
				webClient.httpGet(url).parseHtml()
			} catch (_: Exception) {
				break
			}
			val pageRows = doc.select(".chapter-row[data-href]")
			if (pageRows.isEmpty()) break
			var added = 0
			for (r in pageRows) {
				val href = r.attr("data-href")
				if (href.isBlank() || !seen.add(href)) continue
				rows.add(r)
				added++
			}
			if (added == 0 || pageRows.size < 20) break
			pageNum++
		}
		if (rows.isEmpty()) return emptyList()
		return parseChaptersFromRows(rows)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		var pages = doc.select("#page-wrap img, .reader img[alt^=Page], img[alt^=Page]")
		if (pages.isEmpty()) {
			pages = doc.select(".reader img, .reader-area img")
		}
		if (pages.isEmpty()) {
			throw ParseException("No images found on page", fullUrl)
		}
		return parsePageUrls(pages)
	}

	/* Utilities */

	private val staticGenres: List<String> = listOf(
		"4-koma",
		"action",
		"adult",
		"adventure",
		"aliens",
		"animals",
		"anthology",
		"comedy",
		"comedy-ecchi",
		"cooking",
		"crime",
		"crossdressing",
		"delinquents",
		"demon",
		"demons",
		"drama",
		"ecchi",
		"ecchi-comedy",
		"fantasy",
		"game",
		"gender-bender",
		"genderswap",
		"genre-drama",
		"ghosts",
		"gore",
		"gyaru",
		"harem",
		"historical",
		"horror",
		"incest",
		"isekai",
		"isekai-action",
		"josei",
		"long-strip",
		"mafia",
		"magic",
		"magical-girls",
		"manga",
		"manhua",
		"martial-art",
		"martial-arts",
		"mature",
		"mecha",
		"medical",
		"military",
		"mons",
		"monster",
		"monster-girls",
		"monsters",
		"music",
		"mystery",
		"ninja",
		"novel",
		"office-workers",
		"oneshot",
		"philosophical",
		"police",
		"project",
		"psychological",
		"regression",
		"reincarnation",
		"reverse-harem",
		"romance",
		"samurai",
		"school",
		"school-life",
		"sci-fi",
		"seinen",
		"seinenaction",
		"sexual-violence",
		"shotacon",
		"shoujo",
		"shoujo-ai",
		"shounen",
		"si-fi",
		"slice-of-life",
		"smut",
		"sports",
		"super-power",
		"supernatural",
		"survival",
		"suspense",
		"system",
		"thriller",
		"time-travel",
		"tragedy",
		"urban",
		"vampire",
		"video-games",
		"villainess",
		"virtual-reality",
		"webtoons",
		"yuri",
		"zombies",
	)

	private suspend fun fetchTags(): Set<MangaTag> {
		val discovered = try {
			val doc = webClient.httpGet("https://$domain").parseHtml()
			val regex = Regex("""[?&]genre=([^&"\s]+)""")
			doc.select("a[href*='genre=']").mapNotNull { element ->
				regex.find(element.attr("href"))?.groupValues?.get(1)
			}.filter { it.isNotBlank() }.toSet()
		} catch (_: Exception) {
			emptySet()
		}
		val merged = LinkedHashSet<String>().apply {
			addAll(staticGenres)
			addAll(discovered)
		}
		return merged.map { slug ->
			MangaTag(
				key = slug,
				title = slug.replace('-', ' ').toTitleCase(sourceLocale),
				source = source,
			)
		}.toSet()
	}

	private fun getSortParam(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "popular"
		SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "latest_update"
		SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "latest_update"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "title"
		SortOrder.RATING, SortOrder.RATING_ASC -> "rating"
		else -> "popular"
	}

	private suspend fun searchManga(query: String): List<Manga> {
		val url = "https://$domain/search/quick?q=${query.urlEncoded()}"
		val json = webClient.httpGet(url).parseJson()
		val results = json.optJSONArray("results") ?: return emptyList()
		return results.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				url = "/manga/$slug",
				publicUrl = jo.optString("url").nullIfEmpty() ?: "https://$domain/manga/$slug",
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
		val sortParam = getSortParam(order)
		val url = buildString {
			append("https://").append(domain).append("/?sort=").append(sortParam)
			if (page > 1) append("&page=").append(page)
		}
		val doc = webClient.httpGet(url).parseHtml()
		val cards = doc.select("a.card")
		if (cards.isNotEmpty()) {
			return cards.map { parseListCard(it) }.distinctBy { it.id }
		}
		if (page <= 1) {
			val popularJson = webClient.httpGet("https://$domain/home/lazy/popular").parseJson()
			val popularHtml = popularJson.optString("html").orEmpty()
			if (popularHtml.isNotBlank()) {
				val popularDoc = Jsoup.parse(popularHtml, "https://$domain/")
				return popularDoc.select("a.popular-feature-card").map { a ->
					val href = a.attrAsRelativeUrl("href")
					val slug = href.removePrefix("/manga/")
					Manga(
						id = generateUid(slug),
						url = "/manga/$slug",
						publicUrl = "https://$domain$href",
						title = a.selectFirstOrThrow(".popular-feature-title").text(),
						altTitles = emptySet(),
						coverUrl = a.selectFirst("img")?.src(),
						rating = a.selectFirst(".popular-feature-rating span")?.ownText()
							?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
						tags = emptySet(),
						state = null,
						authors = emptySet(),
						source = source,
						contentRating = null,
					)
				}
			}
		}
		return emptyList()
	}

	private suspend fun getMangaListByGenre(genre: String, page: Int, order: SortOrder, tags: Set<MangaTag>): List<Manga> {
		val sortParam = getSortParam(order)
		val url = buildString {
			append("https://").append(domain).append("/?genre=").append(genre).append("&sort=").append(sortParam)
			if (page > 1) append("&page=").append(page)
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("a.card").map { parseListCard(it, tags) }.distinctBy { it.id }
	}

	private fun parsePageUrls(pages: Elements): List<MangaPage> {
		return pages.mapNotNull { img ->
			val url = img.src()
				?: img.attrOrNull("data-src")
				?: img.attrOrNull("data-lazy-src")
				?: img.attrOrNull("data-original")
			url?.takeIf { it.isNotBlank() && it.startsWith("http") }
				?.let { MangaPage(generateUid(it), it, null, source) }
		}
	}

	private fun parseChaptersFromRows(rows: List<org.jsoup.nodes.Element>): List<MangaChapter> {
		val ordered = rows.sortedBy { row ->
			row.selectFirst(".chapter-badge")?.text()?.trim()?.toFloatOrNull() ?: 0f
		}
		return ordered.mapIndexed { i, row ->
			val href = row.attrAsRelativeUrl("data-href")
			val badge = row.selectFirst(".chapter-badge")?.text()?.trim()
			val title = row.selectFirst(".chapter-title")?.ownText()?.nullIfEmpty()
				?: row.selectFirst(".chapter-title")?.textOrNull()
			val dateText = row.selectFirst(".chapter-meta [data-local-time]")?.attr("data-local-time")
				?.nullIfEmpty()
				?: row.selectFirst(".chapter-meta")?.textOrNull()
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

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val chapterRows = doc.select(".chapter-row[data-href]")
		if (chapterRows.isEmpty()) return emptyList()
		return parseChaptersFromRows(chapterRows.toList())
	}

	private fun parseChapterDate(dateText: String?): Long {
		if (dateText.isNullOrBlank()) return 0L
		// ISO 8601 from data-local-time: 2026-04-27T16:08:00+00:00
		if (dateText.length >= 19 && dateText[4] == '-' && dateText[10] == 'T') {
			try {
				val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
				return iso.parse(dateText)?.time ?: 0L
			} catch (_: Exception) {
				try {
					val iso2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
					return iso2.parse(dateText.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2"))?.time ?: 0L
				} catch (_: Exception) {}
			}
		}
		val date = dateText.lowercase(Locale.US)
		val now = Calendar.getInstance()
		val num = Regex("""(\d+)""").find(date)?.value?.toIntOrNull()
		return when {
			date.contains("detik") || date.contains("second") -> {
				if (num == null) 0L else now.apply { add(Calendar.SECOND, -num) }.timeInMillis
			}
			date.contains("menit") || date.contains("minute") || date.contains("min") -> {
				if (num == null) 0L else now.apply { add(Calendar.MINUTE, -num) }.timeInMillis
			}
			date.contains("jam") || date.contains("hour") || date.contains("h ") -> {
				if (num == null) 0L else now.apply { add(Calendar.HOUR, -num) }.timeInMillis
			}
			date.contains("kemarin") || date.contains("yesterday") -> {
				now.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
			}
			date.contains("hari") || date.contains("day") -> {
				if (num == null) 0L else now.apply { add(Calendar.DAY_OF_MONTH, -num) }.timeInMillis
			}
			date.contains("minggu") || date.contains("week") -> {
				if (num == null) 0L else now.apply { add(Calendar.DAY_OF_MONTH, -num * 7) }.timeInMillis
			}
			date.contains("bulan") || date.contains("month") -> {
				if (num == null) 0L else now.apply { add(Calendar.MONTH, -num) }.timeInMillis
			}
			date.contains("tahun") || date.contains("year") -> {
				if (num == null) 0L else now.apply { add(Calendar.YEAR, -num) }.timeInMillis
			}
			date.contains("baru saja") || date.startsWith("baru") || date.contains("just now") -> now.timeInMillis
			else -> {
				val formats = listOf(
					SimpleDateFormat("dd MMM yyyy", sourceLocale),
					SimpleDateFormat("dd MMMM yyyy", sourceLocale),
					SimpleDateFormat("d MMM yyyy", sourceLocale),
					SimpleDateFormat("d MMMM yyyy", sourceLocale),
					SimpleDateFormat("MMM dd, yyyy", Locale.US),
					SimpleDateFormat("MMMM dd, yyyy", Locale.US),
					SimpleDateFormat("yyyy-MM-dd", Locale.US),
				)
				for (format in formats) {
					try {
						return format.parse(dateText)?.time ?: continue
					} catch (_: Exception) {
						continue
					}
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
