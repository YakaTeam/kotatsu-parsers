package org.koitharu.kotatsu.parsers.site.vi

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("TUSACHXINHXINH", "Tủ Sách Xinh Xinh", "vi", ContentType.MANGA)
internal class TuSachXinhXinhParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TUSACHXINHXINH, 36) {

	override val configKeyDomain = ConfigKey.Domain("tusachxinhxinh12.online")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchTags(),
	)

	// ========================= List ===========================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Search by query
		if (!filter.query.isNullOrEmpty()) {
			if (page > 1) return emptyList()

			val payload = "action=searchtax&keyword=${filter.query.urlEncoded()}"
			return webClient.httpPost("/wp-admin/admin-ajax.php".toAbsoluteUrl(domain), payload)
				.parseJson().getJSONArray("data")
				.mapJSONNotNull { jo ->
					val link = jo.getString("link")
					if (!link.contains("/truyen-tranh/")) return@mapJSONNotNull null

					val relativeUrl = link.toRelativeUrl(domain)
					Manga(
						id = generateUid(relativeUrl),
						title = jo.getString("title"),
						altTitles = emptySet(),
						url = relativeUrl,
						publicUrl = relativeUrl.toAbsoluteUrl(domain),
						rating = RATING_UNKNOWN,
						contentRating = null,
						coverUrl = jo.optString("img").let { img ->
							if (img.isNullOrBlank()) null else img.replace(SMALL_THUMBNAIL_REGEX, "$1")
						},
						tags = emptySet(),
						state = null,
						authors = emptySet(),
						largeCoverUrl = null,
						description = null,
						chapters = null,
						source = source,
					)
				}.distinctBy { it.url }
		}

		// Filter by tag
		val tag = filter.tags.oneOrThrowIfMany()
		if (tag != null) {
			if (page > 1) return emptyList()
			val doc = webClient.httpGet("/${tag.key}/".toAbsoluteUrl(domain)).parseHtml()
			return parseFilterPage(doc)
		}

		// Order-based listing
		return when (order) {
			SortOrder.POPULARITY -> {
				if (page > 1) return emptyList()
				val doc = webClient.httpGet("/nhieu-xem-nhat/".toAbsoluteUrl(domain)).parseHtml()
				parsePopularPage(doc)
			}

			else -> {
				// Default: latest updates with pagination
				val url = if (page == 1) "/" else "/page/$page/"
				val doc = webClient.httpGet(url.toAbsoluteUrl(domain)).parseHtml()
				parseLatestPage(doc)
			}
		}
	}

	// ========================= Parsing ========================

	private fun parsePopularPage(doc: Document): List<Manga> {
		return doc.select("ul.most-views.single-list-comic li.position-relative").map { element ->
			val linkElement = element.selectFirstOrThrow("p.super-title a")
			val relativeUrl = linkElement.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(relativeUrl),
				title = linkElement.text(),
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = element.selectFirst("img.list-left-img")?.lazyImgUrl(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	private fun parseLatestPage(doc: Document): List<Manga> {
		return doc.select(".col-md-3.col-xs-6.comic-item")
			.filter { element ->
				val href = element.selectFirst("a")?.attrOrNull("href").orEmpty()
				href.contains("/truyen-tranh/")
			}
			.map { element ->
				val titleEl = element.selectFirstOrThrow("h3.comic-title")
				val linkEl = titleEl.parent()!!
				val relativeUrl = linkEl.attrAsRelativeUrl("href")
				Manga(
					id = generateUid(relativeUrl),
					title = titleEl.text(),
					altTitles = emptySet(),
					url = relativeUrl,
					publicUrl = relativeUrl.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = element.selectFirst("img")?.lazyImgUrl(),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					largeCoverUrl = null,
					description = null,
					chapters = null,
					source = source,
				)
			}
	}

	private fun parseFilterPage(doc: Document): List<Manga> {
		return doc.select("ul.single-list-comic li.position-relative").map { element ->
			val linkElement = element.selectFirstOrThrow("p.super-title a")
			val relativeUrl = linkElement.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(relativeUrl),
				title = linkElement.text(),
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = element.selectFirst("img.list-left-img")?.lazyImgUrl(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	// ========================= Details ========================

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val author = doc.selectFirst("strong:contains(Tác giả) + span")?.textOrNull()
		return manga.copy(
			altTitles = setOfNotNull(
				doc.selectFirst("strong:contains(Tên khác) + span")?.textOrNull(),
			),
			authors = setOfNotNull(author),
			state = when (doc.selectFirst("span.comic-stt")?.text()) {
				"Đang tiến hành" -> MangaState.ONGOING
				"Hoàn thành", "Trọn bộ" -> MangaState.FINISHED
				else -> null
			},
			tags = doc.select("a[href*=/the-loai/]").mapToSet { a ->
				MangaTag(
					key = a.attrAsRelativeUrl("href").removeSuffix("/").substringAfterLast("/"),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = doc.selectFirst("div.text-justify")?.html(),
			contentRating = if (doc.getElementById("adult-modal") != null) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			chapters = doc.select(".table-scroll table tr").mapNotNull { row ->
				val linkElement = row.selectFirst("a.text-capitalize") ?: return@mapNotNull null
				val url = linkElement.attrAsRelativeUrl("href")
				val dateText = row.selectFirst("td.hidden-xs.hidden-sm")?.text()

				MangaChapter(
					id = generateUid(url),
					title = parseChapterName(linkElement.text()),
					number = 0f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = parseChapterDate(dateText),
					branch = null,
					source = source,
				)
			}.reversed(),
		)
	}

	// ========================= Pages =========================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val html = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseRaw()

		// Try encrypted content first
		val match = ENCRYPTED_CONTENT_REGEX.find(html)
		if (match != null) {
			val encryptedJsonString = match.groupValues[1]
				.replace("\\\"", "\"")
				.replace("\\/", "/")

			val decryptedHtml = decryptContent(encryptedJsonString)
			val images = extractImagesFromDecryptedHtml(decryptedHtml)
			if (images.isNotEmpty()) {
				return images.mapIndexed { index, url ->
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		}

		// Fallback to regular image extraction
		val doc = Jsoup.parse(html)
		return doc.select("#view-chapter img")
			.ifEmpty { doc.select(".chapter-content img, .reading-content img, .content-chapter img") }
			.mapNotNull { element ->
				val imageUrl = element.attrOrNull("data-src")?.ifEmpty { null }
					?: element.attrOrNull("src")?.ifEmpty { null }
					?: return@mapNotNull null
				if (imageUrl.startsWith("data:")) return@mapNotNull null
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
	}

	// ========================= Helpers ========================

	private fun org.jsoup.nodes.Element.lazyImgUrl(): String? {
		val url = attrOrNull("data-lazy-src")?.ifEmpty { null }
			?: attrOrNull("src")?.takeUnless { it.startsWith("data:") }?.ifEmpty { null }
			?: return null
		return url.replace(SMALL_THUMBNAIL_REGEX, "$1")
	}

	private fun parseChapterName(rawName: String): String {
		val match = CHAPTER_NAME_REGEX.find(rawName)
		return match?.value?.trim() ?: rawName.substringAfterLast("–").substringAfterLast("-").trim()
	}

	private fun parseChapterDate(dateStr: String?): Long {
		if (dateStr.isNullOrBlank()) return 0L
		return try {
			DATE_FORMAT.parse(dateStr)?.time ?: 0L
		} catch (_: Exception) {
			0L
		}
	}

	// ========================= Decryption =====================

	private fun decryptContent(encryptedJsonString: String): String {
		val json = JSONObject(encryptedJsonString)
		val salt = json.getString("salt").decodeHex()
		val iv = json.getString("iv").decodeHex()
		val cipherText = context.decodeBase64(json.getString("ciphertext"))

		val keySpec = PBEKeySpec(PASSPHRASE.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
		val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(keySpec).encoded
		val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(iv))
		return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
	}

	private fun extractImagesFromDecryptedHtml(html: String): List<String> {
		val doc = Jsoup.parse(html)
		return doc.select("img").mapNotNull { img ->
			val dataAttr = img.attrOrNull("data-${KEY_PART_1.lowercase()}")
			if (!dataAttr.isNullOrBlank()) {
				return@mapNotNull deobfuscateUrl(dataAttr)
			}

			val src = img.attrOrNull("src") ?: return@mapNotNull null
			if (src.startsWith("data:")) return@mapNotNull null
			src.takeIf { it.isNotBlank() && it.startsWith("http") }
		}
	}

	private fun deobfuscateUrl(url: String): String = url
		.replace(KEY_PART_1, ".")
		.replace(KEY_PART_2, ":")
		.replace(KEY_PART_3, "/")

	private fun String.decodeHex(): ByteArray {
		check(length % 2 == 0) { "Must have an even length" }
		return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
	}

	// ========================= Tags ==========================

	private fun fetchTags(): Set<MangaTag> {
		return GENRES.mapToSet { (name, slug) ->
			MangaTag(
				key = "the-loai/$slug",
				title = name.toTitleCase(),
				source = source,
			)
		}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
		return parseLatestPage(doc)
	}

	companion object {
		private const val KEY_PART_1 = "qX3xRL"
		private const val KEY_PART_2 = "guhD2Z"
		private const val KEY_PART_3 = "9f7sWJ"
		private const val PASSPHRASE = KEY_PART_1 + KEY_PART_2 + KEY_PART_3

		private const val PBKDF2_ITERATIONS = 999
		private const val KEY_SIZE_BITS = 256

		private val ENCRYPTED_CONTENT_REGEX = Regex(
			"""var\s+htmlContent\s*=\s*"(.*?)"\s*;""",
			RegexOption.DOT_MATCHES_ALL,
		)

		private val CHAPTER_NAME_REGEX = Regex("Chap\\s*\\d+(\\.\\d+)?", RegexOption.IGNORE_CASE)

		private val SMALL_THUMBNAIL_REGEX = Regex("-150x150(\\.[a-zA-Z]+)$")

		private val DATE_FORMAT by lazy {
			SimpleDateFormat("dd/MM/yy", Locale.ROOT).apply {
				timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
			}
		}

		private val GENRES = listOf(
			"15+" to "15",
			"18+" to "18",
			"Action" to "action",
			"Adaptation" to "adaptation",
			"Adult" to "adult",
			"Adventure" to "adventure",
			"Bách Hợp" to "bach-hop",
			"Bí ẩn" to "bi-an",
			"Bi Kịch" to "bi-kich",
			"BL" to "bl",
			"Chữa Lành" to "chua-lanh",
			"Chuyển Sinh" to "chuyen-sinh",
			"Cổ Đại" to "co-dai",
			"Cổ Trang" to "co-trang",
			"Comedy" to "comedy",
			"Cooking" to "cooking",
			"Crime" to "crime",
			"Dark" to "dark",
			"Drama" to "drama",
			"Đam Mỹ" to "dam-my",
			"Đô Thị" to "do-thi",
			"Ecchi" to "ecchi",
			"Fantasy" to "fantasy",
			"Full Color" to "full-color",
			"GL" to "gl",
			"Hài Hước" to "hai-huoc",
			"Harem" to "harem",
			"Hệ Thống" to "he-thong",
			"Hiện Đại" to "hien-dai",
			"Historical" to "historical",
			"Học Đường" to "hoc-duong",
			"Horror" to "horror",
			"Huyền Huyễn" to "huyen-huyen",
			"Isekai" to "isekai",
			"Josei" to "josei",
			"Kinh Dị" to "kinh-di",
			"Lãng Mạn" to "lang-man",
			"Magic" to "magic",
			"Manga" to "manga",
			"Manhua" to "manhua",
			"Manhwa" to "manhwa",
			"Martial Arts" to "martial-arts",
			"Mature" to "mature",
			"Mystery" to "mystery",
			"Ngôn Tình" to "ngon-tinh",
			"Oneshot" to "oneshot",
			"Psychological" to "psychological",
			"Reincarnation" to "reincarnation",
			"Romance" to "romance",
			"School Life" to "school-life",
			"Shoujo" to "shoujo",
			"Shoujo Ai" to "shoujo-ai",
			"Shounen" to "shounen",
			"Shounen Ai" to "shounen-ai",
			"Slice of Life" to "slice-of-life",
			"Supernatural" to "supernatural",
			"Thriller" to "thriller",
			"Tragedy" to "tragedy",
			"Webtoon" to "webtoon",
			"Xuyên Không" to "xuyen-khong",
			"Yaoi" to "yaoi",
			"Yuri" to "yuri",
		)
	}
}
