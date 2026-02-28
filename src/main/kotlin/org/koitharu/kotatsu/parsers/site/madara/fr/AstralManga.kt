package org.koitharu.kotatsu.parsers.site.madara.fr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
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
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("ASTRALMANGA", "AstralManga", "fr")
internal class AstralManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ASTRALMANGA, 24) {

	override val configKeyDomain = ConfigKey.Domain("astral-manga.fr")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isAuthorSearchSupported = true,
	)

	private data class MangaCache(
		val manga: Manga,
		val popularity: Int,
		val updatedAt: Long,
		val type: ContentType,
		val chaptersCount: Int,
	)

	private data class CoverCacheEntry(
		val url: String,
		val validUntil: Long,
	)

	private data class JsonArrayCandidate(
		val array: JSONArray,
		val count: Int,
	)

	private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}
	private val amzDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val nextFPushRegex = Regex(
		"""self\.__next_f\.push\(\s*\[\s*1\s*,\s*"((?:\\.|[^"\\])*)"\s*]\s*\)""",
		RegexOption.DOT_MATCHES_ALL,
	)

	private val nextJsPayloadAnchorPatterns = arrayOf(
		""""manga":{""",
		""""mangas":[""",
		""""urlId":"""",
		""""chapters":[""",
		""""chapter":{""",
		""""images":[""",
		""""pages":[""",
	)

	private val allMangaCache = suspendLazy { fetchCatalogMangaList() }
	private val allTagsCache = suspendLazy {
		allMangaCache.get().flatMapTo(LinkedHashSet()) { it.manga.tags }
	}
	private val coverCacheBySlug = ConcurrentHashMap<String, CoverCacheEntry>()
	private val coverRetryAfterBySlug = ConcurrentHashMap<String, Long>()

	private val coverResolveParallelism = 4
	private val coverRetryDelayMs = 2 * 60 * 1000L

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
			ContentType.COMICS,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val all = getFilteredAndSortedList(order, filter)
		val from = ((page - 1) * pageSize).coerceAtLeast(0)
		if (from >= all.size) return emptyList()
		val to = (from + pageSize).coerceAtMost(all.size)
		val list = all.subList(from, to)
		return resolveListCovers(list)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		if (!manga.chapters.isNullOrEmpty()) return manga

		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDocument(fullUrl)
		val slug = extractSlugFromMangaUrl(manga.url).ifBlank {
			extractSlugFromMangaUrl(manga.publicUrl)
		}
		val containers = extractNextJsObjects(doc)
		val detailsJson = findMangaDetailsObject(containers, slug)

		if (detailsJson == null) {
			val resolvedCover = manga.coverUrl
				?.takeIf { isCoverUrlFresh(it) }
				?: normalizeCoverUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
				?: manga.coverUrl
			if (!slug.isBlank() && !resolvedCover.isNullOrBlank()) {
				putCoverCache(slug, resolvedCover)
			}
			return manga.copy(
				description = manga.description ?: doc.selectFirst("meta[name=description]")?.attr("content"),
				coverUrl = resolvedCover,
			)
		}

		val parsed = parseMangaCache(detailsJson)?.manga
		val chapterSlug = extractSlugFromMangaJson(detailsJson).ifBlank { slug }
		val chapters = extractChapters(detailsJson, chapterSlug)
		val resolvedCover = parsed?.coverUrl
			?.takeIf { isCoverUrlFresh(it) }
			?: normalizeCoverUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
			?: manga.coverUrl
		if (!slug.isBlank() && !resolvedCover.isNullOrBlank()) {
			putCoverCache(slug, resolvedCover)
		}

		return manga.copy(
			title = parsed?.title ?: manga.title,
			altTitles = parsed?.altTitles ?: manga.altTitles,
			rating = parsed?.rating ?: manga.rating,
			contentRating = parsed?.contentRating ?: manga.contentRating,
			coverUrl = resolvedCover,
			tags = parsed?.tags?.takeIf { it.isNotEmpty() } ?: manga.tags,
			state = parsed?.state ?: manga.state,
			authors = parsed?.authors?.takeIf { it.isNotEmpty() } ?: manga.authors,
			description = parsed?.description ?: manga.description ?: doc.selectFirst("meta[name=description]")?.attr("content"),
			chapters = chapters.ifEmpty { manga.chapters.orEmpty() },
		)
	}

	private suspend fun resolveListCovers(list: List<Manga>): List<Manga> = coroutineScope {
		if (list.isEmpty()) return@coroutineScope list

		val now = System.currentTimeMillis()
		var result: MutableList<Manga>? = null
		fun mutableResult(): MutableList<Manga> {
			val current = result
			if (current != null) return current
			val created = list.toMutableList()
			result = created
			return created
		}
		val toResolve = ArrayList<Pair<Int, Manga>>(list.size)

		for ((index, manga) in list.withIndex()) {
			val slug = extractSlugFromMangaUrl(manga.url)
			val cached = slug.takeIf { it.isNotBlank() }?.let { coverCacheBySlug[it] }
			if (cached != null && isCoverEntryFresh(cached, now)) {
				if (manga.coverUrl != cached.url) {
					mutableResult()[index] = manga.copy(coverUrl = cached.url)
				}
				continue
			}
			if (isCoverUrlFresh(manga.coverUrl, now)) {
				if (slug.isNotBlank() && !manga.coverUrl.isNullOrBlank()) {
					putCoverCache(slug, manga.coverUrl, now)
				}
				continue
			}
			if (slug.isNotBlank() && (coverRetryAfterBySlug[slug] ?: 0L) > now) {
				continue
			}
			toResolve += index to manga
		}

		if (toResolve.isEmpty()) return@coroutineScope result ?: list

		val semaphore = Semaphore(coverResolveParallelism)
		toResolve.map { (index, manga) ->
			async {
				semaphore.withPermit {
					index to resolveCoverFromDetailsPage(manga)
				}
			}
		}.awaitAll().forEach { (index, resolved) ->
			if (resolved != list[index]) {
				mutableResult()[index] = resolved
			}
		}

		result ?: list
	}

	private suspend fun resolveCoverFromDetailsPage(manga: Manga): Manga {
		val slug = extractSlugFromMangaUrl(manga.url)
		if (slug.isNotBlank()) {
			coverCacheBySlug[slug]
				?.takeIf { isCoverEntryFresh(it) }
				?.let { return manga.copy(coverUrl = it.url) }
		}
		if (isCoverUrlFresh(manga.coverUrl)) return manga

		val doc = try {
			fetchDocument(manga.url.toAbsoluteUrl(domain))
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			if (slug.isNotBlank()) {
				coverRetryAfterBySlug[slug] = System.currentTimeMillis() + coverRetryDelayMs
			}
			return manga
		}

		val refreshed = normalizeCoverUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
			?: run {
				if (slug.isNotBlank()) {
					coverRetryAfterBySlug[slug] = System.currentTimeMillis() + coverRetryDelayMs
				}
				return manga
			}
		if (slug.isNotBlank()) {
			putCoverCache(slug, refreshed)
		}
		return manga.copy(coverUrl = refreshed)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDocument(chapter.url.toAbsoluteUrl(domain))
		val containers = extractNextJsObjects(doc)
		val urls = LinkedHashSet<String>()

		var chapterArray: JSONArray? = null
		var chapterCount = 0
		for (container in containers) {
			val candidate = findChapterImagesArray(container) ?: continue
			if (candidate.count > chapterCount) {
				chapterCount = candidate.count
				chapterArray = candidate.array
			}
		}

		if (chapterArray != null) {
			for (index in 0 until chapterArray.length()) {
				extractImageUrlFromArrayItem(chapterArray.opt(index))?.let { urls += it }
			}
		}

		var bestArray: JSONArray? = null
		var bestCount = 0
		if (urls.isEmpty()) {
			for (container in containers) {
				val candidate = findBestImagesArray(container) ?: continue
				if (candidate.count > bestCount) {
					bestCount = candidate.count
					bestArray = candidate.array
				}
			}
		}

		if (urls.isEmpty() && bestArray != null) {
			for (index in 0 until bestArray.length()) {
				extractImageUrlFromArrayItem(bestArray.opt(index))?.let { urls += it }
			}
		}

		if (urls.isEmpty()) {
			urls += extractImageUrlsFromChapterHtml(doc)
		}

		return urls.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = chapter.source,
			)
		}
	}

	private suspend fun getFilteredAndSortedList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		var sequence = allMangaCache.get().asSequence()
		val requiredTags = filter.tags
		val excludedTags = filter.tagsExclude

		val query = filter.query?.trim().takeUnless { it.isNullOrBlank() }
		if (query != null) {
			sequence = sequence.filter { mangaCache ->
				mangaCache.manga.title.contains(query, ignoreCase = true) ||
					mangaCache.manga.altTitles.any { it.contains(query, ignoreCase = true) }
			}
		}

		val author = filter.author?.trim().takeUnless { it.isNullOrBlank() }
		if (author != null) {
			sequence = sequence.filter { mangaCache ->
				mangaCache.manga.authors.any { it.contains(author, ignoreCase = true) }
			}
		}

		if (filter.states.isNotEmpty()) {
			sequence = sequence.filter { filter.states.contains(it.manga.state) }
		}

		if (filter.types.isNotEmpty()) {
			sequence = sequence.filter { filter.types.contains(it.type) }
		}

		if (requiredTags.isNotEmpty()) {
			sequence = sequence.filter { mangaCache ->
				requiredTags.all { requiredTag -> mangaCache.manga.tags.contains(requiredTag) }
			}
		}

		if (excludedTags.isNotEmpty()) {
			sequence = sequence.filter { mangaCache ->
				mangaCache.manga.tags.none { blockedTag -> excludedTags.contains(blockedTag) }
			}
		}

		val cache = sequence.filter { it.chaptersCount > 0 }.toList()

		val sorted = when (order) {
			SortOrder.UPDATED -> cache.sortedByDescending { it.updatedAt }
			SortOrder.UPDATED_ASC -> cache.sortedBy { it.updatedAt }
			SortOrder.ALPHABETICAL -> cache.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.manga.title })
			SortOrder.ALPHABETICAL_DESC -> cache.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.manga.title })
			SortOrder.POPULARITY -> cache.sortedByDescending { it.popularity }
			SortOrder.POPULARITY_ASC -> cache.sortedBy { it.popularity }
			SortOrder.RATING -> cache.sortedByDescending { it.manga.rating }
			SortOrder.RATING_ASC -> cache.sortedBy { it.manga.rating }
			else -> cache
		}

		return sorted.map { it.manga }
	}

	private suspend fun fetchCatalogMangaList(): List<MangaCache> {
		val doc = fetchDocument("https://$domain/catalog")
		val containers = extractNextJsObjects(doc)
		val mangaBySlug = LinkedHashMap<String, MangaCache>()
		for (mangaJson in extractMangaObjects(containers)) {
			val parsed = parseMangaCache(mangaJson) ?: continue
			val slug = extractSlugFromMangaUrl(parsed.manga.url)
			val previous = mangaBySlug[slug]
			mangaBySlug[slug] = if (previous == null) {
				parsed
			} else {
				mergeMangaCache(previous, parsed)
			}
		}
		val htmlMangaBySlug = extractMangaCardsFromHtml(doc).associateBy { extractSlugFromMangaUrl(it.url) }
		for ((slug, htmlManga) in htmlMangaBySlug) {
			val existing = mangaBySlug[slug]
			if (existing == null) {
				mangaBySlug[slug] = MangaCache(
					manga = htmlManga,
					popularity = 0,
					updatedAt = 0L,
					type = ContentType.MANGA,
					chaptersCount = 1,
				)
				continue
			}
			if (!htmlManga.coverUrl.isNullOrBlank() && htmlManga.coverUrl != existing.manga.coverUrl) {
				mangaBySlug[slug] = existing.copy(
					manga = existing.manga.copy(coverUrl = htmlManga.coverUrl),
				)
			}
		}
		if (mangaBySlug.isNotEmpty()) {
			return mangaBySlug.values.toList()
		}
		return extractMangaCardsFromHtml(doc).map { manga ->
			MangaCache(
				manga = manga,
				popularity = 0,
				updatedAt = 0L,
				type = ContentType.MANGA,
				chaptersCount = 1,
			)
		}
	}

	private fun extractMangaCardsFromHtml(doc: Document): List<Manga> {
		data class Draft(
			var title: String? = null,
			var coverUrl: String? = null,
		)

		val draftsBySlug = LinkedHashMap<String, Draft>()
		for (link in doc.select("a[href^=/manga/]")) {
			val href = link.attr("href")
				.substringBefore('?')
				.substringBefore('#')
			if (href.contains("/chapter/")) continue
			val slug = extractSlugFromMangaUrl(href)
			if (slug.isBlank()) continue

			val draft = draftsBySlug.getOrPut(slug) { Draft() }

			val titleCandidate = link.attr("title").trim()
				.takeIf { it.isNotEmpty() }
				?: link.attr("aria-label").trim().takeIf { it.isNotEmpty() }
				?: link.selectFirst("h1,h2,h3,h4")?.text()?.trim().takeIf { !it.isNullOrEmpty() }
				?: link.selectFirst("p,span")?.text()?.trim().takeIf { !it.isNullOrEmpty() }
				?: link.ownText().trim().takeIf { it.isNotEmpty() }
				?: link.text().trim().takeIf { it.isNotEmpty() }
			if (!titleCandidate.isNullOrBlank()) {
				if (draft.title.isNullOrBlank() || titleCandidate.length > draft.title.orEmpty().length) {
					draft.title = titleCandidate
				}
			}

			val img = link.selectFirst("img")
			val srcsetCandidate = img?.attr("srcset")
				?.takeIf { it.isNotBlank() }
				?.substringAfterLast(',')
				?.trim()
				?.substringBefore(' ')
				?.trim()
				?.takeIf { it.isNotEmpty() }
			val coverCandidate = normalizeCoverUrl(
				srcsetCandidate
					?: img?.attr("src")
					?: img?.attr("data-src")
					?: img?.attr("data-original"),
			)
			if (!coverCandidate.isNullOrBlank()) {
				draft.coverUrl = coverCandidate
			}
		}

		val mangaByUrl = LinkedHashMap<String, Manga>()
		for ((slug, draft) in draftsBySlug) {
			val title = draft.title ?: continue
			val url = "/manga/$slug"
			mangaByUrl.putIfAbsent(
				url,
				Manga(
					id = generateUid(url),
					title = title,
					altTitles = emptySet(),
					url = url,
					publicUrl = url.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = draft.coverUrl,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				),
			)
		}
		return mangaByUrl.values.toList()
	}

	private fun parseMangaCache(mangaJson: JSONObject): MangaCache? {
		val slug = extractSlugFromMangaJson(mangaJson).takeIf { it.isNotBlank() } ?: return null
		val title = mangaJson.getStringOrNull("title")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
		val url = "/manga/$slug"

		val ratingRaw = mangaJson.optDouble("avgNote", 0.0).toFloat()
		val rating = if (ratingRaw > 0f) {
			(ratingRaw / 5f).coerceIn(0f, 1f)
		} else {
			RATING_UNKNOWN
		}

		val tags = parseTags(mangaJson.optJSONArray("genres"))
		val authors = parseNames(mangaJson.optJSONArray("authors")) + parseNames(mangaJson.optJSONArray("artists"))
		val state = parseStatus(mangaJson.getStringOrNull("status"))
		val type = parseType(mangaJson.getStringOrNull("type"))
		val description = mangaJson.getStringOrNull("description")
			?.takeIf { it.isNotBlank() && it != "null" }
		val contentRating = if (mangaJson.optBoolean("isAdult", false)) ContentRating.ADULT else null
		val coverUrl = normalizeCoverUrl(extractCoverUrl(mangaJson))

		val chaptersCount = mangaJson.optJSONObject("_count")?.optInt("chapters", -1)?.takeIf { it >= 0 }
			?: mangaJson.optJSONArray("chapters")?.length()
			?: 0
		val chapters = mangaJson.optJSONArray("chapters")
		var chapterViews = 0
		if (chapters != null) {
			for (index in 0 until chapters.length()) {
				val chapterJson = chapters.optJSONObject(index) ?: continue
				chapterViews += chapterJson.optInt("views", 0).coerceAtLeast(0)
			}
		}
		val popularity = mangaJson.optInt("views", 0).takeIf { it > 0 }
			?: mangaJson.optJSONObject("_count")?.optInt("favorites", 0)?.takeIf { it > 0 }
			?: chapterViews.takeIf { it > 0 }
			?: chaptersCount

		var latestChapterDate = maxOf(
			parseDate(mangaJson.getStringOrNull("updatedAt")),
			parseDate(mangaJson.getStringOrNull("publishDate")),
			parseDate(mangaJson.getStringOrNull("createdAt")),
		)
		if (chapters != null) {
			for (index in 0 until chapters.length()) {
				val chapterJson = chapters.optJSONObject(index) ?: continue
				val chapterDate = maxOf(
					parseDate(chapterJson.getStringOrNull("publishDate")),
					parseDate(chapterJson.getStringOrNull("createdAt")),
					parseDate(chapterJson.getStringOrNull("updatedAt")),
				)
				if (chapterDate > latestChapterDate) {
					latestChapterDate = chapterDate
				}
			}
		}

		return MangaCache(
			manga = Manga(
				id = generateUid(url),
				title = title,
				altTitles = emptySet(),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				rating = rating,
				contentRating = contentRating,
				coverUrl = coverUrl,
				tags = tags,
				state = state,
				authors = authors,
				description = description,
				source = source,
			),
			popularity = popularity,
			updatedAt = latestChapterDate,
			type = type,
			chaptersCount = chaptersCount,
		)
	}

	private fun mergeMangaCache(first: MangaCache, second: MangaCache): MangaCache {
		val mergedManga = first.manga.copy(
			title = if (first.manga.title.length >= second.manga.title.length) first.manga.title else second.manga.title,
			altTitles = (first.manga.altTitles + second.manga.altTitles).toSet(),
			rating = when {
				first.manga.rating == RATING_UNKNOWN -> second.manga.rating
				second.manga.rating == RATING_UNKNOWN -> first.manga.rating
				else -> maxOf(first.manga.rating, second.manga.rating)
			},
			contentRating = first.manga.contentRating ?: second.manga.contentRating,
			coverUrl = first.manga.coverUrl ?: second.manga.coverUrl,
			tags = (first.manga.tags + second.manga.tags).toSet(),
			state = first.manga.state ?: second.manga.state,
			authors = (first.manga.authors + second.manga.authors).toSet(),
			description = when {
				first.manga.description.isNullOrBlank() -> second.manga.description
				second.manga.description.isNullOrBlank() -> first.manga.description
				first.manga.description.length >= second.manga.description.length -> first.manga.description
				else -> second.manga.description
			},
		)
		return MangaCache(
			manga = mergedManga,
			popularity = maxOf(first.popularity, second.popularity),
			updatedAt = maxOf(first.updatedAt, second.updatedAt),
			type = if (first.type != ContentType.MANGA) first.type else second.type,
			chaptersCount = maxOf(first.chaptersCount, second.chaptersCount),
		)
	}

	private fun extractMangaObjects(containers: List<JSONObject>): List<JSONObject> {
		val bySlug = LinkedHashMap<String, JSONObject>()
		for (container in containers) {
			collectMangaObjects(container, bySlug)
		}
		return bySlug.values.toList()
	}

	private fun collectMangaObjects(node: Any?, out: MutableMap<String, JSONObject>) {
		when (node) {
			is JSONObject -> {
				if (looksLikeMangaObject(node)) {
					val slug = extractSlugFromMangaJson(node)
					if (slug.isNotBlank()) {
						val previous = out[slug]
						out[slug] = if (previous == null || mangaJsonScore(node) >= mangaJsonScore(previous)) {
							node
						} else {
							previous
						}
					}
				}
				val keys = node.keys()
				while (keys.hasNext()) {
					collectMangaObjects(node.opt(keys.next()), out)
				}
			}
			is JSONArray -> {
				for (index in 0 until node.length()) {
					collectMangaObjects(node.opt(index), out)
				}
			}
		}
	}

	private fun looksLikeMangaObject(node: JSONObject): Boolean {
		return !node.getStringOrNull("title").isNullOrBlank() && !extractSlugFromMangaJson(node).isBlank()
	}

	private fun mangaJsonScore(node: JSONObject): Int {
		var score = 0
		if (!extractCoverUrl(node).isNullOrBlank()) score += 25
		if (!node.getStringOrNull("description").isNullOrBlank()) score += 10
		if (!node.getStringOrNull("updatedAt").isNullOrBlank()) score += 12
		if (!node.getStringOrNull("publishDate").isNullOrBlank()) score += 6
		if (!node.getStringOrNull("createdAt").isNullOrBlank()) score += 4

		val chaptersLength = node.optJSONArray("chapters")?.length() ?: 0
		score += chaptersLength.coerceAtMost(200)
		if (chaptersLength > 0) score += 20

		val countChapters = node.optJSONObject("_count")?.optInt("chapters", 0) ?: 0
		score += countChapters.coerceAtMost(100)

		if (node.optDouble("avgNote", 0.0) > 0.0) score += 10
		if (node.optInt("views", 0) > 0) score += 10

		if ((node.optJSONArray("genres")?.length() ?: 0) > 0) score += 6
		if ((node.optJSONArray("authors")?.length() ?: 0) > 0) score += 6
		if ((node.optJSONArray("artists")?.length() ?: 0) > 0) score += 6

		return score
	}

	private fun findMangaDetailsObject(containers: List<JSONObject>, slug: String): JSONObject? {
		for (container in containers) {
			container.optJSONObject("manga")?.takeIf { matchesSlug(it, slug) }?.let { return it }
			container.optJSONObject("initialData")
				?.optJSONObject("manga")
				?.takeIf { matchesSlug(it, slug) }
				?.let { return it }
		}

		for (container in containers) {
			findMangaObjectBySlug(container, slug)?.let { return it }
		}

		for (container in containers) {
			findFirstDetailedMangaObject(container)?.let { return it }
		}
		return null
	}

	private fun findMangaObjectBySlug(node: Any?, slug: String): JSONObject? {
		when (node) {
			is JSONObject -> {
				if (matchesSlug(node, slug)) return node
				val keys = node.keys()
				while (keys.hasNext()) {
					findMangaObjectBySlug(node.opt(keys.next()), slug)?.let { return it }
				}
			}
			is JSONArray -> {
				for (index in 0 until node.length()) {
					findMangaObjectBySlug(node.opt(index), slug)?.let { return it }
				}
			}
		}
		return null
	}

	private fun findFirstDetailedMangaObject(node: Any?): JSONObject? {
		when (node) {
			is JSONObject -> {
				if (looksLikeMangaObject(node) && (node.optJSONArray("chapters")?.length() ?: 0) > 0) return node
				val keys = node.keys()
				while (keys.hasNext()) {
					findFirstDetailedMangaObject(node.opt(keys.next()))?.let { return it }
				}
			}
			is JSONArray -> {
				for (index in 0 until node.length()) {
					findFirstDetailedMangaObject(node.opt(index))?.let { return it }
				}
			}
		}
		return null
	}

	private fun matchesSlug(mangaJson: JSONObject, slug: String): Boolean {
		if (slug.isBlank()) return false
		return extractSlugFromMangaJson(mangaJson).equals(slug, ignoreCase = true)
	}

	private fun extractChapters(mangaJson: JSONObject, slug: String): List<MangaChapter> {
		if (slug.isBlank()) return emptyList()
		val chapters = mangaJson.optJSONArray("chapters") ?: return emptyList()
		val list = ArrayList<MangaChapter>(chapters.length())
		for (index in 0 until chapters.length()) {
			val chapterJson = chapters.optJSONObject(index) ?: continue
			val chapterId = chapterJson.getStringOrNull("id")?.takeIf { it.isNotBlank() } ?: continue
			val chapterNumber = parseChapterNumber(chapterJson.opt("orderId"), index + 1)
			val chapterName = chapterJson.getStringOrNull("name")
				?.takeIf { it.isNotBlank() && it != "null" }
			val chapterTitle = if (chapterName != null) {
				"Chapitre ${formatChapterNumber(chapterNumber)} - $chapterName"
			} else {
				"Chapitre ${formatChapterNumber(chapterNumber)}"
			}
			val chapterUrl = "/manga/$slug/chapter/$chapterId"

			list += MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				number = chapterNumber,
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = parseDate(chapterJson.getStringOrNull("publishDate"))
					.coerceAtLeast(parseDate(chapterJson.getStringOrNull("createdAt"))),
				branch = null,
				source = source,
			)
		}
		return list.distinctBy { it.url }.sortedWith(
			compareBy<MangaChapter> { it.number.toDouble() }
				.thenBy { it.uploadDate }
				.thenBy { it.title },
		)
	}

	private fun parseChapterNumber(raw: Any?, fallback: Int): Float {
		return when (raw) {
			is Number -> raw.toFloat()
			is String -> raw.toFloatOrNull() ?: fallback.toFloat()
			else -> fallback.toFloat()
		}
	}

	private fun formatChapterNumber(number: Float): String {
		val rounded = number.toInt()
		return if (number == rounded.toFloat()) {
			rounded.toString()
		} else {
			number.toString()
		}
	}

	private fun parseStatus(raw: String?): MangaState? {
		return when (raw?.trim()?.lowercase(sourceLocale)) {
			"on_going", "ongoing", "en cours" -> MangaState.ONGOING
			"finished", "completed", "terminé", "termine" -> MangaState.FINISHED
			"paused", "hiatus", "en pause" -> MangaState.PAUSED
			"abandoned", "cancelled", "annulé", "annule" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseType(raw: String?): ContentType {
		return when (raw?.trim()?.lowercase(sourceLocale)) {
			"manhwa", "webtoon" -> ContentType.MANHWA
			"manhua" -> ContentType.MANHUA
			"comics", "comic" -> ContentType.COMICS
			else -> ContentType.MANGA
		}
	}

	private fun parseTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		val tags = LinkedHashSet<MangaTag>(array.length())
		for (index in 0 until array.length()) {
			val tagName = array.optJSONObject(index)?.getStringOrNull("name")
				?.trim()
				?.takeIf { it.isNotBlank() }
				?: continue
			tags.add(
				MangaTag(
					key = tagName,
					title = tagName,
					source = source,
				),
			)
		}
		return tags
	}

	private fun parseNames(array: JSONArray?): Set<String> {
		if (array == null) return emptySet()
		val names = LinkedHashSet<String>(array.length())
		for (index in 0 until array.length()) {
			val name = array.optJSONObject(index)?.getStringOrNull("name")
				?.trim()
				?.takeIf { it.isNotBlank() }
				?: continue
			names.add(name)
		}
		return names
	}

	private fun extractCoverUrl(mangaJson: JSONObject): String? {
		return mangaJson.optJSONObject("cover")
			?.optJSONObject("image")
			?.getStringOrNull("link")
			?: mangaJson.optJSONObject("cover")?.getStringOrNull("link")
			?: mangaJson.getStringOrNull("coverImage")
			?: mangaJson.getStringOrNull("cover")
	}

	private fun extractSlugFromMangaJson(mangaJson: JSONObject): String {
		return mangaJson.getStringOrNull("urlId")
			?.takeIf { it.isNotBlank() }
			?: mangaJson.getStringOrNull("slug")
				?.takeIf { it.isNotBlank() }
			?: ""
	}

	private fun extractSlugFromMangaUrl(url: String): String {
		val tail = url.substringAfter("/manga/", "").trim()
		if (tail.isEmpty()) return ""
		return tail.substringBefore('/').substringBefore('?').substringBefore('#')
	}

	private fun parseDate(rawDate: String?): Long {
		if (rawDate.isNullOrBlank()) return 0L
		val cleaned = rawDate.removePrefix("\"")
			.removeSuffix("\"")
			.removePrefix("\$D")
			.removePrefix("D")
			.trim()
		return synchronized(isoDateFormat) {
			isoDateFormat.parseSafe(cleaned)
		}
	}

	private fun putCoverCache(slug: String, url: String, now: Long = System.currentTimeMillis()) {
		coverCacheBySlug[slug] = CoverCacheEntry(
			url = url,
			validUntil = computeCoverExpiry(url, now),
		)
		coverRetryAfterBySlug.remove(slug)
	}

	private fun isCoverEntryFresh(entry: CoverCacheEntry, now: Long = System.currentTimeMillis()): Boolean {
		return entry.validUntil == Long.MAX_VALUE || entry.validUntil - now > 60_000L
	}

	private fun isCoverUrlFresh(url: String?, now: Long = System.currentTimeMillis()): Boolean {
		if (url.isNullOrBlank()) return false
		val expiresAt = computeCoverExpiry(url, now)
		return expiresAt == Long.MAX_VALUE || expiresAt - now > 60_000L
	}

	private fun computeCoverExpiry(url: String, now: Long = System.currentTimeMillis()): Long {
		val http = url.toHttpUrlOrNull() ?: return Long.MAX_VALUE
		if (!http.host.contains("wasabisys.com")) return Long.MAX_VALUE
		if (http.queryParameter("X-Amz-Signature").isNullOrBlank()) return 0L

		val dateRaw = http.queryParameter("X-Amz-Date").orEmpty()
		val expiresSeconds = http.queryParameter("X-Amz-Expires")?.toLongOrNull() ?: return 0L
		val issuedAt = synchronized(amzDateFormat) {
			amzDateFormat.parseSafe(dateRaw)
		}
		if (issuedAt <= 0L) return 0L
		return (issuedAt + expiresSeconds * 1000L).coerceAtLeast(now)
	}

	private suspend fun fetchDocument(url: String): Document {
		return try {
			val doc = webClient.httpGet(url).parseHtml()
			if (isCloudflareChallengePage(doc)) {
				context.requestBrowserAction(this, url)
			}
			doc
		} catch (e: HttpStatusException) {
			handleCloudflare(e)
		}
	}

	private fun handleCloudflare(e: HttpStatusException): Nothing {
		if (e.statusCode == HttpURLConnection.HTTP_FORBIDDEN || e.statusCode == HttpURLConnection.HTTP_UNAVAILABLE) {
			context.requestBrowserAction(this, e.url)
		}
		throw e
	}

	private fun isCloudflareChallengePage(doc: Document): Boolean {
		val title = doc.title()
		return title == "Just a moment..."
			|| title == "Attention Required! | Cloudflare"
			|| doc.getElementById("challenge-error-title") != null
			|| doc.getElementById("challenge-error-text") != null
			|| doc.selectFirst("form#challenge-form") != null
			|| doc.selectFirst("iframe[src*='challenges.cloudflare.com']") != null
			|| doc.selectFirst("div.cf-turnstile") != null
			|| doc.selectFirst("div#turnstile-wrapper") != null
			|| doc.getElementById("cf-wrapper") != null
	}

	private fun extractNextJsObjects(document: Document): List<JSONObject> {
		val objects = ArrayList<JSONObject>()
		val seen = HashSet<String>()

		for (script in document.select("script")) {
			val scriptContent = script.data()
			if (!scriptContent.contains("self.__next_f.push")) continue

			val matches = nextFPushRegex.findAll(scriptContent)
			for (match in matches) {
				if (match.groupValues.size < 2) continue
				val rawData = match.groupValues[1]
				val cleanedData = rawData
					.replace("\\\\", "\\")
					.replace("\\\"", "\"")
				val seenStarts = HashSet<Int>()

				for (pattern in nextJsPayloadAnchorPatterns) {
					var index = -1
					while (true) {
						index = cleanedData.indexOf(pattern, startIndex = index + 1)
						if (index == -1) break
						val objectStart = findJsonObjectStart(cleanedData, index)
						if (objectStart == -1 || !seenStarts.add(objectStart)) continue
						val jsonString = extractJsonObjectString(cleanedData, objectStart) ?: continue
						val parsed = runCatching { JSONObject(jsonString) }.getOrNull() ?: continue
						if (seen.add(jsonString)) {
							objects += parsed
						}
					}
				}
			}
		}
		return objects
	}

	private fun findJsonObjectStart(data: String, fromIndex: Int): Int {
		var braceDepth = 0
		for (index in fromIndex downTo 0) {
			when (data[index]) {
				'}' -> braceDepth++
				'{' -> {
					if (braceDepth == 0) return index
					braceDepth--
				}
			}
		}
		return -1
	}

	private fun extractJsonObjectString(data: String, startIndex: Int): String? {
		if (startIndex < 0 || startIndex >= data.length || data[startIndex] != '{') return null
		var balance = 1
		var inString = false
		var index = startIndex + 1
		while (index < data.length) {
			when (val ch = data[index]) {
				'\\' -> if (inString) index++
				'"' -> inString = !inString
				'{' -> if (!inString) balance++
				'}' -> if (!inString) {
					balance--
					if (balance == 0) return data.substring(startIndex, index + 1)
				}
				else -> if (ch == '\n' && !inString) {
					// no-op
				}
			}
			index++
		}
		return null
	}

	private fun findBestImagesArray(node: Any?): JsonArrayCandidate? {
		var best: JSONArray? = null
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
						best = value
					}
					for (index in 0 until value.length()) {
						visit(value.opt(index))
					}
				}
			}
		}

		visit(node)
		val bestArray = best ?: return null
		return if (bestCount > 0) JsonArrayCandidate(bestArray, bestCount) else null
	}

	private fun findChapterImagesArray(node: Any?): JsonArrayCandidate? {
		var best: JSONArray? = null
		var bestCount = 0

		fun considerChapter(chapter: JSONObject?) {
			val images = chapter?.optJSONArray("images") ?: return
			val count = countImageEntries(images)
			if (count > bestCount) {
				bestCount = count
				best = images
			}
		}

		fun visit(value: Any?) {
			when (value) {
				is JSONObject -> {
					considerChapter(value.optJSONObject("chapter"))
					val keys = value.keys()
					while (keys.hasNext()) {
						visit(value.opt(keys.next()))
					}
				}
				is JSONArray -> {
					for (index in 0 until value.length()) {
						visit(value.opt(index))
					}
				}
			}
		}

		visit(node)
		val bestArray = best ?: return null
		return if (bestCount > 0) JsonArrayCandidate(bestArray, bestCount) else null
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
		return when (item) {
			is JSONObject -> {
				checkPageImageUrl(item.getStringOrNull("originalUrl"))
					?: checkPageImageUrl(item.getStringOrNull("url"))
					?: checkPageImageUrl(item.getStringOrNull("src"))
					?: checkPageImageUrl(item.getStringOrNull("link"))
					?: checkPageImageUrl(item.getStringOrNull("path"))
					?: run {
						val nestedImage = item.optJSONObject("image")
						if (nestedImage != null) {
							checkPageImageUrl(nestedImage.getStringOrNull("originalUrl"))
								?: checkPageImageUrl(nestedImage.getStringOrNull("url"))
								?: checkPageImageUrl(nestedImage.getStringOrNull("src"))
								?: checkPageImageUrl(nestedImage.getStringOrNull("link"))
						} else {
							null
						}
					}
			}
			is String -> {
				checkPageImageUrl(item)
			}
			else -> null
		}
	}

	private fun checkPageImageUrl(raw: String?): String? {
		val normalized = normalizeMediaUrl(raw) ?: return null
		return if (looksLikePageImageUrl(normalized)) normalized else null
	}

	private fun extractImageUrlsFromChapterHtml(doc: Document): List<String> {
		val urls = LinkedHashSet<String>()
		val candidates = LinkedHashSet<String>()

		for (element in doc.select("img[src],img[data-src],img[data-original],a[href],source[srcset]")) {
			element.attr("src").takeIf { it.isNotBlank() }?.let { candidates += it }
			element.attr("data-src").takeIf { it.isNotBlank() }?.let { candidates += it }
			element.attr("data-original").takeIf { it.isNotBlank() }?.let { candidates += it }
			element.attr("href").takeIf { it.isNotBlank() }?.let { candidates += it }
			element.attr("srcset").takeIf { it.isNotBlank() }?.let { srcSet ->
				for (part in srcSet.splitToSequence(',')) {
					val src = part.trim().substringBefore(' ').trim()
					if (src.isNotEmpty()) {
						candidates += src
					}
				}
			}
		}

		for (candidate in candidates) {
			val normalized = normalizeMediaUrl(candidate) ?: continue
			if (looksLikePageImageUrl(normalized)) {
				urls += normalized
			}
		}
		return urls.toList()
	}

	private fun normalizeMediaUrl(rawUrl: String?): String? {
		var value = rawUrl?.trim()
			?.replace("\\/", "/")
			?.takeIf { it.isNotBlank() && it != "null" }
			?: return null
		if (value.startsWith("data:image", ignoreCase = true)) return null

		val absoluteRaw = value.toAbsoluteUrl(domain)
		val nextImageUrl = absoluteRaw.toHttpUrlOrNull()
			?.takeIf { it.encodedPath.endsWith("/_next/image") }
			?.queryParameter("url")
		if (!nextImageUrl.isNullOrBlank()) {
			value = nextImageUrl
		}

		if (value.startsWith("s3:", ignoreCase = true)) {
			value = "https://s3.eu-west-2.wasabisys.com/astral-bucket/" + value.removePrefix("s3:")
				.trimStart('/')
		}

		val normalized = value.toAbsoluteUrl(domain)
		val lower = normalized.lowercase(Locale.ROOT)
		if (lower.contains("/images/no_image")) return null
		if (lower.contains("/images/logo")) return null
		if (lower.contains("/icons/")) return null
		if (lower.contains("/favicon")) return null

		val fixed = if (normalized.toHttpUrlOrNull() != null) {
			normalized
		} else {
			normalized.replace(" ", "%20")
		}
		return fixed
	}

	private fun normalizeCoverUrl(rawUrl: String?): String? {
		val raw = rawUrl?.trim()
			?.replace("\\/", "/")
			?.takeIf { it.isNotBlank() && it != "null" }
			?: return null
		if (raw.startsWith("data:image", ignoreCase = true)) return null

		var value = raw
		val absoluteRaw = raw.toAbsoluteUrl(domain)
		val nextImageInner = absoluteRaw.toHttpUrlOrNull()
			?.takeIf { it.encodedPath.endsWith("/_next/image") }
			?.queryParameter("url")
			?.takeIf { it.isNotBlank() }
		if (nextImageInner != null) {
			value = nextImageInner
		}

		val absoluteValue = value.toAbsoluteUrl(domain)
		val lowerRaw = absoluteValue.lowercase(Locale.ROOT)
		if (lowerRaw.contains("/images/no_image")) return null
		if (lowerRaw.contains("/images/logo")) return null
		if (lowerRaw.contains("/icons/")) return null
		if (lowerRaw.contains("/favicon")) return null

		// Raw S3 pseudo-paths from JSON are not directly fetchable without a signed URL.
		if (value.startsWith("s3:", ignoreCase = true)) {
			return null
		}

		// Wasabi bucket links work only when pre-signed.
		val absoluteHttp = absoluteValue.toHttpUrlOrNull()
		if (absoluteHttp != null && absoluteHttp.host.contains("wasabisys.com")) {
			return if (absoluteHttp.queryParameter("X-Amz-Signature").isNullOrBlank()) null else normalizeMediaUrl(value)
		}

		return normalizeMediaUrl(value)
	}

	private fun looksLikePageImageUrl(value: String): Boolean {
		val lower = value.lowercase(Locale.ROOT)
		if (lower.contains("/uploads/projects/")) return true
		if (lower.contains("/api/chapters/")) return true
		if (lower.contains("s3.eu-west-2.wasabisys.com/astral-bucket/")) return true
		return PAGE_IMAGE_URL_REGEX.matches(lower)
	}

	private companion object {
		private val PAGE_IMAGE_URL_REGEX = Regex(""".*\.(jpg|jpeg|png|webp|avif|gif)(\?.*)?$""")
	}
}
