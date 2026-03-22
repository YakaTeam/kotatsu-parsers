package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MIMIHENTAI", "MimiHentai", "vi", type = ContentType.HENTAI)
internal class MimiHentai(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MIMIHENTAI, 18) {

	private val apiSuffix = "api/v2/manga"
	override val configKeyDomain = ConfigKey.Domain("mimimoe.moe")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon(
					"https://raw.githubusercontent.com/dragonx943/plugin-sdk/refs/heads/sources/mimihentai/app/src/main/ic_launcher-playstore.png",
					512,
					null),
			),
			domain,
		)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isAuthorSearchSupported = true,
			isTagsExclusionSupported = true,
		)

	init {
		setFirstPage(0)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(availableTags = fetchTags())

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append("$domain/$apiSuffix")

			if (!filter.query.isNullOrEmpty() ||
				!filter.author.isNullOrEmpty() ||
				filter.tags.isNotEmpty()
			) {
				append("/advance-search?page=")
				append(page)
				append("&max=18") // page size, avoid rate limit

				if (!filter.query.isNullOrEmpty()) {
					append("&name=")
					append(filter.query.urlEncoded())
				}

				if (!filter.author.isNullOrEmpty()) {
					append("&author=")
					append(filter.author.urlEncoded())
				}

				if (filter.tags.isNotEmpty()) {
					append("&genre=")
					append(filter.tags.joinToString(",") { it.key })
				}

				if (filter.tagsExclude.isNotEmpty()) {
					append("&ex=")
					append(filter.tagsExclude.joinToString(",") { it.key })
				}

				append("&sort=")
				append(
					when (order) {
						SortOrder.UPDATED -> "updated_at"
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.POPULARITY -> "follows"
						SortOrder.POPULARITY_TODAY,
						SortOrder.POPULARITY_WEEK,
						SortOrder.POPULARITY_MONTH -> "views"
						SortOrder.RATING -> "likes"
						else -> ""
					}
				)
			}

			else {
				append(
					when (order) {
						SortOrder.UPDATED -> "/tatcatruyen?page=$page&sort=updated_at"
						SortOrder.ALPHABETICAL -> "/tatcatruyen?page=$page&sort=title"
						SortOrder.POPULARITY -> "/tatcatruyen?page=$page&sort=follows"
						SortOrder.POPULARITY_TODAY -> "/tatcatruyen?page=$page&sort=views"
						SortOrder.POPULARITY_WEEK -> "/top-manga?page=$page&timeType=1&limit=18"
						SortOrder.POPULARITY_MONTH -> "/top-manga?page=$page&timeType=2&limit=18"
						SortOrder.RATING -> "/tatcatruyen?page=$page&sort=likes"
						else -> "/tatcatruyen?page=$page&sort=updated_at" // default
					}
				)

				if (filter.tagsExclude.isNotEmpty()) {
					append("&ex=")
					append(filter.tagsExclude.joinToString(",") { it.key })
				}
			}
		}

		val raw = webClient.httpGet(url)
		return if (url.contains("/top-manga")) {
			val data = raw.parseJsonArray()
			parseTopMangaList(data)
		} else {
			val data = raw.parseJson().getJSONArray("data")
			parseMangaList(data)
		}
	}

	private fun parseTopMangaList(data: JSONArray): List<Manga> {
		return data.mapJSON { jo ->
			val id = jo.getLong("id")
			val title = jo.getString("title").takeIf { it.isNotEmpty() } ?: "Web chưa đặt tên"
			val description = jo.getStringOrNull("description")

			val differentNames = mutableSetOf<String>().apply {
				jo.optJSONArray("differentNames")?.let { namesArray ->
					for (i in 0 until namesArray.length()) {
						namesArray.optString(i)?.takeIf { it.isNotEmpty() }?.let { name ->
							add(name)
						}
					}
				}
			}

			val authors = jo.optJSONArray("authors")?.mapJSON {
				it.getString("name")
			}?.toSet() ?: emptySet()

			val tags = jo.optJSONArray("genres")?.mapJSON { genre ->
				MangaTag(
					key = genre.getLong("id").toString(),
					title = genre.getString("name"),
					source = source
				)
			}?.toSet() ?: emptySet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = differentNames,
				url = "/$apiSuffix/info/$id",
				publicUrl = "https://$domain/g/$id",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = jo.getString("coverUrl"),
				state = null,
				description = description,
				tags = tags,
				authors = authors,
				source = source,
			)
		}
	}

	private fun parseMangaList(data: JSONArray): List<Manga> {
		return data.mapJSON { jo ->
			val id = jo.getLong("id")
			val title = jo.getString("title").takeIf { it.isNotEmpty() } ?: "Web chưa đặt tên"
			val description = jo.getStringOrNull("description")

			val differentNames = mutableSetOf<String>().apply {
				jo.optJSONArray("differentNames")?.let { namesArray ->
					for (i in 0 until namesArray.length()) {
						namesArray.optString(i)?.takeIf { it.isNotEmpty() }?.let { name ->
							add(name)
						}
					}
				}
			}

			val authors = jo.getJSONArray("authors").mapJSON {
				it.getString("name")
			}.toSet()

			val tags = jo.getJSONArray("genres").mapJSON { genre ->
				MangaTag(
					key = genre.getLong("id").toString(),
					title = genre.getString("name"),
					source = source
				)
			}.toSet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = differentNames,
				url = "/$apiSuffix/info/$id",
				publicUrl = "https://$domain/g/$id",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = jo.getString("coverUrl"),
				state = null,
				tags = tags,
				description = description,
				authors = authors,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val json = webClient.httpGet(url).parseJson()
		val id = json.getLong("id")
		val description = json.getStringOrNull("description")
		val uploaderName = json.getJSONObject("uploader").getString("displayName")

		val tags = json.getJSONArray("genres").mapJSONToSet { jo ->
			MangaTag(
				title = jo.getString("name").toTitleCase(sourceLocale),
				key = jo.getLong("id").toString(),
				source = source,
			)
		}

		val urlChaps = "https://$domain/$apiSuffix/gallery/$id"
		val parsedChapters = webClient.httpGet(urlChaps).parseJsonArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US)
		val chapters = parsedChapters.mapJSON { jo ->
			MangaChapter(
				id = generateUid(jo.getLong("id")),
				title = jo.getStringOrNull("title"),
				number = jo.getFloatOrDefault("order", 0f),
				url = "/$apiSuffix/chapter?id=${jo.getLong("id")}",
				uploadDate = dateFormat.parse(jo.getString("createdAt"))?.time ?: 0L,
				source = source,
				scanlator = uploaderName,
				branch = null,
				volume = 0,
			)
		}.reversed()

		return manga.copy(
			tags = tags,
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseJson()
		return json.getJSONArray("pages").mapJSON { jo ->
			val imageUrl = jo.getString("imageUrl")
			val gt = jo.getStringOrNull("drm")
			MangaPage(
				id = generateUid(imageUrl),
				url = if (gt != null) "$imageUrl#$GT$gt" else imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		val fragment = response.request.url.fragment

		if (fragment == null || !fragment.contains(GT)) {
			return response
		}

		return context.redrawImageResponse(response) { bitmap ->
			val ori = fragment.substringAfter(GT)
			runBlocking {
				extractMetadata(bitmap, ori)
			}
		}
	}

	private fun extractMetadata(bitmap: Bitmap, ori: String): Bitmap {
		val gt = decodeGt(ori)

		// Cre: FiorenMas
		// Refer from https://github.com/keiyoushi/extensions-source/pull/13977
		// Parse format: v1|sw:W|sh:H|ID@x,y,w,h>ID|...
		val mapMatch = MAP_REGEX.find(gt) ?: return bitmap
		val sw = mapMatch.groupValues[1].toIntOrNull() ?: return bitmap
		val sh = mapMatch.groupValues[2].toIntOrNull() ?: return bitmap
		val rawSegments = mapMatch.groupValues[3].split('|')

		data class Seg(val id: String, val x: Int, val y: Int, val w: Int, val h: Int, val srcId: String)

		val segments = rawSegments.mapNotNull { seg ->
			val m = SEGMENT_REGEX.matchEntire(seg) ?: return@mapNotNull null
			Seg(
				id = m.groupValues[1],
				x = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
				y = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null,
				w = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null,
				h = m.groupValues[5].toIntOrNull() ?: return@mapNotNull null,
				srcId = m.groupValues[6],
			)
		}
		if (segments.size != 9) return bitmap

		val fullW = bitmap.width
		val fullH = bitmap.height

		val working = context.createBitmap(sw, sh).also { k ->
			k.drawBitmap(bitmap, Rect(0, 0, sw, sh), Rect(0, 0, sw, sh))
		}

		val byId = segments.associateBy { it.id }
		val result = context.createBitmap(fullW, fullH)
		for (dst in segments) {
			val src = byId[dst.srcId] ?: continue
			val drawW = minOf(dst.w, sw - src.x, fullW - dst.x)
			val drawH = minOf(dst.h, sh - src.y, fullH - dst.y)
			if (drawW <= 0 || drawH <= 0) continue
			result.drawBitmap(
				working,
				Rect(src.x, src.y, src.x + drawW, src.y + drawH),
				Rect(dst.x, dst.y, dst.x + drawW, dst.y + drawH),
			)
		}

		if (sh < fullH) {
			result.drawBitmap(bitmap, Rect(0, sh, fullW, fullH), Rect(0, sh, fullW, fullH))
		}
		if (sw < fullW) {
			result.drawBitmap(bitmap, Rect(sw, 0, fullW, sh), Rect(sw, 0, fullW, sh))
		}

		return result
	}

	private fun decodeGt(drm: String): String {
		val payload = drm.dropLast(2)
		if (payload.isEmpty() || payload.length % 2 != 0) return drm

		val encryptedBytes = parseHex(payload) ?: return drm
		val key = deriveKey(drm).toByteArray(Charsets.UTF_8)
		val decrypted = ByteArray(encryptedBytes.size) { i ->
			(encryptedBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
		}
		return decrypted.toString(Charsets.UTF_8)
	}

	private fun parseHex(hex: String): ByteArray? {
		if (hex.length % 2 != 0) return null
		return try {
			ByteArray(hex.length / 2) { i ->
				hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
			}
		} catch (_: NumberFormatException) { null }
	}

	private fun deriveKey(drm: String): String {
		if (drm.isEmpty()) return KEY_TABLE[0]
		val last = drm.last().digitToIntOrNull() ?: return KEY_TABLE[0]
		val prev = if (drm.length >= 2) drm[drm.length - 2].digitToIntOrNull() ?: 0 else 0
		val index = prev * 10 + last
		return when {
			index in KEY_TABLE.indices -> KEY_TABLE[index]
			index > 49 -> FALLBACK_HIGH_KEY
			else -> KEY_TABLE[0]
		}
	}

    private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://$domain/$apiSuffix/genres"
		val response = webClient.httpGet(url).parseJsonArray()
		return response.mapJSONToSet { jo ->
			MangaTag(
				title = jo.getString("name").toTitleCase(sourceLocale),
				key = jo.getLong("id").toString(),
				source = source,
			)
		}
	}

	companion object {
		private const val GT = "gt="
		private val MAP_REGEX = Regex(
			"""v1\|sw:(\d+)\|sh:(\d+)\|((?:[0-2]{2}@\d+,\d+,\d+,\d+>[0-2]{2}\|){8}[0-2]{2}@\d+,\d+,\d+,\d+>[0-2]{2})"""
		)
		private val SEGMENT_REGEX = Regex("""([0-2]{2})@(\d+),(\d+),(\d+),(\d+)>([0-2]{2})""")
		private const val FALLBACK_HIGH_KEY = "3.8672468480107685"
		private val KEY_TABLE = arrayOf(
			"10.094534846668065", "7.830415197347441",  "16.99376503124865",
			"13.206661543266259", "7.316826787559291",  "10.4581449488877",
			"4.175296661012279",  "10.175873934720146", "16.434397649190988",
			"7.009874458739787",  "13.575803014637726", "29.279163189766738",
			"10.750231018960623", "10.094342559715047", "28.658921501338497",
			"25.793772667060153", "25.79379811121803",  "15.748609882695796",
			"7.534001429117513",  "28.907337185559953", "13.22733409213105",
			"7.266890610739514",  "6.669662254093193",  "13.227334074999675",
			"28.564557448091602", "16.619459066493555", "6.969300123013573",
			"26.138465628985216", "13.317787084345925", "19.228026822727582",
			"10.772577818410019", "3.7994766625978458", "29.188688520919868",
			"16.369262643760873", "7.631192793297872",  "22.635116664169104",
			"7.008299254805293",  "19.918386626762093", "10.432972563129333",
			"4.367499602056042",  "26.166382731558237", "16.342370610615042",
			"7.515015438908234",  "29.295296241376956", "32.16934026452751",
			"4.177784547778614",  "4.159160201118592",  "10.436068860553476",
			"4.1529681276331845", "10.436068612003677",
		)
	}
}
