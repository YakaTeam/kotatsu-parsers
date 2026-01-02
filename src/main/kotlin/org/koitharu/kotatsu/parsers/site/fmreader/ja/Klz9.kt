package org.koitharu.kotatsu.parsers.site.fmreader.ja

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KLZ9", "Klz9", "ja")
internal class Klz9(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KLZ9, pageSize = 36) {

    private val apiUrl = "https://klz9.com/api"
    private val secretKey = "KL9K40zaSyC9K40vOMLLbEcepIFBhUKXwELqxlwTEF"

    override val configKeyDomain = ConfigKey.Domain("klz9.com")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true
    )

    private val tagsCache = suspendLazy {
        try {
            webClient.httpGet("$apiUrl/genres".toHttpUrl(), generateHeaders())
                .parseJsonArray()
                .mapJSON {
                    val name = it.getString("name")
                    MangaTag(title = name, key = name, source = source)
                }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = tagsCache.get(),
            availableContentRating = setOf(ContentRating.ADULT),
            availableStates = setOf(MangaState.ONGOING, MangaState.FINISHED)
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val builder = "$apiUrl/manga/list".toHttpUrl().newBuilder()
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("limit", pageSize.toString())

        if (!filter.query.isNullOrEmpty()) {
            builder.addQueryParameter("search", filter.query)
        }

        if (filter.tags.isNotEmpty()) {
            builder.addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
        }

        when (order) {
            SortOrder.POPULARITY -> {
                builder.addQueryParameter("sort", "views")
                builder.addQueryParameter("order", "desc")
            }
            SortOrder.UPDATED -> {
                builder.addQueryParameter("sort", "updated")
                builder.addQueryParameter("order", "desc")
            }
            SortOrder.NEWEST -> {
                builder.addQueryParameter("sort", "created")
                builder.addQueryParameter("order", "desc")
            }
            SortOrder.ALPHABETICAL -> {
                builder.addQueryParameter("sort", "name")
                builder.addQueryParameter("order", "asc")
            }
            else -> {
                builder.addQueryParameter("sort", "updated")
                builder.addQueryParameter("order", "desc")
            }
        }

        val url = builder.build()
        val json = webClient.httpGet(url, generateHeaders()).parseJson()
        val items = json.optJSONArray("items") ?: return emptyList()

        val list = mutableListOf<Manga>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            list.add(parseMangaItem(item))
        }
        return list
    }

    private fun parseMangaItem(json: JSONObject): Manga {
        val slug = json.getString("slug")
        val url = "/$slug.html"
        return Manga(
            id = generateUid(url),
            url = url,
            publicUrl = "https://klz9.com$url",
            coverUrl = json.optString("cover", ""),
            title = json.getString("name"),
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = when (json.optInt("m_status", 0)) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                else -> null
            },
            source = source,
            contentRating = ContentRating.ADULT,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfter("/").substringBefore(".html")
        val url = "$apiUrl/manga/slug/$slug"
        val json = webClient.httpGet(url, generateHeaders()).parseJson()

        val authors = json.optString("authors", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val genres = json.optString("genres", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val desc = json.optString("description", "")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")

        val chapters = parseChapters(json)

        return manga.copy(
            description = desc,
            authors = authors,
            tags = genres.map { MangaTag(title = it, key = it, source = source) }.toSet(),
            coverUrl = json.optString("cover", manga.coverUrl),
            state = when (json.optInt("m_status", 0)) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                else -> null
            },
            chapters = chapters
        )
    }

    private fun parseChapters(json: JSONObject): List<MangaChapter> {
        val chapters = json.optJSONArray("chapters") ?: return emptyList()
        val list = mutableListOf<MangaChapter>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        for (i in 0 until chapters.length()) {
            val item = chapters.getJSONObject(i)
            val id = item.getLong("id")
            val chapterNum = item.optDouble("chapter", 0.0).toFloat()
            val dateStr = item.optString("last_update")
            val date = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            list.add(MangaChapter(
                id = id,
                title = item.optString("name").takeIf { !it.isNullOrEmpty() && it != "null" } ?: "Chapter $chapterNum",
                number = chapterNum,
                volume = 0,
                url = "/chapter/$id",
                uploadDate = date,
                source = source,
                scanlator = null,
                branch = null
            ))
        }
        return list.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.id
        val url = "$apiUrl/chapter/$chapterId"
        val json = webClient.httpGet(url, generateHeaders()).parseJson()
        val content = json.optString("content", "")
        
        val urls = if (content.trim().startsWith("[")) {
            try {
                val jsonArray = org.json.JSONArray(content)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray.getString(i))
                }
                list
            } catch (e: Exception) {
                emptyList<String>()
            }
        } else {
            content.split(Regex("[\r\n]+")).map { it.trim() }.filter { it.startsWith("http") }
        }

        val blacklisted = setOf(
            "https://1.bp.blogspot.com/-ZMyVQcnjYyE/W2cRdXQb15I/AAAAAAACDnk/8X1Hm7wmhz4hLvpIzTNBHQnhuKu05Qb0gCHMYCw/s0/LHScan.png",
            "https://s4.imfaclub.com/images/20190814/Credit_LHScan_5d52edc2409e7.jpg",
            "https://s4.imfaclub.com/images/20200112/5e1ad960d67b2_5e1ad962338c7.jpg"
        )

        return urls.filter { !blacklisted.contains(it) }.map {
            var newUrl = it.replace("http://", "https://")
            newUrl = newUrl.replace("https://imfaclub.com", "https://h1.klimv1.xyz")
            newUrl = newUrl.replace("https://s2.imfaclub.com", "https://h2.klimv1.xyz")
            newUrl = newUrl.replace("https://s4.imfaclub.com", "https://h4.klimv1.xyz")
            newUrl = newUrl.replace("https://ihlv1.xyz", "https://h1.klimv1.xyz")
            newUrl = newUrl.replace("https://s2.ihlv1.xyz", "https://h2.klimv1.xyz")
            newUrl = newUrl.replace("https://s4.ihlv1.xyz", "https://h4.klimv1.xyz")
            newUrl = newUrl.replace("https://h1.klimv1.xyz", "https://j1.jfimv2.xyz")
            newUrl = newUrl.replace("https://h2.klimv1.xyz", "https://j2.jfimv2.xyz")
            newUrl = newUrl.replace("https://h4.klimv1.xyz", "https://j4.jfimv2.xyz")

            MangaPage(
                id = generateUid(newUrl),
                url = newUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun generateHeaders(): Headers {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val s = "$ts.$secretKey"
        val sig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return Headers.Builder()
            .add("x-client-ts", ts)
            .add("x-client-sig", sig)
            .add("User-Agent", UserAgents.CHROME_DESKTOP)
            .build()
    }
}