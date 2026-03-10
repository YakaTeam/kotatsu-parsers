package org.koitharu.kotatsu.parsers.site.fr

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
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
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getLongOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import java.text.Normalizer
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("BIGSOLO", "BigSolo", "fr")
internal class BigSolo(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.BIGSOLO) {

	override val configKeyDomain = ConfigKey.Domain("bigsolo.org")

	override val defaultSortOrder: SortOrder = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isYearSupported = true,
		isYearRangeSupported = true,
		isAuthorSearchSupported = true,
	)

	@Volatile
	private var catalogCache: CatalogCache? = null
	private val pagesCache = HashMap<String, CachedPages>()

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val catalog = getCatalog()
		return MangaListFilterOptions(
			availableTags = catalog.availableTags,
			availableStates = catalog.availableStates,
			availableContentTypes = catalog.availableContentTypes,
			availableDemographics = catalog.availableDemographics,
		)
	}

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val catalog = getCatalog()
		val query = normalizeSearchText(filter.query)
		val includeTags = filter.tags.mapTo(hashSetOf()) { normalizeTagKey(it.key) }
		val excludeTags = filter.tagsExclude.mapTo(hashSetOf()) { normalizeTagKey(it.key) }
		val stateFilter = filter.states
		val typesFilter = filter.types
		val demographicFilter = filter.demographics
		val authorFilter = normalizeSearchText(filter.author)
		val filtered = catalog.entries.filter { entry ->
			if (query.isNotEmpty() && query !in entry.searchText) {
				return@filter false
			}
			if (authorFilter.isNotEmpty() && authorFilter !in entry.authorsSearchText) {
				return@filter false
			}
			if (includeTags.isNotEmpty() && !entry.tagKeys.containsAll(includeTags)) {
				return@filter false
			}
			if (excludeTags.isNotEmpty() && entry.tagKeys.any { it in excludeTags }) {
				return@filter false
			}
			if (stateFilter.isNotEmpty() && entry.state !in stateFilter) {
				return@filter false
			}
			if (typesFilter.isNotEmpty() && entry.contentType !in typesFilter) {
				return@filter false
			}
			if (demographicFilter.isNotEmpty() && entry.demographic !in demographicFilter) {
				return@filter false
			}
			if (filter.year != YEAR_UNKNOWN && entry.releaseYear != filter.year) {
				return@filter false
			}
			if (filter.yearFrom != YEAR_UNKNOWN && (entry.releaseYear == YEAR_UNKNOWN || entry.releaseYear < filter.yearFrom)) {
				return@filter false
			}
			if (filter.yearTo != YEAR_UNKNOWN && (entry.releaseYear == YEAR_UNKNOWN || entry.releaseYear > filter.yearTo)) {
				return@filter false
			}
			true
		}
		return sort(filtered, order).map { entry ->
			Manga(
				id = generateUid(entry.url),
				title = entry.title,
				altTitles = entry.altTitles,
				url = entry.url,
				publicUrl = entry.publicUrl,
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = entry.coverUrl,
				tags = entry.tags,
				state = entry.state,
				authors = entry.authors,
				largeCoverUrl = entry.largeCoverUrl,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = extractSlugFromUrl(manga.url) ?: extractSlugFromUrl(manga.publicUrl) ?: return manga
		val catalog = getCatalog()
		val entry = catalog.bySlug[slug] ?: getCatalog(forceRefresh = true).bySlug[slug] ?: return manga
		return manga.copy(
			title = entry.title,
			altTitles = entry.altTitles,
			url = "/${entry.slug}",
			publicUrl = "https://$domain/${entry.slug}",
			coverUrl = entry.coverUrl ?: manga.coverUrl,
			largeCoverUrl = entry.largeCoverUrl ?: manga.largeCoverUrl,
			description = entry.description ?: manga.description,
			tags = entry.tags,
			state = entry.state,
			authors = entry.authors,
			chapters = entry.chapters.map { chapter ->
				val chapterUrl = buildChapterUrl(entry.slug, chapter.key, chapter.sourceService, chapter.sourceId)
				MangaChapter(
					id = generateUid(chapterUrl),
					title = buildChapterTitle(chapter.numberLabel, chapter.title),
					number = chapter.number,
					volume = chapter.volume,
					url = chapterUrl,
					scanlator = chapter.scanlator,
					uploadDate = chapter.uploadDate,
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fallbackChapter = resolveChapterFromCatalog(chapter.url)
		val sourceId = chapter.url.queryParameter(SOURCE_ID_PARAM)
			?: fallbackChapter?.sourceId
			?: return emptyList()
		val sourceService = chapter.url.queryParameter(SOURCE_SERVICE_PARAM)
			?: fallbackChapter?.sourceService
			?: SOURCE_IMG_CHEST
		if (!sourceService.equals(SOURCE_IMG_CHEST, ignoreCase = true)) {
			return emptyList()
		}
		val now = System.currentTimeMillis()
		synchronized(pagesCache) {
			val cached = pagesCache[sourceId]
			if (cached != null && now - cached.fetchedAt <= PAGES_TTL_MS) {
				return cached.pages
			}
		}
		val apiUrl = HttpUrl.Builder()
			.scheme("https")
			.host(domain)
			.addPathSegments("api/imgchest-chapter-pages")
			.addQueryParameter("id", sourceId)
			.build()
		val pagesArray = runCatching {
			webClient.httpGet(apiUrl).parseJsonArray()
		}.getOrElse {
			return emptyList()
		}
		val pages = parsePages(sourceId, pagesArray)
		if (pages.isNotEmpty()) {
			synchronized(pagesCache) {
				pagesCache[sourceId] = CachedPages(
					fetchedAt = now,
					pages = pages,
				)
			}
		}
		return pages
	}

	private suspend fun getCatalog(forceRefresh: Boolean = false): CatalogCache {
		val now = System.currentTimeMillis()
		val cached = catalogCache
		if (!forceRefresh && cached != null && now - cached.fetchedAt <= CATALOG_TTL_MS) {
			return cached
		}
		val fresh = fetchCatalog(now)
		catalogCache = fresh
		return fresh
	}

	private suspend fun fetchCatalog(fetchedAt: Long): CatalogCache {
		val payload = webClient.httpGet("https://$domain/data/series").parseJson()
		val teamNames = fetchTeamNames()
		val entries = ArrayList<BigSoloEntry>()
		parseSeriesArray(payload.optJSONArray("series"), isOneShot = false, teamNames = teamNames, destination = entries)
		parseSeriesArray(payload.optJSONArray("os"), isOneShot = true, teamNames = teamNames, destination = entries)
		val availableTags = entries
			.flatMap { it.tags }
			.distinctBy { it.key }
			.sortedBy { it.title.lowercase(Locale.ROOT) }
			.toCollection(LinkedHashSet())
		val availableStates = EnumSet.noneOf(MangaState::class.java).apply {
			entries.forEach { e ->
				e.state?.let { add(it) }
			}
		}
		val availableContentTypes = EnumSet.noneOf(ContentType::class.java).apply {
			entries.forEach { add(it.contentType) }
		}
		val availableDemographics = EnumSet.noneOf(Demographic::class.java).apply {
			entries.forEach { e ->
				e.demographic?.let { add(it) }
			}
		}
		return CatalogCache(
			fetchedAt = fetchedAt,
			entries = entries,
			bySlug = entries.associateBy { it.slug.lowercase(Locale.ROOT) },
			availableTags = availableTags,
			availableStates = availableStates,
			availableContentTypes = availableContentTypes,
			availableDemographics = availableDemographics,
		)
	}

	private fun parseSeriesArray(
		array: JSONArray?,
		isOneShot: Boolean,
		teamNames: Map<String, String>,
		destination: MutableList<BigSoloEntry>,
	) {
		if (array == null) return
		for (i in 0 until array.length()) {
			val jo = array.optJSONObject(i) ?: continue
			val entry = parseSeriesEntry(jo, isOneShot, teamNames) ?: continue
			destination.add(entry)
		}
	}

	private fun parseSeriesEntry(
		json: JSONObject,
		isOneShotFromList: Boolean,
		teamNames: Map<String, String>,
	): BigSoloEntry? {
		val rawSlug = json.getStringOrNull("slug")?.trim().orEmpty()
		if (rawSlug.isEmpty()) return null
		val title = json.getStringOrNull("title")?.trim().orEmpty()
		if (title.isEmpty()) return null
		val coverUrls = parseCoverUrls(json)
		val coverUrl = coverUrls.first
		val largeCoverUrl = coverUrls.second

		val tagsRaw = parseStringArray(json.optJSONArray("tags"))
		val tags = tagsRaw.mapTo(LinkedHashSet()) { value ->
			createTag(value)
		}
		val tagKeys = tags.mapTo(hashSetOf()) { it.key }
		val isOneShot = isOneShotFromList || json.optBoolean("os", false)
		val contentType = detectContentType(isOneShot, tagKeys)
		val chapters = parseChapters(json.optJSONObject("chapters"), teamNames)
		val lastUpdate = normalizeTimestamp(json.optJSONObject("last_chapter")?.getLongOrDefault("timestamp", 0L) ?: 0L)
			.takeIf { it > 0L }
			?: chapters.maxOfOrNull { it.uploadDate }
			?: 0L
		val altTitles = parseAlternativeTitles(json, title)
		val authors = parseAuthors(json)
		val releaseYear = json.getIntOrDefault("release_year", YEAR_UNKNOWN)
		return BigSoloEntry(
			slug = rawSlug,
			title = title,
			altTitles = altTitles,
			url = "/$rawSlug",
			publicUrl = "https://$domain/$rawSlug",
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
			description = json.getStringOrNull("description"),
			tags = tags,
			tagKeys = tagKeys,
			state = parseState(json.getStringOrNull("status")),
			contentType = contentType,
			demographic = parseDemographic(json.getStringOrNull("demographic")),
			releaseYear = releaseYear,
			authors = authors,
			lastUpdate = lastUpdate,
			chapters = chapters,
			chaptersByKey = chapters.associateBy { it.key },
			searchText = buildSearchText(title, altTitles, authors, tags),
			authorsSearchText = normalizeSearchText(authors.joinToString(" ")),
		)
	}

	private fun parseChapters(
		chaptersObject: JSONObject?,
		teamNames: Map<String, String>,
	): List<BigSoloChapterEntry> {
		if (chaptersObject == null) return emptyList()
		val chapters = ArrayList<BigSoloChapterEntry>(chaptersObject.length())
		val keys = chaptersObject.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			val chapterJson = chaptersObject.optJSONObject(key) ?: continue
			val numberLabel = normalizeChapterLabel(key)
			val number = numberLabel.toFloatOrNull() ?: continue
			val sourceJson = chapterJson.optJSONObject("source") ?: continue
			val sourceId = sourceJson.getStringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
			val sourceService = sourceJson.getStringOrNull("service")
				?.trim()
				?.lowercase(Locale.ROOT)
				?.takeIf { it.isNotEmpty() }
				?: SOURCE_IMG_CHEST
			val title = chapterJson.getStringOrNull("title")
			val volume = chapterJson.getStringOrNull("volume")
				?.trim()
				?.toIntOrNull()
				?: 0
			val teamIds = parseStringList(chapterJson.optJSONArray("teams"))
			val scanlator = teamIds.asSequence()
				.map { id -> teamNames[id] ?: id.replace('_', ' ') }
				.map { it.trim() }
				.filter { it.isNotEmpty() }
				.distinct()
				.joinToString(", ")
				.ifBlank { null }
			chapters.add(
				BigSoloChapterEntry(
					key = key,
					number = number,
					numberLabel = numberLabel,
					title = title,
					volume = volume,
					uploadDate = normalizeTimestamp(chapterJson.getLongOrDefault("timestamp", 0L)),
					sourceService = sourceService,
					sourceId = sourceId,
					scanlator = scanlator,
				),
			)
		}
		return chapters.sortedWith(
			compareBy<BigSoloChapterEntry> { it.number }
				.thenBy { it.uploadDate }
				.thenBy { it.key },
		)
	}

	private fun parsePages(sourceId: String, pagesArray: JSONArray): List<MangaPage> {
		val pages = ArrayList<IndexedPage>(pagesArray.length())
		for (i in 0 until pagesArray.length()) {
			val jo = pagesArray.optJSONObject(i) ?: continue
			val pageUrl = jo.getStringOrNull("link")?.toAbsoluteUrl(domain) ?: continue
			val previewUrl = jo.getStringOrNull("thumbnail")?.toAbsoluteUrl(domain)
			val position = jo.getIntOrDefault("position", i + 1)
			val pageId = jo.getStringOrNull("id") ?: "$sourceId-$position"
			pages += IndexedPage(
				position = position,
				page = MangaPage(
					id = generateUid("$sourceId/$pageId"),
					url = pageUrl,
					preview = previewUrl,
					source = source,
				),
			)
		}
		return pages.sortedBy { it.position }.map { it.page }
	}

	private fun sort(entries: List<BigSoloEntry>, order: SortOrder): List<BigSoloEntry> {
		return when (order) {
			SortOrder.ALPHABETICAL -> entries.sortedBy { it.title.lowercase(Locale.ROOT) }
			SortOrder.ALPHABETICAL_DESC -> entries.sortedByDescending { it.title.lowercase(Locale.ROOT) }
			SortOrder.UPDATED -> entries.sortedWith(
				compareByDescending<BigSoloEntry> { it.lastUpdate }.thenBy { it.title.lowercase(Locale.ROOT) },
			)
			SortOrder.UPDATED_ASC -> entries.sortedWith(
				compareBy<BigSoloEntry> { it.lastUpdate }.thenBy { it.title.lowercase(Locale.ROOT) },
			)
			SortOrder.NEWEST -> entries.sortedWith(
				compareByDescending<BigSoloEntry> { it.releaseYear }
					.thenByDescending { it.lastUpdate }
					.thenBy { it.title.lowercase(Locale.ROOT) },
			)
			SortOrder.NEWEST_ASC -> entries.sortedWith(
				compareBy<BigSoloEntry> { it.releaseYear }
					.thenBy { it.lastUpdate }
					.thenBy { it.title.lowercase(Locale.ROOT) },
			)
			else -> entries.sortedWith(
				compareByDescending<BigSoloEntry> { it.lastUpdate }.thenBy { it.title.lowercase(Locale.ROOT) },
			)
		}
	}

	private suspend fun fetchTeamNames(): Map<String, String> {
		return runCatching {
			val teamsJson = webClient.httpGet("https://$domain/data/teams").parseJson()
			val names = HashMap<String, String>(teamsJson.length())
			val keys = teamsJson.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				val team = teamsJson.optJSONObject(key)
				val name = team?.getStringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() }
					?: key.replace('_', ' ')
				names[key] = name
			}
			names
		}.getOrDefault(emptyMap())
	}

	private fun parseCoverUrls(json: JSONObject): Pair<String?, String?> {
		val cover = json.optJSONObject("cover")
		var coverUrl = cover?.getStringOrNull("url_lq")?.toAbsoluteUrl(domain)
			?: cover?.getStringOrNull("url_hq")?.toAbsoluteUrl(domain)
		var largeCoverUrl = cover?.getStringOrNull("url_hq")?.toAbsoluteUrl(domain)
			?: cover?.getStringOrNull("url_lq")?.toAbsoluteUrl(domain)
		if (coverUrl != null && largeCoverUrl != null) {
			return coverUrl to largeCoverUrl
		}
		val covers = json.optJSONArray("covers") ?: return coverUrl to largeCoverUrl
		for (i in 0 until covers.length()) {
			val item = covers.optJSONObject(i) ?: continue
			if (coverUrl == null) {
				coverUrl = item.getStringOrNull("url_lq")?.toAbsoluteUrl(domain)
					?: item.getStringOrNull("url_hq")?.toAbsoluteUrl(domain)
			}
			if (largeCoverUrl == null) {
				largeCoverUrl = item.getStringOrNull("url_hq")?.toAbsoluteUrl(domain)
					?: item.getStringOrNull("url_lq")?.toAbsoluteUrl(domain)
			}
			if (coverUrl != null && largeCoverUrl != null) {
				break
			}
		}
		return coverUrl to largeCoverUrl
	}

	private fun parseAlternativeTitles(json: JSONObject, title: String): Set<String> {
		val altTitles = LinkedHashSet<String>()
		json.getStringOrNull("ja_title")?.trim()?.takeIf { it.isNotEmpty() }?.let { altTitles.add(it) }
		val altArray = json.optJSONArray("alternative_titles")
		if (altArray != null) {
			for (i in 0 until altArray.length()) {
				altArray.optString(i).trim().takeIf { it.isNotEmpty() }?.let { altTitles.add(it) }
			}
		}
		altTitles.remove(title)
		return altTitles
	}

	private fun parseAuthors(json: JSONObject): Set<String> {
		val authors = LinkedHashSet<String>()
		json.getStringOrNull("author")?.trim()?.takeIf { it.isNotEmpty() }?.let { authors.add(it) }
		json.getStringOrNull("artist")?.trim()?.takeIf { it.isNotEmpty() }?.let { authors.add(it) }
		return authors
	}

	private fun createTag(value: String): MangaTag {
		return MangaTag(
			key = normalizeTagKey(value),
			title = value,
			source = source,
		)
	}

	private fun buildSearchText(
		title: String,
		altTitles: Set<String>,
		authors: Set<String>,
		tags: Set<MangaTag>,
	): String {
		return normalizeSearchText(
			buildString {
				append(title)
				altTitles.forEach {
					append(' ')
					append(it)
				}
				authors.forEach {
					append(' ')
					append(it)
				}
				tags.forEach {
					append(' ')
					append(it.title)
				}
			},
		)
	}

	private fun detectContentType(isOneShot: Boolean, tagKeys: Set<String>): ContentType {
		if (isOneShot) return ContentType.ONE_SHOT
		return when {
			tagKeys.any { it.contains("manhua") } -> ContentType.MANHUA
			tagKeys.any { it.contains("manhwa") || it.contains("webtoon") } -> ContentType.MANHWA
			else -> ContentType.MANGA
		}
	}

	private fun parseDemographic(raw: String?): Demographic? {
		val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
		return when {
			value.startsWith("shonen") || value.startsWith("shounen") -> Demographic.SHOUNEN
			value.startsWith("shojo") || value.startsWith("shoujo") -> Demographic.SHOUJO
			value.startsWith("seinen") -> Demographic.SEINEN
			value.startsWith("josei") -> Demographic.JOSEI
			value.startsWith("kodomo") -> Demographic.KODOMO
			else -> null
		}
	}

	private fun parseState(raw: String?): MangaState? {
		val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
		return when {
			"cours" in value || "ongoing" in value -> MangaState.ONGOING
			"fini" in value || "termin" in value || "finished" in value || "complete" in value -> MangaState.FINISHED
			"pause" in value || "hiatus" in value -> MangaState.PAUSED
			"annul" in value || "cancel" in value || "aband" in value -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun normalizeChapterLabel(raw: String): String {
		val value = raw.trim()
		if (value.isEmpty()) return value
		return value.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value
	}

	private fun buildChapterTitle(numberLabel: String, rawTitle: String?): String {
		val title = rawTitle?.trim()?.takeIf { it.isNotEmpty() }
		if (title == null) {
			return "Chapitre $numberLabel"
		}
		val normalizedTitle = title.lowercase(Locale.ROOT)
		if (
			normalizedTitle.startsWith("chapitre") ||
			normalizedTitle.startsWith("chapter") ||
			normalizedTitle.startsWith("ch.")
		) {
			return title
		}
		if (normalizedTitle == numberLabel || normalizedTitle == "#$numberLabel") {
			return "Chapitre $numberLabel"
		}
		return "Chapitre $numberLabel - $title"
	}

	private suspend fun resolveChapterFromCatalog(chapterUrl: String): BigSoloChapterEntry? {
		val slug = extractSlugFromUrl(chapterUrl) ?: return null
		val chapterKey = extractChapterKey(chapterUrl) ?: return null
		return getCatalog().bySlug[slug]?.chaptersByKey?.get(chapterKey)
	}

	private fun buildChapterUrl(slug: String, chapterKey: String, sourceService: String, sourceId: String): String {
		return "/$slug/$chapterKey?$SOURCE_ID_PARAM=${sourceId.urlEncoded()}&$SOURCE_SERVICE_PARAM=${sourceService.urlEncoded()}"
	}

	private fun extractSlugFromUrl(url: String): String? {
		val absoluteUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
			url
		} else {
			"https://$domain/${url.trimStart('/')}"
		}
		val path = absoluteUrl.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?').substringBefore('#')
		val slug = path.trim('/').substringBefore('/').trim()
		return slug.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
	}

	private fun extractChapterKey(url: String): String? {
		val absoluteUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
			url
		} else {
			"https://$domain/${url.trimStart('/')}"
		}
		val path = absoluteUrl.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?').substringBefore('#')
		val parts = path.trim('/').split('/')
		return parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
	}

	private fun String.queryParameter(name: String): String? {
		val absoluteUrl = if (startsWith("http://") || startsWith("https://")) {
			this
		} else {
			"https://$domain/${trimStart('/')}"
		}
		return absoluteUrl.toHttpUrlOrNull()?.queryParameter(name)?.takeIf { it.isNotEmpty() }
	}

	private fun normalizeTimestamp(raw: Long): Long {
		if (raw <= 0L) return 0L
		return if (raw < TIMESTAMP_SECONDS_THRESHOLD) raw * 1000L else raw
	}

	private fun normalizeTagKey(value: String): String {
		return value.trim().lowercase(Locale.ROOT).replace(WHITESPACES, " ")
	}

	private fun normalizeSearchText(value: String?): String {
		val raw = value?.trim().orEmpty()
		if (raw.isEmpty()) return ""
		val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
		return DIACRITICS.replace(decomposed, "")
			.lowercase(Locale.ROOT)
			.replace(WHITESPACES, " ")
			.trim()
	}

	private fun parseStringArray(array: JSONArray?): Set<String> {
		if (array == null) return emptySet()
		val result = LinkedHashSet<String>(array.length())
		for (i in 0 until array.length()) {
			val value = array.optString(i).trim()
			if (value.isNotEmpty()) {
				result.add(value)
			}
		}
		return result
	}

	private fun parseStringList(array: JSONArray?): List<String> {
		if (array == null) return emptyList()
		val result = ArrayList<String>(array.length())
		for (i in 0 until array.length()) {
			val value = array.optString(i).trim()
			if (value.isNotEmpty()) {
				result += value
			}
		}
		return result
	}

	private data class CatalogCache(
		val fetchedAt: Long,
		val entries: List<BigSoloEntry>,
		val bySlug: Map<String, BigSoloEntry>,
		val availableTags: Set<MangaTag>,
		val availableStates: Set<MangaState>,
		val availableContentTypes: Set<ContentType>,
		val availableDemographics: Set<Demographic>,
	)

	private data class BigSoloEntry(
		val slug: String,
		val title: String,
		val altTitles: Set<String>,
		val url: String,
		val publicUrl: String,
		val coverUrl: String?,
		val largeCoverUrl: String?,
		val description: String?,
		val tags: Set<MangaTag>,
		val tagKeys: Set<String>,
		val state: MangaState?,
		val contentType: ContentType,
		val demographic: Demographic?,
		val releaseYear: Int,
		val authors: Set<String>,
		val lastUpdate: Long,
		val chapters: List<BigSoloChapterEntry>,
		val chaptersByKey: Map<String, BigSoloChapterEntry>,
		val searchText: String,
		val authorsSearchText: String,
	)

	private data class BigSoloChapterEntry(
		val key: String,
		val number: Float,
		val numberLabel: String,
		val title: String?,
		val volume: Int,
		val uploadDate: Long,
		val sourceService: String,
		val sourceId: String,
		val scanlator: String?,
	)

	private data class IndexedPage(
		val position: Int,
		val page: MangaPage,
	)

	private data class CachedPages(
		val fetchedAt: Long,
		val pages: List<MangaPage>,
	)

	private companion object {
		private const val SOURCE_ID_PARAM = "sid"
		private const val SOURCE_SERVICE_PARAM = "svc"
		private const val SOURCE_IMG_CHEST = "imgchest"
		private const val CATALOG_TTL_MS = 10 * 60 * 1000L
		private const val PAGES_TTL_MS = 30 * 60 * 1000L
		private const val TIMESTAMP_SECONDS_THRESHOLD = 10_000_000_000L
		private val WHITESPACES = Regex("\\s+")
		private val DIACRITICS = Regex("\\p{Mn}+")
	}
}
