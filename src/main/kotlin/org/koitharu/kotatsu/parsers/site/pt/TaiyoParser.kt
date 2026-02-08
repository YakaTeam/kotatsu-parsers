package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.regex.Pattern

@MangaSourceParser("TAIYO", "Taiyō", "pt")
internal class TaiyoParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TAIYO, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("taiyo.moe")

	private val cdnDomain = "cdn.taiyo.moe"
	private val meiliDomain = "meilisearch.taiyo.moe"
	private val meiliKey = "48aa86f73de09a7705a2938a1a35e5a12cff6519695fcad395161315182286e5"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val queryObj = JSONObject().apply {
			put("indexUid", "medias")
			put("q", "")
			put("facets", JSONArray().put("genres"))
			put("limit", 0)
			put("filter", JSONArray().put("deletedAt IS NULL"))
		}
		val body = JSONObject().apply {
			put("queries", JSONArray().put(queryObj))
		}
		val response = meiliPost(body)
		val facets = response.getJSONArray("results")
			.getJSONObject(0)
			.optJSONObject("facetDistribution")
			?.optJSONObject("genres")

		val tags = mutableSetOf<MangaTag>()
		if (facets != null) {
			for (key in facets.keys()) {
				tags.add(
					MangaTag(
						key = key,
						title = key.lowercase().replace('_', ' ').toTitleCase(sourceLocale),
						source = source,
					),
				)
			}
		}
		return MangaListFilterOptions(availableTags = tags)
	}

	/**
	 * Extract RSC (React Server Components) flight data from a page.
	 * Each script tag contains: self.__next_f.push([1,"content"])
	 * We extract just the string content from type-1 pushes and concatenate them.
	 */
	private fun extractFlightData(html: org.jsoup.nodes.Document): String {
		val result = StringBuilder()
		val pushContentPattern = Pattern.compile("""self\.__next_f\.push\(\[1,"(.*?)"\]\)""", Pattern.DOTALL)
		for (script in html.select("script")) {
			val data = script.data()
			if (data.contains("self.__next_f.push")) {
				val matcher = pushContentPattern.matcher(data)
				if (matcher.find()) {
					result.append(matcher.group(1))
				}
			}
		}
		return result.toString()
			.replace("\\\"", "\"")
			.replace("\\n", "\n")
	}

	/**
	 * Make a raw OkHttp POST to MeiliSearch, bypassing webClient interceptors.
	 */
	private suspend fun meiliPost(body: JSONObject): JSONObject {
		val mediaType = "application/json; charset=utf-8".toMediaType()
		val requestBody = body.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.url("https://$meiliDomain/multi-search")
			.post(requestBody)
			.addHeader("Authorization", "Bearer $meiliKey")
			.addHeader("Origin", "https://$domain")
			.addHeader("Referer", "https://$domain/")
			.addHeader("Accept", "application/json")
			.tag(MangaParserSource::class.java, source)
			.build()
		val client = context.httpClient.newBuilder()
			.apply { interceptors().clear() }
			.build()
		return client.newCall(request).await().parseJson()
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query.orEmpty()
		val offset = (page - 1) * pageSize
		val sort = when (order) {
			SortOrder.UPDATED -> "updatedAt:desc"
			SortOrder.POPULARITY -> "updatedAt:desc"
			else -> "updatedAt:desc"
		}

		val filters = JSONArray().put("deletedAt IS NULL")
		for (tag in filter.tags) {
			filters.put("genres = '${tag.key}'")
		}

		val queryObj = JSONObject().apply {
			put("indexUid", "medias")
			put("q", query)
			put("filter", filters)
			put("limit", pageSize)
			put("offset", offset)
			put("sort", JSONArray().put(sort))
		}
		val body = JSONObject().apply {
			put("queries", JSONArray().put(queryObj))
		}

		val response = meiliPost(body)

		val results = response.getJSONArray("results")
		if (results.length() == 0) return emptyList()

		val hits = results.getJSONObject(0).getJSONArray("hits")
		val mangaList = mutableListOf<Manga>()

		for (i in 0 until hits.length()) {
			val hit = hits.getJSONObject(i)
			val mediaId = hit.getString("id")
			val coverId = hit.optString("mainCoverId", "")

			// Get main title (prefer pt_br, then isMainTitle)
			val titles = hit.optJSONArray("titles")
			var mainTitle = ""
			var ptBrTitle: String? = null
			if (titles != null) {
				for (t in 0 until titles.length()) {
					val titleObj = titles.getJSONObject(t)
					if (titleObj.optString("language") == "pt_br") {
						ptBrTitle = titleObj.optString("title")
					}
					if (titleObj.optBoolean("isMainTitle", false)) {
						mainTitle = titleObj.optString("title", "")
					}
				}
			}
			val title = ptBrTitle ?: mainTitle
			if (title.isEmpty()) continue

			val coverUrl = if (coverId.isNotEmpty()) {
				"https://$cdnDomain/medias/$mediaId/covers/$coverId.jpg"
			} else {
				""
			}
			val url = "/media/$mediaId"

			mangaList.add(
				Manga(
					id = generateUid(url),
					url = url,
					publicUrl = "https://$domain$url",
					title = title,
					coverUrl = coverUrl,
					altTitles = emptySet(),
					rating = RATING_UNKNOWN,
					tags = emptySet(),
					description = null,
					state = null,
					authors = emptySet(),
					contentRating = null,
					source = source,
				),
			)
		}

		return mangaList
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mediaId = manga.url.substringAfter("/media/")

		// Use MeiliSearch to get media details (synopsis, status, genres, etc.)
		val queryObj = JSONObject().apply {
			put("indexUid", "medias")
			put("q", "")
			put("filter", JSONArray().put("id = '$mediaId'").put("deletedAt IS NULL"))
			put("limit", 1)
		}
		val body = JSONObject().apply {
			put("queries", JSONArray().put(queryObj))
		}

		val response = meiliPost(body)

		val hits = response.getJSONArray("results")
			.getJSONObject(0)
			.getJSONArray("hits")

		var synopsis: String? = null
		var status: String? = null
		var mainTitle = manga.title
		var coverId: String? = null
		val genres = mutableSetOf<MangaTag>()

		if (hits.length() > 0) {
			val hit = hits.getJSONObject(0)
			synopsis = hit.optString("synopsis", "").ifEmpty { null }
			status = hit.optString("status", "")
			coverId = hit.optString("mainCoverId", "")

			// Get best title
			val titles = hit.optJSONArray("titles")
			if (titles != null) {
				for (t in 0 until titles.length()) {
					val titleObj = titles.getJSONObject(t)
					if (titleObj.optString("language") == "pt_br") {
						mainTitle = titleObj.optString("title", mainTitle)
						break
					}
					if (titleObj.optBoolean("isMainTitle", false)) {
						mainTitle = titleObj.optString("title", mainTitle)
					}
				}
			}

			// Parse genres
			val genresArray = hit.optJSONArray("genres")
			if (genresArray != null) {
				for (g in 0 until genresArray.length()) {
					val genre = genresArray.getString(g)
					genres.add(
						MangaTag(
							key = genre.lowercase(),
							title = genre.lowercase().replace('_', ' ').toTitleCase(sourceLocale),
							source = source,
						),
					)
				}
			}
		}

		// Fetch chapters via tRPC API
		val chapters = fetchChaptersViaTrpc(mediaId)

		val coverUrl = if (!coverId.isNullOrEmpty()) {
			"https://$cdnDomain/medias/$mediaId/covers/$coverId.jpg"
		} else {
			manga.coverUrl
		}

		val mangaState = when (status?.uppercase()) {
			"ONGOING", "RELEASING" -> MangaState.ONGOING
			"FINISHED", "COMPLETED" -> MangaState.FINISHED
			"HIATUS" -> MangaState.PAUSED
			"CANCELLED", "DROPPED" -> MangaState.ABANDONED
			else -> null
		}

		return manga.copy(
			title = mainTitle,
			coverUrl = coverUrl,
			description = synopsis,
			tags = genres,
			state = mangaState,
			chapters = chapters,
		)
	}

	/**
	 * Fetch all chapters for a media via the tRPC API.
	 * Endpoint: /api/trpc/chapters.getByMediaId?input={"json":{"mediaId":"...","page":N,"perPage":100}}
	 * Response: {"result":{"data":{"json":{"chapters":[...],"totalPages":N}}}}
	 */
	private suspend fun fetchChaptersViaTrpc(mediaId: String): List<MangaChapter> {
		val chapters = mutableListOf<MangaChapter>()
		val perPage = 100
		var page = 1
		var totalPages = 1

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
		dateFormat.timeZone = TimeZone.getTimeZone("UTC")

		do {
			val input = "{\"json\":{\"mediaId\":\"$mediaId\",\"page\":$page,\"perPage\":$perPage}}"
			val encodedInput = java.net.URLEncoder.encode(input, "UTF-8")
			val apiUrl = "https://$domain/api/trpc/chapters.getByMediaId?input=$encodedInput"

			val response = webClient.httpGet(apiUrl).parseJson()
			val data = response.getJSONObject("result")
				.getJSONObject("data")
				.getJSONObject("json")

			totalPages = data.optInt("totalPages", 1)
			val chaptersArray = data.getJSONArray("chapters")

			for (i in 0 until chaptersArray.length()) {
				val chapterObj = chaptersArray.getJSONObject(i)
				val chId = chapterObj.getString("id")
				val number = chapterObj.optDouble("number", 0.0)
				val volume = chapterObj.optInt("volume", 0)
				val title = chapterObj.optString("title", "").let {
					if (it == "null" || it.isEmpty()) null else it
				}
				val chapterName = title ?: "Capítulo ${number.toInt()}"
				val url = "/chapter/$chId/1"

				// Parse upload date
				val uploadDate = try {
					val dateStr = chapterObj.optString("createdAt", "")
					if (dateStr.isNotEmpty()) {
						dateFormat.parse(dateStr.substringBefore("."))?.time ?: 0L
					} else {
						0L
					}
				} catch (_: Exception) {
					0L
				}

				// Get scanlator name
				val scanlator = try {
					val scans = chapterObj.optJSONArray("scans")
					if (scans != null && scans.length() > 0) {
						scans.getJSONObject(0).optString("name", null)
					} else {
						null
					}
				} catch (_: Exception) {
					null
				}

				chapters.add(
					MangaChapter(
						id = generateUid(url),
						title = chapterName,
						number = number.toFloat(),
						volume = volume,
						url = url,
						scanlator = scanlator,
						uploadDate = uploadDate,
						branch = null,
						source = source,
					),
				)
			}

			page++
		} while (page <= totalPages)

		return chapters.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val html = webClient.httpGet(fullUrl).parseHtml()
		val flightStr = extractFlightData(html)

		val chapterId = chapter.url.substringAfter("/chapter/").substringBefore("/")

		// Extract mediaId from the flight data
		val mediaIdPattern = Pattern.compile("\"media\":\\{\"id\":\"([a-f0-9-]+)\"")
		val mediaIdMatcher = mediaIdPattern.matcher(flightStr)
		val mediaId = if (mediaIdMatcher.find()) {
			mediaIdMatcher.group(1)
		} else {
			throw IllegalStateException("Could not find media ID for chapter")
		}

		// Extract pages array from flight data
		// Format: "pages":[{"id":"uuid","extension":"jpg"},{"id":"uuid","extension":"png"}, ...]
		val pagesPattern = Pattern.compile("\"pages\":\\[(.+?)](?:,\"previous)")
		val pagesMatcher = pagesPattern.matcher(flightStr)

		if (!pagesMatcher.find()) {
			throw IllegalStateException("Could not find pages for chapter")
		}

		val pagesArray = JSONArray("[" + pagesMatcher.group(1) + "]")
		val pages = mutableListOf<MangaPage>()

		for (i in 0 until pagesArray.length()) {
			val pageObj = pagesArray.getJSONObject(i)
			val pageId = pageObj.getString("id")
			val ext = pageObj.optString("extension", "jpg")
			val imageUrl = "https://$cdnDomain/medias/$mediaId/chapters/$chapterId/$pageId.$ext"

			pages.add(
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				),
			)
		}

		return pages
	}
}
