package org.koitharu.kotatsu.parsers.site.mangabox.en

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Include
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Match
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*
import kotlin.collections.set


@MangaSourceParser("HMANGABAT", "MangaBat", "en")
internal class Mangabat(context: MangaLoaderContext) :
	MangaboxParser(context, MangaParserSource.HMANGABAT) {

	override val configKeyDomain = ConfigKey.Domain("mangabats.com")

	// Use custom implementation instead of base class URL handling
	override val listUrl = "/genre/all"
	override val searchUrl = "/search/story"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities(
			SearchCapability(
				field = TAG,
				criteriaTypes = setOf(Include::class),
				isMultiple = false,
			),
			SearchCapability(
				field = TITLE_NAME,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
				isExclusive = true,
			),
			SearchCapability(
				field = STATE,
				criteriaTypes = setOf(Include::class),
				isMultiple = false,
			),
		)

	override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
		val titleQuery = query.criteria.filterIsInstance<Match<*>>()
			.firstOrNull { it.field == TITLE_NAME }?.value as? String

		val url = if (!titleQuery.isNullOrBlank()) {
			"https://$domain/search/story/${titleQuery.urlEncoded()}?page=$page"
		} else {
			val tagCriterion = query.criteria.filterIsInstance<Include<*>>()
				.firstOrNull { it.field == TAG }

			val tagKey = (tagCriterion?.values?.firstOrNull() as? MangaTag)?.key
				?: (tagCriterion?.values?.firstOrNull() as? String)

			val baseUrl = tagKey ?: "https://$domain/genre/all"

			val sortParam = when (query.order ?: SortOrder.UPDATED) {
				SortOrder.POPULARITY -> "topview"
				SortOrder.NEWEST -> "newest"
				else -> "latest"
			}

			val stateParam = query.criteria.filterIsInstance<Include<*>>()
				.firstOrNull { it.field == STATE }?.values?.firstOrNull()?.let {
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						else -> "all"
					}
				} ?: "all"

			"$baseUrl?type=$sortParam&state=$stateParam&page=$page"
		}

		val doc = webClient.httpGet(url).parseHtml()

		// Try different selectors similar to MangaKakalot
		var elements = doc.select("div.list-comic-item-wrap")
		if (elements.isEmpty()) {
			elements = doc.select("div.itemupdate")
		}
		if (elements.isEmpty()) {
			elements = doc.select("div.story_item")
		}
		if (elements.isEmpty()) {
			elements = doc.select("div.manga-item")
		}

		return elements.map { div ->
			val linkElement = div.selectFirst("a.cover")
				?: div.selectFirst("a")
				?: div.selectFirst("a[href*='/manga/']")

			val href = linkElement?.attrAsRelativeUrl("href") ?: ""
			val img = linkElement?.selectFirst("img") ?: div.selectFirst("img")
			val title = div.selectFirst("h3")?.text()
				?: div.selectFirst("h2")?.text()
				?: div.selectFirst(".manga-title")?.text()
				?: linkElement?.text()
				?: ""

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src"),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		val tags = doc.select("td a[href*='/genre/']").drop(1)
		val uniqueTags = mutableSetOf<MangaTag>()
		for (a in tags) {
			val key = a.attr("href").substringAfter("/genre/").substringBefore("?")
			val name = a.text().replaceFirstChar { it.uppercaseChar() }
			if (uniqueTags.none { it.key == key }) {
				uniqueTags.add(
					MangaTag(
						key = key,
						title = name,
						source = source,
					)
				)
			}
		}
		return uniqueTags
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://www.mangabats.com/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val originalRequest = chain.request()

		// Remove encoding headers from requests to avoid gzip compression issues
		if (originalRequest.method == "GET" || originalRequest.method == "POST") {
			val newRequest = originalRequest.newBuilder()
				.removeHeader("Content-Encoding")
				.removeHeader("Accept-Encoding")
				.build()
			return chain.proceed(newRequest)
		}

		return chain.proceed(originalRequest)
	}

}
