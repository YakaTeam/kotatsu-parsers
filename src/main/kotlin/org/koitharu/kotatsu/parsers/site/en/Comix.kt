package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.arraySetOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    // central path suffix
    private val apiSuffix = "api/v2/manga"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    // -------------------------
    // Helper: central API url builder
    // -------------------------
    private fun api(path: String) =
        "https://$domain/$path".toHttpUrl().newBuilder()

    // epoch guard
    private fun epochToMillis(epochSecondsOrMs: Long): Long =
        when {
            epochSecondsOrMs <= 0L -> 0L
            epochSecondsOrMs > 1_000_000_000_000L -> epochSecondsOrMs
            else -> epochSecondsOrMs * 1000L
        }

    // -------------------------
    // Safe network helpers
    // -------------------------
    private fun urlToString(url: HttpUrl) = url.toString()

    // FIX 1: Marked as suspend because httpGet is suspend
    private suspend fun safeParseJson(url: HttpUrl): JSONObject {
        try {
            return webClient.httpGet(url).parseJson()
        } catch (e: Exception) {
            throw ParseException("HTTP/JSON error for $url: ${e.message}", urlToString(url))
        }
    }

    private suspend fun safeParseJsonWithRetry(url: HttpUrl, retries: Int = 2, initialDelayMs: Long = 800L): JSONObject {
        var attempt = 0
        var delayMs = initialDelayMs
        while (true) {
            try {
                return safeParseJson(url)
            } catch (e: Exception) {
                attempt++
                if (attempt > retries) {
                    throw ParseException("Failed after $attempt attempts for $url: ${e.message}", urlToString(url))
                }
                delay(delayMs)
                delayMs *= 2
            }
        }
    }

    // -------------------------
    // Content type detection & tag helper
    // -------------------------
    private fun detectContentType(json: JSONObject): String {
        val explicit = sequenceOf(
            json.optString("type", null),
            json.optString("format", null),
            json.optString("subtype", null),
            json.optString("manga_type", null)
        ).firstOrNull { !it.isNullOrBlank() }?.lowercase()?.trim()

        if (!explicit.isNullOrBlank()) {
            when {
                explicit!!.contains("manhwa") -> return "manhwa"
                explicit.contains("manhua") -> return "manhua"
                explicit.contains("webtoon") -> return "webtoon"
                explicit.contains("comic") -> return "comic"
                explicit.contains("novel") || explicit.contains("light novel") -> return "novel"
                explicit.contains("manga") -> return "manga"
            }
        }

        val demographic = json.optString("demographic", "").lowercase()
        if (demographic.contains("korean") || demographic.contains("manhwa")) return "manhwa"
        if (demographic.contains("chinese") || demographic.contains("manhua")) return "manhua"

        val genreArray = json.optJSONArray("genre") ?: json.optJSONArray("genres") ?: JSONArray()
        for (i in 0 until genreArray.length()) {
            val g = genreArray.optJSONObject(i)?.optString("title", null) ?: genreArray.optString(i, null)
            if (!g.isNullOrBlank()) {
                val name = g!!.lowercase()
                when {
                    name.contains("webtoon") -> return "webtoon"
                    name.contains("manhwa") -> return "manhwa"
                    name.contains("manhua") -> return "manhua"
                    name.contains("western") || name.contains("comic") -> return "comic"
                }
            }
        }

        val slug = json.optString("slug", json.optString("url", "")).lowercase()
        if (slug.contains("manhwa")) return "manhwa"
        if (slug.contains("manhua")) return "manhua"
        if (slug.contains("webtoon")) return "webtoon"

        return "manga"
    }

    private fun typeAsTag(typeKey: String): MangaTag =
        MangaTag("Type: ${typeKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}", typeKey, source)

    // -------------------------
    // List (search / sorted)
    // -------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = api(apiSuffix).apply {
            if (!filter.query.isNullOrEmpty()) addQueryParameter("keyword", filter.query)

            val orderParam = when (order) {
                SortOrder.RELEVANCE -> "relevance"
                SortOrder.UPDATED -> "chapter_updated_at"
                SortOrder.POPULARITY -> "views_30d"
                SortOrder.NEWEST -> "created_at"
                SortOrder.ALPHABETICAL -> "title"
                else -> "chapter_updated_at"
            }
            val direction = if (order == SortOrder.ALPHABETICAL) "asc" else "desc"
            addQueryParameter("order[$orderParam]", direction)

            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach { addQueryParameter("genres[]", it.key) }
            } else {
                listOf("87264", "87266", "87268", "87265").forEach { addQueryParameter("genres[]", "-$it") }
            }

            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("page", page.toString())
        }.build()

        val response = safeParseJsonWithRetry(url)
        val result = response.optJSONObject("result")
            ?: throw ParseException("API response missing 'result' for $url", urlToString(url))
        val items = result.optJSONArray("items") ?: JSONArray()

        return (0 until items.length()).map { parseMangaFromJson(items.getJSONObject(it)) }
    }

    // -------------------------
    // Parse manga JSON -> Manga
    // -------------------------
    private fun parseMangaFromJson(json: JSONObject): Manga {
        // FIX 2: Safe calls for nullable strings
        val hashId = json.optString("hash_id", "").nullIfEmpty() ?: ""
        val title = json.optString("title", "Untitled").nullIfEmpty() ?: "Untitled"
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        val coverUrl = poster?.optString("medium", null) ?: poster?.optString("large", null) ?: poster?.optString("original", null)
        val status = json.optString("status", "")
        val rating = json.optDouble("rated_avg", 0.0)

        val state = when (status.lowercase()) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        val detectedType = detectContentType(json)
        val typeTag = typeAsTag(detectedType)

        val mappedTags = mutableSetOf<MangaTag>()
        json.optJSONArray("genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                val name = obj?.optString("title", null) ?: arr.optString(i, null)
                if (!name.isNullOrBlank()) mappedTags.add(MangaTag(name!!, name.lowercase(), source))
            }
        }
        mappedTags.add(typeTag)

        return Manga(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://$domain/title/$hashId",
            coverUrl = coverUrl?.nullIfEmpty(), // FIX 2: Safe call
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = if (rating > 0) (rating / 10.0f).toFloat() else RATING_UNKNOWN,
            tags = mappedTags,
            authors = emptySet(),
            state = state,
            source = source,
            // FIX 3: MATURE -> ADULT
            contentRating = if (json.optBoolean("is_nsfw", false)) ContentRating.ADULT else ContentRating.SAFE,
        )
    }

    // -------------------------
    // Details (concurrent chapters fetch)
    // -------------------------
    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val hashId = manga.url.substringAfter("/title/").substringBefore("-")

        val detailsUrl = api("$apiSuffix/$hashId").apply {
            addQueryParameter("includes[]", "author")
            addQueryParameter("includes[]", "artist")
            addQueryParameter("includes[]", "genre")
            addQueryParameter("includes[]", "theme")
            addQueryParameter("includes[]", "demographic")
        }.build()

        val detailsDeferred = async { safeParseJsonWithRetry(detailsUrl) }
        val chaptersDeferred = async { getChapters(hashId) }

        val response = detailsDeferred.await()
        val chapters = chaptersDeferred.await()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            val authors = result.optJSONArray("author")?.let { arr ->
                (0 until arr.length()).mapNotNull { 
                    // FIX 2: Safe call before nullIfEmpty
                    arr.optJSONObject(it)?.optString("title")?.nullIfEmpty() 
                }
            }?.toSet() ?: emptySet()

            val genres = buildSet {
                result.optJSONArray("genre")?.let { arr ->
                    (0 until arr.length()).forEach { idx -> arr.optJSONObject(idx)?.optString("title")?.let { add(it) } }
                }
                result.optJSONArray("theme")?.let { arr ->
                    (0 until arr.length()).forEach { idx -> arr.optJSONObject(idx)?.optString("title")?.let { add(it) } }
                }
            }

            val mappedTags = genres.mapNotNull { name -> MangaTag(name, name.lowercase(), source) }.toMutableSet()
            mappedTags.add(typeAsTag(detectContentType(result)))

            return@coroutineScope updatedManga.copy(chapters = chapters, authors = authors, tags = mappedTags)
        }

        return@coroutineScope manga.copy(chapters = chapters)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    // -------------------------
    // Chapters (pagination + dedupe)
    // -------------------------
    private suspend fun getChapters(hashId: String): List<MangaChapter> {
        val allChapters = ArrayList<JSONObject>()
        var page = 1
        var lastPage = 1

        do {
            val chaptersUrl = api("$apiSuffix/$hashId/chapters").apply {
                addQueryParameter("order[number]", "asc")
                addQueryParameter("limit", "100")
                addQueryParameter("page", page.toString())
            }.build()

            val response = safeParseJsonWithRetry(chaptersUrl)
            val result = response.optJSONObject("result") ?: JSONObject()
            val items = result.optJSONArray("items") ?: JSONArray()

            for (i in 0 until items.length()) allChapters.add(items.getJSONObject(i))

            val pagination = result.optJSONObject("pagination")
            lastPage = pagination?.optInt("last_page", lastPage) ?: lastPage
            page++
        } while (page <= lastPage)

        // dedupe (official -> votes -> updated_at)
        val chapterMap = HashMap<String, JSONObject>()
        val chapterOrder = ArrayList<String>()

        for (item in allChapters) {
            val numberStr = item.optString("number", "").nullIfEmpty() ?: continue
            val current = chapterMap[numberStr]
            if (current == null) {
                chapterMap[numberStr] = item
                chapterOrder.add(numberStr)
                continue
            }

            val scanGroupId = item.optInt("scanlation_group_id", 0)
            val votes = item.optInt("votes", 0)
            val updatedAt = item.optLong("updated_at", 0L)

            val currentGroupId = current.optInt("scanlation_group_id", 0)
            val currentVotes = current.optInt("votes", 0)
            val currentUpdatedAt = current.optLong("updated_at", 0L)

            val officialNew = (scanGroupId == 9275)
            val officialCurrent = (currentGroupId == 9275)
            var better = false

            if (officialNew && !officialCurrent) better = true
            else if (!officialNew && officialCurrent) better = false
            else {
                if (votes > currentVotes) better = true
                else if (votes < currentVotes) better = false
                else if (updatedAt > currentUpdatedAt) better = true
            }

            if (better) chapterMap[numberStr] = item
        }

        val finalList = chapterOrder.mapNotNull { numberStr ->
            val item = chapterMap[numberStr] ?: return@mapNotNull null
            val chapterId = item.optLong("chapter_id", -1L)
            if (chapterId <= 0L) return@mapNotNull null

            val number = item.optDouble("number", 0.0).toFloat()
            val volume = item.optString("volume", "0")
            val name = item.optString("name", "").nullIfEmpty()
            val createdAt = epochToMillis(item.optLong("created_at", 0L))
            val scanlationGroup = item.optJSONObject("scanlation_group")
            // FIX 2: Safe call for nullIfEmpty
            val scanlatorName = scanlationGroup?.optString("name", null)?.nullIfEmpty()

            val volStr = if (volume != "0") "Vol. $volume " else ""
            val chStr = if (numberStr.isNotEmpty()) "Ch. ${number.niceString()}" else ""
            val titleStr = if (!name.isNullOrEmpty()) " - $name" else ""
            val fullTitle = "$volStr$chStr$titleStr".trim()

            MangaChapter(
                id = generateUid(chapterId.toString()),
                title = fullTitle.ifEmpty { "Chapter ${number.niceString()}" },
                number = number,
                volume = volume.toIntOrNull() ?: 0,
                url = "/chapters/$chapterId",
                uploadDate = createdAt,
                source = source,
                scanlator = scanlatorName,
                branch = null,
            )
        }

        return finalList.reversed()
    }

    private fun Float.niceString(): String =
        if (this == this.toLong().toFloat()) this.toLong().toString() else this.toString()

    // -------------------------
    // Pages (chapter images)
    // -------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        if (chapterId.isEmpty()) throw ParseException("Invalid chapter URL: ${chapter.url}", chapter.url)

        val apiUrl = api("api/v2/chapters/$chapterId").build()
        val response = safeParseJsonWithRetry(apiUrl)
        val result = response.optJSONObject("result") ?: JSONObject()
        val images = result.optJSONArray("images") ?: JSONArray()

        return (0 until images.length()).map { i ->
            val imageUrl = images.optString(i, "").nullIfEmpty() ?: ""
            MangaPage(id = generateUid("$chapterId-$i"), url = imageUrl, preview = null, source = source)
        }
    }

    // -------------------------
    // Tags
    // -------------------------
    private fun fetchAvailableTags() = arraySetOf(
        MangaTag("Action", "6", source),
        MangaTag("Adult", "87264", source),
        MangaTag("Adventure", "7", source),
        MangaTag("Boys Love", "8", source),
        MangaTag("Comedy", "9", source),
        MangaTag("Crime", "10", source),
        MangaTag("Drama", "11", source),
        MangaTag("Ecchi", "87265", source),
        MangaTag("Fantasy", "12", source),
        MangaTag("Girls Love", "13", source),
        MangaTag("Hentai", "87266", source),
        MangaTag("Historical", "14", source),
        MangaTag("Horror", "15", source),
        MangaTag("Isekai", "16", source),
        MangaTag("Magical Girls", "17", source),
        MangaTag("Mature", "87267", source),
        MangaTag("Mecha", "18", source),
        MangaTag("Medical", "19", source),
        MangaTag("Mystery", "20", source),
        MangaTag("Philosophical", "21", source),
        MangaTag("Psychological", "22", source),
        MangaTag("Romance", "23", source),
        MangaTag("Sci-Fi", "24", source),
        MangaTag("Slice of Life", "25", source),
        MangaTag("Smut", "87268", source),
        MangaTag("Sports", "26", source),
        MangaTag("Superhero", "27", source),
        MangaTag("Thriller", "28", source),
        MangaTag("Tragedy", "29", source),
        MangaTag("Wuxia", "30", source),
        MangaTag("Aliens", "31", source),
        MangaTag("Animals", "32", source),
        MangaTag("Cooking", "33", source),
        MangaTag("Crossdressing", "34", source),
        MangaTag("Delinquents", "35", source),
        MangaTag("Demons", "36", source),
        MangaTag("Genderswap", "37", source),
        MangaTag("Ghosts", "38", source),
        MangaTag("Gyaru", "39", source),
        MangaTag("Harem", "40", source),
        MangaTag("Incest", "41", source),
        MangaTag("Loli", "42", source),
        MangaTag("Mafia", "43", source),
        MangaTag("Magic", "44", source),
        MangaTag("Martial Arts", "45", source),
        MangaTag("Military", "46", source),
        MangaTag("Monster Girls", "47", source),
        MangaTag("Monsters", "48", source),
        MangaTag("Music", "49", source),
        MangaTag("Ninja", "50", source),
        MangaTag("Office Workers", "51", source),
        MangaTag("Police", "52", source),
        MangaTag("Post-Apocalyptic", "53", source),
        MangaTag("Reincarnation", "54", source),
        MangaTag("Reverse Harem", "55", source),
        MangaTag("Samurai", "56", source),
        MangaTag("School Life", "57", source),
        MangaTag("Shota", "58", source),
        MangaTag("Supernatural", "59", source),
        MangaTag("Survival", "60", source),
        MangaTag("Time Travel", "61", source),
        MangaTag("Traditional Games", "62", source),
        MangaTag("Vampires", "63", source),
        MangaTag("Video Games", "64", source),
        MangaTag("Villainess", "65", source),
        MangaTag("Virtual Reality", "66", source),
        MangaTag("Zombies", "67", source),
    )
}
