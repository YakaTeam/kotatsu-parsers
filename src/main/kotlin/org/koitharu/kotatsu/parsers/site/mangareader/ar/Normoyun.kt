package org.koitharu.kotatsu.parsers.site.mangareader.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGASWAT", "Manga Swat", "ar", ContentType.MANGA)
internal class MangaSwat(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGASWAT, 20) {

	override val configKeyDomain = ConfigKey.Domain("meshmanga.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
		)

	override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
		listOf(
			SortOrder.RELEVANCE,
			SortOrder.POPULARITY,
			SortOrder.RATING,
		)
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val response = webClient.httpGet("https://appswat.com/v2/api/v2/genres/").parseJsonArray()
		val tags = mutableSetOf<MangaTag>()
		for (i in 0 until response.length()) {
			val genreObj = response.getJSONObject(i)
			tags.add(MangaTag(
				key = genreObj.getInt("id").toString(),
				title = genreObj.getString("name"),
				source = source,
			))
		}
		return tags
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://appswat.com/v2/api/v2/series/?page_size=20")

			// Add page offset
			if (page > 1) {
				val offset = (page - 1) * 20
				append("&offset=$offset")
			}

			// Add search query
			if (!filter.query.isNullOrEmpty()) {
				append("&search=${filter.query.urlEncoded()}")
			}

			// Add sort order
			when (order) {
				SortOrder.POPULARITY -> append("&order_by=followers_count")
				SortOrder.RATING -> append("&order_by=-rating")
				else -> {} // Default relevance
			}

			// Add genre filters
			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach { tag ->
					append("&genres=${tag.key}")
				}
			}
		}

		val response = webClient.httpGet(url).parseJson()
		val results = response.getJSONArray("results")

		return (0 until results.length()).map { i ->
			val item = results.getJSONObject(i)
			parseMangaFromJson(item)
		}
	}

	private fun parseMangaFromJson(json: JSONObject): Manga {
		val id = json.getInt("id")
		val title = json.getString("title")
		val slug = json.getString("slug")

		val status = json.getJSONObject("status")
		val state = when (status.getString("name")) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			else -> null
		}

		val poster = json.getJSONObject("poster")
		val coverUrl = poster.optString("medium").nullIfEmpty()
			?: poster.optString("thumbnail").nullIfEmpty()

		val rating = json.optString("rating", "0.0").toFloatOrNull() ?: 0f
		val normalizedRating = if (rating > 0) rating / 10f else RATING_UNKNOWN

		val genres = json.getJSONArray("genres")
		val tags = mutableSetOf<MangaTag>()
		for (i in 0 until genres.length()) {
			val genre = genres.getJSONObject(i)
			tags.add(MangaTag(
				key = genre.getInt("id").toString(),
				title = genre.getString("name"),
				source = source,
			))
		}

		// Extract translator/editor info for authors
		val authors = mutableSetOf<String>()
		json.optJSONObject("translator")?.let { translator ->
			authors.add(translator.getString("name"))
		}
		json.optJSONObject("editor")?.let { editor ->
			authors.add(editor.getString("name"))
		}

		return Manga(
			id = generateUid(id.toString()),
			url = "/series/$id",
			publicUrl = "https://meshmanga.com/series/$slug/",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			rating = normalizedRating,
			tags = tags,
			authors = authors,
			state = state,
			source = source,
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val seriesId = manga.url.substringAfter("/series/")
		val chaptersDeferred = async { getChapters(seriesId) }

		// Get detailed manga info from the same API endpoint
		val detailUrl = "https://appswat.com/v2/api/v2/series/?page_size=100"
		val response = webClient.httpGet(detailUrl).parseJson()
		val results = response.getJSONArray("results")

		// Find the specific manga by ID
		var updatedManga = manga
		for (i in 0 until results.length()) {
			val item = results.getJSONObject(i)
			if (item.getInt("id").toString() == seriesId) {
				updatedManga = parseMangaFromJson(item)
				break
			}
		}

		return@coroutineScope updatedManga.copy(
			chapters = chaptersDeferred.await(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfter("/chapters/")
		val chapterUrl = "https://appswat.com/v2/api/v2/chapters/$chapterId/"

		val response = webClient.httpGet(chapterUrl).parseJson()
		val images = response.getJSONArray("images")

		return (0 until images.length()).map { i ->
			val imageObj = images.getJSONObject(i)
			val imageUrl = imageObj.getString("image")
			val order = imageObj.getInt("order")

			MangaPage(
				id = generateUid("$chapterId-$order"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun getChapters(seriesId: String): List<MangaChapter> {
		val allChapters = mutableListOf<JSONObject>()
		var page = 1

		// Fetch all chapters with pagination
		while (true) {
			val chaptersUrl = "https://appswat.com/v2/api/v2/chapters/?page=$page&serie=$seriesId&order_by=-order&page_size=20"
			val response = webClient.httpGet(chaptersUrl).parseJson()
			val results = response.getJSONArray("results")

			if (results.length() == 0) break

			for (i in 0 until results.length()) {
				allChapters.add(results.getJSONObject(i))
			}

			// Check if there's a next page - properly handle null case
			val hasNext = !response.isNull("next")
			if (!hasNext) break
			page++
		}

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

		return allChapters.mapIndexedNotNull { index, item ->
			val chapterId = item.getInt("id")
			val chapterNumber = item.optString("chapter", "").toFloatOrNull() ?: (index + 1f)
			val title = item.getString("title")
			val createdAt = item.getString("created_at")

			val uploadDate = try {
				dateFormat.parse(createdAt)?.time ?: 0L
			} catch (e: Exception) {
				0L
			}

			MangaChapter(
				id = generateUid(chapterId.toString()),
				title = title,
				number = chapterNumber,
				volume = 0,
				url = "/chapters/$chapterId",
				uploadDate = uploadDate,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.reversed() // Reverse to have ascending order
	}
}
