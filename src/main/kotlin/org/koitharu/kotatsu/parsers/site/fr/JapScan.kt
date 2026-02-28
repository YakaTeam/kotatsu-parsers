package org.koitharu.kotatsu.parsers.site.fr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("JAPSCAN", "JapScan", "fr")
internal class JapScan(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.JAPSCAN, 42) {

	@Volatile
	private var cachedFilterOptions: MangaListFilterOptions? = null

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("www.japscan.foo", "japscan.foo")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.ADDED,
		SortOrder.ADDED_ASC,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		cachedFilterOptions?.let { return it }
		val doc = fetchDocument("https://$domain/mangas/")
		return MangaListFilterOptions(
			availableTags = parseAvailableTags(doc),
			availableStates = parseAvailableStates(doc),
			availableContentTypes = parseAvailableTypes(doc),
			availableDemographics = parseAvailableDemographics(doc),
		).also { cachedFilterOptions = it }
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/mangas/"
			.toHttpUrl()
			.newBuilder()
			.addQueryParameter("p", page.toString())
			.apply {
				addQueryParameter("sort", order.toJapScanSortValue())

				filter.query
					?.trim()
					?.takeIf { it.isNotEmpty() }
					?.let { addQueryParameter("search", it) }

				when (filter.states.oneOrThrowIfMany()) {
					MangaState.ONGOING -> addQueryParameter("status", "encours")
					MangaState.FINISHED -> addQueryParameter("status", "termine")
					else -> Unit
				}

				for (type in filter.types) {
					when (type) {
						ContentType.MANGA -> addQueryParameter("type[]", "Manga")
						ContentType.MANHWA -> addQueryParameter("type[]", "Manhwa")
						ContentType.MANHUA -> addQueryParameter("type[]", "Manhua")
						else -> Unit
					}
				}

				for (demography in filter.demographics) {
					when (demography) {
						Demographic.SHOUNEN -> addQueryParameter("demog[]", "shonen")
						Demographic.SHOUJO -> addQueryParameter("demog[]", "shojo")
						Demographic.SEINEN -> addQueryParameter("demog[]", "seinen")
						Demographic.JOSEI -> addQueryParameter("demog[]", "josei")
						else -> Unit
					}
				}

				val tagKeys = LinkedHashSet<String>(filter.tags.size)
				for (tag in filter.tags) {
					val key = tag.key.trim()
					if (key.isNotEmpty()) {
						tagKeys.add(key)
					}
				}
				for (tagKey in tagKeys) {
					addQueryParameter("genre[]", tagKey)
					addQueryParameter("genre", tagKey)
					addQueryParameter("genres[]", tagKey)
				}
				if (tagKeys.isNotEmpty()) {
					addQueryParameter("genres", tagKeys.joinToString(","))
				}
			}
			.build()
			.toString()

