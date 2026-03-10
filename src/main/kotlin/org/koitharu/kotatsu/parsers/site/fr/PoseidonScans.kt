package org.koitharu.kotatsu.parsers.site.fr

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
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("POSEIDONSCANS", "Poseidon Scans", "fr")
internal class PoseidonScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.POSEIDONSCANS, 24) {

	override val configKeyDomain = ConfigKey.Domain("poseidon-scans.co")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
		isAuthorSearchSupported = true,
		isTagsExclusionSupported = true,
	)

	private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.FRENCH).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val nextFPushRegex = Regex(
		"""self\.__next_f\.push\(\s*\[\s*1\s*,\s*"((?:\\.|[^"\\])*)"\s*]\s*\)""",
		RegexOption.DOT_MATCHES_ALL,
	)
	private val chapterRouteRegex = Regex("""(?:https?://[^/]+)?/serie/([^/]+)/chapter/([^/?#]+)""")
	private val nextJsPayloadAnchorPatterns = arrayOf(
		""""initialData":{""",
		""""manga":{""",
		""""mangas":[""",
		""""series":[""",
		""""chapter":{""",
		""""images":[""",
		""""pages":[""",
	)


	// Helper data classes for sorting and chapter recovery.
	private data class MangaCache(
		val manga: Manga,
		val viewCount: Int,
		val type: ContentType,
		val latestChapterDate: Long,
		val chaptersCount: Int,
	)
	private data class ChapterSeed(
		val raw: String,
		val value: Double,
	)
	private data class ApiChapterEntry(
		val id: String?,
		val numberRaw: String,
		val numberValue: Double,
		val title: String?,
		val createdAt: String?,
	)
	private data class ChapterRoute(
		val slug: String,
		val chapterNumberRaw: String,
	)

	// Cache all manga for local search and sorting
	private val allMangaCache = suspendLazy {
		runCatching {
			fetchAllMangaFromApi()
		}.getOrElse {
			// Fallback if API route changes: parse homepage Next.js payload.
			val doc = webClient.httpGet("https://$domain").parseHtml()
			extractFullMangaListFromDocument(doc)
		}
	}

	// Cache all available tags for filtering
	private val allTagsCache = suspendLazy {
		allMangaCache.get()
			.flatMap { it.manga.tags }
			.toSet()
	}

	// Cache fallback chapters from last updates API (used when series page is blocked by Cloudflare)
	private val recentChaptersCache = suspendLazy {
		fetchRecentChaptersBySlugFromApi()
	}

	// Popularity endpoint exposes stable metrics (viewCount/favorites) unlike /api/manga/all.
	private val popularityBySlugCache = suspendLazy {
		fetchPopularityBySlugFromApi()
	}

	// Latest endpoint gives the current chapter number per slug (seed for full chapter list API).
	private val latestChapterSeedBySlugCache = suspendLazy {
		fetchLatestChapterSeedBySlugFromApi()
	}
	private val mangaCacheBySlug = suspendLazy {
		allMangaCache.get()
			.mapNotNull { cached ->
				val slug = extractSlugFromSeriesUrl(cached.manga.url)
				if (slug.isBlank()) null else slug to cached
			}.toMap()
	}
	private val apiChaptersBySlugCache = HashMap<String, List<MangaChapter>>()
	private val chapterPagesCache = HashMap<String, List<MangaPage>>()

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = allTagsCache.get(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val all = getFilteredAndSortedList(order, filter)
		val fromIndex = ((page - 1) * pageSize).coerceAtLeast(0)
		if (fromIndex >= all.size) {
			return emptyList()
		}
		val toIndex = (fromIndex + pageSize).coerceAtMost(all.size)
		return all.subList(fromIndex, toIndex)
	}

	private suspend fun getFilteredAndSortedList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		var mangaCacheList = allMangaCache.get()

		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(sourceLocale)
		if (query != null) {
			mangaCacheList = mangaCacheList.filter { cachedManga ->
				cachedManga.manga.title.lowercase(sourceLocale)
					.contains(query) || cachedManga.manga.altTitles.any { it.lowercase(sourceLocale).contains(query) }
			}
		}

		val author = filter.author?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(sourceLocale)
		if (author != null) {
			mangaCacheList = mangaCacheList.filter { cachedManga ->
				cachedManga.manga.authors.any {
					it.lowercase(sourceLocale).contains(author)
				}
			}
		}

		if (filter.states.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { filter.states.contains(it.manga.state) }
		}

		if (filter.types.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { filter.types.contains(it.type) }
		}

		// Tag inclusion filter
		if (filter.tags.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { cachedManga ->
				cachedManga.manga.tags.any { tag -> filter.tags.contains(tag) }
			}
		}

		// Tag exclusion filter
		if (filter.tagsExclude.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { cachedManga ->
				!cachedManga.manga.tags.any { tag -> filter.tagsExclude.contains(tag) }
			}
		}

		// Hide entries without chapters to avoid unusable detail pages.
		mangaCacheList = mangaCacheList.filter { it.chaptersCount > 0 }

		// Sorting
		val sortedCachedMangaList = when (order) {
			SortOrder.UPDATED -> mangaCacheList.sortedByDescending { it.latestChapterDate }
			SortOrder.UPDATED_ASC -> mangaCacheList.sortedBy { it.latestChapterDate }
			SortOrder.ALPHABETICAL -> mangaCacheList.sortedBy { it.manga.title.lowercase(sourceLocale) }
			SortOrder.ALPHABETICAL_DESC -> mangaCacheList.sortedByDescending { it.manga.title.lowercase(sourceLocale) }
			SortOrder.POPULARITY -> mangaCacheList.sortedByDescending { it.viewCount }
			SortOrder.POPULARITY_ASC -> mangaCacheList.sortedBy { it.viewCount }
			SortOrder.RATING -> mangaCacheList.sortedByDescending { it.manga.rating }
			SortOrder.RATING_ASC -> mangaCacheList.sortedBy { it.manga.rating }
			else -> mangaCacheList
		}

		return sortedCachedMangaList.map { it.manga }
	}

	private suspend fun fetchAllMangaFromApi(): List<MangaCache> {
		val payload = webClient.httpGet("https://$domain/api/manga/all").parseJson()
		val mangasArray = payload.optJSONArray("data") ?: return emptyList()
		val popularityBySlug = runCatching { popularityBySlugCache.get() }.getOrDefault(emptyMap())
		return mangasArray.mapJSON { mangaJson ->
			val slug = mangaJson.getStringOrNull("slug").orEmpty()
			parseMangaDetailsFromJson(mangaJson, popularityBySlug[slug])
		}
	}

	private suspend fun fetchPopularityBySlugFromApi(): Map<String, Int> {
		val payload = webClient.httpGet("https://$domain/api/manga/popular?limit=500").parseJson()
		val mangasArray = payload.optJSONArray("data") ?: return emptyMap()
		return mangasArray.mapJSONNotNull { mangaJson ->
			val slug = mangaJson.getStringOrNull("slug")?.takeIf { it.isNotBlank() } ?: return@mapJSONNotNull null
			val viewCount = mangaJson.optInt("viewCount", 0)
			val favorites = mangaJson.optJSONObject("_count")?.optInt("favorites", 0) ?: 0
			slug to maxOf(viewCount, favorites)
		}.toMap()
	}

	private suspend fun fetchLatestChapterSeedBySlugFromApi(): Map<String, ChapterSeed> {
		val payload = webClient.httpGet("https://$domain/api/manga/latest?limit=500&page=1").parseJson()
		val mangasArray = payload.optJSONArray("data") ?: return emptyMap()
		return mangasArray.mapJSONNotNull { mangaJson ->
			val slug = mangaJson.getStringOrNull("slug")?.takeIf { it.isNotBlank() } ?: return@mapJSONNotNull null
			val chaptersArray = mangaJson.optJSONArray("chapters") ?: return@mapJSONNotNull null
			val latestChapter =
				chaptersArray.mapJSONNotNull { chapterJson ->
					parseChapterSeed(chapterJson.opt("number"))
				}.maxByOrNull { it.value } ?: return@mapJSONNotNull null
			slug to latestChapter
		}.toMap()
	}

	private suspend fun fetchRecentChaptersBySlugFromApi(): Map<String, List<MangaChapter>> {
		val payload = webClient.httpGet("https://$domain/api/manga/lastchapters?limit=500&page=1").parseJson()
		val mangasArray = payload.optJSONArray("data") ?: return emptyMap()
		return mangasArray.mapJSONNotNull { mangaJson ->
			val slug = mangaJson.getStringOrNull("slug")?.takeIf { it.isNotBlank() } ?: return@mapJSONNotNull null
			val chaptersArray = mangaJson.optJSONArray("chapters") ?: return@mapJSONNotNull null
			val chapters = chaptersArray.mapJSONNotNull { chapterJson ->
				if (chapterJson.optBoolean("isPremium", false)) {
					return@mapJSONNotNull null
				}
				val chapterNumber = chapterJson.optDouble("number", 0.0).toFloat()
				val chapterNumberRaw = chapterJson.opt("number")?.toString()?.trim()
				val chapterNumberForUrl =
					chapterNumberRaw?.takeIf { it.isNotEmpty() && it != "null" } ?: formatChapterNumber(chapterNumber)
				val chapterUrl = "/serie/$slug/chapter/$chapterNumberForUrl"
				val chapterTitleRaw = chapterJson.getStringOrNull("title")?.takeIf { it.isNotBlank() && it != "null" }
				val chapterTitle = if (chapterTitleRaw != null) {
					"Chapitre ${formatChapterNumber(chapterNumber)} - $chapterTitleRaw"
				} else {
					"Chapitre ${formatChapterNumber(chapterNumber)}"
				}
				MangaChapter(
					id = generateUid("${chapterUrl}#${chapterJson.getStringOrNull("id") ?: chapterNumberForUrl}"),
					title = chapterTitle,
					number = chapterNumber,
					volume = 0,
					url = chapterUrl,
					uploadDate = 0L,
					source = source,
					scanlator = null,
					branch = null,
				)
			}.reversed()
			if (chapters.isEmpty()) {
				null
			} else {
				slug to chapters
			}
		}.toMap()
	}

	private suspend fun fetchAllChaptersBySlugFromApi(slug: String): List<MangaChapter> {
		if (slug.isBlank()) return emptyList()
		val chapterSeedCandidates = buildChapterSeedCandidates(slug)
		for (seed in chapterSeedCandidates) {
			val chapters = runCatching {
				fetchChapterListBySeed(slug, seed)
			}.getOrNull().orEmpty()
			if (chapters.isNotEmpty()) {
				return chapters
			}
		}
		return emptyList()
	}

	private suspend fun buildChapterSeedCandidates(slug: String): List<ChapterSeed> {
		val candidatesByRaw = LinkedHashMap<String, ChapterSeed>()

		fun addCandidate(seed: ChapterSeed?) {
			if (seed == null) return
			if (seed.value <= 0.0) return
			candidatesByRaw.putIfAbsent(seed.raw, seed)
		}

		addCandidate(latestChapterSeedBySlugCache.get()[slug])
		addCandidate(
			recentChaptersCache.get()[slug].orEmpty().maxByOrNull { it.number }?.let {
				ChapterSeed(formatChapterNumber(it.number), it.number.toDouble())
			},
		)
		addCandidate(
			mangaCacheBySlug.get()[slug]?.chaptersCount
				?.takeIf { it > 0 }?.let { ChapterSeed(it.toString(), it.toDouble()) },
		)
		addCandidate(ChapterSeed("1", 1.0))
		return candidatesByRaw.values.toList()
	}

	private suspend fun fetchChapterListBySeed(slug: String, seed: ChapterSeed): List<MangaChapter> {
		val payload = webClient.httpGet("https://$domain/api/manga/$slug/${seed.raw}").parseJson()
		val data = payload.optJSONObject("data") ?: return emptyList()
		val chapterListArray = data.optJSONArray("chapterList") ?: return emptyList()
		val chapterEntries = chapterListArray.mapJSONNotNull { parseApiChapterEntry(it) }.sortedBy { it.numberValue }
		if (chapterEntries.isEmpty()) {
			return emptyList()
		}

		val premiumStatusCache = mutableMapOf<String, Boolean>()
		val chapterData = data.optJSONObject("chapterData")
		if (chapterData != null) {
			parseChapterSeed(chapterData.opt("number"))?.let { chapterSeed ->
				premiumStatusCache[chapterSeed.raw] = chapterData.optBoolean("isPremium", false)
			}
		}

		val firstPremiumChapterIndex = findFirstPremiumChapterIndex(slug, chapterEntries, premiumStatusCache)
		val readableChapters = if (firstPremiumChapterIndex != null) {
			chapterEntries.subList(0, firstPremiumChapterIndex)
		} else {
			chapterEntries
		}

		return readableChapters.map { chapterEntry ->
			buildMangaChapterFromApiChapter(slug, chapterEntry)
		}
	}

	private fun parseApiChapterEntry(chapterJson: JSONObject): ApiChapterEntry? {
		val chapterSeed = parseChapterSeed(chapterJson.opt("number")) ?: return null
		return ApiChapterEntry(
			id = chapterJson.getStringOrNull("id"),
			numberRaw = chapterSeed.raw,
			numberValue = chapterSeed.value,
			title = chapterJson.getStringOrNull("title")?.takeIf { it.isNotBlank() && it != "null" },
			createdAt = chapterJson.getStringOrNull("createdAt"),
		)
	}

	private suspend fun findFirstPremiumChapterIndex(
		slug: String,
		chapterEntries: List<ApiChapterEntry>,
		premiumStatusCache: MutableMap<String, Boolean>,
	): Int? {
		if (chapterEntries.isEmpty()) return null

		val latestChapter = chapterEntries.last()
		val latestChapterIsPremium =
			getChapterPremiumStatus(slug, latestChapter.numberRaw, premiumStatusCache) ?: return null
		if (!latestChapterIsPremium) {
			return null
		}

		var low = 0
		var high = chapterEntries.lastIndex
		var firstPremiumIndex = chapterEntries.lastIndex

		while (low <= high) {
			val mid = (low + high) ushr 1
			val chapterIsPremium =
				getChapterPremiumStatus(slug, chapterEntries[mid].numberRaw, premiumStatusCache) ?: return null
			if (chapterIsPremium) {
				firstPremiumIndex = mid
				high = mid - 1
			} else {
				low = mid + 1
			}
		}
		return firstPremiumIndex
	}

	private suspend fun getChapterPremiumStatus(
		slug: String,
		chapterNumberRaw: String,
		premiumStatusCache: MutableMap<String, Boolean>,
	): Boolean? {
		premiumStatusCache[chapterNumberRaw]?.let { return it }

		return runCatching {
			val payload = webClient.httpGet("https://$domain/api/manga/$slug/$chapterNumberRaw").parseJson()
			val isPremium = payload.optJSONObject("data")
				?.optJSONObject("chapterData")
				?.optBoolean("isPremium", false) ?: false
			premiumStatusCache[chapterNumberRaw] = isPremium
			isPremium
		}.getOrNull()
	}

	private fun buildMangaChapterFromApiChapter(slug: String, chapterEntry: ApiChapterEntry): MangaChapter {
		val chapterNumber = chapterEntry.numberValue.toFloat()
		val chapterTitle = if (!chapterEntry.title.isNullOrBlank()) {
			"Chapitre ${formatChapterNumber(chapterNumber)} - ${chapterEntry.title}"
		} else {
			"Chapitre ${formatChapterNumber(chapterNumber)}"
		}
		val chapterUrl = "/serie/$slug/chapter/${chapterEntry.numberRaw}"
		return MangaChapter(
			id = generateUid("${chapterUrl}#${chapterEntry.id ?: chapterEntry.numberRaw}"),
			title = chapterTitle,
			number = chapterNumber,
			volume = 0,
			url = chapterUrl,
			uploadDate = parseDate(chapterEntry.createdAt),
			source = source,
			scanlator = null,
			branch = null,
		)
	}

	private fun parseChapterSeed(rawNumber: Any?): ChapterSeed? {
		val rawText = rawNumber?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
		val value = rawText.toDoubleOrNull() ?: return null
		val normalizedRaw = if (value % 1.0 == 0.0) {
			value.toInt().toString()
		} else {
			rawText
		}
		return ChapterSeed(raw = normalizedRaw, value = value)
	}

	private fun extractFullMangaListFromDocument(doc: Document): List<MangaCache> {
		val pageData = extractNextJsPageData(doc)

		val mangasArray = pageData?.let {
			it.optJSONArray("mangas") ?: it.optJSONArray("series") ?: it.optJSONObject("initialData")
				?.optJSONArray("mangas") ?: it.optJSONObject("initialData")?.optJSONArray("series")
		} ?: return emptyList()

		return mangasArray.mapJSON { parseMangaDetailsFromJson(it, popularityScore = null) }

	}

	private fun parseMangaDetailsFromJson(mangaJson: JSONObject, popularityScore: Int?): MangaCache {
		val slug = mangaJson.getString("slug")
		val url = "/serie/$slug"
		val coverImageRaw = mangaJson.getStringOrNull("coverImage")
		val coverUrl = when {
			coverImageRaw.isNullOrBlank() || coverImageRaw == "null" -> "https://$domain/api/covers/$slug.webp"
			coverImageRaw.startsWith("http://") || coverImageRaw.startsWith("https://") -> coverImageRaw
			coverImageRaw.startsWith("storage/") || coverImageRaw.startsWith("/storage/") -> "https://$domain/api/covers/$slug.webp"
			else -> coverImageRaw.toAbsoluteUrl(domain)
		}

		val authors = mangaJson.getStringOrNull("author")?.takeIf { it.isNotBlank() && it != "null" }?.split(',')
			?.map(String::trim)?.toSet() ?: emptySet()

		val artists = mangaJson.getStringOrNull("artist")?.takeIf { it.isNotBlank() && it != "null" }?.split(',')
			?.map(String::trim)?.toSet() ?: emptySet()

		val altNamesString = mangaJson.optString("alternativeNames")
		val altTitles = altNamesString.split(',').map(String::trim).filter { it.isNotEmpty() && it != "null" }.toSet()

		val genresArray = mangaJson.optJSONArray("categories")
		val genres = mutableSetOf<MangaTag>()
		if (genresArray != null) {
			for (i in 0 until genresArray.length()) {
				val genreObj = genresArray.optJSONObject(i)
				val genreName = genreObj?.optString("name")
				if (!genreName.isNullOrEmpty()) {
					genres.add(MangaTag(key = genreName, title = genreName, source = source))
				}
			}
		}

		val ratingValue = mangaJson.optDouble("rating").toFloat()
		val rating = if (ratingValue > 0f) ratingValue.div(5f) else RATING_UNKNOWN
		val nsfw = mangaJson.optBoolean("isExplicit", false)

		val manga = Manga(
			id = generateUid(url),
			title = mangaJson.getString("title"),
			altTitles = altTitles,
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = rating,
			contentRating = if (nsfw) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = coverUrl,
			tags = genres,
			state = parseStatus(mangaJson.optString("status")),
			authors = authors + artists,
			description = mangaJson.getStringOrNull("description")
				?.takeIf { it.isNotBlank() && it != "null" && it != "Aucune description." },
			source = source,
		)
		val viewCount = when {
			popularityScore != null && popularityScore > 0 -> popularityScore
			mangaJson.has("viewCount") -> mangaJson.optInt("viewCount", 0)
			else -> mangaJson.optJSONObject("_count")?.optInt("favorites", 0) ?: 0
		}
		val latestChapterDate = parseDate(mangaJson.optString("latestChapterCreatedAt"))
		val chaptersCount = mangaJson.optJSONObject("_count")?.optInt("chapters", 1) ?: 1
		val type = when (mangaJson.optString("type", "").lowercase(sourceLocale)) {
			"manhwa" -> ContentType.MANHWA
			"manhua" -> ContentType.MANHUA
			"webtoon" -> ContentType.MANHWA
			else -> ContentType.MANGA
		}

		return MangaCache(
			manga = manga,
			viewCount = viewCount,
			latestChapterDate = latestChapterDate,
			type = type,
			chaptersCount = chaptersCount,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		if (!manga.chapters.isNullOrEmpty()) {
			return manga.copy(
				chapters = normalizeChapterOrder(manga.chapters.orEmpty()),
			)
		}

		var cloudflareProtectionError: Throwable? = null

		// Preferred path: parse series page (full chapters list).
		val pageChapters = runCatching {
			val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
			val pageData = extractNextJsPageData(doc) ?: throw Exception("Could not extract Next.js data for manga details")
			val mangaDetailsJson =
				pageData.optJSONObject("manga") ?: pageData.optJSONObject("initialData")?.optJSONObject("manga")
				?: pageData.takeIf { it.has("slug") && it.has("title") }
				?: throw Exception("JSON 'manga' structure not found")
			extractChaptersFromMangaData(mangaDetailsJson, manga)
		}.onFailure { error ->
			findCloudflareProtectionError(error)?.let { cloudflareProtectionError = it }
		}.getOrNull().orEmpty()
		if (pageChapters.isNotEmpty()) {
			return manga.copy(
				description = manga.description ?: "",
				chapters = normalizeChapterOrder(pageChapters),
			)
		}

		// Fallback path: use API chapter list endpoint (works without parsing the series HTML).
		val slug = extractSlugFromSeriesUrl(manga.url).ifBlank {
			extractSlugFromSeriesUrl(manga.publicUrl)
		}
		val cachedManga = if (slug.isBlank()) {
			null
		} else {
			mangaCacheBySlug.get()[slug]?.manga
		}
		val base = cachedManga ?: manga
		val expectedChapterCount = if (slug.isBlank()) {
			null
		} else {
			mangaCacheBySlug.get()[slug]?.chaptersCount?.takeIf { it > 0 }
		}
		val apiChapters = if (slug.isBlank()) {
			emptyList()
		} else {
			fetchAllChaptersBySlugFromApiCached(slug)
		}
		if (apiChapters.isNotEmpty()) {
			return base.copy(
				description = base.description ?: "",
				chapters = normalizeChapterOrder(apiChapters),
			)
		}

		// Last-resort fallback: lastchapters endpoint (usually only latest 2 chapters).
		val fallbackChapters = normalizeChapterOrder(
			if (slug.isBlank()) emptyList() else recentChaptersCache.get()[slug].orEmpty(),
		)
		if (cloudflareProtectionError != null &&
			shouldRequestBrowserChallenge(
				expectedChapterCount = expectedChapterCount,
				recoveredChaptersCount = fallbackChapters.size,
			)
		) {
			throw cloudflareProtectionError
		}
		return base.copy(
			description = base.description ?: "",
			chapters = fallbackChapters,
		)
	}

	private fun extractChaptersFromMangaData(mangaJson: JSONObject, manga: Manga): List<MangaChapter> {
		val chaptersArray = mangaJson.optJSONArray("chapters") ?: return emptyList()

		val slug = manga.url.substringAfterLast("/serie/").substringBefore("/")

		return normalizeChapterOrder(chaptersArray.mapJSONNotNull { chapterJson ->
			val chapterNumber = chapterJson.optDouble("number", 0.0).toFloat()
			val title = chapterJson.optString("title", "")
			if (chapterJson.optBoolean("isPremium", false)) return@mapJSONNotNull null // Skip premium chapters

			val createdAt = chapterJson.optString("createdAt", "").takeIf(String::isNotEmpty)
				?: chapterJson.optString("publishedAt", "")

			val chapterNumberRaw = chapterJson.opt("number")?.toString()?.trim()
			val chapterNumberForUrl =
				chapterNumberRaw?.takeIf { it.isNotEmpty() && it != "null" } ?: formatChapterNumber(chapterNumber)
			val chapterUrl = "/serie/$slug/chapter/$chapterNumberForUrl"
			val uploadDate = parseDate(createdAt)

			val chapterTitle = if (title.isNotBlank() && title != "null") {
				"Chapitre ${formatChapterNumber(chapterNumber)} - $title"
			} else {
				"Chapitre ${formatChapterNumber(chapterNumber)}"
			}

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				number = chapterNumber,
				volume = 0,
				url = chapterUrl,
				uploadDate = uploadDate,
				source = source,
				scanlator = null,
				branch = null,
			)
		})
	}

	fun formatChapterNumber(number: Float): String {
		return if (number % 1 == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		synchronized(chapterPagesCache) {
			chapterPagesCache[chapter.url]?.let { return it }
		}
		val apiPages = fetchChapterPagesFromApi(chapter)
		if (apiPages.isNotEmpty()) {
			synchronized(chapterPagesCache) {
				chapterPagesCache[chapter.url] = apiPages
			}
			return apiPages
		}
		val fallbackPages = runCatching {
			val directDoc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
			extractPagesFromDocument(chapter, directDoc)
		}.getOrDefault(emptyList())
		if (fallbackPages.isNotEmpty()) {
			synchronized(chapterPagesCache) {
				chapterPagesCache[chapter.url] = fallbackPages
			}
		}
		return fallbackPages
	}

	private suspend fun fetchAllChaptersBySlugFromApiCached(slug: String): List<MangaChapter> {
		synchronized(apiChaptersBySlugCache) {
			apiChaptersBySlugCache[slug]?.let { return it }
		}
		val chapters = fetchAllChaptersBySlugFromApi(slug)
		synchronized(apiChaptersBySlugCache) {
			apiChaptersBySlugCache[slug] = chapters
		}
		return chapters
	}

	private suspend fun fetchChapterPagesFromApi(chapter: MangaChapter): List<MangaPage> {
		val chapterRoute = parseChapterRoute(chapter.url) ?: return emptyList()
		val chapterData = runCatching {
			webClient.httpGet("https://$domain/api/manga/${chapterRoute.slug}/${chapterRoute.chapterNumberRaw}")
				.parseJson()
		}.getOrNull()
			?.optJSONObject("data")
			?.optJSONObject("chapterData") ?: return emptyList()
		if (chapterData.optBoolean("isPremium", false)) {
			return emptyList()
		}
		val chapterId = chapterData.getStringOrNull("id")?.takeIf { it.isNotBlank() } ?: return emptyList()
		val imagesArray = runCatching {
			webClient.httpGet("https://$domain/api/chapters/${chapterRoute.slug}/$chapterId/images")
				.parseJson()
		}.getOrNull()
			?.let { payload ->
				payload.optJSONArray("images")
					?: payload.optJSONObject("data")?.optJSONArray("images")
			} ?: return emptyList()

		val indexedUrls = mutableListOf<Pair<Int, String>>()
		for (index in 0 until imagesArray.length()) {
			val imageEntry = imagesArray.opt(index)
			val rawUrl = when (imageEntry) {
				is JSONObject -> imageEntry.getStringOrNull("url")
					?: imageEntry.getStringOrNull("originalUrl")
					?: imageEntry.getStringOrNull("src")
				is String -> imageEntry
				else -> null
			} ?: continue
			val normalizedUrl = normalizePageImageUrl(rawUrl) ?: continue
			val pageIndex = (imageEntry as? JSONObject)?.optInt("index", index) ?: index
			indexedUrls.add(pageIndex to normalizedUrl)
		}
		if (indexedUrls.isEmpty()) return emptyList()

		return indexedUrls.sortedBy { it.first }.map { it.second }.distinct().map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = chapter.source,
			)
		}
	}

	private fun parseChapterRoute(chapterUrl: String): ChapterRoute? {
		val routeMatch = chapterRouteRegex.find(chapterUrl) ?: return null
		val slug = routeMatch.groupValues.getOrNull(1)?.trim().orEmpty()
		val chapterNumberRaw = routeMatch.groupValues.getOrNull(2)?.trim().orEmpty()
		if (slug.isEmpty() || chapterNumberRaw.isEmpty()) {
			return null
		}
		return ChapterRoute(
			slug = slug,
			chapterNumberRaw = chapterNumberRaw,
		)
	}

	private fun extractSlugFromSeriesUrl(url: String): String {
		return url.substringAfter("/serie/").substringBefore("/").substringBefore("?").substringBefore("#").trim()
	}

	private fun extractPagesFromDocument(chapter: MangaChapter, document: Document): List<MangaPage> {
		val extractedUrls = LinkedHashSet<String>()

		val pageData = extractNextJsPageData(document)
		val imagesArray = pageData?.let { findBestImagesArray(it) }
		if (imagesArray != null) {
			for (index in 0 until imagesArray.length()) {
				val imageUrl = extractImageUrlFromArrayItem(imagesArray.opt(index)) ?: continue
				extractedUrls.add(imageUrl)
			}
		}

		if (extractedUrls.isEmpty()) {
			extractedUrls += extractImageUrlsFromChapterHtml(document)
		}

		if (extractedUrls.isEmpty()) return emptyList()

		val slug = chapter.url.substringAfter("/serie/").substringBefore("/chapter/").lowercase(Locale.ROOT)
		val chapterScopedUrls = if (slug.isBlank()) {
			emptyList()
		} else {
			extractedUrls.filter { url ->
				val lower = url.lowercase(Locale.ROOT)
				lower.contains("/$slug/") || lower.contains("/mangas/$slug/") || lower.contains("/api/chapters/$slug/")
			}
		}
		val finalUrls = if (chapterScopedUrls.isNotEmpty()) chapterScopedUrls else extractedUrls
		return finalUrls.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = chapter.source,
			)
		}
	}

	private fun extractImageUrlsFromChapterHtml(document: Document): List<String> {
		val urls = LinkedHashSet<String>()

		fun extractFromSelector(selector: String, attribute: String) {
			document.select(selector).forEach { element ->
				val value = element.attr(attribute).trim()
				val normalized = normalizePageImageUrl(value)
				normalized?.let { urls.add(it) }
			}
		}

		extractFromSelector("a[href*=\"/api/chapters/\"]", "href")
		extractFromSelector("img[src*=\"/api/chapters/\"]", "src")
		extractFromSelector("link[href*=\"/api/chapters/\"]", "href")

		return urls.toList()
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

	private fun findCloudflareProtectionError(error: Throwable): Throwable? {
		var current: Throwable? = error
		while (current != null) {
			if (current::class.java.simpleName == "CloudFlareProtectedException") {
				return current
			}
			current = current.cause
		}
		return null
	}

	private fun shouldRequestBrowserChallenge(expectedChapterCount: Int?, recoveredChaptersCount: Int): Boolean {
		if (recoveredChaptersCount == 0) return true
		if (expectedChapterCount != null && expectedChapterCount >= recoveredChaptersCount + 3) return true
		return recoveredChaptersCount <= 2 && (expectedChapterCount == null || expectedChapterCount > 2)
	}

	private fun extractNextJsPageData(document: Document): JSONObject? {
		try {
			var bestObject: JSONObject? = null
			var bestImageCount = 0
			for (script in document.select("script")) {
				val scriptContent = script.data()
				if (!scriptContent.contains("self.__next_f.push")) continue

				val matches = nextFPushRegex.findAll(scriptContent)
				for (matchResult in matches) {
					if (matchResult.groupValues.size < 2) continue

					val rawDataString = matchResult.groupValues[1]
					val cleanedDataString = rawDataString.replace("\\\\", "\\").replace("\\\"", "\"")
					val seenObjectStarts = HashSet<Int>()

					for (pattern in nextJsPayloadAnchorPatterns) {
						var searchIdx = -1
						while (true) {
							searchIdx = cleanedDataString.indexOf(pattern, startIndex = searchIdx + 1)
							if (searchIdx == -1) break

							val objectStartIndex = findJsonObjectStart(cleanedDataString, searchIdx)
							if (objectStartIndex == -1 || !seenObjectStarts.add(objectStartIndex)) continue

							val potentialJson = extractJsonObjectString(cleanedDataString, objectStartIndex)
							if (potentialJson != null) {
								try {
									val parsedContainer = JSONObject(potentialJson)
									val imageCount = findBestImagesArray(parsedContainer)?.let { countImageEntries(it) } ?: 0
									if (
										bestObject == null ||
										imageCount > bestImageCount
									) {
										bestObject = parsedContainer
										bestImageCount = imageCount
									}
								} catch (_: Exception) {
									// Continue searching
								}
							}
						}
					}
				}
			}

			return bestObject
		} catch (_: Exception) {
			return null
		}
	}

	private fun findJsonObjectStart(data: String, fromIndex: Int): Int {
		var braceDepth = 0
		for (i in fromIndex downTo 0) {
			when (data[i]) {
				'}' -> braceDepth++
				'{' -> {
					if (braceDepth == 0) {
						return i
					}
					braceDepth--
				}
			}
		}
		return -1
	}

	private fun findBestImagesArray(node: Any?): JSONArray? {
		var bestArray: JSONArray? = null
		var bestCount = 0

		fun visit(value: Any?) {
			when (value) {
				is JSONObject -> {
					val keys = value.keys()
					while (keys.hasNext()) {
						visit(value.opt(keys.next()))
					}
				}
				is JSONArray -> {
					val count = countImageEntries(value)
					if (count > bestCount) {
						bestCount = count
						bestArray = value
					}
					for (index in 0 until value.length()) {
						visit(value.opt(index))
					}
				}
			}
		}

		visit(node)
		return if (bestCount > 0) bestArray else null
	}

	private fun countImageEntries(array: JSONArray): Int {
		var count = 0
		for (index in 0 until array.length()) {
			if (extractImageUrlFromArrayItem(array.opt(index)) != null) {
				count++
			}
		}
		return count
	}

	private fun extractImageUrlFromArrayItem(item: Any?): String? {
		val raw = when (item) {
			is JSONObject -> {
				item.getStringOrNull("originalUrl")
					?: item.getStringOrNull("url")
					?: item.getStringOrNull("src")
			}
			is String -> item
			else -> null
		} ?: return null
		return normalizePageImageUrl(raw)
	}

	private fun normalizePageImageUrl(rawUrl: String): String? {
		val value = rawUrl.trim()
			.replace("\\/", "/")
			.takeIf { it.isNotEmpty() && it != "null" } ?: return null
		val lowerValue = value.lowercase(Locale.ROOT)
		if (lowerValue.contains("/api/covers/")) return null
		if (lowerValue.contains("/preview.")) return null
		if (!looksLikeImageUrl(lowerValue)) return null
		return value.toAbsoluteUrl(domain)
	}

	private fun looksLikeImageUrl(valueLowercase: String): Boolean {
		if (valueLowercase.endsWith(".jpg") || valueLowercase.endsWith(".jpeg") || valueLowercase.endsWith(".png") ||
			valueLowercase.endsWith(".webp") || valueLowercase.endsWith(".avif") || valueLowercase.endsWith(".gif")
		) {
			return true
		}
		if (valueLowercase.contains("/api/chapters/")) {
			return true
		}
		return valueLowercase.contains("/storage/") && valueLowercase.contains("/mangas/")
	}

	private fun extractJsonObjectString(data: String, startIndex: Int): String? {
		if (startIndex < 0 || startIndex >= data.length || data[startIndex] != '{') return null

		var braceBalance = 1
		var inString = false
		var i = startIndex + 1
		while (i < data.length) {
			val char = data[i]
			when (char) {
				'\\' -> if (inString) i++
				'"' -> inString = !inString
				'{' -> if (!inString) braceBalance++
				'}' -> if (!inString) {
					braceBalance--
					if (braceBalance == 0) return data.substring(startIndex, i + 1)
				}
			}
			i++
		}
		return null
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

	private fun parseDate(dateString: String?): Long {
		if (dateString.isNullOrBlank()) return 0L

		val cleanedDateString =
			dateString.removePrefix("\"").removeSuffix("\"").removePrefix("\$D").removePrefix("D").trim()

		return isoDateFormat.parseSafe(cleanedDateString)
	}
}
