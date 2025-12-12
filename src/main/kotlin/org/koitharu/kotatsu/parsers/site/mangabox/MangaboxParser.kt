package org.koitharu.kotatsu.parsers.site.mangabox

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.FlexiblePagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.text.get

internal abstract class MangaboxParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	pageSize: Int = 24,
) : FlexiblePagedMangaParser(context, source, pageSize) {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities(
			SearchCapability(
				field = TAG,
				criteriaTypes = setOf(Include::class, Exclude::class),
				isMultiple = true,
			),
			SearchCapability(
				field = TITLE_NAME,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
			),
			SearchCapability(
				field = STATE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = AUTHOR,
				criteriaTypes = setOf(Include::class),
				isMultiple = false,
				isExclusive = true,
			),
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	init {
		paginator.firstPage = 1
		searchPaginator.firstPage = 1
	}

	@JvmField
	protected val ongoing: Set<String> = setOf(
		"ongoing",
	)

	@JvmField
	protected val finished: Set<String> = setOf(
		"completed",
	)

	protected open val listUrl = "/manga-list/latest-manga"
	protected open val authorUrl = "/search/author"
	protected open val searchUrl = "/search/story/"
	protected open val datePattern = "MMM dd,yy"

	private fun SearchableField.toParamName(): String = when (this) {
		TITLE_NAME, AUTHOR -> "keyw"
		TAG -> "g_i"
		STATE -> "sts"
		else -> ""
	}

	private fun Any?.toQueryParam(): String = when (this) {
		is String -> replace(" ", "_").urlEncoded()
		is MangaTag -> key
		is MangaState -> when (this) {
			MangaState.ONGOING -> "ongoing"
			MangaState.FINISHED -> "completed"
			else -> ""
		}

		is SortOrder -> when (this) {
			SortOrder.ALPHABETICAL -> "az"
			SortOrder.NEWEST -> "newest"
			SortOrder.POPULARITY -> "topview"
			else -> ""
		}

		else -> this.toString().replace(" ", "_").urlEncoded()
	}

	private fun StringBuilder.appendCriterion(field: SearchableField, value: Any?, paramName: String? = null) {
		val param = paramName ?: field.toParamName()
		if (param.isNotBlank()) {
			append("&$param=")
			append(value.toQueryParam())
		}
	}

	override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
		// Handle simple search queries using the new search URL pattern
		val titleCriteria = query.criteria.filterIsInstance<Match<*>>()
			.find { it.field == TITLE_NAME }

		// Handle genre filtering using the new genre URL pattern
		val genreIncludeCriteria = query.criteria.filterIsInstance<Include<*>>()
			.find { it.field == TAG }

		if (titleCriteria != null) {
			val searchTerm = titleCriteria.value.toString()
			if (searchTerm.isNotBlank()) {
				// Use direct search URL - WebView to be implemented later if needed for Cloudflare
				val searchUrl = "https://${domain}/search/story/${searchTerm.replace(" ", "-").lowercase()}"
				val doc = webClient.httpGet(searchUrl).parseHtml()

				// Search results might have different structure than homepage carousel
				return parseSearchResults(doc)
			}
		} else if (genreIncludeCriteria != null && genreIncludeCriteria.values.isNotEmpty()) {
			// Handle genre browsing - use only the first genre
			val genre = genreIncludeCriteria.values.first()
			val genreKey = when (genre) {
				is MangaTag -> genre.key
				else -> genre.toString().replace(" ", "-").lowercase()
			}

			val genreUrl = "https://${domain}/genre/${genreKey}?page=$page"
			val doc = webClient.httpGet(genreUrl).parseHtml()

			return parseSearchResults(doc)
		}

		// For regular listing (no search), use the new manga list URL
		val listingUrl = "https://${domain}${listUrl}?page=$page"
		val doc = webClient.httpGet(listingUrl).parseHtml()

		return parseSearchResults(doc)
	}

	protected open fun parseSearchResults(doc: Document): List<Manga> {
		// Search results use .story_item, listing uses .item (carousel)
		val elements = doc.select(".story_item")  // Search results structure
			.ifEmpty { doc.select(".item") }  // Homepage carousel
			.ifEmpty { doc.select(".manga-item") }  // Grid layout
			.ifEmpty { doc.select("div.content-genres-item") }  // Alternative layout
			.ifEmpty { doc.select("div.list-story-item") }  // List layout
			.ifEmpty { doc.select("div.search-story-item") }  // Search specific
			.ifEmpty { doc.select("div[class*='story']") }  // Generic story containers
			.ifEmpty { doc.select("a[href*='/manga/']").map { it.parent() ?: it } }  // Links to manga

		return elements.mapNotNull { div ->
			// Handle search result structure (.story_item)
			val linkElement = if (div.hasClass("story_item")) {
				div.selectFirst(".story_name a") ?: div.selectFirst("a[href*='/manga/']")
			} else {
				// Handle carousel structure (.item)
				div.selectFirst(".slide-caption h3 a")  // Carousel structure
					?: div.selectFirst("h3 a")  // Direct h3 link
					?: div.selectFirst("a[href*='/manga/']")  // Any manga link
					?: if (div.tagName() == "a") div else null  // Element itself is a link
			}

			val href = linkElement?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (!href.contains("/manga/")) return@mapNotNull null

			// Extract title from different possible locations
			val title = linkElement.text().trim()
				.takeIf { it.isNotEmpty() }
				?: linkElement.attr("title").trim().takeIf { it.isNotEmpty() }
				?: div.selectFirst("h3, h2, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
				?: return@mapNotNull null

			val coverUrl = div.selectFirst("img")?.src()

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = coverUrl,
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = sourceContentRating,
			)
		}
	}

	protected open val selectTagMap = "div.panel-genres-list a:not(.genres-select)"

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain$listUrl").parseHtml()
		val tags = doc.select(selectTagMap).drop(1) // remove all tags
		return tags.mapToSet { a ->
			val key = a.attr("href").removeSuffix('/').substringAfterLast('/')
			val name = a.attr("title").replace(" Manga", "")
			MangaTag(
				key = key,
				title = name,
				source = source,
			)
		}
	}

	protected open val selectDesc = "div#noidungm, div#panel-story-info-description"
	protected open val selectState = "li:contains(status), td:containsOwn(status) + td"
	protected open val selectAlt = ".story-alternative, tr:has(.info-alternative) h2"
	protected open val selectAut = "li:contains(author) a, td:contains(author) + td a"
	protected open val selectTag = "div.manga-info-top li:contains(genres) a , td:containsOwn(genres) + td a"

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val chaptersDeferred = async { getChapters(doc) }
		val desc = doc.selectFirst(selectDesc)?.html()
		val stateDiv = doc.select(selectState).text()
		val state = stateDiv.let {
			when (it.lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				else -> null
			}
		}
		val alt = doc.body().select(selectAlt).text().replace("Alternative : ", "").nullIfEmpty()
		val authors = doc.body().select(selectAut).mapToSet { it.text() }

		manga.copy(
			tags = doc.body().select(selectTag).mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast("category=").substringBefore("&"),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = desc,
			altTitles = setOfNotNull(alt),
			authors = authors,
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}

	protected open val selectDate = "span"
	protected open val selectChapter = "div.chapter-list div.row, ul.row-content-chapter li"

	protected open suspend fun getChapters(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return doc.body().select(selectChapter).mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val dateText = li.select(selectDate).last()?.text()

			MangaChapter(
				id = generateUid(href),
				title = a.text(),
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	protected open val selectPage = "div#vungdoc img, div.container-chapter-reader img"

	protected open val otherDomain = ""

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		if (doc.select(selectPage).isEmpty()) {
			val fullUrl2 = chapter.url.toAbsoluteUrl(domain).replace(domain, otherDomain)
			val doc2 = webClient.httpGet(fullUrl2).parseHtml()

			return doc2.select(selectPage).map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)

				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		} else {
			return doc.select(selectPage).map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)

				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

	}

	protected fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			WordSet(" ago", " h", " d").endsWith(d) -> {
				parseRelativeDate(d)
			}

			WordSet("today").startsWith(d) -> {
				Calendar.getInstance().apply {
					set(Calendar.HOUR_OF_DAY, 0)
					set(Calendar.MINUTE, 0)
					set(Calendar.SECOND, 0)
					set(Calendar.MILLISECOND, 0)
				}.timeInMillis
			}

			date.contains(Regex("""\d(st|nd|rd|th)""")) -> date.split(" ").map {
				if (it.contains(Regex("""\d\D\D"""))) {
					it.replace(Regex("""\D"""), "")
				} else {
					it
				}
			}.let { dateFormat.parseSafe(it.joinToString(" ")) }

			else -> dateFormat.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		val cal = Calendar.getInstance()
		return when {
			WordSet("second")
				.anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis

			WordSet("min", "minute", "minutes")
				.anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis

			WordSet("hour", "hours", "h")
				.anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis

			WordSet("day", "days")
				.anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis

			WordSet("month", "months")
				.anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis

			WordSet("year")
				.anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis

			else -> 0
		}
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
		.add("Accept-Encoding", "gzip, deflate, br")
		.add("Accept-Language", "en-US,en;q=0.9")
		.build()
}
