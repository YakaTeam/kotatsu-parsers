package org.koitharu.kotatsu.parsers.site.fr

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("ORTEGASCANS", "Ortega Scans", "fr", ContentType.HENTAI)
internal class OrtegaScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ORTEGASCANS, 24) {

	override val configKeyDomain = ConfigKey.Domain("ortegascans.fr")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
	)

	private data class MangaCache(
		val slug: String,
		val manga: Manga,
		val popularity: Int,
		val createdAt: Long,
		val updatedAt: Long,
		val chaptersCount: Int,
		val searchText: String,
	)

	private data class ChapterRoute(
		val slug: String,
		val numberRaw: String,
	)

	private data class ChapterPayload(
		val chapterId: String?,
		val isPremium: Boolean,
		val imageUrls: List<String>,
	)

	private data class ListRequestKey(
		val order: SortOrder,
		val filter: MangaListFilter,
	)

	private val nextFPushRegex = Regex(
		"""self\.__next_f\.push\(\s*\[\s*1\s*,\s*"((?:\\.|[^"\\])*)"\s*]\s*\)""",
		RegexOption.DOT_MATCHES_ALL,
	)
	private val chapterRouteRegex = Regex("""(?:https?://[^/]+)?/serie/([^/]+)/chapter/([^/?#]+)""")
	private val chapterImageRegex = Regex(
		""""index":\s*(\d+)\s*,"url":"(/api/chapters/[^"\\]+)"""",
	)
	private val currentChapterRegex = Regex(
		""""currentChapter":\{"id":"([^"]+)".*?"isPremium":(true|false)""",
		RegexOption.DOT_MATCHES_ALL,
	)

	private val allMangaCache = suspendLazy {
		fetchAllMangaFromApi()
	}
	private val allMangaWithChaptersCache = suspendLazy {
		allMangaCache.get().filter { it.chaptersCount > 0 }
	}
	private val allMangaBySlugCache = suspendLazy {
		allMangaCache.get().associateBy { it.slug }
	}
	private val allTagsCache = suspendLazy {
		allMangaCache.get().flatMapTo(LinkedHashSet()) { it.manga.tags }
	}
	private val chaptersBySlugCache = object : LinkedHashMap<String, List<MangaChapter>>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaChapter>>?): Boolean {
			return size > chaptersCacheSize
		}
	}
	private val chapterPagesCache = object : LinkedHashMap<String, List<MangaPage>>(128, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaPage>>?): Boolean {
			return size > pagesCacheSize
		}
	}
	private val sortedByOrderCache = HashMap<SortOrder, List<MangaCache>>()
	private val filteredByRequestCache = object : LinkedHashMap<ListRequestKey, List<Manga>>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ListRequestKey, List<Manga>>?): Boolean {
			return size > listRequestCacheSize
		}
	}
	private val apiPageLimit = 200
	private val apiMaxPages = 50
	private val chaptersCacheSize = 300
	private val pagesCacheSize = 300
	private val listRequestCacheSize = 20

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = allTagsCache.get(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(ContentType.HENTAI),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val all = getFilteredAndSortedList(order, filter)
		val from = ((page - 1) * pageSize).coerceAtLeast(0)
		if (from >= all.size) return emptyList()
		val to = (from + pageSize).coerceAtMost(all.size)
		return all.subList(from, to)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = extractSlugFromSeriesUrl(manga.url).ifBlank {
			extractSlugFromSeriesUrl(manga.publicUrl)
		}
		if (slug.isBlank()) return manga

		val cached = allMangaBySlugCache.get()[slug]
		val base = cached?.manga ?: manga
		val doc = webClient.httpGet(base.url.toAbsoluteUrl(domain)).parseHtml()
		val mangaJson = extractMangaObjectFromSeriesDocument(doc, slug)
		val chapters = getChaptersForSlug(slug, mangaJson)

		if (mangaJson == null) {
			return base.copy(chapters = chapters)
		}

		val parsed = parseMangaFromSeriesDetailJson(mangaJson)
		return base.copy(
			title = parsed.title.takeIf { it.isNotBlank() } ?: base.title,
			altTitles = parsed.altTitles.ifEmpty { base.altTitles },
			rating = if (parsed.rating == RATING_UNKNOWN) base.rating else parsed.rating,
			contentRating = parsed.contentRating,
			coverUrl = parsed.coverUrl ?: base.coverUrl,
			tags = parsed.tags.ifEmpty { base.tags },
			state = parsed.state ?: base.state,
			authors = parsed.authors.ifEmpty { base.authors },
			description = parsed.description ?: base.description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		synchronized(chapterPagesCache) {
			chapterPagesCache[chapter.url]?.let { return it }
		}
		val route = parseChapterRoute(chapter.url) ?: return emptyList()
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val payload = parseChapterPayload(doc, route.slug)
		if (payload.isPremium) return emptyList()
		val images = if (payload.imageUrls.isNotEmpty()) {
			payload.imageUrls
		} else {
			fetchImageUrlsByChapterId(route.slug, payload.chapterId)
		}
		if (images.isEmpty()) return emptyList()
		val pages = images.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
		synchronized(chapterPagesCache) {
			chapterPagesCache[chapter.url] = pages
		}
		return pages
	}

	private suspend fun getFilteredAndSortedList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val requestKey = ListRequestKey(order = order, filter = filter)
		synchronized(filteredByRequestCache) {
			filteredByRequestCache[requestKey]?.let { return it }
		}
		val query = filter.query?.trim()?.lowercase(sourceLocale).orEmpty()
		val includeTags = filter.tags
		val excludeTags = filter.tagsExclude
		val states = filter.states
		val filtered = getSortedByOrder(order).asSequence().filter { cached ->
			if (query.isNotEmpty() && !cached.searchText.contains(query)) return@filter false
			if (states.isNotEmpty() && cached.manga.state !in states) return@filter false
			if (filter.types.isNotEmpty() && ContentType.HENTAI !in filter.types) return@filter false
			if (includeTags.isNotEmpty() && !includeTags.all { tag -> cached.manga.tags.contains(tag) }) return@filter false
			if (excludeTags.isNotEmpty() && excludeTags.any { tag -> cached.manga.tags.contains(tag) }) return@filter false
			true
		}
		val result = filtered.map { it.manga }.toList()
		synchronized(filteredByRequestCache) {
			filteredByRequestCache[requestKey] = result
		}
		return result
	}

	private suspend fun getSortedByOrder(order: SortOrder): List<MangaCache> {
		synchronized(sortedByOrderCache) {
			sortedByOrderCache[order]?.let { return it }
		}
		val all = allMangaWithChaptersCache.get()
		val titleSelector: (MangaCache) -> String = { it.manga.title.lowercase(sourceLocale) }
		val sorted = when (order) {
			SortOrder.UPDATED -> all.sortedWith(compareByDescending<MangaCache> { it.updatedAt }.thenBy(titleSelector))
			SortOrder.UPDATED_ASC -> all.sortedWith(compareBy<MangaCache> { it.updatedAt }.thenBy(titleSelector))
			SortOrder.NEWEST -> all.sortedWith(compareByDescending<MangaCache> { it.createdAt }.thenBy(titleSelector))
			SortOrder.NEWEST_ASC -> all.sortedWith(compareBy<MangaCache> { it.createdAt }.thenBy(titleSelector))
			SortOrder.ALPHABETICAL -> all.sortedBy(titleSelector)
			SortOrder.ALPHABETICAL_DESC -> all.sortedByDescending(titleSelector)
			SortOrder.POPULARITY -> all.sortedWith(compareByDescending<MangaCache> { it.popularity }.thenBy(titleSelector))
			SortOrder.POPULARITY_ASC -> all.sortedWith(compareBy<MangaCache> { it.popularity }.thenBy(titleSelector))
			SortOrder.RATING -> all.sortedWith(compareByDescending<MangaCache> { it.manga.rating }.thenBy(titleSelector))
			SortOrder.RATING_ASC -> all.sortedWith(compareBy<MangaCache> { it.manga.rating }.thenBy(titleSelector))
			else -> all.sortedWith(compareByDescending<MangaCache> { it.updatedAt }.thenBy(titleSelector))
		}
		synchronized(sortedByOrderCache) {
			sortedByOrderCache[order] = sorted
		}
		return sorted
	}

	private suspend fun fetchAllMangaFromApi(): List<MangaCache> {
		val bySlug = LinkedHashMap<String, MangaCache>()
		var page = 1
		var guard = 0
		while (guard++ < apiMaxPages) {
			val payload = webClient.httpGet("https://$domain/api/series?page=$page&limit=$apiPageLimit").parseJson()
			val data = payload.optJSONArray("data") ?: break
			for (i in 0 until data.length()) {
				val item = data.optJSONObject(i) ?: continue
				val parsed = parseMangaFromApiJson(item) ?: continue
				bySlug[parsed.slug] = parsed
			}
			if (!payload.optBoolean("hasMore", false) || data.length() == 0) break
			page++
		}
		return bySlug.values.toList()
	}

	private fun parseMangaFromApiJson(json: JSONObject): MangaCache? {
		val slug = json.getStringOrNull("slug")?.trim().orEmpty()
		if (slug.isEmpty()) return null
		val title = json.getStringOrNull("title")?.trim().orEmpty()
		if (title.isEmpty()) return null

		val url = "/serie/$slug"
		val coverUrl = normalizeCoverUrl(slug, json.getStringOrNull("coverImage"))
		val tags = parseCategories(json.optJSONArray("categories"))
		val altTitles = parseCommaValues(json.getStringOrNull("alternativeNames"))
		val state = parseStatus(json.getStringOrNull("status"))
		val rating = parseRating(json)
		val description = json.getStringOrNull("description")?.takeIf { it.isNotBlank() }
		val viewCount = json.optInt("viewCount", 0)
		val favorites = json.optJSONObject("_count")?.optInt("favorites", 0) ?: 0
		val popularity = maxOf(viewCount, favorites)
		val createdAt = parseDate(json.getStringOrNull("createdAt"))
		val updatedAt = parseDate(json.getStringOrNull("updatedAt"))
		val chaptersCount = json.optJSONObject("_count")?.optInt("chapters", 0) ?: 0

		val manga = Manga(
			id = generateUid(url),
			title = title,
			altTitles = altTitles,
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = rating,
			contentRating = ContentRating.ADULT,
			coverUrl = coverUrl,
			tags = tags,
			state = state,
			authors = emptySet(),
			description = description,
			source = source,
		)
		val searchText = buildString {
			append(title.lowercase(sourceLocale))
			if (altTitles.isNotEmpty()) {
				append(' ')
				append(altTitles.joinToString(" ") { it.lowercase(sourceLocale) })
			}
			if (!description.isNullOrBlank()) {
				append(' ')
				append(description.lowercase(sourceLocale))
			}
		}
		return MangaCache(
			slug = slug,
			manga = manga,
			popularity = popularity,
			createdAt = createdAt,
			updatedAt = updatedAt,
			chaptersCount = chaptersCount,
			searchText = searchText,
		)
	}

	private fun parseMangaFromSeriesDetailJson(json: JSONObject): Manga {
		val slug = json.getStringOrNull("slug")?.trim().orEmpty()
		val url = "/serie/$slug"
		val title = json.getStringOrNull("title")?.trim().orEmpty()
		val coverUrl = normalizeCoverUrl(slug, json.getStringOrNull("coverImage"))
		val tags = parseCategories(json.optJSONArray("categories"))
		val altTitles = parseCommaValues(json.getStringOrNull("alternativeNames"))
		val authors = parseCommaValues(json.getStringOrNull("author")) + parseCommaValues(json.getStringOrNull("artist"))
		val description = json.getStringOrNull("description")?.takeIf { it.isNotBlank() }
		val rating = parseRating(json)
		val explicit = json.optBoolean("isExplicit", true)
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = altTitles,
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = rating,
			contentRating = if (explicit) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = coverUrl,
			tags = tags,
			state = parseStatus(json.getStringOrNull("status")),
			authors = authors,
			description = description,
			source = source,
		)
	}

	private fun parseCategories(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		return buildSet {
			for (i in 0 until array.length()) {
				val item = array.optJSONObject(i) ?: continue
				val name = item.getStringOrNull("name")?.trim().orEmpty()
				if (name.isEmpty()) continue
				add(MangaTag(key = name, title = name, source = source))
			}
		}
	}

	private fun parseCommaValues(value: String?): Set<String> {
		val text = value?.trim().orEmpty()
		if (text.isEmpty()) return emptySet()
		return text.split(',')
			.mapNotNull { part -> part.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) } }
			.toSet()
	}

	private fun parseRating(json: JSONObject): Float {
		val value = json.optDouble("rating", 0.0).toFloat()
		if (value <= 0f) return RATING_UNKNOWN
		return value / 5f
	}

	private fun parseStatus(status: String?): MangaState? {
		return when (status?.trim()?.lowercase(sourceLocale)) {
			"en cours", "ongoing" -> MangaState.ONGOING
			"terminé", "finished", "completed" -> MangaState.FINISHED
			"en pause", "hiatus", "paused" -> MangaState.PAUSED
			"annulé", "abandonné", "cancelled", "abandoned" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun normalizeCoverUrl(slug: String, raw: String?): String {
		val value = raw?.trim().orEmpty()
		if (value.isEmpty() || value.equals("null", ignoreCase = true)) {
			return "https://$domain/api/covers/$slug.webp"
		}
		if (value.startsWith("http://") || value.startsWith("https://")) {
			return value
		}
		if (value.startsWith("storage/") || value.startsWith("/storage/")) {
			return "https://$domain/api/covers/$slug.webp"
		}
		return value.toAbsoluteUrl(domain)
	}

	private fun parseDate(raw: String?): Long {
		val date = raw?.trim().orEmpty()
		if (date.isEmpty()) return 0L
		val normalized = date.removePrefix("\$D")
		return runCatching { Instant.parse(normalized).toEpochMilli() }
			.getOrElse { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parseSafe(normalized) }
	}

	private fun getChaptersForSlug(slug: String, mangaJson: JSONObject?): List<MangaChapter> {
		synchronized(chaptersBySlugCache) {
			chaptersBySlugCache[slug]?.let { return it }
		}
		val chapters = when {
			mangaJson != null -> parseChaptersFromMangaJson(slug, mangaJson)
			else -> emptyList()
		}
		synchronized(chaptersBySlugCache) {
			chaptersBySlugCache[slug] = chapters
		}
		return chapters
	}

	private fun parseChaptersFromMangaJson(slug: String, mangaJson: JSONObject): List<MangaChapter> {
		val chapters = mangaJson.optJSONArray("chapters") ?: return emptyList()
		val parsed = buildList(chapters.length()) {
			for (i in 0 until chapters.length()) {
				val item = chapters.optJSONObject(i) ?: continue
				if (item.optBoolean("isPremium", false)) continue
				val numberRaw = item.opt("number")?.toString()?.trim().orEmpty()
				if (numberRaw.isEmpty()) continue
				val numberValue = numberRaw.toFloatOrNull() ?: continue
				val numberLabel = formatChapterNumber(numberValue)
				val chapterUrl = "/serie/$slug/chapter/$numberRaw"
				val titlePart = item.getStringOrNull("title")?.trim().orEmpty()
				val chapterTitle = if (titlePart.isNotEmpty() && !titlePart.equals("null", ignoreCase = true)) {
					"Chapitre $numberLabel - $titlePart"
				} else {
					"Chapitre $numberLabel"
				}
				add(
					MangaChapter(
						id = generateUid("${chapterUrl}#${item.getStringOrNull("id") ?: numberRaw}"),
						title = chapterTitle,
						number = numberValue,
						volume = 0,
						url = chapterUrl,
						uploadDate = parseDate(item.getStringOrNull("createdAt")),
						scanlator = null,
						branch = null,
						source = source,
					),
				)
			}
		}
		return parsed
			.distinctBy { it.url }
			.sortedWith(compareBy<MangaChapter> { it.number.toDouble() }.thenBy { it.uploadDate })
	}

	private fun parseChapterPayload(document: Document, expectedSlug: String): ChapterPayload {
		val payloads = extractNextPayloadStrings(document)
		val indexedUrls = LinkedHashMap<Int, String>()
		var chapterId: String? = null
		var isPremium = false

		for (payload in payloads) {
			currentChapterRegex.find(payload)?.let { match ->
				chapterId = chapterId ?: match.groupValues[1]
				isPremium = isPremium || match.groupValues[2].toBoolean()
			}
				for (match in chapterImageRegex.findAll(payload)) {
				val index = match.groupValues[1].toIntOrNull() ?: continue
				val url = match.groupValues[2]
				if (!url.contains("/api/chapters/$expectedSlug/")) continue
				indexedUrls[index] = url.toAbsoluteUrl(domain)
			}
		}

		return ChapterPayload(
			chapterId = chapterId,
			isPremium = isPremium,
			imageUrls = indexedUrls.toSortedMap().values.toList(),
		)
	}

	private suspend fun fetchImageUrlsByChapterId(slug: String, chapterId: String?): List<String> {
		val id = chapterId?.takeIf { it.isNotBlank() } ?: return emptyList()
		val payload = webClient.httpGet("https://$domain/api/chapters/$slug/$id/images").parseJson()
		val images = payload.optJSONArray("images") ?: return emptyList()
		val indexed = buildList(images.length()) {
			for (i in 0 until images.length()) {
				val item = images.optJSONObject(i) ?: continue
				val index = item.optInt("index", i)
				val rawUrl = item.getStringOrNull("url")?.trim().orEmpty()
				if (rawUrl.isEmpty()) continue
				add(index to rawUrl.toAbsoluteUrl(domain))
			}
		}
		return indexed
			.sortedBy { it.first }
			.map { it.second }
			.distinct()
	}

	private fun parseChapterRoute(chapterUrl: String): ChapterRoute? {
		val match = chapterRouteRegex.find(chapterUrl) ?: return null
		val slug = match.groupValues.getOrNull(1)?.trim().orEmpty()
		val numberRaw = match.groupValues.getOrNull(2)?.trim().orEmpty()
		if (slug.isEmpty() || numberRaw.isEmpty()) return null
		return ChapterRoute(slug, numberRaw)
	}

	private fun formatChapterNumber(number: Float): String {
		return if (number % 1f == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}

	private fun extractSlugFromSeriesUrl(url: String): String {
		return url.substringAfter("/serie/").substringBefore('/').substringBefore('?').substringBefore('#').trim()
	}

	private fun extractMangaObjectFromSeriesDocument(document: Document, slug: String): JSONObject? {
		val key = "\"slug\":\"$slug\""
		for (payload in extractNextPayloadStrings(document)) {
			if (!payload.contains(key) || !payload.contains("\"chapters\":[")) continue
			val objectStart = findJsonObjectStart(payload, payload.indexOf(key))
			if (objectStart == -1) continue
			val objectString = extractJsonObjectString(payload, objectStart) ?: continue
			val root = runCatching { JSONObject(objectString) }.getOrNull() ?: continue
			findMangaObjectBySlug(root, slug)?.let { return it }
		}
		return null
	}

	private fun findMangaObjectBySlug(node: Any?, slug: String): JSONObject? {
		when (node) {
			is JSONObject -> {
				val objectSlug = node.getStringOrNull("slug")
				if (objectSlug != null && objectSlug.equals(slug, ignoreCase = true) && node.has("chapters")) {
					return node
				}
				val keys = node.keys()
				while (keys.hasNext()) {
					findMangaObjectBySlug(node.opt(keys.next()), slug)?.let { return it }
				}
			}
			is JSONArray -> {
				for (i in 0 until node.length()) {
					findMangaObjectBySlug(node.opt(i), slug)?.let { return it }
				}
			}
		}
		return null
	}

	private fun extractNextPayloadStrings(document: Document): List<String> {
		val payloads = ArrayList<String>()
		for (script in document.select("script")) {
			val content = script.data()
			if (!content.contains("self.__next_f.push")) continue
			for (match in nextFPushRegex.findAll(content)) {
				val raw = match.groupValues.getOrNull(1).orEmpty()
				if (raw.isEmpty()) continue
				payloads += raw.replace("\\\\", "\\").replace("\\\"", "\"")
			}
		}
		return payloads
	}

	private fun findJsonObjectStart(data: String, fromIndex: Int): Int {
		if (fromIndex <= 0) return -1
		var depth = 0
		for (i in fromIndex downTo 0) {
			when (data[i]) {
				'}' -> depth++
				'{' -> {
					if (depth == 0) return i
					depth--
				}
			}
		}
		return -1
	}

	private fun extractJsonObjectString(data: String, startIndex: Int): String? {
		if (startIndex !in data.indices || data[startIndex] != '{') return null
		var depth = 0
		var inString = false
		var escaped = false
		for (i in startIndex until data.length) {
			val ch = data[i]
			if (inString) {
				if (escaped) {
					escaped = false
				} else if (ch == '\\') {
					escaped = true
				} else if (ch == '"') {
					inString = false
				}
				continue
			}
			when (ch) {
				'"' -> inString = true
				'{' -> depth++
				'}' -> {
					depth--
					if (depth == 0) {
						return data.substring(startIndex, i + 1)
					}
				}
			}
		}
		return null
	}
}
