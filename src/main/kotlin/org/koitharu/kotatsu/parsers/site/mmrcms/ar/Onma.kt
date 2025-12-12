package org.koitharu.kotatsu.parsers.site.mmrcms.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mmrcms.MmrcmsParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.util.*

@MangaSourceParser("ONMA", "Onma", "ar")
internal class Onma(context: MangaLoaderContext) :
	MmrcmsParser(context, MangaParserSource.ONMA, "onma.me") {

	override val sourceLocale: Locale = Locale.ENGLISH
	override val selectState = "h3:contains(الحالة) .text"
	override val selectAlt = "h3:contains(أسماء أخرى) .text"
	override val selectAut = "h3:contains(المؤلف) .text"
	override val selectTag = "h3:contains(التصنيفات) .text"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false, // Search and tag filters use separate endpoints
		)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Search uses completely separate endpoint and cannot be combined with tags
		if (!filter.query.isNullOrEmpty()) {
			if (filter.tags.isNotEmpty()) {
				throw IllegalArgumentException("Search cannot be combined with tag filters on this source.")
			}
			return performSearch(filter.query, page)
		}

		// For UPDATED sort with tag filters, fall back to alphabetical to avoid exception
		val actualOrder = if (order == SortOrder.UPDATED && filter.tags.isNotEmpty()) {
			SortOrder.ALPHABETICAL
		} else {
			order
		}

		return super.getListPage(page, actualOrder, filter)
	}

	private suspend fun performSearch(query: String, page: Int): List<Manga> {
		if (page > 1) {
			// Search endpoint doesn't support pagination, return empty for page > 1
			return emptyList()
		}

		val url = "https://$domain/search?query=${query.urlEncoded()}"
		val response = webClient.httpGet(url).parseJson()
		val suggestions = response.optJSONArray("suggestions") ?: return emptyList()

		return suggestions.mapJSON<Manga> { suggestion ->
			val title = suggestion.getString("value")
			val slug = suggestion.getString("data")
			val href = "/manga/$slug"

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = "https://$domain/uploads/manga/$slug/cover/cover_250x350.jpg",
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$tagUrl/").parseHtml()
		return doc.select("ul.list-category li").mapIndexedNotNull { index, li ->
			val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
			MangaTag(
				key = (index + 1).toString(), // Use 1-based index as key (cat=1, cat=2, etc.)
				title = a.text(),
				source = source,
			)
		}.toSet()
	}


	override fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.chapter-container").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = div.selectFirstOrThrow("h5.media-heading").text().orEmpty(),
				altTitles = emptySet(),
				rating = div.selectFirstOrThrow("span").ownText().toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val body = doc.body().selectFirstOrThrow("div.panel-body")
		val chaptersDeferred = async { getChapters(doc) }
		val desc = doc.selectFirst(selectDesc)?.text().orEmpty()
		val stateDiv = body.selectFirst(selectState)
		val state = stateDiv?.let {
			when (it.text()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				else -> null
			}
		}
		val alt = doc.body().selectFirst(selectAlt)?.textOrNull()
		val author = doc.body().selectFirst(selectAut)?.textOrNull()
		val tags = doc.body().selectFirst(selectTag)?.select("a") ?: emptySet()
		manga.copy(
			tags = tags.mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text().toTitleCase(sourceLocale),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}
}
