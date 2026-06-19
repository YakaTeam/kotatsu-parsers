package org.koitharu.kotatsu.parsers.site.fr

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.Locale

@MangaSourceParser("MANGAMOINS", "MangaMoins", "fr")
internal class MangaMoins(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAMOINS, API_LIMIT) {

	override val configKeyDomain = ConfigKey.Domain("mangamoins.com")
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)
	override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
	private val detailsCache = object : LinkedHashMap<String, Manga>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean {
			return size > DETAILS_CACHE_SIZE
		}
	}
	private val pagesCache = object : LinkedHashMap<String, List<MangaPage>>(128, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaPage>>?): Boolean {
			return size > PAGES_CACHE_SIZE
		}
	}
	@Volatile
	private var sessionWarmUpTime = 0L

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = HttpUrl.Builder()
			.scheme("https")
			.host(domain)
			.addPathSegments("api/v1/mangas")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", API_LIMIT.toString())
			.apply {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
					addQueryParameter("q", q)
				}
			}
			.build()
		val json = fetchApiJson(url)
		if (json.optString("status").equals("error", ignoreCase = true)) {
			throw ParseException(json.optString("message"), url.toString())
		}
		val data = json.optJSONArray("data") ?: return emptyList()
		val list = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val jo = data.optJSONObject(i) ?: continue
			val title = decodeHtml(jo.optString("title"))
			if (title.isEmpty()) continue
			val slug = jo.optString("mangaSlug").trim().ifEmpty { title.toMangaSlug() }
			val coverUrl = jo.optString("cover").trim().ifEmpty {
				jo.optString("cover_folder").trim().takeIf { it.isNotEmpty() }?.let {
					"https://$domain/files/scans/$it/thumbnail.webp"
				}.orEmpty()
			}.ifEmpty { null }?.toAbsoluteUrl(domain)
			list += Manga(
				id = generateUid(slug),
				title = title,
				altTitles = emptySet(),
				url = "/manga/$slug",
				publicUrl = "https://$domain/manga/$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		return list
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val cacheKey = buildDetailsCacheKey(manga)
		synchronized(detailsCache) {
			detailsCache[cacheKey]?.let { return it }
		}
		val json = fetchMangaJson(manga)
		val info = json.optJSONObject("info")
		val detailsTitle = decodeHtml(info?.optString("title")).ifBlank { manga.title }
		val chapters = parseChapters(json).ifEmpty { manga.chapters.orEmpty() }
		val description = decodeHtml(info?.optString("description")).ifEmpty { null }
		val authors = parseAuthors(decodeHtml(info?.optString("author"))).ifEmpty { manga.authors }
		val coverUrl = info?.optString("cover").orEmpty().trim().ifEmpty { null }?.toAbsoluteUrl(domain)
		val canonicalSlug = buildDetailsCandidates(manga).firstOrNull() ?: detailsTitle.toMangaSlug()
		val details = manga.copy(
			title = detailsTitle,
			publicUrl = "https://$domain/manga/$canonicalSlug",
			coverUrl = coverUrl ?: manga.coverUrl,
			description = description ?: manga.description,
			state = parseState(info?.optString("status")) ?: manga.state,
			authors = authors,
			chapters = chapters.takeIf { it.isNotEmpty() } ?: manga.chapters,
		)
		synchronized(detailsCache) {
			detailsCache[cacheKey] = details
			detailsCache[canonicalSlug.lowercase(Locale.ROOT)] = details
		}
		return details
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val cacheKey = extractScanFolder(chapter.url) ?: chapter.url.lowercase(Locale.ROOT)
		synchronized(pagesCache) {
			pagesCache[cacheKey]?.let { return it }
		}
		val folder = extractScanFolder(chapter.url)
		if (folder != null) {
			val apiPages = fetchPagesFromApi(folder, chapter.source, chapter.url.toAbsoluteUrl(domain))
			if (apiPages.isNotEmpty()) {
				synchronized(pagesCache) {
					pagesCache[cacheKey] = apiPages
				}
				return apiPages
			}
		}
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val readerPages = parsePagesFromReader(doc, chapter.source)
		if (readerPages.isNotEmpty()) {
			synchronized(pagesCache) {
				pagesCache[cacheKey] = readerPages
			}
			return readerPages
		}
		val preloaded = doc.select("link[rel=preload][as=image]")
			.mapNotNull { sanitizePageUrl(it.attr("href")) }
			.distinct()
		if (preloaded.isNotEmpty()) {
			val pages = preloaded.map { pageUrl ->
				MangaPage(
					id = generateUid(pageUrl),
					url = pageUrl,
					preview = null,
					source = chapter.source,
				)
			}
			synchronized(pagesCache) {
				pagesCache[cacheKey] = pages
			}
			return pages
		}
		val pages = folder?.let { parsePagesFromScript(doc, it, chapter.source) }.orEmpty()
		if (pages.isNotEmpty()) {
			synchronized(pagesCache) {
				pagesCache[cacheKey] = pages
			}
		}
		return pages
	}

	private suspend fun fetchMangaJson(manga: Manga): JSONObject {
		val candidates = buildDetailsCandidates(manga)
		if (candidates.isEmpty()) {
			throw ParseException("Cannot build manga lookup candidates", manga.publicUrl)
		}
		var bestScore = Int.MIN_VALUE
		var bestMatch: JSONObject? = null
		for (title in candidates) {
			val url = HttpUrl.Builder()
				.scheme("https")
				.host(domain)
				.addPathSegments("api/v1/manga")
				.addQueryParameter("manga", title)
				.build()
			val json = try {
				fetchApiJson(url)
			} catch (e: HttpStatusException) {
				if (e.statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
					continue
				} else {
					throw e
				}
			}
			val score = scoreDetailsResponse(json)
			if (score > bestScore) {
				bestScore = score
				bestMatch = json
			}
			if (score >= DETAILS_SCORE_WITH_CHAPTERS) {
				return json
			}
		}
		return bestMatch ?: throw ParseException("Cannot load manga details", manga.publicUrl)
	}

	private fun parseChapters(json: JSONObject): List<MangaChapter> {
		val ja = json.optJSONArray("chapters") ?: return emptyList()
		val result = ArrayList<MangaChapter>(ja.length())
		for (i in ja.length() - 1 downTo 0) {
			val jo = ja.optJSONObject(i) ?: continue
			val slug = jo.optString("slug").trim().ifEmpty { jo.optString("folder").trim() }
			if (slug.isEmpty()) continue
			val chapterNumber = parseChapterNumber(jo.optString("num"), slug)
			val chapterTitle = buildChapterTitle(chapterNumber, decodeHtml(jo.optString("title")))
			result += MangaChapter(
				id = generateUid(slug),
				title = chapterTitle,
				number = chapterNumber,
				volume = 0,
				url = "/scan/$slug",
				scanlator = null,
				uploadDate = jo.optLong("time", 0L) * 1000L,
				branch = null,
				source = source,
			)
		}
		return result.distinctBy { it.url }
	}

	private suspend fun fetchPagesFromApi(slug: String, source: MangaSource, referer: String): List<MangaPage> {
		val url = HttpUrl.Builder()
			.scheme("https")
			.host(domain)
			.addPathSegments("api/v1/scan")
			.addQueryParameter("slug", slug)
			.build()
		val json = fetchApiJson(url, referer)
		val pagesBaseUrl = json.optString("pagesBaseUrl").trim().ifEmpty { return emptyList() }
		val pagesCount = json.optInt("pageNumbers", 0)
		if (pagesCount <= 0) return emptyList()
		val baseUrl = if (pagesBaseUrl.endsWith('/')) pagesBaseUrl else "$pagesBaseUrl/"
		return (1..pagesCount).mapNotNull { page ->
			val pageName = page.toString().padStart(2, '0')
			sanitizePageUrl("$baseUrl$pageName.webp")
		}.distinct().map { pageUrl ->
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun parsePagesFromReader(doc: Document, source: MangaSource): List<MangaPage> {
		val imageUrls = doc.select("img.reader-manga-page[src], #readerContent img[src], main img[src]")
			.mapNotNull { sanitizePageUrl(it.attr("src")) }
			.distinct()
		if (imageUrls.isEmpty()) return emptyList()
		val totalPages = doc.getElementById("readerTotalPages")?.text()?.trim()?.toIntOrNull() ?: imageUrls.size
		val pages = if (imageUrls.size == 1 && totalPages > 1) {
			generateReaderPageUrls(imageUrls.first(), totalPages).ifEmpty { imageUrls }
		} else {
			imageUrls
		}
		return pages.map { pageUrl ->
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun generateReaderPageUrls(firstPageUrl: String, pagesCount: Int): List<String> {
		val url = firstPageUrl.toHttpUrlOrNull() ?: return emptyList()
		val lastSegment = url.pathSegments.lastOrNull() ?: return emptyList()
		val match = PAGE_IMAGE_NAME_REGEX.matchEntire(lastSegment) ?: return emptyList()
		val width = match.groupValues[1].length
		val extension = match.groupValues[2]
		val baseUrl = firstPageUrl.substringBeforeLast('/', missingDelimiterValue = "")
			.takeIf { it.isNotEmpty() }
			?.plus('/')
			?: return emptyList()
		return (1..pagesCount).mapNotNull { page ->
			val pageName = page.toString().padStart(width, '0')
			sanitizePageUrl("$baseUrl$pageName$extension")
		}
	}

	private fun parsePagesFromScript(doc: Document, folder: String, source: MangaSource): List<MangaPage> {
		val scriptData = doc.select("script")
			.firstOrNull { it.data().contains("imageMtimes") }
			?.data()
			.orEmpty()
		val payload = IMAGE_MTIMES_BLOCK.find(scriptData)?.groupValues?.getOrNull(1).orEmpty()
		if (payload.isEmpty()) return emptyList()
		val pairs = IMAGE_MTIMES_ENTRY.findAll(payload)
			.mapNotNull { m ->
				val page = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
				val mtime = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
				page to mtime
			}
			.sortedBy { it.first }
			.mapNotNull { (page, mtime) ->
				val pageName = page.toString().padStart(2, '0')
				sanitizePageUrl("/files/scans/$folder/$pageName.png?v=$mtime")
			}
			.distinct()
		return pairs.map { pageUrl ->
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}.toList()
	}

	private fun sanitizePageUrl(raw: String?): String? {
		val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val absolute = candidate.toAbsoluteUrl(domain)
		val url = absolute.toHttpUrlOrNull() ?: return null
		if (url.queryParameterNames.contains("v") && url.queryParameter("v").isNullOrBlank()) {
			return null
		}
		return url.newBuilder().build().toString()
	}

	private suspend fun fetchApiJson(url: HttpUrl, referer: String = "https://$domain/"): JSONObject {
		var lastError: HttpStatusException? = null
		for (attempt in 0 until API_SESSION_ATTEMPTS) {
			ensureApiSession(force = attempt > 0)
			try {
				val json = webClient.httpGet(url, apiHeaders(referer)).parseJson()
				if (json.isSessionError() && attempt + 1 < API_SESSION_ATTEMPTS) {
					continue
				}
				return json
			} catch (e: HttpStatusException) {
				if (e.statusCode != HttpURLConnection.HTTP_FORBIDDEN || attempt + 1 >= API_SESSION_ATTEMPTS) {
					throw e
				}
				lastError = e
			}
		}
		throw lastError ?: ParseException("Cannot load API response", url.toString())
	}

	private suspend fun ensureApiSession(force: Boolean) {
		val now = System.currentTimeMillis()
		if (!force && now - sessionWarmUpTime < SESSION_REFRESH_INTERVAL) return
		webClient.httpGet("https://$domain/").parseHtml()
		sessionWarmUpTime = now
	}

	private fun apiHeaders(referer: String): Headers = Headers.Builder()
		.add(CommonHeaders.ACCEPT, "application/json, text/plain, */*")
		.add(CommonHeaders.REFERER, referer)
		.add(CommonHeaders.SEC_FETCH_DEST, "empty")
		.add(CommonHeaders.SEC_FETCH_MODE, "cors")
		.add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
		.build()

	private fun JSONObject.isSessionError(): Boolean {
		val error = optString("error")
		return optInt("code") == API_FORBIDDEN_CODE ||
			error.equals("Forbidden", ignoreCase = true) ||
			error.equals("Unauthorized", ignoreCase = true)
	}

	private fun decodeHtml(raw: String?): String {
		return Parser.unescapeEntities(raw.orEmpty().trim(), false).trim()
	}

	private fun parseState(status: String?): MangaState? {
		val value = status?.lowercase(Locale.ROOT)?.trim() ?: return null
		return when {
			"cours" in value -> MangaState.ONGOING
			"term" in value || "fini" in value -> MangaState.FINISHED
			else -> null
		}
	}

	private fun parseAuthors(raw: String?): Set<String> {
		return raw.orEmpty()
			.split(AUTHORS_SPLIT_PATTERN)
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.equals("Auteur Inconnu", ignoreCase = true) }
			.toSet()
	}

	private fun parseChapterNumber(raw: String, folder: String): Float {
		val fromLabel = raw.replace("#", "").trim().toFloatOrNull()
		if (fromLabel != null) return fromLabel
		val fromFolder = CHAPTER_NUMBER_REGEX.find(folder)?.groupValues?.getOrNull(1)?.toFloatOrNull()
		return fromFolder ?: 0f
	}

	private fun buildChapterTitle(number: Float, rawTitle: String?): String? {
		val title = rawTitle?.trim()?.takeIf { it.isNotEmpty() }
		if (number <= 0f) return title

		val formattedNumber = formatChapterNumber(number)
		if (title == null) {
			return "Chapitre $formattedNumber"
		}

		val normalizedTitle = title.lowercase(Locale.ROOT)
		if (
			normalizedTitle.startsWith("chapitre") ||
			normalizedTitle.startsWith("chapter") ||
			normalizedTitle.startsWith("ch.")
		) {
			return title
		}
		if (normalizedTitle == formattedNumber || normalizedTitle == "#$formattedNumber") {
			return "Chapitre $formattedNumber"
		}
		return "Chapitre $formattedNumber - $title"
	}

	private fun formatChapterNumber(number: Float): String {
		return if (number % 1f == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}

	private fun buildDetailsCandidates(manga: Manga): List<String> {
		val urlSlug = extractSlugFromMangaUrl(manga.url)
		val publicUrlSlug = extractSlugFromMangaUrl(manga.publicUrl)
		val ordered = listOfNotNull(
			legacyMangaSlug(urlSlug),
			urlSlug?.toMangaSlug(),
			legacyMangaSlug(publicUrlSlug),
			publicUrlSlug?.toMangaSlug(),
			legacyMangaSlug(manga.url),
			manga.title.trim().takeIf { it.isNotEmpty() }?.toMangaSlug(),
		).distinctBy { it.lowercase(Locale.ROOT) }

		return when {
			ordered.size <= 1 -> ordered
			else -> ordered.filterNot { it.equals(UNKNOWN_MANGA_TITLE, ignoreCase = true) }
		}
	}

	private fun scoreDetailsResponse(json: JSONObject): Int {
		val info = json.optJSONObject("info") ?: return DETAILS_SCORE_EMPTY
		val chaptersCount = json.optJSONArray("chapters")?.length() ?: 0
		if (chaptersCount > 0) {
			return DETAILS_SCORE_WITH_CHAPTERS
		}
		val author = info.optString("author").trim()
		val description = info.optString("description").trim()
		val cover = info.optString("cover").trim().lowercase(Locale.ROOT)
		if (author.isNotEmpty() && !author.equals("Auteur Inconnu", ignoreCase = true)) {
			return DETAILS_SCORE_WITH_METADATA
		}
		if (description.isNotEmpty()) {
			return DETAILS_SCORE_WITH_METADATA
		}
		if (cover.isNotEmpty() && "logo-luffy" !in cover) {
			return DETAILS_SCORE_WITH_METADATA
		}
		return DETAILS_SCORE_EMPTY
	}

	private fun buildDetailsCacheKey(manga: Manga): String {
		return buildDetailsCandidates(manga).firstOrNull()?.lowercase(Locale.ROOT)
			?: manga.title.toMangaSlug().lowercase(Locale.ROOT)
	}

	private fun extractScanFolder(url: String): String? {
		val httpUrl = url.toAbsoluteUrl(domain).toHttpUrlOrNull() ?: return null
		httpUrl.queryParameter("scan")?.takeIf { it.isNotBlank() }?.let { return it }
		val scanIndex = httpUrl.pathSegments.indexOf("scan")
		if (scanIndex != -1 && scanIndex + 1 < httpUrl.pathSegments.size) {
			return httpUrl.pathSegments[scanIndex + 1].takeIf { it.isNotBlank() }
		}
		return null
	}

	private fun extractSlugFromMangaUrl(url: String): String? {
		if (url.startsWith("/manga/")) {
			return url.substringAfter("/manga/").substringBefore('/').takeIf { it.isNotBlank() }
		}
		val httpUrl = url.toHttpUrlOrNull() ?: return null
		val mangaIndex = httpUrl.pathSegments.indexOf("manga")
		if (mangaIndex != -1 && mangaIndex + 1 < httpUrl.pathSegments.size) {
			return httpUrl.pathSegments[mangaIndex + 1].takeIf { it.isNotBlank() }
		}
		return null
	}

	private fun String.toMangaTitle(): String = URLDecoder.decode(this, StandardCharsets.UTF_8.name())
		.replace('+', ' ')
		.trim()

	private fun String.toMangaSlug(): String {
		val normalized = Normalizer.normalize(decodeHtml(toMangaTitle()), Normalizer.Form.NFD)
			.replace(DIACRITICS_REGEX, "")
		return normalized.lowercase(Locale.ROOT)
			.replace(MANGA_SLUG_SEPARATOR_REGEX, "_")
			.trim('_')
	}

	private fun legacyMangaSlug(raw: String?): String? = when (raw?.uppercase(Locale.ROOT)) {
		"OP" -> "one_piece"
		"LCDL" -> "les_carnets_de_l_apothicaire"
		"JKM" -> "jujutsu_kaisen_modulo"
		"OPC" -> "one_piece_colo"
		"LDS" -> "l_atelier_des_sorciers"
		else -> null
	}

	private companion object {

		private const val API_LIMIT = 12
		private const val API_FORBIDDEN_CODE = 101
		private const val API_SESSION_ATTEMPTS = 2
		private const val SESSION_REFRESH_INTERVAL = 60L * 60L * 1000L
		private const val UNKNOWN_MANGA_TITLE = "Unknown manga"
		private const val DETAILS_SCORE_EMPTY = 0
		private const val DETAILS_SCORE_WITH_METADATA = 1
		private const val DETAILS_SCORE_WITH_CHAPTERS = 2
		private const val DETAILS_CACHE_SIZE = 200
		private const val PAGES_CACHE_SIZE = 400
		private val AUTHORS_SPLIT_PATTERN = Regex("[,&/]")
		private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
		private val MANGA_SLUG_SEPARATOR_REGEX = Regex("[^a-z0-9]+")
		private val CHAPTER_NUMBER_REGEX = Regex("(\\d+(?:\\.\\d+)?)$")
		private val IMAGE_MTIMES_BLOCK = Regex("imageMtimes\\s*=\\s*\\{([^}]*)\\}")
		private val IMAGE_MTIMES_ENTRY = Regex("[\"']?([0-9]+)[\"']?\\s*:\\s*([0-9]+)")
		private val PAGE_IMAGE_NAME_REGEX = Regex("(\\d+)(\\.[^./?#]+)")
	}
}
