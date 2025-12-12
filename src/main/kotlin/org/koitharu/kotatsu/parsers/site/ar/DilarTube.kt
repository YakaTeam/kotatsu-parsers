package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
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

@MangaSourceParser("DILARTUBE", "Dilar Tube", "ar", ContentType.MANGA)
internal class DilarTube(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DILARTUBE, 24) {

	override val configKeyDomain = ConfigKey.Domain("v2.dilar.tube")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val originalRequest = chain.request()

		// Only modify POST requests to the search/filter endpoint
		if (originalRequest.method == "POST" && originalRequest.url.toString().contains("/api/search/filter")) {
			val newRequest = originalRequest.newBuilder()
				.removeHeader("Content-Encoding")
				.build()
			return chain.proceed(newRequest)
		}

		return chain.proceed(originalRequest)
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.RELEVANCE)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val response = webClient.httpGet("https://v2.dilar.tube/api/categories").parseJsonArray()
		val tags = mutableSetOf<MangaTag>()

		for (i in 0 until response.length()) {
			val group = response.getJSONObject(i)
			val groupId = group.optString("id").toIntOrNull() ?: group.optInt("id")
			val categories = group.getJSONArray("categories")

			// Group 3 is "Style" (Manhwa, etc) -> seriesType
			// Others -> categories
			val prefix = if (groupId == 3) "seriesType" else "categories"

			for (j in 0 until categories.length()) {
				val category = categories.getJSONObject(j)
				val catId = category.optString("id").toIntOrNull() ?: category.optInt("id")
				tags.add(
					MangaTag(
						key = "$prefix:$catId",
						title = category.getString("name"),
						source = source,
					)
				)
			}
		}
		return tags
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Check if we have any actual filters to apply
		val hasSearch = !filter.query.isNullOrBlank()
		val hasTagFilters = filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty()

		// Use GET for default listing only (no search, no tags)
		if (!hasSearch && !hasTagFilters) {
			val url = "https://v2.dilar.tube/api/series/?page=$page"
			val response = webClient.httpGet(url).parseJson()
			val series = response.getJSONArray("series")
			return (0 until series.length()).map { i ->
				parseMangaFromJson(series.getJSONObject(i))
			}
		}

		// Use POST for search and/or tag filtering
		val url = "https://v2.dilar.tube/api/search/filter"

		val seriesTypeInclude = mutableListOf<Int>()
		val seriesTypeExclude = mutableListOf<Int>()
		val categoriesInclude = mutableListOf<Int>()
		val categoriesExclude = mutableListOf<Int>()

		filter.tags.forEach { tag ->
			val parts = tag.key.split(":")
			if (parts.size == 2) {
				val type = parts[0]
				val id = parts[1].toIntOrNull() ?: return@forEach
				if (type == "seriesType") {
					seriesTypeInclude.add(id)
				} else if (type == "categories") {
					categoriesInclude.add(id)
				}
			}
		}

		filter.tagsExclude.forEach { tag ->
			val parts = tag.key.split(":")
			if (parts.size == 2) {
				val type = parts[0]
				val id = parts[1].toIntOrNull() ?: return@forEach
				if (type == "seriesType") {
					seriesTypeExclude.add(id)
				} else if (type == "categories") {
					categoriesExclude.add(id)
				}
			}
		}

		// Build JSON payload exactly as shown in working examples
		val jsonBody = JSONObject()

		// Add query
		jsonBody.put("query", filter.query ?: "")

		// Add seriesType
		val seriesTypeObject = JSONObject()
		val seriesTypeIncludeArray = JSONArray()
		seriesTypeInclude.forEach { seriesTypeIncludeArray.put(it) }
		val seriesTypeExcludeArray = JSONArray()
		seriesTypeExclude.forEach { seriesTypeExcludeArray.put(it) }
		seriesTypeObject.put("include", seriesTypeIncludeArray)
		seriesTypeObject.put("exclude", seriesTypeExcludeArray)
		jsonBody.put("seriesType", seriesTypeObject)

		// Add oneshot
		jsonBody.put("oneshot", false)

		// Add categories
		val categoriesObject = JSONObject()
		val categoriesIncludeArray = JSONArray()
		categoriesInclude.forEach { categoriesIncludeArray.put(it) }
		val categoriesExcludeArray = JSONArray()
		categoriesExclude.forEach { categoriesExcludeArray.put(it) }
		categoriesObject.put("include", categoriesIncludeArray)
		categoriesObject.put("exclude", categoriesExcludeArray)
		jsonBody.put("categories", categoriesObject)

		// Add chapters
		val chaptersObject = JSONObject()
		chaptersObject.put("min", "")
		chaptersObject.put("max", "")
		jsonBody.put("chapters", chaptersObject)

		// Add dates
		val datesObject = JSONObject()
		datesObject.put("start", JSONObject.NULL)
		datesObject.put("end", JSONObject.NULL)
		jsonBody.put("dates", datesObject)

		// Add page
		jsonBody.put("page", page)

		val response = webClient.httpPost(url.toHttpUrl(), jsonBody).parseJson()

		// Parse the response - search/filter endpoint returns "rows", regular endpoint returns "series"
		val rows = when {
			response.has("rows") -> response.getJSONArray("rows")
			response.has("series") -> response.getJSONArray("series")
			else -> JSONArray() // Empty array if no results
		}

		return (0 until rows.length()).map { i ->
			val item = rows.getJSONObject(i)
			parseMangaFromJson(item)
		}
	}

	private fun parseMangaFromJson(json: JSONObject): Manga {
		val id = json.getInt("id")
		val title = json.getString("title")
		val cover = json.optString("cover", "")
		val summary = json.optString("summary", "")

		// Build cover URL - rollback to original working structure
		val coverUrl = if (cover.isNotEmpty()) {
			if (cover.startsWith("http")) {
				cover
			} else {
				val coverName = cover.substringBeforeLast('.') + ".webp"
				"https://dilar.tube/uploads/manga/cover/$id/large_$coverName"
			}
		} else ""

		val rating = json.optString("rating", "0.00").toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

		// Get alternative titles from synonyms
		val synonyms = json.optJSONObject("synonyms")
		val altTitles = mutableSetOf<String>()
		synonyms?.let { syn ->
			syn.optString("arabic")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
			syn.optString("english")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
			syn.optString("japanese")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
			syn.optString("alternative")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
		}

		val status = json.optString("story_status", "")
		val state = when (status.lowercase()) {
			"completed" -> MangaState.FINISHED
			"ongoing" -> MangaState.ONGOING
			"hiatus" -> MangaState.PAUSED
			else -> null
		}

		return Manga(
			id = generateUid(id.toLong()),
			title = title,
			url = "/series/$id",
			publicUrl = "https://v2.dilar.tube/series/$id",
			coverUrl = coverUrl,
			source = source,
			rating = rating,
			altTitles = altTitles,
			contentRating = ContentRating.SAFE,
			tags = emptySet(),
			state = state,
			authors = emptySet(),
			largeCoverUrl = null,
			description = summary.takeIf { it.isNotEmpty() },
			chapters = null,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val id = manga.url.substringAfterLast("/")
		val url = "https://v2.dilar.tube/api/series/$id"
		val json = webClient.httpGet(url).parseJson()

		val title = json.getString("title")
		val summary = json.optString("summary").nullIfEmpty()

		val cover = json.optString("cover").nullIfEmpty()
		val coverUrl = if (cover != null) {
			val coverName = cover.substringBeforeLast('.') + ".webp"
			"https://dilar.tube/uploads/manga/cover/$id/large_$coverName"
		} else manga.coverUrl

		val statusStr = json.optString("story_status")
		val state = when (statusStr?.lowercase()) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.ONGOING
			else -> null
		}

		val authors = mutableSetOf<String>()
		json.optJSONObject("creator")?.let {
			authors.add(it.getString("nick"))
		}

		val tags = mutableSetOf<MangaTag>()
		val categories = json.optJSONArray("categories")
		if (categories != null) {
			for (i in 0 until categories.length()) {
				val cat = categories.getJSONObject(i)
				tags.add(MangaTag(
					key = cat.getInt("id").toString(),
					title = cat.getString("name"),
					source = source
				))
			}
		}

		return manga.copy(
			title = title,
			description = summary,
			coverUrl = coverUrl,
			state = state,
			authors = authors,
			tags = tags,
			chapters = getChapters(id),
		)
	}

	private suspend fun getChapters(seriesId: String): List<MangaChapter> {
		val url = "https://v2.dilar.tube/api/series/$seriesId/chapters"
		val response = webClient.httpGet(url).parseJson()
		val chaptersJson = response.getJSONArray("chapters")
		val chapters = mutableListOf<MangaChapter>()

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

		for (i in 0 until chaptersJson.length()) {
			val item = chaptersJson.getJSONObject(i)
			val releases = item.getJSONArray("releases")
			if (releases.length() == 0) continue

			val release = releases.getJSONObject(0)
			val releaseId = release.getInt("id")

			val chapterNum = item.optString("chapter").toFloatOrNull() ?: 0f
			val volNum = item.optString("volume").toIntOrNull() ?: 0
			val title = item.optString("title").nullIfEmpty() ?: ""

			val dateStr = item.optString("created_at")
			val date = try {
				dateFormat.parse(dateStr)?.time ?: 0L
			} catch (e: Exception) {
				0L
			}

			chapters.add(
				MangaChapter(
					id = generateUid(releaseId.toString()),
					title = title,
					number = chapterNum,
					volume = volNum,
					url = "/api/chapters/$releaseId",
					uploadDate = date,
					source = source,
					scanlator = null,
					branch = null
				)
			)
		}
		return chapters.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val id = chapter.url.substringAfterLast("/")
		val url = "https://v2.dilar.tube/api/chapters/$id"
		val json = webClient.httpGet(url).parseJson()
		val pagesJson = json.getJSONArray("pages")
		val storageKey = json.optString("storage_key").nullIfEmpty()

		return (0 until pagesJson.length()).map { i ->
			val page = pagesJson.getJSONObject(i)
			val imageUrl = page.getString("url")

			val fullUrl = if (imageUrl.startsWith("http")) {
				imageUrl
			} else {
				if (storageKey != null) {
					"https://dilar.tube/uploads/releases/$storageKey/hq/$imageUrl"
				} else {
					"https://dilar.tube/uploads/$imageUrl"
				}
			}

			MangaPage(
				id = generateUid("$id-$i"),
				url = fullUrl,
				preview = null,
				source = source
			)
		}
	}
}
