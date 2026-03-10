package org.koitharu.kotatsu.parsers.site.fr

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
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
		val json = webClient.httpGet(url).parseJson()
		if (json.optString("status").equals("error", ignoreCase = true)) {
			throw ParseException(json.optString("message"), url.toString())
		}
		val data = json.optJSONArray("data") ?: return emptyList()
		val list = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val jo = data.optJSONObject(i) ?: continue
			val title = jo.optString("title").trim()
			if (title.isEmpty()) continue
			val slug = title.toMangaSlug()
			val coverFolder = jo.optString("cover_folder").trim()
			val coverUrl = coverFolder.takeIf { it.isNotEmpty() }?.let {
				"https://$domain/files/scans/$it/thumbnail.webp"
			}
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
		val detailsTitle = info?.optString("title").orEmpty().ifBlank { manga.title }
		val chapters = parseChapters(json).ifEmpty { manga.chapters.orEmpty() }
		val description = info?.optString("description").orEmpty().trim().ifEmpty { null }
		val authors = parseAuthors(info?.optString("author")).ifEmpty { manga.authors }
		val coverUrl = info?.optString("cover").orEmpty().trim().ifEmpty { null }?.toAbsoluteUrl(domain)
		val canonicalSlug = extractSlugFromMangaUrl(manga.url)
			?: extractSlugFromMangaUrl(manga.publicUrl)
			?: detailsTitle.toMangaSlug()
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
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
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
		val folder = extractScanFolder(chapter.url) ?: return emptyList()
		val pages = parsePagesFromScript(doc, folder, chapter.source)
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
			val json = webClient.httpGet(url).parseJson()
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
			val folder = jo.optString("folder").trim()
			if (folder.isEmpty()) continue
			val chapterNumber = parseChapterNumber(jo.optString("num"), folder)
			val chapterTitle = buildChapterTitle(chapterNumber, jo.optString("title"))
			result += MangaChapter(
				id = generateUid(folder),
				title = chapterTitle,
				number = chapterNumber,
				volume = 0,
				url = "/scan/$folder",
				scanlator = null,
				uploadDate = jo.optLong("time", 0L) * 1000L,
				branch = null,
				source = source,
			)
		}
		return result.distinctBy { it.url }
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
		val ordered = listOfNotNull(
			extractSlugFromMangaUrl(manga.url)?.toMangaTitle(),
			extractSlugFromMangaUrl(manga.publicUrl)?.toMangaTitle(),
			legacyMangaTitle(manga.url),
			manga.title.trim().takeIf { it.isNotEmpty() },
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
		return (
			extractSlugFromMangaUrl(manga.url)
				?: extractSlugFromMangaUrl(manga.publicUrl)
				?: manga.title.toMangaSlug()
			)
			.lowercase(Locale.ROOT)
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

	private fun String.toMangaTitle(): String = URLDecoder.decode(this, StandardCharsets.UTF_8.name()).replace('+', ' ').trim()

	private fun String.toMangaSlug(): String = lowercase(Locale.ROOT)
		.trim()
		.replace(WHITESPACES_REGEX, "+")

	private fun legacyMangaTitle(url: String): String? = when (url.uppercase(Locale.ROOT)) {
		"OP" -> "One Piece"
		"LCDL" -> "Les Carnets de l'apothicaire"
		"JKM" -> "Jujutsu Kaisen Modulo"
		"OPC" -> "One Piece Colo"
		"LDS" -> "L'Atelier des Sorciers"
		else -> null
	}

	private companion object {

		private const val API_LIMIT = 12
		private const val UNKNOWN_MANGA_TITLE = "Unknown manga"
			private const val DETAILS_SCORE_EMPTY = 0
			private const val DETAILS_SCORE_WITH_METADATA = 1
			private const val DETAILS_SCORE_WITH_CHAPTERS = 2
			private const val DETAILS_CACHE_SIZE = 200
			private const val PAGES_CACHE_SIZE = 400
			private val AUTHORS_SPLIT_PATTERN = Regex("[,&/]")
		private val WHITESPACES_REGEX = Regex("\\s+")
		private val CHAPTER_NUMBER_REGEX = Regex("(\\d+(?:\\.\\d+)?)$")
		private val IMAGE_MTIMES_BLOCK = Regex("imageMtimes\\s*=\\s*\\{([^}]*)\\}")
		private val IMAGE_MTIMES_ENTRY = Regex("[\"']?([0-9]+)[\"']?\\s*:\\s*([0-9]+)")
	}
}
