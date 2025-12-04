package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.arraySetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@Broken("Need to refactor this")
@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext):
	PagedMangaParser(context, MangaParserSource.COMIX, 28) {

	override val configKeyDomain = ConfigKey.Domain("comix.to")

	override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
		listOf(
			SortOrder.RELEVANCE,
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.NEWEST,
			SortOrder.ALPHABETICAL,
		)
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://comix.to/api/v2/manga?")
			var firstParam = true
			fun addParam(param: String) {
				if (firstParam) {
					append(param)
					firstParam = false
				} else {
					append("&").append(param)
				}
			}

			// Search keyword if provided
			if (!filter.query.isNullOrEmpty()) {
				addParam("keyword=${filter.query.urlEncoded()}")
			}

			// Use the provided sort order directly
			when (order) {
				SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
				SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
				SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
				SortOrder.NEWEST -> addParam("order[created_at]=desc")
				SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
				else -> addParam("order[chapter_updated_at]=desc")
			}

			// Handle genre filtering
			if (filter.tags.isNotEmpty()) {
				for (tag in filter.tags) {
					addParam("genres[]=${tag.key}")
				}
			}

			// Default exclude adult content
			addParam("genres[]=-87264") // Adult
			addParam("genres[]=-87266") // Hentai
			addParam("genres[]=-87268") // Smut
			addParam("genres[]=-87265") // Ecchi
			addParam("limit=$pageSize")
			addParam("page=$page")
		}

		val response = webClient.httpGet(url).parseJson()
		val result = response.getJSONObject("result")
		val items = result.getJSONArray("items")

		return (0 until items.length()).map { i ->
			val item = items.getJSONObject(i)
			parseMangaFromJson(item)
		}
	}

	private fun parseMangaFromJson(json: JSONObject): Manga {
		val hashId = json.getString("hash_id")
		val title = json.getString("title")
		val description = json.optString("synopsis", "").nullIfEmpty()
		val poster = json.getJSONObject("poster")
		val coverUrl = poster.optString("large", "").nullIfEmpty()
		val status = json.optString("status", "")
		val rating = json.optDouble("rated_avg", 0.0)

		val state = when (status) {
			"finished" -> MangaState.FINISHED
			"releasing" -> MangaState.ONGOING
			"on_hiatus" -> MangaState.PAUSED
			else -> null
		}

		return Manga(
			id = generateUid(hashId),
			url = "/title/$hashId",
			publicUrl = "https://comix.to/title/$hashId",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			description = description,
			rating = if (rating > 0) (rating / 10.0f).toFloat() else RATING_UNKNOWN,
			tags = emptySet(),
			authors = emptySet(),
			state = state,
			source = source,
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val hashId = manga.url.substringAfter("/title/")
		val chaptersDeferred = async { getChapters(manga) }

		// Get detailed manga info
		val detailUrl = "https://comix.to/api/v2/manga/$hashId"
		val response = webClient.httpGet(detailUrl).parseJson()

		if (response.has("result")) {
			val result = response.getJSONObject("result")
			val updatedManga = parseMangaFromJson(result)

			return@coroutineScope updatedManga.copy(
				chapters = chaptersDeferred.await(),
			)
		}

		return@coroutineScope manga.copy(
			chapters = chaptersDeferred.await(),
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	private suspend fun getChapters(manga: Manga): List<MangaChapter> {
		val hashId = manga.url.substringAfter("/title/")
		val allChapters = mutableListOf<JSONObject>()
		var page = 1

		// Fetch all chapters with pagination
		while (true) {
			val chaptersUrl = "https://comix.to/api/v2/manga/$hashId/chapters?order[number]=desc&limit=100&page=$page"
			val response = webClient.httpGet(chaptersUrl).parseJson()
			val result = response.getJSONObject("result")
			val items = result.getJSONArray("items")

			if (items.length() == 0) break

			for (i in 0 until items.length()) {
				allChapters.add(items.getJSONObject(i))
			}

			// Check pagination info to see if we have more pages
			val pagination = result.optJSONObject("pagination")
			if (pagination != null) {
				val currentPage = pagination.getInt("current_page")
				val lastPage = pagination.getInt("last_page")
				if (currentPage >= lastPage) break
			}

			page++
		}

		// Group chapters by number and pick one translation per chapter (preferring latest)
		val uniqueChapters = allChapters
			.groupBy { it.getDouble("number") }
			.mapValues { (_, chapters) ->
				// Sort by creation date descending and take the first (most recent)
				chapters.maxByOrNull { it.getLong("created_at") }!!
			}
			.values
			.sortedByDescending { it.getDouble("number") } // Sort by chapter number descending

		return uniqueChapters.mapIndexed { index, item ->
			val chapterId = item.getLong("chapter_id")
			val number = item.getDouble("number").toFloat()
			val name = item.optString("name", "").nullIfEmpty()
			val createdAt = item.getLong("created_at")
			val scanlationGroup = item.optJSONObject("scanlation_group")
			val scanlatorName = scanlationGroup?.optString("name", null)

			val title = if (name != null) {
				"Chapter $number: $name"
			} else {
				"Chapter $number"
			}

			MangaChapter(
				id = generateUid(chapterId.toString()),
				title = title,
				number = number,
				volume = 0,
				url = "/title/$hashId/$chapterId-chapter-${number.toInt()}",
				uploadDate = createdAt * 1000L, // Convert to milliseconds
				source = source,
				scanlator = scanlatorName,
				branch = null,
			)
		}.reversed() // Reverse to have ascending order
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
		val chapterUrl = "https://comix.to${chapter.url}"

		// Get the chapter page HTML to extract images from the script
		val response = webClient.httpGet(chapterUrl).parseHtml()

		// Look for the images array in the JavaScript (with escaped quotes)
		val scripts = response.select("script")
		var images: JSONArray? = null

		for (script in scripts) {
			val scriptContent = script.html()

			// Look for the images array with escaped quotes in JSON
			if (scriptContent.contains("\\\"images\\\":[")) {
				try {
					// Find the start of the images array (with escaped quotes)
					val imagesStart = scriptContent.indexOf("\\\"images\\\":[")
					val colonPos = scriptContent.indexOf(":", imagesStart)
					val arrayStart = scriptContent.indexOf("[", colonPos)

					// Find the matching closing bracket for the array
					var bracketCount = 1 // Start with 1 since we're at the opening bracket
					var arrayEnd = arrayStart + 1 // Start after the opening bracket
					var inString = false
					var escapeNext = false

					for (i in (arrayStart + 1) until scriptContent.length) {
						val char = scriptContent[i]

						if (escapeNext) {
							escapeNext = false
							continue
						}

						when (char) {
							'\\' -> escapeNext = true
							'"' -> inString = !inString
							'[' -> if (!inString) bracketCount++
							']' -> if (!inString) {
								bracketCount--
								if (bracketCount == 0) {
									arrayEnd = i + 1
									break
								}
							}
						}
					}

					val imagesJsonString = scriptContent.substring(arrayStart, arrayEnd)
					// Parse the JSON array, handling escaped quotes
					images = JSONArray(imagesJsonString.replace("\\\"", "\""))
					break
				} catch (_: Exception) {
					// Continue to next script if parsing fails
					continue
				}
			}
		}

		if (images == null) {
			throw ParseException("Unable to find chapter images", chapterUrl)
		}

		return (0 until images.length()).map { i ->
			val imageUrl = images.getString(i)
			MangaPage(
				id = generateUid("$chapterId-$i"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun fetchAvailableTags() = arraySetOf(
		// Genres
		MangaTag("Action", "6", source),
		MangaTag("Adult", "87264", source),
		MangaTag("Adventure", "7", source),
		MangaTag("Boys Love", "8", source),
		MangaTag("Comedy", "9", source),
		MangaTag("Crime", "10", source),
		MangaTag("Drama", "11", source),
		MangaTag("Ecchi", "87265", source),
		MangaTag("Fantasy", "12", source),
		MangaTag("Girls Love", "13", source),
		MangaTag("Hentai", "87266", source),
		MangaTag("Historical", "14", source),
		MangaTag("Horror", "15", source),
		MangaTag("Isekai", "16", source),
		MangaTag("Magical Girls", "17", source),
		MangaTag("Mature", "87267", source),
		MangaTag("Mecha", "18", source),
		MangaTag("Medical", "19", source),
		MangaTag("Mystery", "20", source),
		MangaTag("Philosophical", "21", source),
		MangaTag("Psychological", "22", source),
		MangaTag("Romance", "23", source),
		MangaTag("Sci-Fi", "24", source),
		MangaTag("Slice of Life", "25", source),
		MangaTag("Smut", "87268", source),
		MangaTag("Sports", "26", source),
		MangaTag("Superhero", "27", source),
		MangaTag("Thriller", "28", source),
		MangaTag("Tragedy", "29", source),
		MangaTag("Wuxia", "30", source),

		// Themes
		MangaTag("Aliens", "31", source),
		MangaTag("Animals", "32", source),
		MangaTag("Cooking", "33", source),
		MangaTag("Crossdressing", "34", source),
		MangaTag("Delinquents", "35", source),
		MangaTag("Demons", "36", source),
		MangaTag("Genderswap", "37", source),
		MangaTag("Ghosts", "38", source),
		MangaTag("Gyaru", "39", source),
		MangaTag("Harem", "40", source),
		MangaTag("Incest", "41", source),
		MangaTag("Loli", "42", source),
		MangaTag("Mafia", "43", source),
		MangaTag("Magic", "44", source),
		MangaTag("Martial Arts", "45", source),
		MangaTag("Military", "46", source),
		MangaTag("Monster Girls", "47", source),
		MangaTag("Monsters", "48", source),
		MangaTag("Music", "49", source),
		MangaTag("Ninja", "50", source),
		MangaTag("Office Workers", "51", source),
		MangaTag("Police", "52", source),
		MangaTag("Post-Apocalyptic", "53", source),
		MangaTag("Reincarnation", "54", source),
		MangaTag("Reverse Harem", "55", source),
		MangaTag("Samurai", "56", source),
		MangaTag("School Life", "57", source),
		MangaTag("Shota", "58", source),
		MangaTag("Supernatural", "59", source),
		MangaTag("Survival", "60", source),
		MangaTag("Time Travel", "61", source),
		MangaTag("Traditional Games", "62", source),
		MangaTag("Vampires", "63", source),
		MangaTag("Video Games", "64", source),
		MangaTag("Villainess", "65", source),
		MangaTag("Virtual Reality", "66", source),
		MangaTag("Zombies", "67", source),
	)
}
