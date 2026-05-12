package org.koitharu.kotatsu.parsers.site.madara.fr

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
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
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.setHeader
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@MangaSourceParser("EPSILONSCAN", "EpsilonScan", "fr", ContentType.HENTAI)
internal class EpsilonscanParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.EPSILONSCAN, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("epsilonscan.to")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	private val pageRequests = Collections.synchronizedMap(
		object : LinkedHashMap<String, PageRequestInfo>(32, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageRequestInfo>?): Boolean {
				return size > MAX_PAGE_REQUESTS
			}
		},
	)
	private val readerSessions = Collections.synchronizedMap(
		object : LinkedHashMap<String, ReaderSession>(4, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReaderSession>?): Boolean {
				return size > MAX_READER_SESSIONS
			}
		},
	)

	init {
		setFirstPage(1)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val page = fetchInertiaPage("https://$domain/library")
		val genres = page.optJSONObject("props")
			?.optJSONObject("filterOptions")
			?.optJSONArray("genres")
			?.mapJSONNotNull { genre ->
				val title = decodeHtml(genre.getStringOrNull("name")).ifEmpty { return@mapJSONNotNull null }
				val key = genre.getStringOrNull("slug") ?: title.toSlug()
				MangaTag(title = title, key = key, source = source)
			}
			?.toSet()
			.orEmpty()
		return MangaListFilterOptions(
			availableTags = genres,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
				MangaState.UPCOMING,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty()) {
			if (page != 1) {
				return emptyList()
			}
			val url = urlBuilder()
				.addPathSegments("api/v1/search/series")
				.addQueryParameter("q", query)
				.build()
			return fetchJsonArray(url, "https://$domain/library").mapJSON { parseMangaItem(it) }
		}

		val (orderBy, direction) = when (order) {
			SortOrder.NEWEST -> "date" to "desc"
			SortOrder.POPULARITY -> "views" to "desc"
			SortOrder.ALPHABETICAL -> "alphabetical" to "asc"
			SortOrder.ALPHABETICAL_DESC -> "alphabetical" to "desc"
			else -> "recently" to "desc"
		}
		val url = urlBuilder()
			.addPathSegment("library")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("orderby", orderBy)
			.addQueryParameter("order", direction)
			.addOptionalCsvParameter("include_genres", filter.tags.map { it.key })
			.addOptionalCsvParameter("exclude_genres", filter.tagsExclude.map { it.key })
			.addOptionalCsvParameter("status", filter.states.mapNotNull { it.toEpsilonStatus() })
			.build()
		val pageData = fetchInertiaPage(url.toString())
		val series = pageData.optJSONObject("props")
			?.optJSONObject("series")
			?.optJSONArray("data")
			?: return emptyList()
		return series.mapJSON { parseMangaItem(it) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val page = fetchInertiaPage(manga.url.toAbsoluteUrl(domain))
		val serie = page.optJSONObject("props")?.optJSONObject("serie")
			?: throw ParseException("Cannot find serie data", manga.publicUrl)
		val slug = serie.getStringOrNull("slug")
			?: manga.url.trimEnd('/').substringAfterLast('/')
		val url = "/serie/$slug"
		val coverUrl = serie.getStringOrNull("cover_image")
			?.toAbsoluteUrl(domain)
			?: manga.coverUrl
		val chapters = serie.optJSONArray("chapters")
			?.mapChapters { index, chapter ->
				parseChapter(chapter, slug, index)
			}
			.orEmpty()
		return manga.copy(
			id = generateUid(url),
			title = decodeHtml(serie.getStringOrNull("name") ?: serie.getStringOrNull("title")).ifEmpty { manga.title },
			altTitles = parseAltTitles(serie.getStringOrNull("name_alternative")),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			contentRating = sourceContentRating,
			coverUrl = coverUrl,
			largeCoverUrl = serie.getStringOrNull("banner_image")?.toAbsoluteUrl(domain) ?: coverUrl,
			tags = parseTags(serie),
			state = parseState(serie.getStringOrNull("status")) ?: manga.state,
			authors = parseAuthors(serie),
			description = decodeHtml(serie.getStringOrNull("description")).ifEmpty { manga.description },
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val page = fetchInertiaPage(chapter.url.toAbsoluteUrl(domain))
		val props = page.getJSONObject("props")
		val data = props.optJSONObject("data")
			?: throw ParseException("Cannot find chapter data", chapter.url.toAbsoluteUrl(domain))
		if (data.optInt("captcha", 0) != 0 && !props.optBoolean("captcha_passed", false)) {
			context.requestBrowserAction(this, chapter.url.toAbsoluteUrl(domain))
		}
		val pageCount = props.optInt("page_count", data.optInt("page_count", 0))
		val token = props.getStringOrNull("chapter_token")
			?: data.getStringOrNull("chapter_token")
			?: throw ParseException("Cannot find chapter token", chapter.url.toAbsoluteUrl(domain))
		val serverPubkey = props.getStringOrNull("server_pubkey")
			?: data.getStringOrNull("server_pubkey")
			?: throw ParseException("Cannot find reader server key", chapter.url.toAbsoluteUrl(domain))
		val serieSlug = data.optJSONObject("serie")?.getStringOrNull("slug")
			?: chapter.url.substringBefore("/chapter/").trimEnd('/').substringAfterLast('/')
		val chapterSlug = data.getStringOrNull("slug")
			?: chapter.url.trimEnd('/').substringAfterLast('/')
		if (pageCount <= 0) {
			throw ParseException("Cannot find page count", chapter.url.toAbsoluteUrl(domain))
		}
		return (1..pageCount).map { index ->
			val url = urlBuilder()
				.addPathSegments("serie/$serieSlug/chapter/$chapterSlug/page/$index")
				.addQueryParameter("token", token)
				.addQueryParameter("server_pubkey", serverPubkey)
				.build()
				.toString()
				.toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val url = page.url.toAbsoluteUrl(domain).toHttpUrl()
		val token = url.queryParameter("token")
			?: throw ParseException("Cannot find page token", url.toString())
		val serverPublicKey = url.queryParameter("server_pubkey")
			?: throw ParseException("Cannot find reader server key", url.toString())
		val pageIndex = url.pathSegments.lastOrNull()?.toIntOrNull()
			?: throw ParseException("Cannot find page index", url.toString())
		val timestamp = (System.currentTimeMillis() / 1000).toString()
		val nonce = ByteArray(NONCE_SIZE).also(RANDOM::nextBytes).toHex()
		val signature = hmacSha256Hex(token, "$pageIndex$timestamp$nonce")
		val session = getReaderSession(serverPublicKey)
		val finalUrl = url.newBuilder()
			.removeAllQueryParameters("server_pubkey")
			.addQueryParameter("ts", timestamp)
			.addQueryParameter("nonce", nonce)
			.addQueryParameter("sig", signature)
			.build()
			.toString()
		pageRequests[finalUrl] = PageRequestInfo(
			clientPublicKey = session.clientPublicKey,
			sharedSecret = session.sharedSecret,
			referer = "https://$domain${url.encodedPath.substringBeforeLast("/page/")}",
		)
		return finalUrl
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val pageRequest = pageRequests[request.url.toString()]
		val effectiveRequest = if (pageRequest == null) {
			request
		} else {
			request.newBuilder()
				.header("X-Client-Pubkey", pageRequest.clientPublicKey)
				.header(CommonHeaders.REFERER, pageRequest.referer)
				.header(CommonHeaders.ACCEPT, "*/*")
				.build()
		}
		val response = chain.proceed(effectiveRequest)
		if (pageRequest == null || response.header("X-Reader-Response") != "1") {
			return response
		}
		val contentType = response.body.contentType()
		val bytes = response.body.bytes()
		val pageName = response.header("X-Page-Name")
		val keyHint = response.header("X-Key-Hint")
		if (pageName.isNullOrEmpty() || keyHint.isNullOrEmpty()) {
			return response.newBuilder()
				.body(bytes.toResponseBody(contentType))
				.build()
		}
		val decoded = runCatching {
			decryptPage(bytes, pageRequest.sharedSecret, pageName, keyHint)
		}.getOrElse {
			bytes
		}
		val imageType = getImageContentType(pageName, decoded).toMediaTypeOrNull()
		return response.newBuilder()
			.setHeader(CommonHeaders.CONTENT_TYPE, imageType?.toString())
			.removeHeader(CommonHeaders.CONTENT_LENGTH)
			.removeHeader(CommonHeaders.CONTENT_ENCODING)
			.body(decoded.toResponseBody(imageType))
			.build()
	}

	private suspend fun fetchInertiaPage(url: String): JSONObject {
		val absoluteUrl = url.toAbsoluteUrl(domain)
		val doc = try {
			webClient.httpGet(absoluteUrl, htmlHeaders(absoluteUrl)).parseHtml()
		} catch (e: HttpStatusException) {
			if (e.statusCode == HttpURLConnection.HTTP_FORBIDDEN || e.statusCode == HttpURLConnection.HTTP_UNAVAILABLE) {
				context.requestBrowserAction(this, absoluteUrl)
			}
			throw e
		}
		if (doc.isCloudflareChallenge()) {
			context.requestBrowserAction(this, absoluteUrl)
		}
		val app = doc.getElementById("app")
			?: throw ParseException("Cannot find Inertia app", absoluteUrl)
		val rawPage = app.attr("data-page")
		if (rawPage.isEmpty()) {
			throw ParseException("Cannot find Inertia data", absoluteUrl)
		}
		return JSONObject(rawPage)
	}

	private suspend fun fetchJsonArray(url: HttpUrl, referer: String): JSONArray {
		val raw = try {
			webClient.httpGet(url, ajaxHeaders(referer)).parseRaw()
		} catch (e: HttpStatusException) {
			if (e.statusCode == HttpURLConnection.HTTP_FORBIDDEN || e.statusCode == HttpURLConnection.HTTP_UNAVAILABLE) {
				context.requestBrowserAction(this, url.toString())
			}
			throw e
		}
		if (raw.contains("cf-challenge", ignoreCase = true) || raw.contains("Just a moment", ignoreCase = true)) {
			context.requestBrowserAction(this, url.toString())
		}
		return JSONArray(raw)
	}

	private fun parseMangaItem(jo: JSONObject): Manga {
		val rawUrl = jo.getStringOrNull("link")
			?: jo.getStringOrNull("url")
			?: jo.getStringOrNull("slug")?.let { "/serie/$it" }
			?: throw ParseException("Cannot find manga URL", "https://$domain/library")
		val url = rawUrl.toAbsoluteUrl(domain).toRelativeUrl(domain)
		return Manga(
			id = generateUid(url),
			title = decodeHtml(jo.getStringOrNull("title") ?: jo.getStringOrNull("name")).ifEmpty { url },
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = sourceContentRating,
			coverUrl = (jo.getStringOrNull("cover_image") ?: jo.getStringOrNull("image"))?.toAbsoluteUrl(domain),
			tags = parseTags(jo),
			state = parseState(jo.getStringOrNull("serie_status") ?: jo.getStringOrNull("status")),
			authors = emptySet(),
			description = decodeHtml(jo.getStringOrNull("description")).ifEmpty { null },
			source = source,
		)
	}

	private fun parseChapter(jo: JSONObject, serieSlug: String, index: Int): MangaChapter? {
		val slug = jo.getStringOrNull("slug") ?: return null
		val url = "/serie/$serieSlug/chapter/$slug"
		return MangaChapter(
			id = generateUid(url),
			title = decodeHtml(jo.getStringOrNull("title")).ifEmpty { null },
			number = jo.optDouble("chapterNumber", Double.NaN).takeUnless { it.isNaN() }
				?.toFloat()
				?: jo.optDouble("chapter_number", Double.NaN).takeUnless { it.isNaN() }?.toFloat()
				?: parseChapterNumber(jo.getStringOrNull("title"))
				?: (index + 1f),
			volume = 0,
			url = url,
			scanlator = null,
			uploadDate = parseDate(jo.getStringOrNull("createdAt") ?: jo.getStringOrNull("created_at")),
			branch = null,
			source = source,
		)
	}

	private fun parseTags(jo: JSONObject): Set<MangaTag> {
		val result = LinkedHashSet<MangaTag>()
		result.addTagValue(jo.opt("type"))
		result.addTagValue(jo.opt("badge"))
		result.addTagValue(jo.getStringOrNull("genre"))
		jo.optJSONArray("genres")?.let { array ->
			for (i in 0 until array.length()) {
				result.addTagValue(array.opt(i))
			}
		}
		jo.optJSONArray("genres_slugs")?.let { array ->
			for (i in 0 until array.length()) {
				result.addTagValue(array.optString(i))
			}
		}
		return result
	}

	private fun MutableSet<MangaTag>.addTagValue(raw: Any?) {
		when (raw) {
			null, JSONObject.NULL -> return
			is JSONObject -> {
				val title = decodeHtml(
					raw.getStringOrNull("name")
						?: raw.getStringOrNull("title")
						?: raw.getStringOrNull("label"),
				).ifEmpty { return }
				val key = raw.getStringOrNull("slug") ?: title.toSlug()
				add(MangaTag(title = title, key = key, source = source))
			}
			is String -> {
				val title = decodeHtml(raw).ifEmpty { return }
				add(MangaTag(title = title.toDisplayTag(), key = title.toSlug(), source = source))
			}
		}
	}

	private fun parseAltTitles(value: String?): Set<String> {
		return value.orEmpty()
			.split('|', ',')
			.mapNotNullTo(LinkedHashSet()) { decodeHtml(it).ifEmpty { null } }
	}

	private fun parseAuthors(serie: JSONObject): Set<String> {
		return listOfNotNull(
			serie.getStringOrNull("artist"),
			serie.getStringOrNull("author"),
		).flatMapTo(LinkedHashSet()) { raw ->
			raw.split('|', ',').mapNotNull { decodeHtml(it).ifEmpty { null } }
		}
	}

	private fun parseState(status: String?): MangaState? = when (status?.lowercase(Locale.ROOT)?.trim()) {
		"ongoing", "en cours" -> MangaState.ONGOING
		"completed", "complete", "finished", "terminé", "termine" -> MangaState.FINISHED
		"hiatus", "paused", "pause", "onhold", "on hold" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled", "abandonné", "abandonne" -> MangaState.ABANDONED
		"upcoming", "coming_soon", "a venir", "à venir" -> MangaState.UPCOMING
		else -> null
	}

	private fun MangaState.toEpsilonStatus(): String? = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "finished"
		MangaState.PAUSED -> "onhold"
		MangaState.ABANDONED -> "dropped"
		MangaState.UPCOMING -> "upcoming"
		else -> null
	}

	private fun parseChapterNumber(title: String?): Float? {
		return CHAPTER_NUMBER_REGEX.find(title.orEmpty())
			?.groupValues
			?.getOrNull(1)
			?.replace(',', '.')
			?.toFloatOrNull()
	}

	private fun parseDate(value: String?): Long {
		val raw = value?.trim().orEmpty()
		if (raw.isEmpty()) {
			return 0L
		}
		runCatching {
			return Instant.parse(raw).toEpochMilli()
		}
		val formats = arrayOf("dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd")
		for (format in formats) {
			val date = runCatching {
				SimpleDateFormat(format, Locale.FRANCE).apply {
					timeZone = TimeZone.getTimeZone("UTC")
				}.parse(raw)
			}.getOrNull()
			if (date is Date) {
				return date.time
			}
		}
		return 0L
	}

	private fun decryptPage(payload: ByteArray, sharedSecret: ByteArray, pageName: String, keyHint: String): ByteArray {
		if (payload.size <= RESPONSE_PREFIX_SIZE + SECRETSTREAM_HEADER_SIZE) {
			throw IllegalArgumentException("Encrypted response is too short")
		}
		val digest = MessageDigest.getInstance("SHA-256").digest(sharedSecret + pageName.toByteArray())
		val hint = Base64.getDecoder().decode(keyHint)
		if (hint.size < SECRETSTREAM_KEY_SIZE) {
			throw IllegalArgumentException("Invalid key hint")
		}
		val pageKey = ByteArray(SECRETSTREAM_KEY_SIZE) { i ->
			(digest[i].toInt() xor hint[i].toInt()).toByte()
		}
		val header = payload.copyOfRange(RESPONSE_PREFIX_SIZE, RESPONSE_PREFIX_SIZE + SECRETSTREAM_HEADER_SIZE)
		var key = hChaCha20(pageKey, header.copyOfRange(0, HCHACHA_NONCE_SIZE))
		var nonce = header.copyOfRange(HCHACHA_NONCE_SIZE, SECRETSTREAM_HEADER_SIZE)
		var messageCounter = 1
		var offset = RESPONSE_PREFIX_SIZE + SECRETSTREAM_HEADER_SIZE
		val output = ByteArrayOutputStream(payload.size)
		while (offset < payload.size) {
			val chunkLength = min(ENCRYPTED_CHUNK_SIZE, payload.size - offset)
			if (chunkLength < SECRETSTREAM_ABYTES) {
				throw IllegalArgumentException("Invalid encrypted chunk")
			}
			val firstBlock = chacha20Block(key, messageCounter, nonce, 1)
			val tag = (payload[offset].toInt() xor firstBlock[0].toInt()) and 0xff
			val messageLength = chunkLength - SECRETSTREAM_ABYTES
			xorChaCha20(
				input = payload,
				inputOffset = offset + 1,
				output = output,
				length = messageLength,
				key = key,
				messageCounter = messageCounter,
				nonce = nonce,
				skipBytes = CHACHA_BLOCK_SIZE,
				initialBlockCounter = 1,
			)
			val macOffset = offset + chunkLength - MAC_SIZE
			for (i in 0 until nonce.size) {
				nonce[i] = (nonce[i].toInt() xor payload[macOffset + i].toInt()).toByte()
			}
			offset += chunkLength
			messageCounter++
			if (messageCounter == 0 || tag and SECRETSTREAM_TAG_REKEY != 0) {
				val rekeyInput = key + nonce
				val rekeyOutput = ByteArrayOutputStream(rekeyInput.size)
				xorChaCha20(
					input = rekeyInput,
					inputOffset = 0,
					output = rekeyOutput,
					length = rekeyInput.size,
					key = key,
					messageCounter = messageCounter,
					nonce = nonce,
					skipBytes = 0,
					initialBlockCounter = 0,
				)
				val next = rekeyOutput.toByteArray()
				key = next.copyOfRange(0, SECRETSTREAM_KEY_SIZE)
				nonce = next.copyOfRange(SECRETSTREAM_KEY_SIZE, SECRETSTREAM_KEY_SIZE + SECRETSTREAM_NONCE_SIZE)
				messageCounter = 1
			}
		}
		return output.toByteArray()
	}

	private fun getReaderSession(serverPublicKey: String): ReaderSession {
		synchronized(readerSessions) {
			readerSessions[serverPublicKey]?.let { return it }
			val privateKey = ByteArray(CURVE25519_KEY_SIZE).also(RANDOM::nextBytes)
			val serverPublic = Base64.getDecoder().decode(serverPublicKey)
			val session = ReaderSession(
				clientPublicKey = Base64.getEncoder().encodeToString(x25519(privateKey, X25519_BASE_POINT)),
				sharedSecret = x25519(privateKey, serverPublic),
			)
			readerSessions[serverPublicKey] = session
			return session
		}
	}

	private fun getImageContentType(pageName: String, image: ByteArray): String = when {
		image.size >= 3 &&
			image[0] == 0xff.toByte() &&
			image[1] == 0xd8.toByte() &&
			image[2] == 0xff.toByte() -> "image/jpeg"
		image.size >= 8 &&
			image[0] == 0x89.toByte() &&
			image[1] == 0x50.toByte() &&
			image[2] == 0x4e.toByte() &&
			image[3] == 0x47.toByte() -> "image/png"
		image.size >= 12 &&
			image[0] == 0x52.toByte() &&
			image[1] == 0x49.toByte() &&
			image[2] == 0x46.toByte() &&
			image[3] == 0x46.toByte() &&
			image[8] == 0x57.toByte() &&
			image[9] == 0x45.toByte() &&
			image[10] == 0x42.toByte() &&
			image[11] == 0x50.toByte() -> "image/webp"
		pageName.endsWith(".png", ignoreCase = true) -> "image/png"
		pageName.endsWith(".webp", ignoreCase = true) -> "image/webp"
		else -> "image/jpeg"
	}

	private fun hChaCha20(key: ByteArray, nonce: ByteArray): ByteArray {
		val state = IntArray(16)
		for (i in CHACHA_CONSTANTS.indices) {
			state[i] = CHACHA_CONSTANTS[i]
		}
		for (i in 0 until 8) {
			state[4 + i] = key.readIntLe(i * 4)
		}
		for (i in 0 until 4) {
			state[12 + i] = nonce.readIntLe(i * 4)
		}
		chachaRounds(state)
		val out = ByteArray(SECRETSTREAM_KEY_SIZE)
		state[0].writeIntLe(out, 0)
		state[1].writeIntLe(out, 4)
		state[2].writeIntLe(out, 8)
		state[3].writeIntLe(out, 12)
		state[12].writeIntLe(out, 16)
		state[13].writeIntLe(out, 20)
		state[14].writeIntLe(out, 24)
		state[15].writeIntLe(out, 28)
		return out
	}

	private fun chacha20Block(key: ByteArray, messageCounter: Int, nonce: ByteArray, blockCounter: Int): ByteArray {
		val state = IntArray(16)
		for (i in CHACHA_CONSTANTS.indices) {
			state[i] = CHACHA_CONSTANTS[i]
		}
		for (i in 0 until 8) {
			state[4 + i] = key.readIntLe(i * 4)
		}
		state[12] = blockCounter
		state[13] = messageCounter
		state[14] = nonce.readIntLe(0)
		state[15] = nonce.readIntLe(4)
		val working = state.copyOf()
		chachaRounds(working)
		val out = ByteArray(CHACHA_BLOCK_SIZE)
		for (i in 0 until 16) {
			(working[i] + state[i]).writeIntLe(out, i * 4)
		}
		return out
	}

	private fun chachaRounds(state: IntArray) {
		repeat(10) {
			quarterRound(state, 0, 4, 8, 12)
			quarterRound(state, 1, 5, 9, 13)
			quarterRound(state, 2, 6, 10, 14)
			quarterRound(state, 3, 7, 11, 15)
			quarterRound(state, 0, 5, 10, 15)
			quarterRound(state, 1, 6, 11, 12)
			quarterRound(state, 2, 7, 8, 13)
			quarterRound(state, 3, 4, 9, 14)
		}
	}

	private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
		state[a] += state[b]
		state[d] = (state[d] xor state[a]).rotateLeft(16)
		state[c] += state[d]
		state[b] = (state[b] xor state[c]).rotateLeft(12)
		state[a] += state[b]
		state[d] = (state[d] xor state[a]).rotateLeft(8)
		state[c] += state[d]
		state[b] = (state[b] xor state[c]).rotateLeft(7)
	}

	private fun xorChaCha20(
		input: ByteArray,
		inputOffset: Int,
		output: ByteArrayOutputStream,
		length: Int,
		key: ByteArray,
		messageCounter: Int,
		nonce: ByteArray,
		skipBytes: Int,
		initialBlockCounter: Int,
	) {
		var remaining = length
		var inputPos = inputOffset
		var blockCounter = initialBlockCounter + skipBytes / CHACHA_BLOCK_SIZE
		var blockOffset = skipBytes % CHACHA_BLOCK_SIZE
		while (remaining > 0) {
			val block = chacha20Block(key, messageCounter, nonce, blockCounter)
			val part = min(remaining, CHACHA_BLOCK_SIZE - blockOffset)
			for (i in 0 until part) {
				output.write(input[inputPos + i].toInt() xor block[blockOffset + i].toInt())
			}
			inputPos += part
			remaining -= part
			blockCounter++
			blockOffset = 0
		}
	}

	private fun x25519(rawScalar: ByteArray, rawPoint: ByteArray): ByteArray {
		val scalar = rawScalar.copyOf(CURVE25519_KEY_SIZE)
		scalar[0] = (scalar[0].toInt() and 248).toByte()
		scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()
		val x1 = littleEndianToBigInteger(rawPoint.copyOf(CURVE25519_KEY_SIZE))
		var x2 = BigInteger.ONE
		var z2 = BigInteger.ZERO
		var x3 = x1
		var z3 = BigInteger.ONE
		var swap = 0
		for (t in 254 downTo 0) {
			val bit = ((scalar[t / 8].toInt() and 0xff) shr (t and 7)) and 1
			swap = swap xor bit
			if (swap != 0) {
				var tmp = x2
				x2 = x3
				x3 = tmp
				tmp = z2
				z2 = z3
				z3 = tmp
			}
			swap = bit
			val a = (x2 + z2).mod(CURVE_P)
			val aa = a.multiply(a).mod(CURVE_P)
			val b = (x2 - z2).mod(CURVE_P)
			val bb = b.multiply(b).mod(CURVE_P)
			val e = (aa - bb).mod(CURVE_P)
			val c = (x3 + z3).mod(CURVE_P)
			val d = (x3 - z3).mod(CURVE_P)
			val da = d.multiply(a).mod(CURVE_P)
			val cb = c.multiply(b).mod(CURVE_P)
			x3 = (da + cb).let { it.multiply(it).mod(CURVE_P) }
			z3 = (da - cb).let { x1.multiply(it.multiply(it)).mod(CURVE_P) }
			x2 = aa.multiply(bb).mod(CURVE_P)
			z2 = e.multiply(aa + A24.multiply(e)).mod(CURVE_P)
		}
		if (swap != 0) {
			val tmpX = x2
			x2 = x3
			x3 = tmpX
			val tmpZ = z2
			z2 = z3
			z3 = tmpZ
		}
		return bigIntegerToLittleEndian(x2.multiply(z2.modInverse(CURVE_P)).mod(CURVE_P), CURVE25519_KEY_SIZE)
	}

	private fun htmlHeaders(referer: String): Headers = Headers.Builder()
		.add(CommonHeaders.USER_AGENT, config[userAgentKey])
		.add(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		.add(CommonHeaders.ACCEPT_LANGUAGE, "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
		.add(CommonHeaders.REFERER, referer)
		.build()

	private fun ajaxHeaders(referer: String): Headers = Headers.Builder()
		.add(CommonHeaders.USER_AGENT, config[userAgentKey])
		.add(CommonHeaders.ACCEPT, "application/json, text/plain, */*")
		.add(CommonHeaders.ACCEPT_LANGUAGE, "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
		.add(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
		.add(CommonHeaders.REFERER, referer)
		.build()

	private fun Document.isCloudflareChallenge(): Boolean {
		return title().contains("Just a moment", ignoreCase = true) ||
			location().contains("/cdn-cgi/challenge-platform", ignoreCase = true) ||
			html().contains("cf-challenge", ignoreCase = true)
	}

	private fun HttpUrl.Builder.addOptionalCsvParameter(name: String, values: Collection<String>): HttpUrl.Builder {
		if (values.isNotEmpty()) {
			addQueryParameter(name, values.joinToString(","))
		}
		return this
	}

	private fun decodeHtml(value: String?): String {
		return Parser.unescapeEntities(value.orEmpty().trim(), false).trim()
	}

	private fun String.toSlug(): String {
		return trim().lowercase(Locale.ROOT)
			.replace("&", "and")
			.replace(Regex("[^a-z0-9]+"), "-")
			.trim('-')
	}

	private fun String.toDisplayTag(): String {
		return split('-', '_', ' ')
			.filter { it.isNotEmpty() }
			.joinToString(" ") { part ->
				part.replaceFirstChar { char ->
					if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
				}
			}
	}

	private fun hmacSha256Hex(key: String, message: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
		return mac.doFinal(message.toByteArray()).toHex()
	}

	private fun ByteArray.toHex(): String = buildString(size * 2) {
		for (byte in this@toHex) {
			append(HEX_DIGITS[(byte.toInt() ushr 4) and 0xf])
			append(HEX_DIGITS[byte.toInt() and 0xf])
		}
	}

	private fun ByteArray.readIntLe(offset: Int): Int {
		return (this[offset].toInt() and 0xff) or
			((this[offset + 1].toInt() and 0xff) shl 8) or
			((this[offset + 2].toInt() and 0xff) shl 16) or
			((this[offset + 3].toInt() and 0xff) shl 24)
	}

	private fun Int.writeIntLe(out: ByteArray, offset: Int) {
		out[offset] = this.toByte()
		out[offset + 1] = (this ushr 8).toByte()
		out[offset + 2] = (this ushr 16).toByte()
		out[offset + 3] = (this ushr 24).toByte()
	}

	private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
		var result = BigInteger.ZERO
		for (i in bytes.indices.reversed()) {
			result = result.shiftLeft(8).add(BigInteger.valueOf((bytes[i].toInt() and 0xff).toLong()))
		}
		return result
	}

	private fun bigIntegerToLittleEndian(value: BigInteger, size: Int): ByteArray {
		var current = value
		val out = ByteArray(size)
		for (i in 0 until size) {
			out[i] = current.and(BYTE_MASK).toByte()
			current = current.shiftRight(8)
		}
		return out
	}

	private data class PageRequestInfo(
		val clientPublicKey: String,
		val sharedSecret: ByteArray,
		val referer: String,
	)

	private data class ReaderSession(
		val clientPublicKey: String,
		val sharedSecret: ByteArray,
	)

	private companion object {
		const val PAGE_SIZE = 24
		const val MAX_PAGE_REQUESTS = 128
		const val MAX_READER_SESSIONS = 16
		const val NONCE_SIZE = 16
		const val RESPONSE_PREFIX_SIZE = 128
		const val SECRETSTREAM_HEADER_SIZE = 24
		const val SECRETSTREAM_KEY_SIZE = 32
		const val SECRETSTREAM_NONCE_SIZE = 8
		const val SECRETSTREAM_ABYTES = 17
		const val SECRETSTREAM_TAG_REKEY = 2
		const val HCHACHA_NONCE_SIZE = 16
		const val CHACHA_BLOCK_SIZE = 64
		const val MAC_SIZE = 16
		const val ENCRYPTED_CHUNK_SIZE = 65553
		const val CURVE25519_KEY_SIZE = 32
		val RANDOM = SecureRandom()
		val CHAPTER_NUMBER_REGEX = Regex("""(?:chapitre|chapter)?\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
		val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
		val CHACHA_CONSTANTS = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)
		val BYTE_MASK = BigInteger.valueOf(0xff)
		val CURVE_P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
		val A24 = BigInteger.valueOf(121666)
		val X25519_BASE_POINT = ByteArray(CURVE25519_KEY_SIZE).also { it[0] = 9 }
	}
}
