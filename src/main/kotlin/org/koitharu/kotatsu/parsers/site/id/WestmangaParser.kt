package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class WestmangaParser(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.WESTMANGA) {

    override val configKeyDomain = ConfigKey.Domain("westmanga.me")
	private val apiUrl = "https://data.$domain"

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = fetchTags()
    )

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val page = (offset / 20) + 1

        val urlBuilder = "$apiUrl/api/contents".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", 20.toString())
            .addQueryParameter("type", "Comic")

        if (!filter.query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("q", filter.query)
        } else {
            val orderBy = when (order) {
                SortOrder.POPULARITY -> "Popular"
                SortOrder.UPDATED -> "Update"
                SortOrder.NEWEST -> "Added"
                SortOrder.ALPHABETICAL -> "Az"
                else -> "Default"
            }
            if (orderBy != "Default") {
                urlBuilder.addQueryParameter("orderBy", orderBy)
            }
        }

        if (filter.tags.isNotEmpty()) {
			filter.tags.forEach {
				urlBuilder.addQueryParameter("genre[]", it.key)
			}
        }

        val json = apiRequest(urlBuilder.build().toString())
        val data = json.getJSONArray("data")
		return data.mapJSON { parseManga(it) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val url = "$apiUrl/api/comic/$slug"
        val json = apiRequest(url).getJSONObject("data")

        return manga.copy(
            title = json.getString("title"),
            description = json.optString("synopsis", "").let { Jsoup.parse(it).text() },
            coverUrl = json.getString("cover"),
            authors = setOfNotNull(json.optString("author")),
            state = parseStatus(json.optString("status")),
            chapters = parseChapters(json.getJSONArray("chapters"))
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.removeSuffix("/").substringAfterLast("/")
        val url = "$apiUrl/api/v/$slug"
        val json = apiRequest(url).getJSONObject("data")
        return json.getJSONArray("images").asTypedList<String>().map {
			MangaPage(
				id = it.toLong(),
				url = it,
				preview = null,
				source = source
			)
		}
    }

    private fun parseManga(json: JSONObject): Manga {
        val slug = json.getString("slug")
        return Manga(
            id = slug.longHashCode(),
            title = json.getString("title"),
            altTitles = emptySet(),
            url = "/manga/$slug",
            publicUrl = "$domain/manga/$slug",
            rating = RATING_UNKNOWN,
            contentRating = sourceContentRating,
            coverUrl = json.getString("cover"),
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
    }

    private fun parseChapters(array: org.json.JSONArray): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            chapters.add(MangaChapter(
                id = item.getString("slug").longHashCode(),
                title = "Chapter ${item.optString("number")}",
                number = item.optString("number").toFloatOrNull() ?: 0f,
                volume = 0,
                url = "/${item.getString("slug")}",
                scanlator = null,
                uploadDate = parseDate(item.optString("updated_at")),
                branch = null,
                source = source
            ))
        }
        return chapters.reversed()
    }

    private fun parseStatus(status: String): MangaState {
        return when (status.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            else -> MangaState.ONGOING
        }
    }

    private fun parseDate(dateStr: String): Long {
        // Try parsing as timestamp (long/string)
        dateStr.toLongOrNull()?.let { return it * 1000 }

        // Try parsing as ISO string
        try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC") // API often returns UTC or server time.
            return format.parseSafe(dateStr)
        } catch (_: Exception) {
            return 0L
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val json = apiRequest("$apiUrl/api/contents/genres")
        val data = json.getJSONArray("data")
		return data.mapJSONToSet {
			MangaTag(
				key = it.getInt("id").toString(),
				title = it.getString("name"),
				source = source,
			)
		}
    }

	// I have no idea about it ¯\_(ツ)_/¯
    private suspend fun apiRequest(url: String): JSONObject {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val message = "wm-api-request"
        val httpUrl = url.toHttpUrl()
        val key = timestamp + "GET" + httpUrl.encodedPath + ACCESS_KEY + SECRET_KEY

        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val signature = hash.joinToString("") { "%02x".format(it) }

        val headers = Headers.Builder()
            .add("Referer", "$domain/")
            .add("x-wm-request-time", timestamp)
            .add("x-wm-accses-key", ACCESS_KEY)
            .add("x-wm-request-signature", signature)
            .build()

        val response = webClient.httpGet(httpUrl, headers)
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return JSONObject(response.body.string())
    }

	companion object {
		private const val ACCESS_KEY = "WM_WEB_FRONT_END"
		private const val SECRET_KEY = "xxxoidj"
	}
}
