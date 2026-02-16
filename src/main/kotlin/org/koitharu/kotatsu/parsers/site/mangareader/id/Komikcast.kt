package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KOMIKCAST, pageSize = 20) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("v1.komikcast.fit")
    private val apiUrl = "https://be.komikcast.fit"

    override val userAgentKey = ConfigKey.UserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Origin", "https://$domain")
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
            isSearchSupported = true
        )

    private val tagMap = listOf(
        "Action", "Adventure", "Comedy", "Cooking", "Demons", "Drama", "Ecchi", "Fantasy", "Game",
        "Gender Bender", "Gore", "Harem", "Historical", "Horror", "Isekai", "Josei", "Magic",
        "Martial Arts", "Mature", "Mecha", "Medical", "Military", "Music", "Mystery", "One-Shot",
        "Police", "Psychological", "Reincarnation", "Romance", "School", "School Life", "Sci-Fi",
        "Seinen", "Senen", "Shoujo", "Shoujo Ai", "Shounen", "Shounen Ai", "Slice of Life", "Sports",
        "Super Power", "Supernatural", "Thriller", "Tragedy", "Vampire", "Webtoons", "4-Koma", "Yuri"
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = tagMap.map { MangaTag(it, it, source) }.toSet(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            )
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("$apiUrl/series?")
            append("page=$page")
            append("&take=$pageSize")

            if (filter.query.isNullOrEmpty()) {
                when (order) {
                    SortOrder.UPDATED -> append("&preset=rilisan_terbaru")
                    SortOrder.POPULARITY -> append("&preset=popular_all")
                    SortOrder.ALPHABETICAL -> append("&sort=title&sortOrder=asc")
                    SortOrder.ALPHABETICAL_DESC -> append("&sort=title&sortOrder=desc")
                    else -> append("&preset=rilisan_terbaru")
                }
            } else {
                val q = filter.query!!.replace("\"", "\\\"")
                val filterStr = "title=like=\"$q\",nativeTitle=like=\"$q\""
                append("&filter=${filterStr.urlEncoded()}")
            }

            filter.types.oneOrThrowIfMany()?.let {
                append("&type=")
                append(when (it) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.MANHUA -> "manhua"
                    else -> ""
                })
            }

            filter.states.oneOrThrowIfMany()?.let {
                append("&status=")
                append(when (it) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    else -> ""
                })
            }

            if (filter.tags.isNotEmpty()) {
                append("&genreIds=")
                append(filter.tags.joinToString(",") { it.key }.urlEncoded())
            }
        }

        val json = webClient.httpGet(url).parseJson()
        val data = json.getJSONArray("data")
        val mangaList = ArrayList<Manga>()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val seriesData = item.getJSONObject("data")
            val slug = seriesData.getString("slug")
            val relativeUrl = "/series/$slug"

            mangaList.add(
                Manga(
                    id = generateUid(relativeUrl),
                    title = seriesData.getString("title"),
                    altTitles = emptySet<String>(),
                    url = relativeUrl,
                    publicUrl = "https://$domain$relativeUrl",
                    rating = seriesData.optDouble("rating", -1.0).toFloat().div(10f).takeIf { it >= 0 } ?: RATING_UNKNOWN,
                    contentRating = ContentRating.SAFE,
                    coverUrl = seriesData.optString("coverImage"),
                    tags = emptySet<MangaTag>(),
                    state = null,
                    authors = emptySet<String>(),
                    source = source
                )
            )
        }

        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfterLast("/")
        val detailsUrl = "$apiUrl/series/$slug?includeMeta=true"
        val detailsJson = webClient.httpGet(detailsUrl).parseJson().getJSONObject("data").getJSONObject("data")

        val title = detailsJson.getString("title")
        val description = detailsJson.optString("synopsis")
        val coverUrl = detailsJson.optString("coverImage")
        val author = detailsJson.optString("author")
        val status = detailsJson.optString("status")
        val genresJson = detailsJson.optJSONArray("genres")
        val tags = mutableSetOf<MangaTag>()
        if (genresJson != null) {
            for (i in 0 until genresJson.length()) {
                val genreObj = genresJson.getJSONObject(i).getJSONObject("data")
                val name = genreObj.getString("name")
                tags.add(MangaTag(name, name, source))
            }
        }

        val state = when (status.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }

        val chaptersUrl = "$apiUrl/series/$slug/chapters"
        val chaptersJson = webClient.httpGet(chaptersUrl).parseJson().getJSONArray("data")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        val chapters = chaptersJson.mapChapters(reversed = true) { _, item ->
            val chapterData = item.getJSONObject("data")
            val index = chapterData.getDouble("index")
            val indexStr = if (index % 1.0 == 0.0) index.toInt().toString() else index.toString()
            val chapterApiUrl = "/series/$slug/chapters/$indexStr"
            val dateStr = item.getString("createdAt")

            MangaChapter(
                id = generateUid(chapterApiUrl),
                title = "Chapter $indexStr",
                url = chapterApiUrl,
                number = index.toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = dateFormat.parseSafe(dateStr),
                branch = null,
                source = source
            )
        }

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            authors = author?.let { setOf(it) } ?: emptySet<String>(),
            state = state,
            tags = tags,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "$apiUrl${chapter.url}"
        val json = webClient.httpGet(url).parseJson().getJSONObject("data").getJSONObject("data")
        val images = json.getJSONArray("images")
        val pages = ArrayList<MangaPage>()

        for (i in 0 until images.length()) {
            val imageUrl = images.getString(i)
            pages.add(
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source
                )
            )
        }

        return pages
    }
}
