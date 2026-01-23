package org.koitharu.kotatsu.parsers.site.mangabox.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAKAKALOT", "Mangakakalot.gg", "en")
internal class Mangakakalot(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MANGAKAKALOT, 24) {

	override val configKeyDomain = ConfigKey.Domain(
		"www.mangakakalot.gg",
		"mangakakalot.gg",
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search/story/")
					append(filter.query.replace(" ", "_").urlEncoded())
					append("/?page=")
					append(page)
				}
				else -> {
					append("/genre/")
					if (filter.tags.isNotEmpty()) {
						append(filter.tags.first().key)
					} else {
						append("all")
					}
					append("?page=")
					append(page)
					append("&type=")
					append(
						when (order) {
							SortOrder.POPULARITY -> "topview"
							SortOrder.UPDATED -> "latest"
							SortOrder.NEWEST -> "newest"
							else -> "latest"
						}
					)
					if (filter.states.isNotEmpty()) {
						append("&state=")
						append(
							when (filter.states.oneOrThrowIfMany()) {
								MangaState.ONGOING -> "ongoing"
								MangaState.FINISHED -> "completed"
								else -> "all"
							}
						)
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("div.list-comic-item-wrap").ifEmpty {
			doc.select("div.story_item")
		}.map { div ->
			val a = div.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = div.selectFirst("h3 a")?.text().orEmpty(),
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

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		val tags = doc.select("td a[href*='/genre/']").drop(1)
		return tags.mapToSet { a ->
			val key = a.attr("href").substringAfter("/genre/").substringBefore("?")
			val name = a.text().replaceFirstChar { it.uppercaseChar() }
			MangaTag(
				key = key,
				title = name,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val slug = manga.url.substringAfter("/manga/").removeSuffix("/")
		val chapters = fetchChaptersFromApi(slug)

		val statusText = doc.selectFirst("tr:has(th:contains(Status)) td")?.text()
			?: doc.selectFirst("li:contains(Status)")?.text()
			?: ""

		return manga.copy(
			title = doc.selectFirst("h1")?.text() ?: manga.title,
			altTitles = setOfNotNull(
				doc.selectFirst("tr:has(th:contains(Alternative)) td")?.text()
			),
			authors = doc.select("tr:has(th:contains(Author)) td a").mapToSet { it.text() },
			description = doc.selectFirst("div.desc-content, div.panel-story-info-description")?.text(),
			tags = doc.select("tr:has(th:contains(Genres)) td a, li.genres a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfter("/genre/").substringBefore("?"),
					title = a.text(),
					source = source,
				)
			},
			state = when {
				statusText.contains("ongoing", true) -> MangaState.ONGOING
				statusText.contains("completed", true) -> MangaState.FINISHED
				else -> null
			},
			chapters = chapters,
		)
	}

	private suspend fun fetchChaptersFromApi(slug: String): List<MangaChapter> {
		val allChapters = mutableListOf<MangaChapter>()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
		var offset = 0
		val limit = 50

		while (true) {
			val apiUrl = "https://$domain/api/manga/$slug/chapters?offset=$offset&limit=$limit"
			val response = webClient.httpGet(apiUrl).parseJson()

			if (!response.optBoolean("success", false)) {
				break
			}

			val data = response.getJSONObject("data")
			val chaptersArray = data.getJSONArray("chapters")

			chaptersArray.mapJSON { obj ->
				val chapterSlug = obj.getString("chapter_slug")
				val url = "/manga/$slug/$chapterSlug"
				MangaChapter(
					id = generateUid(url),
					title = obj.getString("chapter_name"),
					number = obj.optDouble("chapter_num", 0.0).toFloat(),
					volume = 0,
					url = url,
					uploadDate = dateFormat.parseSafe(obj.optString("updated_at")),
					source = source,
					scanlator = null,
					branch = null,
				)
			}.let { allChapters.addAll(it) }

			val pagination = data.optJSONObject("pagination")
			if (pagination == null || !pagination.optBoolean("has_more", false)) {
				break
			}
			offset += limit
		}

		return allChapters.sortedByDescending { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val html = doc.html()

		// Extract CDN base URL and image paths from JavaScript
		val cdnRegex = Regex("""var\s+cdns\s*=\s*\[([^\]]+)\]""")
		val imagesRegex = Regex("""var\s+chapterImages\s*=\s*\[([^\]]+)\]""")

		val cdnMatch = cdnRegex.find(html)
		val imagesMatch = imagesRegex.find(html)

		if (cdnMatch == null || imagesMatch == null) {
			// Fallback: try to find images in the HTML directly
			return doc.select("div.reading-content img, div.container-chapter-reader img").map { img ->
				val url = img.src() ?: img.attr("data-src")
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		val cdnBase = cdnMatch.groupValues[1]
			.replace("\"", "")
			.replace("'", "")
			.replace("\\", "")
			.split(",")
			.firstOrNull()
			?.trim()
			?.removeSuffix("/")
			?: return emptyList()

		val imagePaths = imagesMatch.groupValues[1]
			.replace("\"", "")
			.replace("'", "")
			.replace("\\", "")
			.split(",")
			.map { it.trim() }
			.filter { it.isNotBlank() }

		return imagePaths.map { path ->
			val url = "$cdnBase/$path"
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
