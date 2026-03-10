package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

@Broken("Refactor code")
@MangaSourceParser("TAIYO", "Taiyō", "pt")
internal class TaiyoParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TAIYO, 20) {

	override val configKeyDomain = ConfigKey.Domain("taiyo.moe")

	private val cdnDomain = "cdn.$domain"
	private val meiliDomain = "meilisearch.$domain"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY
	)

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
	private fun extractFlightData(html: Document): String {
		val result = StringBuilder()
		val pushContentPattern = Pattern.compile("""self\.__next_f\.push\(\[1,"(.*?)"]\)""", Pattern.DOTALL)
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
			.addHeader("Authorization", "Bearer $MEILIKEY")
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
		return hits.mapJSON { hit ->
			val mediaId = hit.getString("id")
			val coverId = hit.optString("mainCoverId", "")
			val url = "/media/$mediaId"

			val title = hit.optJSONArray("titles")
				?.mapJSON { obj ->
					obj.optString("language") to
						(obj.optString("title") to obj.optBoolean("isMainTitle", false))
				}?.let { list ->
					val ptBr = list.firstOrNull { it.first == "pt_br" }?.second?.first
					val main = list.firstOrNull { it.second.second }?.second?.first.orEmpty()
					ptBr ?: main
				}.orEmpty()

			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = "https://$domain$url",
				title = title,
				coverUrl = "https://$cdnDomain/medias/$mediaId/covers/$coverId.jpg",
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				description = null,
				state = null,
				authors = emptySet(),
				contentRating = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mediaId = manga.url.substringAfter("/media/")

		val body = JSONObject().apply {
			put("queries", JSONArray().put(
				JSONObject().apply {
					put("indexUid", "medias")
					put("q", "")
					put("filter", JSONArray()
						.put("id = '$mediaId'")
						.put("deletedAt IS NULL")
					)
					put("limit", 1)
				}
			))
		}

		val hits = meiliPost(body).getJSONArray("results")
			.getJSONObject(0)
			.optJSONArray("hits")
			?: JSONArray()

		val hit = hits.optJSONObject(0)
		val status = hit?.optString("status", "")
		val synopsis = hit?.optString("synopsis", "")
			?.ifEmpty { null }

		val coverId = hit?.optString("mainCoverId", "")
			?.ifEmpty { null }

		val titles = hit?.optJSONArray("titles")
			?.mapJSON { it }
			.orEmpty()

		val mainTitle = titles
			.firstOrNull { it.optString("language") == "pt_br" }
			?.optString("title")
			?: titles.firstOrNull { it.optBoolean("isMainTitle", false) }
				?.optString("title")
			?: manga.title

		val genres = hit?.optJSONArray("genres")?.let { array ->
			(0 until array.length()).map { array.getString(it) }
				.mapTo(mutableSetOf()) { genre ->
					MangaTag(
						key = genre.lowercase(),
						title = genre.lowercase()
							.replace('_', ' ')
							.toTitleCase(sourceLocale),
						source = source,
					)
				}
			} ?: emptySet()

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
		var totalPages: Int

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
		dateFormat.timeZone = TimeZone.getTimeZone("UTC")

		do {
			val input = "{\"json\":{\"mediaId\":\"$mediaId\",\"page\":$page,\"perPage\":$perPage}}"
			val encodedInput = URLEncoder.encode(input, "UTF-8")
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
		val pagesPattern = Pattern.compile("\"pages\":\\[(.+?)],\"previous")
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

	companion object {
		private const val MEILIKEY = "48aa86f73de09a7705a2938a1a35e5a12cff6519695fcad395161315182286e5"
	}
}
