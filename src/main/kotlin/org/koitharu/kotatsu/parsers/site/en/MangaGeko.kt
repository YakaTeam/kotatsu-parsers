package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANGAGEKO", "MangaGeko", "en")
internal class MangaGeko(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAGEKO, 24) {

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST)

	override val configKeyDomain = ConfigKey.Domain("www.mgeko.cc", "www.mgeko.com", "www.mangageko.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val isSearch = !filter.query.isNullOrEmpty()
		val url = buildString {
			append("https://")
			append(domain)
			when {
				isSearch -> {
					if (page > 1) {
						return emptyList()
					}
					append("/search/?search=")
					append(filter.query.urlEncoded())
				}

				else -> {
					append("/browse-comics/data/?page=")
					append(page)

					if (filter.tags.isNotEmpty()) {
						append("&include_genres=")
						append(filter.tags.joinToString(separator = ",") { it.key })
					}

					if (filter.tagsExclude.isNotEmpty()) {
						append("&exclude_genres=")
						append(filter.tagsExclude.joinToString(separator = ",") { it.key })
					}

					append("&sort=")
					when (order) {
						SortOrder.POPULARITY -> append("popular_all_time")
						SortOrder.UPDATED -> append("latest")
						SortOrder.NEWEST -> append("recently_added")
						SortOrder.ALPHABETICAL -> append("az")
						SortOrder.RATING -> append("rating")
						else -> append("latest")
					}
				}
			}
		}

		if (isSearch) {
			val doc = webClient.httpGet(url).parseHtml()
			return doc.select("li.novel-item").map { div ->
				val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
				val author = div.selectFirst("h6")?.text()?.removePrefix("Author(S): ")?.nullIfEmpty()
				Manga(
					id = generateUid(href),
					title = div.selectFirstOrThrow("h4").text(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = div.selectFirstOrThrow("img").src(),
					tags = emptySet(),
					state = null,
					authors = setOfNotNull(author),
					source = source,
				)
			}
		} else {
			val response = webClient.httpGet(url)
			val jsonStr = response.body?.string() ?: return emptyList()
			val json = JSONObject(jsonStr)
			val html = json.optString("results_html", "")
			val doc = Jsoup.parse(html, "https://$domain/")
			return doc.select("article.comic-card").map { div ->
				val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
				Manga(
					id = generateUid(href),
					title = div.selectFirstOrThrow(".comic-card__title").text(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = div.selectFirstOrThrow("img").src(),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/browse-comics/").parseHtml()
		return doc.select(".chip[data-group='include_genres']").mapToSet { chip ->
			MangaTag(
				key = chip.attr("data-value"),
				title = chip.text(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val author = doc.selectFirst(".author span[itemprop='author']")?.text()
			?: doc.selectFirst(".author")?.text()?.substringAfter("Author:")?.trim()
		manga.copy(
			title = doc.selectFirst("h1")?.text() ?: manga.title,
			altTitles = setOfNotNull(doc.selectFirst(".alternative-title")?.textOrNull()),
			state = when {
				doc.selectFirst("strong.ongoing") != null -> MangaState.ONGOING
				doc.selectFirst("strong.completed") != null -> MangaState.FINISHED
				else -> null
			},
			tags = doc.select(".categories ul li a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfter("genre_included=").substringBefore('&').trim(),
					title = a.text().trim(),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = doc.selectFirst(".description")?.html() ?: doc.selectFirst("#chapter-article .main-header + div")?.html(),
			chapters = loadChapters(doc, manga.url),
		)
	}

	private suspend fun loadChapters(detailsDoc: org.jsoup.nodes.Document, mangaUrl: String): List<MangaChapter> {
		val chapters = mutableListOf<MangaChapter>()

		fun parseChapters(doc: org.jsoup.nodes.Document) {
			doc.select("ul.chapter-list li").forEach { li ->
				val a = li.selectFirst("a") ?: return@forEach
				val url = a.attrAsRelativeUrl("href")
				val name = a.selectFirst(".chapter-title, .chapter-number")?.text()
					?: a.text()
				chapters.add(
					MangaChapter(
						id = generateUid(url),
						title = name,
						number = 0f, // will be set later
						volume = 0,
						url = url,
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = source,
					)
				)
			}
		}

		// Try all-chapters page first
		try {
			val urlChapter = mangaUrl.removeSuffix("/") + "/all-chapters/"
			val allChaptersDoc = webClient.httpGet(urlChapter.toAbsoluteUrl(domain)).parseHtml()
			parseChapters(allChaptersDoc)
		} catch (e: Exception) {
			// fallback to chapters already in details page
		}

		if (chapters.isEmpty()) {
			parseChapters(detailsDoc)
		}

		return chapters.distinctBy { it.url }.mapIndexed { i, chapter ->
			chapter.copy(number = (chapters.size - i).toFloat())
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select("img[id^='image-']")
			.mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
			.filterNot { it.startsWith("data:image") || it.contains("credits-mgeko.png") }
			.distinct()
			.map { url ->
				val finalUrl = url.toRelativeUrl(domain)
				MangaPage(
					id = generateUid(finalUrl),
					url = finalUrl,
					preview = null,
					source = source,
				)
			}
	}
}