		val doc = fetchDocument(url)
		return doc.select(".mangas-list .manga-block").mapNotNull { block ->
			var resolvedLink: org.jsoup.nodes.Element? = null
			var resolvedHref: String? = null
			for (candidate in block.select("a[href]")) {
				val parsed = candidate.attrAsRelativeUrlOrNull("href") ?: continue
				resolvedLink = candidate
				resolvedHref = parsed
				break
			}
			val link = resolvedLink ?: return@mapNotNull null
			val href = resolvedHref ?: return@mapNotNull null
			val title = link.selectFirst(".name")?.textOrNull()
				?: link.attr("title").ifBlank { return@mapNotNull null }
			val coverUrl = block.selectFirst("img")?.src()
			val state = parseState(block.select("span.d-block").firstOrNull()?.textOrNull())
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = state,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDocument(manga.url.toAbsoluteUrl(domain))
		val info = parseInfoFields(doc)
		val altTitles = buildSet {
			addAll(manga.altTitles)
			parseCsvValues(info.findValue("Nom Original")).forEach {
				if (!it.equals(manga.title, ignoreCase = true)) {
					add(it)
				}
			}
			parseCsvValues(info.findValue("Nom(s) Alternatif(s)")).forEach {
				if (!it.equals(manga.title, ignoreCase = true)) {
					add(it)
				}
			}
		}
		val tags = parseTags(info.findValue("Genre(s)"))
		val authors = buildSet {
			addAll(parseCsvValues(info.findValue("Auteur(s)")))
			addAll(parseCsvValues(info.findValue("Artiste(s)")))
		}
		val coverUrl = doc.selectFirst("h1 + hr + .d-flex img")?.src() ?: manga.coverUrl

		return manga.copy(
			altTitles = altTitles,
			coverUrl = coverUrl,
			tags = tags,
			state = parseState(info.findValue("Statut")) ?: manga.state,
			authors = authors,
			// JapScan description is intentionally ignored to avoid unstable/low-quality synopsis payloads.
			description = manga.description,
			chapters = parseChapters(doc),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDocument(chapter.url.toAbsoluteUrl(domain))
		val readerConfig = parseReaderConfig(doc) ?: return emptyList()
		val payloadElement = doc.getElementById(READER_PAYLOAD_ELEMENT_ID) ?: return emptyList()
		val rawPayload = payloadElement.attr("data-${readerConfig.datasetKey}").trim()
		if (rawPayload.isEmpty()) return emptyList()
		val slicedPayload = if (readerConfig.offset in 0..rawPayload.length) {
			rawPayload.substring(readerConfig.offset)
		} else {
			rawPayload
		}
		val decodedPayload = decodeReaderPayload(slicedPayload) ?: return emptyList()
		val payloadJson = runCatching { JSONObject(decodedPayload) }.getOrNull() ?: return emptyList()
		val pageUrls = extractPageUrls(payloadJson)
		if (pageUrls.isEmpty()) return emptyList()
		return pageUrls.map { pageUrl ->
			val absolute = pageUrl.toAbsoluteUrl(domain)
			val finalUrl = appendReaderToken(absolute, readerConfig.queryKey, readerConfig.queryValue)
			MangaPage(
				id = generateUid(finalUrl),
				url = finalUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseReaderConfig(doc: Document): ReaderConfig? {
		var scriptData: String? = null
		for (script in doc.select("script")) {
			val data = script.data()
			if (data.contains("window.__rc")) {
				scriptData = data
				break
			}
		}
		val rcBlock = READER_CONFIG_BLOCK_REGEX.find(scriptData ?: return null)?.groupValues?.getOrNull(1)
			?: return null
		val datasetKey = READER_CONFIG_DATASET_REGEX.find(rcBlock)
			?.groupValues
			?.getOrNull(1)
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return null
		val offset = READER_CONFIG_OFFSET_REGEX.find(rcBlock)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
		val queryKey = READER_CONFIG_QUERY_KEY_REGEX.find(rcBlock)?.groupValues?.getOrNull(1).orEmpty()
		val queryValue = READER_CONFIG_QUERY_VALUE_REGEX.find(rcBlock)?.groupValues?.getOrNull(1).orEmpty()
		return ReaderConfig(
			datasetKey = datasetKey,
			offset = offset,
			queryKey = queryKey,
			queryValue = queryValue,
		)
	}

	private fun decodeReaderPayload(rawPayload: String): String? {
		if (rawPayload.isEmpty()) return null
		val substituted = buildString(rawPayload.length) {
			for (ch in rawPayload) {
				append(READER_SUBSTITUTION_MAP[ch] ?: ch)
			}
		}
		val decodedBytes = decodeBase64Relaxed(substituted) ?: return null
		return decodedBytes.toString(Charsets.UTF_8)
	}

	private fun decodeBase64Relaxed(value: String): ByteArray? {
		val clean = value.filterNot(Char::isWhitespace)
		if (clean.isEmpty()) return null
		decodeBase64Candidate(clean)?.let { return it }
		if ('-' in clean || '_' in clean) {
			val normalized = clean.replace('-', '+').replace('_', '/')
			decodeBase64Candidate(normalized)?.let { return it }
		}
		return null
	}

	private fun decodeBase64Candidate(candidate: String): ByteArray? {
		val padded = candidate.padEnd(((candidate.length + 3) / 4) * 4, '=')
		return try {
			Base64.getDecoder().decode(padded)
		} catch (_: IllegalArgumentException) {
			null
		}
	}

	private fun extractPageUrls(payload: JSONObject): List<String> {
		var bestScore = Int.MIN_VALUE
		var bestUrls = emptyList<String>()
		val keys = payload.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			val array = payload.optJSONArray(key) ?: continue
			val candidate = extractUrlsFromArray(array)
			if (candidate.isEmpty()) continue
			var likelyCount = 0
			var imageLikeCount = 0
				val filtered = ArrayList<String>(candidate.size)
				for (url in candidate) {
					val normalized = url
					val lower = normalized.lowercase(Locale.ROOT)
					val imageLike = isImageUrl(lower)
					if (imageLike) {
						imageLikeCount++
					}
					val likely = when {
						lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("blob:") -> false
						else -> normalized.contains('/')
							|| lower.startsWith("http://")
							|| lower.startsWith("https://")
							|| imageLike
					}
					if (likely) {
						likelyCount++
						filtered.add(normalized)
					}
				}
			if (likelyCount < MIN_PAGE_CANDIDATE_SIZE) continue
			val score = (likelyCount * 100) + imageLikeCount
			if (score > bestScore) {
				bestScore = score
				bestUrls = filtered
			}
		}
		return bestUrls.distinct()
	}

	private fun extractUrlsFromArray(array: JSONArray): List<String> {
		val urls = ArrayList<String>(array.length())
		for (index in 0 until array.length()) {
			val value = array.opt(index)
			when (value) {
				is String -> {
					val item = value.trim()
					if (item.isNotEmpty()) {
						urls.add(item)
					}
				}

				is JSONObject -> {
					val item = value.optString("url").ifBlank { value.optString("src") }.trim()
					if (item.isNotEmpty()) {
						urls.add(item)
					}
				}
			}
		}
		return urls
	}

	private fun isImageUrl(urlLower: String): Boolean {
		val end = urlLower.indexOfAny(URL_SUFFIX_START_CHARS).let { if (it >= 0) it else urlLower.length }
		val path = urlLower.substring(0, end)
		return IMAGE_EXTENSIONS.any { path.endsWith(it) }
	}

	private fun appendReaderToken(url: String, key: String, value: String): String {
		if (key.isBlank() || value.isBlank()) {
			return url
		}
		val httpUrl = url.toHttpUrlOrNull()
		if (httpUrl != null && httpUrl.queryParameter(key) != null) {
			return url
		}
		if (httpUrl != null) {
			return httpUrl.newBuilder()
				.addQueryParameter(key, value)
				.build()
				.toString()
		}
		val separator = if ('?' in url) '&' else '?'
		return "$url$separator$key=$value"
	}

	private fun parseAvailableTags(doc: Document): Set<MangaTag> {
		return doc.select(
			"input[name='genre[]'], input[name='genre'], input[name='genres[]'], input[name='genres']",
		).mapNotNullToSet { input ->
			val key = input.attr("value").trim()
			if (key.isBlank()) return@mapNotNullToSet null
			val title = input.parent()?.selectFirst(".checkbox-text")?.textOrNull() ?: key
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}

	private fun parseAvailableStates(doc: Document): Set<MangaState> {
		return doc.select("input[name='status']").mapNotNullToSet { input ->
			when (input.attr("value").trim()) {
				"encours" -> MangaState.ONGOING
				"termine" -> MangaState.FINISHED
				else -> null
			}
		}
	}

	private fun parseAvailableTypes(doc: Document): Set<ContentType> {
		return doc.select("input[name='type[]']").mapNotNullToSet { input ->
			when (input.attr("value").trim()) {
				"Manga" -> ContentType.MANGA
				"Manhwa" -> ContentType.MANHWA
				"Manhua" -> ContentType.MANHUA
				else -> null
			}
		}
	}

	private fun parseAvailableDemographics(doc: Document): Set<Demographic> {
		return doc.select("input[name='demog[]']").mapNotNullToSet { input ->
			when (input.attr("value").trim()) {
				"shonen" -> Demographic.SHOUNEN
				"shojo" -> Demographic.SHOUJO
				"seinen" -> Demographic.SEINEN
				"josei" -> Demographic.JOSEI
				else -> null
			}
		}
	}

	private fun parseState(raw: String?): MangaState? {
		val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
		return when {
			"cours" in value -> MangaState.ONGOING
			"termin" in value -> MangaState.FINISHED
			"pause" in value -> MangaState.PAUSED
			"abandon" in value || "abondon" in value -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun SortOrder.toJapScanSortValue(): String {
		return when (this) {
			SortOrder.ALPHABETICAL -> "name_asc"
			SortOrder.ALPHABETICAL_DESC -> "name_desc"
			SortOrder.POPULARITY -> "popular"
			SortOrder.ADDED_ASC -> "date_asc"
			SortOrder.ADDED -> "date_desc"
			SortOrder.UPDATED -> "updated"
			else -> "updated"
		}
	}

	private fun parseInfoFields(doc: Document): Map<String, String> {
		return buildMap {
			for (paragraph in doc.select("h1 + hr + .d-flex .m-2 p")) {
				val labelNode = paragraph.selectFirst("span.font-weight-bold") ?: continue
				val rawLabel = labelNode.textOrNull() ?: continue
				val key = rawLabel.removeSuffix(":").trim().lowercase(sourceLocale)
				if (key.isEmpty()) continue
				val value = paragraph.text()
					.removePrefix(rawLabel)
					.removePrefix(":")
					.trim()
				if (value.isEmpty()) continue
				put(key, value)
			}
		}
	}

	private fun parseCsvValues(raw: String?): List<String> {
		if (raw.isNullOrBlank()) return emptyList()
		return raw
			.splitToSequence(',', ';', '\n')
			.map { it.replace(MULTISPACE_REGEX, " ").trim() }
			.filter { it.isNotEmpty() && it != "-" }
			.toList()
	}

	private fun parseTags(raw: String?): Set<MangaTag> {
		return parseCsvValues(raw).mapNotNullToSet { tag ->
			val key = tag.lowercase(Locale.ROOT).trim()
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = tag,
				source = source,
			)
		}
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val chapterDates = listOf(
			SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH),
			SimpleDateFormat("d MMM yyyy", Locale.ENGLISH),
			SimpleDateFormat("dd MMM yyyy", sourceLocale),
			SimpleDateFormat("d MMM yyyy", sourceLocale),
			SimpleDateFormat("dd MMMM yyyy", sourceLocale),
			SimpleDateFormat("d MMMM yyyy", sourceLocale),
		)
		return doc.select("#list_chapters .list_chapters").mapChapters(reversed = true) { index, element ->
			val link = element.selectFirst("a[toto], a[href]:not(.d-none)") ?: return@mapChapters null
			val url = link.attrAsRelativeUrlOrNull("toto")
				?: link.attrAsRelativeUrlOrNull("href")
				?: return@mapChapters null

			val title = link.textOrNull()
				?.replace(MULTISPACE_REGEX, " ")
				?.trim()
				?.takeIf { it.isNotEmpty() }
			val dateText = element.selectFirst("span.float-right")?.textOrNull()
			val uploadDate = parseDate(dateText, chapterDates)
			val volumeTitle = element.parent()?.previousElementSibling()
				?.selectFirst("span[data-id]")
				?.textOrNull()
			val volume = parseVolume(volumeTitle, title, url)
			val number = parseChapterNumber(title, url) ?: if (volume > 0) {
				volume.toFloat()
			} else {
				(index + 1).toFloat()
			}

			MangaChapter(
				id = generateUid(url),
				title = title,
				number = number,
				volume = volume,
				url = url,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}
	}

	private fun parseDate(raw: String?, formats: List<SimpleDateFormat>): Long {
		if (raw.isNullOrBlank()) return 0L
		val value = raw.trim()
		for (format in formats) {
			val parsed = format.parseSafe(value)
			if (parsed > 0L) {
				return parsed
			}
		}
		return 0L
	}

	private fun parseVolume(vararg values: String?): Int {
		for (value in values) {
			val v = value?.let { VOLUME_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
			if (v != null && v > 0) {
				return v
			}
		}
		return 0
	}

	private fun parseChapterNumber(vararg values: String?): Float? {
		for (value in values) {
			val number = value?.let { CHAPTER_NUMBER_REGEX.find(it)?.groupValues?.getOrNull(1)?.toFloatOrNull() }
			if (number != null && number > 0f) {
				return number
			}
		}
		return null
	}

	private fun Map<String, String>.findValue(key: String): String? {
		return this[key.lowercase(sourceLocale)]
	}

	private suspend fun fetchDocument(url: String): Document {
		return try {
			val doc = webClient.httpGet(url).parseHtml()
			if (isCloudflareChallengePage(doc)) {
				context.requestBrowserAction(this, url)
			}
			doc
		} catch (e: HttpStatusException) {
			if (e.statusCode == HttpURLConnection.HTTP_FORBIDDEN || e.statusCode == HttpURLConnection.HTTP_UNAVAILABLE) {
				context.requestBrowserAction(this, e.url)
			}
			throw e
		}
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

	private data class ReaderConfig(
		val datasetKey: String,
		val offset: Int,
		val queryKey: String,
		val queryValue: String,
	)

	private companion object {
		private const val READER_PAYLOAD_ELEMENT_ID = "rPtAUA"
		private const val MIN_PAGE_CANDIDATE_SIZE = 2
		private val MULTISPACE_REGEX = Regex("\\s+")
		private val VOLUME_REGEX = Regex("(?i)\\bvol(?:ume)?[\\s._-]*([0-9]+)")
		private val CHAPTER_NUMBER_REGEX = Regex("(?i)\\bchap(?:itre|ter)?[\\s._-]*([0-9]+(?:\\.[0-9]+)?)")
		private val READER_CONFIG_BLOCK_REGEX = Regex(
			"""window\.__rc\s*=\s*\{(.*?)\}\s*;""",
			setOf(RegexOption.DOT_MATCHES_ALL),
		)
		private val READER_CONFIG_DATASET_REGEX = Regex("""(?:^|[,{\s])["']?d["']?\s*:\s*["']([^"']+)["']""")
		private val READER_CONFIG_OFFSET_REGEX = Regex("""(?:^|[,{\s])["']?s["']?\s*:\s*(-?\d+)""")
		private val READER_CONFIG_QUERY_KEY_REGEX = Regex("""(?:^|[,{\s])["']?p["']?\s*:\s*["']([^"']+)["']""")
		private val READER_CONFIG_QUERY_VALUE_REGEX = Regex("""(?:^|[,{\s])["']?v["']?\s*:\s*["']([^"']+)["']""")
		private val READER_SUBSTITUTION_MAP: Map<Char, Char> = run {
			val from = READER_SUBSTITUTION_FROM.reversed()
			val to = READER_SUBSTITUTION_TO.reversed()
			buildMap(from.length) {
				for (index in from.indices) {
					put(from[index], to[index])
				}
			}
		}
		private const val READER_SUBSTITUTION_FROM = "A6sjoxS9KhlgpmqMO3W8rNFYQa4wL5IbunvtE1Di0ecVGJdkfXCzB2UPyRZTH7"
		private const val READER_SUBSTITUTION_TO = "b5Hys74ZurQeCRTFdtXSogEcpiW2lUP3LMxANajhDYn9GqkVK81Iz0BOJwmf6v"
		private val IMAGE_EXTENSIONS = listOf(
			".avif",
			".bmp",
			".gif",
			".jpeg",
			".jpg",
			".png",
			".svg",
			".webp",
		)
		private val URL_SUFFIX_START_CHARS = charArrayOf('?', '#')
	}
}
