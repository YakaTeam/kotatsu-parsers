package org.koitharu.kotatsu.parsers.site.fr

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone
import kotlin.text.toBigDecimalOrNull

@MangaSourceParser("PUNKRECORDZ", "PunkRecordz", "fr")
internal class PunkRecordz(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.PUNKRECORDZ, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("punkrecordz.com")

	override val defaultSortOrder: SortOrder = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
		isAuthorSearchSupported = true,
	)

	private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val allMangaCache = suspendLazy { fetchAllManga() }
	private val mangaBySlugCache = HashMap<String, CatalogManga>()
	private val chaptersBySlugCache = HashMap<String, List<MangaChapter>>()
	private val highestChapterBySlugCache = HashMap<String, Int>()
	private val chapterPayloadByPathCache = HashMap<String, UrlChapterPayload?>()
	private val pagesByCacheKey = HashMap<String, List<MangaPage>>()

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val mangas = getCatalogManga()
		val tags = mangas
			.flatMap { it.tags }
			.sortedBy { it.title.lowercase(Locale.ROOT) }
			.toCollection(LinkedHashSet())
		val states = EnumSet.noneOf(MangaState::class.java).apply {
			mangas.forEach { item ->
				item.state?.let(::add)
			}
		}
		return MangaListFilterOptions(
			availableTags = if (tags.isNotEmpty()) {
				tags
			} else {
				setOf(
					MangaTag(
						key = ALL_TAG_KEY,
						title = "General",
						source = source,
					),
				)
			},
			availableStates = states,
			availableContentTypes = EnumSet.of(ContentType.MANGA),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val filtered = filterManga(getCatalogManga(), filter)
		val sorted = sortManga(filtered, order, filter.query)
		val fromIndex = ((page - 1) * pageSize).coerceAtLeast(0)
		if (fromIndex >= sorted.size) {
			return emptyList()
		}
		val toIndex = (fromIndex + pageSize).coerceAtMost(sorted.size)
		return sorted.subList(fromIndex, toIndex).map { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = extractSlug(manga.url) ?: extractSlug(manga.publicUrl) ?: return manga
		val mangaData = runCatching { fetchMangaBySlugCached(slug) }.getOrNull()
			?: getCatalogManga().firstOrNull { it.slug.equals(slug, ignoreCase = true) }
		val chapters = normalizeChapterOrder(
			runCatching { fetchChaptersCached(slug) }.getOrDefault(emptyList()),
		)
		return manga.copy(
			title = mangaData?.title ?: manga.title,
			coverUrl = mangaData?.coverUrl ?: manga.coverUrl,
			tags = mangaData?.tags ?: manga.tags,
			state = mangaData?.state ?: manga.state,
			description = mangaData?.description ?: manga.description ?: "",
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val cacheKey = chapter.url.substringBefore('?')
		synchronized(pagesByCacheKey) {
			pagesByCacheKey[cacheKey]?.let { return it }
		}
		val pages = runCatching { fetchPagesFromChapterPage(chapter) }.getOrDefault(emptyList())
		if (pages.isNotEmpty()) {
			synchronized(pagesByCacheKey) {
				pagesByCacheKey[cacheKey] = pages
			}
		}
		return pages
	}

	private suspend fun fetchAllManga(): List<CatalogManga> {
		val fromApi = runCatching { fetchAllMangaFromApi() }.getOrDefault(emptyList())
		val fromSite = runCatching { fetchAllMangaFromSiteHome() }.getOrDefault(emptyList())
		return mergeCatalogLists(fromApi, fromSite)
	}

	private suspend fun fetchAllMangaFromApi(): List<CatalogManga> {
		val result = ArrayList<CatalogManga>()
		var skip = 0
		while (true) {
			val data = graphQl(
				query = MANGAS_QUERY,
				variables = JSONObject().apply {
					put("skip", skip)
					put("limit", CATALOG_PAGE_LIMIT)
					put("order", -1)
				},
			)
			val mangasArray = data.optJSONArray("mangas") ?: break
			if (mangasArray.length() == 0) {
				break
			}
			result.addAll(mangasArray.mapJSONNotNull(::parseMangaJson))
			skip += mangasArray.length()
			if (mangasArray.length() < CATALOG_PAGE_LIMIT) {
				break
			}
		}
		return result
	}

	private suspend fun fetchAllMangaFromSiteHome(): List<CatalogManga> {
		for (baseUrl in siteBaseUrls) {
			val raw = runCatching {
				webClient.httpGet(baseUrl.toHttpUrl(), siteRequestHeaders(baseUrl)).parseRaw()
			}.getOrNull() ?: continue
			val mangasArray = extractTopLevelArray(raw = raw, key = "mangas") ?: continue
			val parsed = mangasArray.mapJSONNotNull(::parseMangaJsonFromHome)
			if (parsed.isNotEmpty()) {
				return parsed
			}
		}
		return emptyList()
	}

	private suspend fun fetchMangaBySlug(slug: String): CatalogManga? {
		val data = graphQl(
			query = MANGA_QUERY,
			variables = JSONObject().apply {
				put("slug", slug)
			},
		)
		val manga = data.optJSONObject("manga") ?: return null
		return parseMangaJson(manga)
	}

	private suspend fun fetchMangaBySlugCached(slug: String): CatalogManga? {
		synchronized(mangaBySlugCache) {
			mangaBySlugCache[slug]?.let { return it }
		}
		val fetched = runCatching { fetchMangaBySlug(slug) }.getOrNull()
			?: getCatalogManga().firstOrNull { it.slug.equals(slug, ignoreCase = true) }
			?: return null
		synchronized(mangaBySlugCache) {
			mangaBySlugCache[slug] = fetched
		}
		return fetched
	}

	private suspend fun fetchChapters(slug: String): List<MangaChapter> {
		val highestContiguousChapter = findHighestChapterNumberByUrl(slug)
		if (highestContiguousChapter <= 0) {
			return emptyList()
		}

		// Build the contiguous base first, then follow nextChapter links to bridge site gaps.
		val chapterNumberByLabel = LinkedHashMap<String, Float>(highestContiguousChapter + 8)
		for (number in 1..highestContiguousChapter) {
			chapterNumberByLabel[number.toString()] = number.toFloat()
		}
		val forwardChapters = collectForwardChapterNumbersFromNextLinks(
			slug = slug,
			startChapterLabel = highestContiguousChapter.toString(),
		)
		for ((number, label) in forwardChapters) {
			chapterNumberByLabel.putIfAbsent(label, number)
		}

		return chapterNumberByLabel.entries
			.sortedByDescending { it.value }
			.map { (chapterNumberLabel, chapterNumber) ->
				MangaChapter(
					id = generateUid("$slug#url#$chapterNumberLabel"),
					title = "Chapitre $chapterNumberLabel",
					number = chapterNumber,
					volume = 0,
					url = "/mangas/$slug/$chapterNumberLabel",
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
	}

	private suspend fun collectForwardChapterNumbersFromNextLinks(
		slug: String,
		startChapterLabel: String,
	): List<Pair<Float, String>> {
		val discovered = ArrayList<Pair<Float, String>>()
		val visited = HashSet<String>()
		var current = fetchChapterPayloadByUrl(slug, startChapterLabel) ?: return emptyList()
		var steps = 0
		while (steps < NEXT_CHAPTER_CHAIN_MAX_STEPS) {
			steps++
			val nextLabel = current.nextChapterNumberLabel ?: break
			if (!visited.add(nextLabel)) break
			val nextPayload = fetchChapterPayloadByUrl(slug, nextLabel) ?: break
			discovered.add(nextPayload.number to nextPayload.numberLabel)
			current = nextPayload
		}
		return discovered
	}

	private suspend fun findHighestChapterNumberByUrl(slug: String): Int {
		synchronized(highestChapterBySlugCache) {
			highestChapterBySlugCache[slug]?.let { return it }
		}

		if (!chapterExistsByUrl(slug, 1)) {
			return 0
		}

		var lowerBound = 1
		var upperBound = 1
		while (upperBound < URL_CHAPTER_PROBE_UPPER_BOUND && chapterExistsByUrl(slug, upperBound)) {
			lowerBound = upperBound
			val next = (upperBound * 2).coerceAtMost(URL_CHAPTER_PROBE_UPPER_BOUND)
			if (next == upperBound) {
				break
			}
			upperBound = next
		}

		var best = lowerBound
		var left = (lowerBound + 1).coerceAtMost(upperBound)
		var right = upperBound
		while (left <= right) {
			val middle = left + ((right - left) / 2)
			if (chapterExistsByUrl(slug, middle)) {
				best = middle
				left = middle + 1
			} else {
				right = middle - 1
			}
		}

		synchronized(highestChapterBySlugCache) {
			highestChapterBySlugCache[slug] = best
		}
		return best
	}

	private suspend fun chapterExistsByUrl(slug: String, chapterNumber: Int): Boolean {
		return fetchChapterPayloadByUrl(slug, chapterNumber.toString()) != null
	}

	private suspend fun fetchChapterPayloadByUrl(slug: String, chapterNumberLabel: String): UrlChapterPayload? {
		val cacheKey = "$slug#$chapterNumberLabel"
		synchronized(chapterPayloadByPathCache) {
			if (chapterPayloadByPathCache.containsKey(cacheKey)) {
				return chapterPayloadByPathCache[cacheKey]
			}
		}
		val absoluteUrl = "$siteBaseUrl/mangas/$slug/$chapterNumberLabel"
		val payload = runCatching {
			val raw = webClient.httpGet(
				absoluteUrl.toHttpUrl(),
				siteRequestHeaders(siteBaseUrl),
			).parseRaw()
			parseUrlChapterPayload(raw)
		}.getOrNull()
		synchronized(chapterPayloadByPathCache) {
			chapterPayloadByPathCache[cacheKey] = payload
		}
		return payload
	}

	private fun parseUrlChapterPayload(raw: String): UrlChapterPayload? {
		val chapter = extractTopLevelObject(raw = raw, key = "chapter") ?: return null
		val chapterId = chapter.getStringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
		val chapterNumber = parseChapterNumber(chapter.opt("number")) ?: return null
		val pages = chapter.optJSONArray("pages") ?: JSONArray()
		val nextChapterNumberLabel = chapter.optJSONObject("nextChapter")
			?.opt("number")
			?.let { parseChapterNumber(it)?.second }
		return UrlChapterPayload(
			id = chapterId,
			number = chapterNumber.first,
			numberLabel = chapterNumber.second,
			pages = pages,
			nextChapterNumberLabel = nextChapterNumberLabel,
		)
	}

	private suspend fun fetchChaptersCached(slug: String): List<MangaChapter> {
		synchronized(chaptersBySlugCache) {
			chaptersBySlugCache[slug]?.let { return it }
		}
		val fetched = fetchChapters(slug)
		if (fetched.isNotEmpty()) {
			synchronized(chaptersBySlugCache) {
				chaptersBySlugCache[slug] = fetched
			}
		}
		return fetched
	}

	private suspend fun fetchPagesFromChapterPage(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(normalizedDomain).substringBefore('?')
		val chapterHttpUrl = chapterUrl.toHttpUrlOrNull() ?: return emptyList()
		val pathSegments = chapterHttpUrl.pathSegments
		if (pathSegments.size < 3 || pathSegments[pathSegments.size - 3] != "mangas") {
			return emptyList()
		}
		val slug = pathSegments[pathSegments.size - 2]
		val chapterNumberLabel = pathSegments[pathSegments.size - 1]
		val chapterPayload = fetchChapterPayloadByUrl(slug, chapterNumberLabel) ?: return emptyList()
		val pagesArray = chapterPayload.pages
		return pagesArray.mapJSONNotNull { pageJson ->
			val image = pageJson.getStringOrNull("colored") ?: pageJson.getStringOrNull("original")
			val pageUrl = buildImageUrl(image) ?: return@mapJSONNotNull null
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun graphQl(query: String, variables: JSONObject = JSONObject()): JSONObject {
		val body = JSONObject().apply {
			put("query", query)
			put("variables", variables)
		}
		val endpoints = graphQlEndpoints
		var lastFailure: Throwable? = null
		for (endpoint in endpoints) {
			repeat(GRAPHQL_RETRY_COUNT) {
				val response = runCatching {
					webClient.httpPost(
						endpoint.toHttpUrl(),
						body,
						graphQlHeaders(endpoint),
					).parseJson()
				}.getOrElse { throwable ->
					lastFailure = throwable
					return@repeat
				}
				val errors = response.optJSONArray("errors")
				val data = response.optJSONObject("data")
				if (errors != null && errors.length() > 0 && data == null) {
					val message = errors.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
					lastFailure = IllegalStateException("PunkRecordz GraphQL error: ${message ?: "Unknown error"}")
					return@repeat
				}
				return data ?: JSONObject()
			}
		}
		throw (lastFailure ?: IllegalStateException("PunkRecordz GraphQL request failed"))
	}

	private fun parseMangaJson(mangaJson: JSONObject): CatalogManga? {
		val slug = mangaJson.getStringOrNull("slug")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val title = mangaJson.getStringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val tags = parseTags(mangaJson.getStringOrNull("keywords"))
		val story = mangaJson.getStringOrNull("story")?.trim()?.takeIf { it.isNotEmpty() }
		val status = mangaJson.getStringOrNull("status")?.trim()?.takeIf { it.isNotEmpty() }
		return CatalogManga(
			slug = slug,
			title = title,
			coverUrl = buildImageUrl(mangaJson.getStringOrNull("thumb")),
			tags = tags,
			tagKeys = tags.mapTo(HashSet(tags.size)) { it.key.lowercase(Locale.ROOT) },
			state = parseState(mangaJson.optInt("statusProgression", -1)),
			description = story ?: status,
			keywords = mangaJson.getStringOrNull("keywords"),
			status = status,
			updateTime = parseDate(mangaJson.getStringOrNull("updateTime")),
			insertTime = parseDate(mangaJson.getStringOrNull("insertTime")),
		)
	}

	private fun parseMangaJsonFromHome(mangaJson: JSONObject): CatalogManga? {
		val slug = mangaJson.getStringOrNull("slug")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val title = mangaJson.getStringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val status = mangaJson.getStringOrNull("status")?.trim()?.takeIf { it.isNotEmpty() }
		return CatalogManga(
			slug = slug,
			title = title,
			coverUrl = buildImageUrl(mangaJson.getStringOrNull("thumb")),
			tags = emptySet(),
			tagKeys = emptySet(),
			state = parseState(mangaJson.optInt("statusProgression", -1)),
			description = status,
			keywords = null,
			status = status,
			updateTime = 0L,
			insertTime = 0L,
		)
	}

	private fun extractTopLevelArray(raw: String, key: String): JSONArray? {
		extractTopLevelArrayImpl(raw, key)?.let { return it }
		val decoded = decodeJsEscapes(raw)
		if (decoded != raw) {
			extractTopLevelArrayImpl(decoded, key)?.let { return it }
		}
		return null
	}

	private fun extractTopLevelObject(raw: String, key: String): JSONObject? {
		extractTopLevelObjectImpl(raw, key)?.let { return it }
		val decoded = decodeJsEscapes(raw)
		if (decoded != raw) {
			extractTopLevelObjectImpl(decoded, key)?.let { return it }
		}
		return null
	}

	private fun extractTopLevelObjectImpl(raw: String, key: String): JSONObject? {
		val keyToken = "\"$key\":"
		val keyIndex = raw.lastIndexOf(keyToken)
		if (keyIndex == -1) {
			return null
		}
		val start = raw.indexOf('{', keyIndex + keyToken.length)
		if (start == -1) {
			return null
		}
		var depth = 0
		var inString = false
		var escaped = false
		var end = -1
		for (index in start until raw.length) {
			val ch = raw[index]
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
						end = index
						break
					}
				}
			}
		}
		if (end == -1) {
			return null
		}
		return runCatching {
			JSONObject(raw.substring(start, end + 1))
		}.getOrNull()
	}
	private fun extractTopLevelArrayImpl(raw: String, key: String): JSONArray? {
		val keyToken = "\"$key\":"
		val keyIndex = raw.indexOf(keyToken)
		if (keyIndex == -1) {
			return null
		}
		val start = raw.indexOf('[', keyIndex + keyToken.length)
		if (start == -1) {
			return null
		}
		var depth = 0
		var inString = false
		var escaped = false
		var end = -1
		for (index in start until raw.length) {
			val ch = raw[index]
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
				'[' -> depth++
				']' -> {
					depth--
					if (depth == 0) {
						end = index
						break
					}
				}
			}
		}
		if (end == -1) {
			return null
		}
		return runCatching {
			JSONArray(raw.substring(start, end + 1))
		}.getOrNull()
	}

	private fun decodeJsEscapes(value: String): String {
		if ('\\' !in value) {
			return value
		}
		val out = StringBuilder(value.length)
		var index = 0
		while (index < value.length) {
			val current = value[index]
			if (current != '\\' || index + 1 >= value.length) {
				out.append(current)
				index++
				continue
			}
			when (val escaped = value[index + 1]) {
				'\\' -> out.append('\\')
				'"' -> out.append('"')
				'\'' -> out.append('\'')
				'/' -> out.append('/')
				'b' -> out.append('\b')
				'f' -> out.append('\u000C')
				'n' -> out.append('\n')
				'r' -> out.append('\r')
				't' -> out.append('\t')
				'u' -> {
					if (index + 5 < value.length) {
						val hex = value.substring(index + 2, index + 6)
						val codePoint = hex.toIntOrNull(16)
						if (codePoint != null) {
							out.append(codePoint.toChar())
							index += 6
							continue
						}
					}
					out.append(escaped)
				}
				else -> out.append(escaped)
			}
			index += 2
		}
		return out.toString()
	}

	private fun parseTags(rawKeywords: String?): Set<MangaTag> {
		if (rawKeywords.isNullOrBlank()) {
			return emptySet()
		}
		return rawKeywords
			.split(',')
			.mapNotNull { raw ->
				val title = raw.trim().replace(Regex("\\s+"), " ")
				if (title.isEmpty()) {
					return@mapNotNull null
				}
				val key = normalizeTagKey(title)
				if (key.isEmpty()) {
					return@mapNotNull null
				}
				MangaTag(
					key = key,
					title = title.replaceFirstChar {
						if (it.isLowerCase()) {
							it.titlecase(sourceLocale)
						} else {
							it.toString()
						}
					},
					source = source,
				)
			}
			.toSet()
	}

	private fun filterManga(mangas: List<CatalogManga>, filter: MangaListFilter): List<CatalogManga> {
		if (mangas.isEmpty()) {
			return mangas
		}
		val query = filter.query?.trim()?.lowercase(sourceLocale).orEmpty()
		val author = filter.author?.trim()?.lowercase(sourceLocale).orEmpty()
		val includeTags = filter.tags.mapTo(HashSet(filter.tags.size)) { it.key.lowercase(Locale.ROOT) }
		val excludeTags = filter.tagsExclude.mapTo(HashSet(filter.tagsExclude.size)) { it.key.lowercase(Locale.ROOT) }

		return mangas.filter { item ->
			if (query.isNotEmpty()) {
				val haystack = buildString {
					append(item.title)
					append('\n')
					append(item.slug)
					item.keywords?.let {
						append('\n')
						append(it)
					}
					item.status?.let {
						append('\n')
						append(it)
					}
				}.lowercase(sourceLocale)
				if (!haystack.contains(query)) {
					return@filter false
				}
			}

			if (author.isNotEmpty()) {
				val haystack = listOfNotNull(item.keywords, item.status).joinToString("\n").lowercase(sourceLocale)
				if (!haystack.contains(author)) {
					return@filter false
				}
			}

			if (filter.states.isNotEmpty() && item.state !in filter.states) {
				return@filter false
			}

			if (filter.types.isNotEmpty() && ContentType.MANGA !in filter.types) {
				return@filter false
			}

			if (includeTags.isNotEmpty() && ALL_TAG_KEY !in includeTags && item.tagKeys.none { it in includeTags }) {
				return@filter false
			}

			if (excludeTags.isNotEmpty() && ALL_TAG_KEY !in excludeTags && item.tagKeys.any { it in excludeTags }) {
				return@filter false
			}

			true
		}
	}

	private fun sortManga(mangas: List<CatalogManga>, order: SortOrder, query: String?): List<CatalogManga> {
		if (mangas.size < 2) {
			return mangas
		}
		return when (order) {
			SortOrder.UPDATED -> mangas.sortedByDescending { it.updateTime }
			SortOrder.UPDATED_ASC -> mangas.sortedBy { it.updateTime }
			SortOrder.NEWEST -> mangas.sortedByDescending { it.insertTime }
			SortOrder.NEWEST_ASC -> mangas.sortedBy { it.insertTime }
			SortOrder.ALPHABETICAL -> mangas.sortedBy { it.title.lowercase(sourceLocale) }
			SortOrder.ALPHABETICAL_DESC -> mangas.sortedByDescending { it.title.lowercase(sourceLocale) }
			SortOrder.RELEVANCE -> {
				val normalizedQuery = query?.trim()?.lowercase(sourceLocale).orEmpty()
				if (normalizedQuery.isEmpty()) {
					mangas.sortedByDescending { it.updateTime }
				} else {
					mangas.sortedWith(
						compareBy<CatalogManga> { relevanceScore(it, normalizedQuery) }
							.thenBy { it.title.length }
							.thenBy { it.title.lowercase(sourceLocale) },
					)
				}
			}
			else -> mangas
		}
	}

	private fun relevanceScore(manga: CatalogManga, query: String): Int {
		val title = manga.title.lowercase(sourceLocale)
		if (title == query) return 0
		if (title.startsWith(query)) return 1
		if (title.contains(query)) return 2
		return 3
	}

	private fun normalizeChapterOrder(chapters: List<MangaChapter>): List<MangaChapter> {
		if (chapters.size < 2) return chapters
		return chapters
			.distinctBy { it.url }
			.sortedWith(
				compareBy<MangaChapter> { it.number.toDouble() }
					.thenBy { it.uploadDate }
					.thenBy { it.title },
			)
	}

	private fun parseState(statusProgression: Int): MangaState? = when (statusProgression) {
		4 -> MangaState.FINISHED
		1, 2, 3 -> MangaState.ONGOING
		else -> null
	}

	private fun buildImageUrl(path: String?): String? {
		val value = path?.trim().orEmpty()
		if (value.isEmpty() || value == "null") {
			return null
		}
		if (value.startsWith("http://") || value.startsWith("https://")) {
			return value
		}
		val clean = value.removePrefix("/")
		return when {
			clean.startsWith("images/") -> "$apiBaseUrl/$clean"
			clean.startsWith("webp/") -> "$apiBaseUrl/images/$clean"
			else -> "$apiBaseUrl/images/webp/$clean.webp"
		}
	}

	private fun extractSlug(url: String): String? {
		val path = url.substringBefore('?').substringBefore('#')
		val after = path.substringAfter("/mangas/", "")
		if (after.isEmpty()) {
			return null
		}
		return after.substringBefore('/').ifBlank { null }
	}

	private fun extractChapterNumber(url: String): Double? {
		val path = url.substringBefore('?').substringBefore('#')
		val raw = path.substringAfterLast('/', "")
		if (raw.isEmpty()) {
			return null
		}
		return raw.toDoubleOrNull()
	}

	private fun parseChapterNumber(rawValue: Any?): Pair<Float, String>? {
		val raw = rawValue?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
		val decimal = raw.toBigDecimalOrNull() ?: return null
		val normalized = decimal.stripTrailingZeros().toPlainString()
		return decimal.toFloat() to normalized
	}

	private fun normalizeTagKey(tag: String): String {
		val normalized = tag
			.lowercase(Locale.ROOT)
			.replace(Regex("[^\\p{L}\\p{N}]+"), "-")
			.trim('-')
		return normalized
	}

	private fun parseDate(date: String?): Long {
		if (date.isNullOrBlank()) {
			return 0L
		}
		return isoDateFormat.parseSafe(date)
	}

	private val normalizedDomain: String
		get() {
			val rawDomain = domain.trim()
			val normalized = rawDomain
				.substringAfter("://", rawDomain)
				.substringBefore('/')
				.substringBefore(':')
				.removePrefix("www.")
				.removePrefix("api.")
				.ifBlank { "punkrecordz.com" }
			val sanitized = normalized
				.lowercase(Locale.ROOT)
				.replace(Regex("[^a-z0-9.-]"), "")
				.trim('.')
				.ifBlank { "punkrecordz.com" }
			return if (sanitized.contains("punkrecordz", ignoreCase = true)) {
				sanitized
			} else {
				"punkrecordz.com"
			}
		}

	private val siteBaseUrl: String
		get() = "https://$normalizedDomain"

	private val siteBaseUrls: List<String>
		get() = listOf(
			siteBaseUrl,
			"https://punkrecordz.com",
			"https://www.punkrecordz.com",
		).distinct()

	private val apiBaseUrl: String
		get() = "https://api.$normalizedDomain"

	private val apiUrl: String
		get() = "$apiBaseUrl/graphql"

	private val graphQlEndpoints: List<String>
		get() = listOf(
			"https://api.punkrecordz.com/graphql",
			apiUrl,
		).distinct()

	private suspend fun getCatalogManga(): List<CatalogManga> {
		return runCatching { allMangaCache.get() }.getOrElse {
			val fromApi = runCatching { fetchAllMangaFromApi() }.getOrDefault(emptyList())
			val fromSite = runCatching { fetchAllMangaFromSiteHome() }.getOrDefault(emptyList())
			mergeCatalogLists(fromApi, fromSite)
		}
	}

	private fun mergeCatalogLists(primary: List<CatalogManga>, secondary: List<CatalogManga>): List<CatalogManga> {
		if (primary.isEmpty()) return secondary.distinctBy { it.slug.lowercase(Locale.ROOT) }
		if (secondary.isEmpty()) return primary.distinctBy { it.slug.lowercase(Locale.ROOT) }
		val merged = LinkedHashMap<String, CatalogManga>(primary.size + secondary.size)
		for (item in primary) {
			merged.putIfAbsent(item.slug.lowercase(Locale.ROOT), item)
		}
		for (item in secondary) {
			merged.putIfAbsent(item.slug.lowercase(Locale.ROOT), item)
		}
		return merged.values.toList()
	}

	private fun siteRequestHeaders(baseUrl: String): Headers {
		val origin = baseUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://punkrecordz.com"
		return Headers.headersOf(
			"Origin", origin,
			"Referer", "$origin/",
			"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
			"X-Requested-With", "XMLHttpRequest",
		)
	}

	private fun graphQlHeaders(endpoint: String): Headers {
		val endpointHost = endpoint.toHttpUrlOrNull()?.host
		val originHost = endpointHost
			?.removePrefix("api.")
			?.removePrefix("www.")
			?.takeIf { it.contains("punkrecordz", ignoreCase = true) }
			?: "punkrecordz.com"
		val origin = "https://$originHost"
		return Headers.headersOf(
			"Origin", origin,
			"Referer", "$origin/",
			"Accept", "application/json",
			"X-Requested-With", "XMLHttpRequest",
		)
	}

	private fun CatalogManga.toManga(): Manga = Manga(
		id = generateUid("/mangas/$slug"),
		title = title,
		altTitles = emptySet(),
		url = "/mangas/$slug",
		publicUrl = "/mangas/$slug".toAbsoluteUrl(normalizedDomain),
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = coverUrl,
		tags = tags,
		state = state,
		authors = emptySet(),
		description = description,
		source = source,
	)

	private data class UrlChapterPayload(
		val id: String,
		val number: Float,
		val numberLabel: String,
		val pages: JSONArray,
		val nextChapterNumberLabel: String?,
	)

	private data class CatalogManga(
		val slug: String,
		val title: String,
		val coverUrl: String?,
		val tags: Set<MangaTag>,
		val tagKeys: Set<String>,
		val state: MangaState?,
		val description: String?,
		val keywords: String?,
		val status: String?,
		val updateTime: Long,
		val insertTime: Long,
	)

	private companion object {
		const val CATALOG_PAGE_LIMIT = 200
		const val URL_CHAPTER_PROBE_UPPER_BOUND = 5000
		const val NEXT_CHAPTER_CHAIN_MAX_STEPS = 4000
		const val ALL_TAG_KEY = "__all__"
		const val GRAPHQL_RETRY_COUNT = 2

		val MANGAS_QUERY: String = """
			query Mangas(${'$'}skip: Int!, ${'$'}limit: Int!, ${'$'}order: Float!) {
			  mangas(
			    skip: ${'$'}skip
			    limit: ${'$'}limit
			    where: { published: true, deleted: false }
			    order: [{ field: "updateTime", order: ${'$'}order }]
			  ) {
			    id
			    slug
			    name
			    thumb
			    keywords
			    story
			    status
			    statusProgression
			    updateTime
			    insertTime
			  }
			}
		""".trimIndent()

		val MANGA_QUERY: String = """
			query MangaBySlug(${'$'}slug: String!) {
			  manga(where: { slug: ${'$'}slug, published: true, deleted: false }) {
			    id
			    slug
			    name
			    thumb
			    keywords
			    story
			    status
			    statusProgression
			    updateTime
			    insertTime
			  }
			}
		""".trimIndent()

		val CHAPTERS_QUERY: String = """
			query Chapters(${'$'}slug: String!, ${'$'}limit: Int!, ${'$'}skip: Int!, ${'$'}order: Float!) {
			  chapters(
			    limit: ${'$'}limit
			    skip: ${'$'}skip
			    where: {
			      deleted: false
			      published: true
			      manga: { slug: ${'$'}slug, published: true, deleted: false }
			    }
			    order: [{ field: "number", order: ${'$'}order }]
			  ) {
			    id
			    number
			    updateTime
			    insertTime
			  }
			}
		""".trimIndent()

	}
}
