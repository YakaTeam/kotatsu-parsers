package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class WestmangaParser(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.WESTMANGA) {

    override val configKeyDomain = ConfigKey.Domain("westmanga.tv")
	private val apiUrl get() = "https://data.$domain"

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
        val page = (offset / PAGE_SIZE) + 1

        val urlBuilder = "$apiUrl/api/contents".toHttpUrl().newBuilder()

        if (!filter.query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("q", filter.query)
        }

        urlBuilder.addQueryParameter("page", page.toString())
        urlBuilder.addQueryParameter("per_page", PAGE_SIZE.toString())

        if (filter.query.isNullOrBlank()) {
            val orderBy = when (order) {
                SortOrder.POPULARITY -> "Popular"
                SortOrder.UPDATED -> "Update"
                SortOrder.NEWEST -> "Added"
                SortOrder.ALPHABETICAL -> "Az"
                else -> null
            }
            if (orderBy != null) {
                urlBuilder.addQueryParameter("orderBy", orderBy)
            }
        }

        urlBuilder.addQueryParameter("project", "false")

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
            description = json.optString("sinopsis", json.optString("synopsis", ""))
				.let { Jsoup.parse(it).text() },
            coverUrl = json.optString("cover").ifBlank { manga.coverUrl.orEmpty() },
            authors = setOfNotNull(json.optString("author").takeIf { it.isNotBlank() }),
            state = parseStatus(json.optString("status")),
            chapters = parseChapters(json.getJSONArray("chapters")),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.removeSuffix("/").substringAfterLast("/")
        val url = "$apiUrl/api/v/$slug"
        val json = apiRequest(url).getJSONObject("data")
        return json.getJSONArray("images").asTypedList<String>().map {
			MangaPage(
				id = generateUid(it),
				url = it,
				preview = null,
				source = source,
			)
		}
    }

    private fun parseManga(json: JSONObject): Manga {
        val slug = json.getString("slug")
        return Manga(
            id = slug.longHashCode(),
            title = json.getString("title"),
            altTitles = emptySet(),
            url = "/comic/$slug",
            publicUrl = "https://$domain/comic/$slug",
            rating = RATING_UNKNOWN,
            contentRating = sourceContentRating,
            coverUrl = json.optString("cover"),
            tags = emptySet(),
            state = parseStatus(json.optString("status")),
            authors = emptySet(),
            source = source,
        )
    }

    private fun parseChapters(array: JSONArray): List<MangaChapter> {
		return array.mapChapters(reversed = true) { i, item ->
			val chapterSlug = item.getString("slug")
			MangaChapter(
				id = chapterSlug.longHashCode(),
                title = "Chapter ${item.optString("number")}",
                number = item.optString("number").toFloatOrNull()
					?: (i + 1).toFloat(),
                volume = 0,
                url = "/view/$chapterSlug",
                scanlator = null,
                uploadDate = parseDate(item.optJSONObject("updated_at")),
                branch = null,
                source = source,
			)
		}
    }

    private fun parseStatus(status: String): MangaState? {
        return when (status.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            else -> null
        }
    }

    private fun parseDate(updatedAt: JSONObject?): Long {
        val seconds = updatedAt?.optLong("time", 0L) ?: 0L
        return if (seconds > 0) seconds * 1000 else 0L
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
            .add(CommonHeaders.REFERER, "https://$domain/")
            .add(CommonHeaders.ORIGIN, "https://$domain")
            .add(CommonHeaders.X_WM_REQUEST_TIME, timestamp)
            .add(CommonHeaders.X_WM_ACCESS_KEY, ACCESS_KEY)
            .add(CommonHeaders.X_WM_REQUEST_SIGNATURE, signature)
            .build()

        val response = webClient.httpGet(httpUrl, headers)
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return JSONObject(response.body.string())
    }

	companion object {
		private const val ACCESS_KEY = "WM_WEB_FRONT_END"
		private const val SECRET_KEY = "xxxoidj"
		private const val PAGE_SIZE = 40
	}
}
