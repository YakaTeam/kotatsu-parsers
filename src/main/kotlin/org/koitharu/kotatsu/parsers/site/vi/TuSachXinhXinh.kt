package org.koitharu.kotatsu.parsers.site.vi

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.text.DateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("TUSACHXINHXINH", "Tủ Sách Xinh Xinh", "vi", ContentType.MANGA)
internal class TuSachXinhXinh(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TUSACHXINHXINH, 36) {

	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("tusachxinhxinh12.online")

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchTags(),
	)

	private suspend fun fetchTags(): Set<MangaTag> {
		return webClient.httpGet("https://$domain").parseHtml()
			.select("#nav-tags .tags a[href*=/the-loai/]")
			.mapToSet(::parseTag)
	}

	private fun parseTag(tagEl: Element): MangaTag {
		return MangaTag(
			title = tagEl.text().toTitleCase(),
			key = tagEl.attrAsRelativeUrl("href"),
			source = source,
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
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

		val tag = filter.tags.oneOrThrowIfMany()
		if (tag != null) {
			if (page > 1) return emptyList()
			return webClient.httpGet("${tag.key}/".toAbsoluteUrl(domain)).parseHtml()
				.select("ul.single-list-comic li.position-relative")
				.map(::parseListItem)
		}

		return when (order) {
			SortOrder.POPULARITY -> {
				if (page > 1) return emptyList()
				webClient.httpGet("/nhieu-xem-nhat/".toAbsoluteUrl(domain)).parseHtml()
					.select("ul.most-views.single-list-comic li.position-relative")
					.map(::parseListItem)
			}

			else -> {
				val url = if (page == 1) "/" else "/page/$page/"
				val doc = webClient.httpGet(url.toAbsoluteUrl(domain)).parseHtml()
				parseLatestPage(doc)
			}
		}
	}

	private fun parseListItem(element: Element): Manga {
		val linkElement = element.selectFirstOrThrow("p.super-title a")
		val relativeUrl = linkElement.attrAsRelativeUrl("href")
		return Manga(
			id = generateUid(relativeUrl),
			title = linkElement.text(),
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = element.selectFirst("img.list-left-img")?.src(),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source,
		)
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
					coverUrl = element.selectFirst("img")?.src(),
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
			tags = doc.select("a[href*=/the-loai/]").mapToSet(::parseTag),
			description = doc.selectFirst("div.text-justify")?.html(),
			contentRating = if (doc.getElementById("adult-modal") != null) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			chapters = doc.select(".table-scroll table tr a.text-capitalize")
				.mapChapters(reversed = true) { index, element ->
					val url = element.attrAsRelativeUrl("href")
					val dateText = element.closest("tr")?.selectFirst("td.hidden-xs.hidden-sm")?.text()
					val rawName = element.text()
					val chapMatch = CHAPTER_REGEX.find(rawName)
					MangaChapter(
						id = generateUid(url),
						title = chapMatch?.value?.trim(),
						number = chapMatch?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1f),
						volume = 0,
						url = url,
						scanlator = null,
						uploadDate = dateFormat.parseSafe(dateText),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val script = doc.selectFirst("#view-chapter script")?.data()

		if (script != null) {
			val encryptedContent = script
				.substringAfter('"')
				.substringBeforeLast('"')
				.replace("\\\"", "\"")

			val decryptedHtml = decryptContent(encryptedContent)
			return Jsoup.parse(decryptedHtml).select("img").mapNotNull { img ->
				val url = img.attrOrNull("data-${KEY_PART_1.lowercase()}")
					?.let(::deobfuscateUrl)
					?: img.src()
					?: return@mapNotNull null
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		return doc.select("#view-chapter img")
			.ifEmpty { doc.select(".chapter-content img, .reading-content img, .content-chapter img") }
			.mapNotNull { img ->
				val url = img.src() ?: return@mapNotNull null
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private fun decryptContent(secret: String): String {
		val json = JSONObject(secret)
		val salt = json.getString("salt").decodeHex()
		val iv = json.getString("iv").decodeHex()
		val cipherText = context.decodeBase64(json.getString("ciphertext"))

		val keySpec = PBEKeySpec(PASSPHRASE.toCharArray(), salt, 999, 256)
		val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(keySpec).encoded
		val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(iv))
		return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
	}

	private fun deobfuscateUrl(url: String): String = url
		.replace(KEY_PART_1, ".")
		.replace(KEY_PART_2, ":")
		.replace(KEY_PART_3, "/")

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
		return parseLatestPage(doc)
	}



	private fun String.decodeHex(): ByteArray {
		check(length % 2 == 0) { "Must have an even length" }
		return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
	}

	companion object {
		private const val KEY_PART_1 = "qX3xRL"
		private const val KEY_PART_2 = "guhD2Z"
		private const val KEY_PART_3 = "9f7sWJ"
		private const val PASSPHRASE = KEY_PART_1 + KEY_PART_2 + KEY_PART_3

		private val SMALL_THUMBNAIL_REGEX = Regex("-150x150(\\.[a-zA-Z]+)$")

		private val CHAPTER_REGEX = Regex("Chap\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)

		private val dateFormat: DateFormat = SimpleDateFormat("dd/MM/yy", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
		}
	}
}
