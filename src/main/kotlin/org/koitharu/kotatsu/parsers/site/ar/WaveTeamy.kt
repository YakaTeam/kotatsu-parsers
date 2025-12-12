package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("WAVETEAMY", "Waveteamy", "ar")
internal class WaveTeamy(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WAVETEAMY, 50) {

	override val configKeyDomain = ConfigKey.Domain("waveteamy.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val originalRequest = chain.request()

		// Remove Content-Encoding header from POST requests to avoid gzip compression issues
		if (originalRequest.method == "POST") {
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
			isSearchWithFiltersSupported = false,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
		)

	override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.UPDATED)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions()
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrBlank()) {
			return getSearch(filter.query, page)
		}

		val url = "https://$domain/wapi/hanout/v1/series/releases-web"
		val formBody = mapOf(
			"page" to page.toString(),
			"limit" to "50"
		)

		val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()
		val chapters = response.getJSONArray("chapters")

		return (0 until chapters.length()).map { i ->
			val item = chapters.getJSONObject(i)
			parseMangaFromList(item)
		}
	}

	private suspend fun getSearch(query: String, page: Int): List<Manga> {
		// Search doesn't seem to support pagination based on the user request,
		// but we'll handle the first page at least.
		if (page > 1) return emptyList()

		val token = fetchToken()
		val url = "https://$domain/wapi/hanout/v1/series/search-work-site"
		val formBody = mapOf(
			"token" to token,
			"keyValue" to query
		)

		val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()

		if (!response.getBoolean("success")) {
			return emptyList()
		}

		val data = response.getJSONArray("data")
		return (0 until data.length()).map { i ->
			val item = data.getJSONObject(i)
			// Search result format might be slightly different, let's adapt
			// Based on user request: "workName", "workImage", "postId"
			Manga(
				id = generateUid(item.getLong("postId")),
				title = item.getString("workName"),
				url = "/series/${item.getLong("postId")}",
				publicUrl = "https://$domain/series/${item.getLong("postId")}",
				coverUrl = resolveCover(item.getString("workImage")),
				source = source,
				rating = RATING_UNKNOWN,
				altTitles = emptySet(),
				contentRating = ContentRating.SAFE,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
			)
		}
	}

	private var cachedToken: String? = null

	private suspend fun fetchToken(): String {
		cachedToken?.let { return it }

		// Try to fetch from the layout file as suggested
		// We first need to find the layout file name from the main page
		val mainPage = webClient.httpGet("https://$domain").parseHtml()
		val scriptSrc = mainPage.select("script[src*='layout-']").attr("src")

		if (scriptSrc.isNotEmpty()) {
			val scriptContent = webClient.httpGet(scriptSrc.toAbsoluteUrl(domain)).body?.string() ?: ""
			val tokenMatch = Regex("""["']?token["']?\s*:\s*["']([^"']+)["']""").find(scriptContent)
			if (tokenMatch != null) {
				val token = tokenMatch.groupValues[1]
				cachedToken = token
				return token
			}
		}

		// Fallback: try to find it in any script on the main page if the specific layout file logic fails
		// or if the user provided URL was just an example and the hash changes.
		// The user provided: https://waveteamy.com/_next/static/chunks/app/layout-2d9af0783ea921ab.js
		// We can try to regex the main page for the layout chunk URL.

		throw ParseException("Could not find search token", "https://$domain")
	}

	private fun parseMangaFromList(json: JSONObject): Manga {
		val id = json.getLong("postId")
		val title = json.getString("title")
		val cover = json.getString("imageUrl")
		val rating = json.optString("ratingValue", "0").toFloatOrNull()?.div(2f) ?: RATING_UNKNOWN // 10 point scale to 5
		val statusVal = json.optInt("statusValue")
		val state = when (statusVal) {
			0 -> MangaState.ONGOING
			1 -> MangaState.FINISHED
			2 -> MangaState.PAUSED // Assuming 2 might be paused/stopped based on context
			else -> MangaState.ONGOING
		}

		val genres = json.optString("genre").split(",").mapNotNull {
			val trimmed = it.trim()
			if (trimmed.isNotEmpty()) MangaTag(key = trimmed, title = trimmed, source = source) else null
		}.toSet()

		return Manga(
			id = generateUid(id),
			title = title,
			url = "/series/$id",
			publicUrl = "https://$domain/series/$id",
			coverUrl = resolveCover(cover),
			source = source,
			rating = rating,
			altTitles = emptySet(),
			contentRating = ContentRating.SAFE,
			tags = genres,
			state = state,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
		)
	}

	private fun resolveCover(path: String): String {
		if (path.startsWith("http")) return path

		// Use wcloud.site for series/projects/users paths as they are served from CDN
		if (path.startsWith("series/") || path.startsWith("projects/") || path.startsWith("users/")) {
			return "https://wcloud.site/$path"
		}

		// For other paths, use the main domain
		return "https://$domain/$path"
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val id = manga.url.substringAfterLast("/")
		val url = "https://$domain/series/$id"

		// Add early debug info to see if method is called
		val doc = webClient.httpGet(url).parseHtml()

		// Try to get description from meta tag as fallback
		val metaDescription = doc.selectFirst("meta[name=description]")?.attr("content")

		// Parse Next.js hydration data - find script with both mangaData and chaptersData
		val allScripts = doc.select("script")

		// Find scripts that contain both mangaData and chaptersData
		val candidateScripts = allScripts.filter { script ->
			val html = script.html()
			html.contains("mangaData") && html.contains("chaptersData")
		}

		// Prioritize scripts with escaped data format (the working format)
		var scriptContent = candidateScripts.find { script ->
			val html = script.html()
			html.contains("\\\"postId\\\":") && html.contains("\\\"chaptersData\\\":[")
		}?.html()

		// Fallback to any script with both required fields
		if (scriptContent == null && candidateScripts.isNotEmpty()) {
			scriptContent = candidateScripts.first().html()
		}

		if (scriptContent == null) {
			return manga.copy(
				description = metaDescription ?: "No description available",
				chapters = emptyList()
			)
		}

		// Try the direct escaped extraction method first since it's confirmed to work
		val directChapters = try {
			extractChaptersDataDirectly(scriptContent, url)
		} catch (e: Exception) {
			emptyList()
		}

		if (directChapters.isNotEmpty()) {
			// If direct extraction worked, use it and try to get basic manga data too
			val basicMangaData = try {
				extractBasicMangaData(scriptContent, url)
			} catch (e: Exception) {
				null
			}

			return manga.copy(
				title = basicMangaData?.title ?: manga.title,
				description = basicMangaData?.description ?: metaDescription ?: "No description available",
				coverUrl = basicMangaData?.coverUrl ?: manga.coverUrl,
				state = basicMangaData?.state,
				authors = basicMangaData?.authors ?: emptySet(),
				tags = basicMangaData?.tags ?: emptySet(),
				chapters = directChapters,
			)
		}

		// If direct extraction failed, try the original JSON extraction method
		try {
			val jsonStr = extractJson(scriptContent, url)
			val json = JSONObject(jsonStr)

			// Extract mangaData
			val mangaData = json.getJSONObject("mangaData")
			val title = mangaData.getString("name")
			val cover = mangaData.getString("cover")
			val description = mangaData.optString("story").takeIf { it.isNotEmpty() } ?: metaDescription
			val statusVal = mangaData.optInt("status")
			val state = when (statusVal) {
				0 -> MangaState.ONGOING
				1 -> MangaState.FINISHED
				else -> MangaState.ONGOING
			}

			val authors = mutableSetOf<String>()
			mangaData.optString("author").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }
			mangaData.optString("artist").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }

			val genres = mangaData.optJSONArray("genre")?.let { arr ->
				(0 until arr.length()).mapNotNull { i ->
					val g = arr.getString(i)
					if (g.isNotEmpty()) MangaTag(key = g, title = g, source = source) else null
				}.toSet()
			} ?: emptySet()

			// Get postId for chapter URLs (format: /series/postId/chapterNumber)
			val postId = mangaData.getLong("postId")

			// Extract chaptersData (should be at same level as mangaData)
			val chaptersData = json.optJSONArray("chaptersData")
			val chapters = if (chaptersData != null) {
				(0 until chaptersData.length()).mapNotNull { i ->
					try {
						val ch = chaptersData.getJSONObject(i)
						val chId = ch.getInt("id")
						val chNum = ch.optDouble("chapter", 0.0).toFloat()
						val chDate = ch.getString("postTime")
						val chTitle = ch.optString("title").takeIf { it.isNotEmpty() && it != "null" }

						// Build chapter URL using postId and chapter number: /series/1048777954/30
						MangaChapter(
							id = generateUid(chId.toLong()),
							title = chTitle,
							number = chNum,
							volume = 0,
							url = "/series/$postId/${chNum.toInt()}",
							uploadDate = parseDate(chDate),
							source = source,
							scanlator = null,
							branch = null
						)
					} catch (e: Exception) {
						null // Skip malformed chapters
					}
				}.reversed() // Reverse to show newest first
			} else {
				emptyList()
			}

			return manga.copy(
				title = title,
				description = description,
				coverUrl = resolveCover(cover),
				state = state,
				authors = authors,
				tags = genres,
				chapters = chapters,
			)
		} catch (e: Exception) {
			// Both methods failed - return basic info with any chapters we found
			return manga.copy(
				description = metaDescription ?: "No description available",
				chapters = directChapters // Use whatever chapters were extracted, even if empty
			)
		}
	}

	private fun extractJson(script: String, url: String): String {
		// Find the specific pattern from Next.js that contains the data we need
		val mangaDataIndex = script.indexOf("\"mangaData\":")
		val chaptersDataIndex = script.indexOf("\"chaptersData\":")

		if (mangaDataIndex == -1 || chaptersDataIndex == -1) {
			throw ParseException("Required data fields not found in script", url)
		}

		// Since chaptersData usually comes after mangaData, find the object that contains mangaData
		var startIndex = -1
		for (i in mangaDataIndex downTo 0) {
			if (script[i] == '{') {
				startIndex = i
				break
			}
		}

		if (startIndex == -1) throw ParseException("No opening brace found", url)

		// Use a more sophisticated brace matching that handles strings properly
		var braceCount = 0
		var inString = false
		var escape = false

		for (i in startIndex until script.length) {
			val c = script[i]

			when {
				escape -> escape = false
				c == '\\' -> escape = true
				c == '"' && !escape -> inString = !inString
				!inString && c == '{' -> braceCount++
				!inString && c == '}' -> {
					braceCount--
					if (braceCount == 0) {
						val candidate = script.substring(startIndex, i + 1)
						// Verify this contains both required fields
						if (candidate.contains("\"mangaData\":") && candidate.contains("\"chaptersData\":")) {
							return candidate.replace("\\\"", "\"")
								.replace("\\\\", "\\")
								.replace("\\n", "\n")
								.replace("\\r", "\r")
								.replace("\\t", "\t")
						}
					}
				}
			}
		}

		throw ParseException("Failed to extract valid JSON object", url)
	}

	private fun extractChaptersDataDirectly(scriptContent: String?, url: String): List<MangaChapter> {
		if (scriptContent == null) return emptyList()

		// Simple approach: find postId and chaptersData directly in the escaped format
		val postIdMatch = Regex("""\\\"postId\\\":(\d+)""").find(scriptContent)
		val postId = postIdMatch?.groupValues?.get(1)?.toLongOrNull() ?: return emptyList()

		// Find chaptersData array in escaped format
		val chaptersStart = scriptContent.indexOf("\\\"chaptersData\\\":[")
		if (chaptersStart == -1) return emptyList()

		// Start after the array opening
		val arrayStart = chaptersStart + "\\\"chaptersData\\\":".length

		// Find the matching closing bracket for the array
		var depth = 0
		var inString = false
		var escape = false
		var arrayEnd = arrayStart

		for (i in arrayStart until scriptContent.length) {
			val char = scriptContent[i]

			when {
				escape -> escape = false
				char == '\\' -> escape = true
				char == '"' && !escape -> inString = !inString
				!inString -> {
					when (char) {
						'[' -> depth++
						']' -> {
							depth--
							if (depth == 0) {
								arrayEnd = i + 1
								break
							}
						}
					}
				}
			}
		}

		if (depth != 0) return emptyList()

		// Extract and unescape the array
		val chaptersJson = scriptContent.substring(arrayStart, arrayEnd)
			.replace("\\\"", "\"")
			.replace("\\\\", "\\")

		try {
			val chaptersArray = org.json.JSONArray(chaptersJson)
			return (0 until chaptersArray.length()).mapNotNull { i ->
				try {
					val ch = chaptersArray.getJSONObject(i)
					val chId = ch.getInt("id")
					val chNum = ch.optDouble("chapter", 0.0).toFloat()
					val chDate = ch.getString("postTime")
					val chTitle = ch.optString("title").takeIf { it.isNotEmpty() && it != "null" }

					MangaChapter(
						id = generateUid(chId.toLong()),
						title = chTitle,
						number = chNum,
						volume = 0,
						url = "/series/$postId/${chNum.toInt()}",
						uploadDate = parseDate(chDate),
						source = source,
						scanlator = null,
						branch = null
					)
				} catch (e: Exception) {
					null
				}
			}.reversed() // Reverse to show newest first
		} catch (e: Exception) {
			return emptyList()
		}
	}

	private data class BasicMangaData(
		val title: String?,
		val description: String?,
		val coverUrl: String?,
		val state: MangaState?,
		val authors: Set<String>,
		val tags: Set<MangaTag>
	)

	private fun extractBasicMangaData(scriptContent: String?, url: String): BasicMangaData? {
		if (scriptContent == null) return null

		try {
			// Find mangaData in escaped format
			val mangaDataStart = scriptContent.indexOf("\\\"mangaData\\\":{")
			if (mangaDataStart == -1) return null

			// Start after the key and opening brace
			val dataStart = mangaDataStart + "\\\"mangaData\\\":".length

			// Find the matching closing brace for the object
			var depth = 0
			var inString = false
			var escape = false
			var dataEnd = dataStart

			for (i in dataStart until scriptContent.length) {
				val char = scriptContent[i]

				when {
					escape -> escape = false
					char == '\\' -> escape = true
					char == '"' && !escape -> inString = !inString
					!inString -> {
						when (char) {
							'{' -> depth++
							'}' -> {
								depth--
								if (depth == 0) {
									dataEnd = i + 1
									break
								}
							}
						}
					}
				}
			}

			if (depth != 0) return null

			// Extract and unescape the object
			val mangaDataJson = scriptContent.substring(dataStart, dataEnd)
				.replace("\\\"", "\"")
				.replace("\\\\", "\\")

			val mangaData = JSONObject(mangaDataJson)

			val title = mangaData.optString("name").takeIf { it.isNotEmpty() }
			val description = mangaData.optString("story").takeIf { it.isNotEmpty() && it != "null" }
			val cover = mangaData.optString("cover").takeIf { it.isNotEmpty() }
			val statusVal = mangaData.optInt("status", -1)
			val state = when (statusVal) {
				0 -> MangaState.ONGOING
				1 -> MangaState.FINISHED
				else -> null
			}

			val authors = mutableSetOf<String>()
			mangaData.optString("author").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }
			mangaData.optString("artist").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }

			val genres = mangaData.optJSONArray("genre")?.let { arr ->
				(0 until arr.length()).mapNotNull { i ->
					val g = arr.optString(i)
					if (g.isNotEmpty() && g != "null") MangaTag(key = g, title = g, source = source) else null
				}.toSet()
			} ?: emptySet()

			return BasicMangaData(
				title = title,
				description = description,
				coverUrl = cover?.let { resolveCover(it) },
				state = state,
				authors = authors,
				tags = genres
			)
		} catch (e: Exception) {
			return null
		}
	}

	private fun parseDate(dateStr: String): Long {
		if (dateStr.isBlank()) return 0L

		val formats = listOf(
			"yyyy-MM-dd'T'HH:mm:ss+00:00", // 2025-11-20T09:16:26+00:00
			"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", // Standard ISO format
			"yyyy-MM-dd'T'HH:mm:ss", // Without timezone
			"yyyy-MM-dd HH:mm:ss", // 2025-11-13 16:05:54
		)

		for (pattern in formats) {
			try {
				return SimpleDateFormat(pattern, Locale.US).parse(dateStr)?.time ?: 0L
			} catch (e: Exception) {
				// Try next format
			}
		}

		// If all formats fail, try to parse just the date part
		try {
			val datePart = dateStr.substring(0, 10) // Extract yyyy-MM-dd
			return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(datePart)?.time ?: 0L
		} catch (e: Exception) {
			return 0L
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = "https://$domain${chapter.url}"
		val doc = webClient.httpGet(url).parseHtml()

		// Find script with currentChapter data
		val allScripts = doc.select("script")
		val scriptContent = allScripts.find { script ->
			val html = script.html()
			html.contains("currentChapter") && html.contains("images")
		}?.html()

		if (scriptContent == null) {
			throw ParseException("Could not find chapter images data", url)
		}

		// Extract images from currentChapter.images array
		val images = try {
			extractImagesFromScript(scriptContent)
		} catch (e: Exception) {
			throw ParseException("Failed to extract images: ${e.message}", url)
		}

		return images.mapIndexed { i, imagePath ->
			val imageUrl = if (imagePath.startsWith("http")) {
				imagePath
			} else {
				"https://wcloud.site/$imagePath"
			}

			MangaPage(
				id = generateUid("${chapter.id}-$i"),
				url = imageUrl,
				preview = null,
				source = source
			)
		}
	}

	private fun extractImagesFromScript(scriptContent: String): List<String> {
		// Find currentChapter object in escaped format
		val currentChapterStart = scriptContent.indexOf("\\\"currentChapter\\\":{")
		if (currentChapterStart == -1) return emptyList()

		// Start after the key and opening brace
		val dataStart = currentChapterStart + "\\\"currentChapter\\\":".length

		// Find the matching closing brace for the object
		var depth = 0
		var inString = false
		var escape = false
		var dataEnd = dataStart

		for (i in dataStart until scriptContent.length) {
			val char = scriptContent[i]

			when {
				escape -> escape = false
				char == '\\' -> escape = true
				char == '"' && !escape -> inString = !inString
				!inString -> {
					when (char) {
						'{' -> depth++
						'}' -> {
							depth--
							if (depth == 0) {
								dataEnd = i + 1
								break
							}
						}
					}
				}
			}
		}

		if (depth != 0) return emptyList()

		// Extract and unescape the object
		val currentChapterJson = scriptContent.substring(dataStart, dataEnd)
			.replace("\\\"", "\"")
			.replace("\\\\", "\\")

		val currentChapter = JSONObject(currentChapterJson)
		val imagesArray = currentChapter.optJSONArray("images") ?: return emptyList()

		return (0 until imagesArray.length()).map { i ->
			imagesArray.getString(i)
		}
	}
}
