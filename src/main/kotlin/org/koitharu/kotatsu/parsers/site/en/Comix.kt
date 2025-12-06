package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
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

/*
 Refactored Comix parser
 - stream-safe getChapters (keeps only best per chapter number to avoid memory blowups)
 - robust script images extraction in getPages (regex + unescape)
 - consistent cover/rating handling and defensive JSON access
*/

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")
    private val domain = "comix.to"
    private val apiBase = "api/v2"
    private val apiBaseUrl = "https://$domain/$apiBase"
    private val pageLimitPerRequest = 100

    private val DEFAULT_MAX_PAGES = 200       // safety cap when streaming chapters
    private val DEFAULT_MAX_UNIQUE = 1000     // safety cap for unique chapter numbers

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false
        )

    override val availableSortOrders: Set<SortOrder> = linkedSetOf(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags()
    )

    // -------------------------
    // Helpers
    // -------------------------
    private fun buildApiUrl(path: String): HttpUrl.Builder =
        "$apiBaseUrl/$path".toHttpUrl().newBuilder()

    private fun jsonOptString(obj: JSONObject?, key: String, default: String? = null): String? =
        obj?.optString(key, default)?.nullIfEmpty()

    private fun jsonOptDouble(obj: JSONObject?, key: String, default: Double = 0.0): Double =
        obj?.optDouble(key, default) ?: default

    private fun jsonOptLong(obj: JSONObject?, key: String, default: Long = 0L): Long =
        obj?.optLong(key, default) ?: default

    private fun safeBuildMangaId(hashId: String?): String =
        hashId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    private fun extractHashFromUrl(url: String?): String? =
        url?.substringAfter("/title/")?.substringBefore("/")?.nullIfEmpty()

    // -------------------------
    // List / Search
    // -------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val builder = buildApiUrl("manga").apply {
            if (!filter.query.isNullOrBlank()) addQueryParameter("keyword", filter.query)
            val (param, dir) = when (order) {
                SortOrder.RELEVANCE -> "relevance" to "desc"
                SortOrder.UPDATED -> "chapter_updated_at" to "desc"
                SortOrder.POPULARITY -> "views_30d" to "desc"
                SortOrder.NEWEST -> "created_at" to "desc"
                SortOrder.ALPHABETICAL -> "title" to "asc"
                else -> "chapter_updated_at" to "desc"
            }
            addQueryParameter("order[$param]", dir)

            if (filter.tags.isNotEmpty()) filter.tags.forEach { addQueryParameter("genres[]", it.key) }

            // Default exclusions (explicit)
            listOf("87264", "87266", "87268", "87265").forEach { addQueryParameter("genres[]", "-$it") }

            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("page", page.toString())
        }

        val url = builder.build()
        val response = try {
            webClient.httpGet(url).parseJson()
        } catch (e: Exception) {
            return emptyList()
        }

        val items = response.optJSONObject("result")?.optJSONArray("items") ?: return emptyList()
        val list = ArrayList<Manga>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            list.add(parseMangaFromJson(it))
        }
        return list
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.optString("hash_id", "").nullIfEmpty()
        val title = json.optString("title", "Unknown")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        // Ensure non-null coverUrl (consistent with other parsers)
        val coverUrl = poster?.optString("medium", "")?.nullIfEmpty()
            ?: poster?.optString("large", "")?.nullIfEmpty()
            ?: ""

        val state = when (json.optString("status", "").lowercase()) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        // rated_avg appears 0..100 on API; convert to 0..10 (adjust if project expects different scale)
        val ratedAvg = json.optDouble("rated_avg", 0.0)
        val rating = if (ratedAvg > 0.0) (ratedAvg / 10.0f).toFloat() else RATING_UNKNOWN

        val resolvedHash = safeBuildMangaId(hashId)
        return Manga(
            id = generateUid(resolvedHash),
            url = "/title/${hashId ?: resolvedHash}",
            publicUrl = "https://$domain/title/${hashId ?: resolvedHash}",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = rating,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = if (json.optBoolean("is_nsfw", false)) ContentRating.ADULT else ContentRating.SAFE
        )
    }

    // -------------------------
    // Details
    // -------------------------
    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val hash = extractHashFromUrl(manga.url) ?: extractHashFromUrl(manga.publicUrl) ?: throw ParseException("Invalid manga URL for $domain", manga.url)
        val detailsUrl = buildApiUrl("manga/$hash").build()
        val detailsDeferred = async { webClient.httpGet(detailsUrl).parseJson() }
        val chaptersDeferred = async { getChapters(manga, DEFAULT_MAX_PAGES, DEFAULT_MAX_UNIQUE) }

        val response = try { detailsDeferred.await() } catch (e: Exception) { JSONObject() }
        val chapters = try { chaptersDeferred.await() } catch (e: Exception) { emptyList<MangaChapter>() }

        val result = response.optJSONObject("result")
        if (result != null) {
            val updated = parseMangaFromJson(result)
            // authors
            val authors = result.optJSONArray("author")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title")?.nullIfEmpty() }.toSet()
            } ?: emptySet()

            val tags = mutableSetOf<MangaTag>()
            fun addTags(field: String) {
                result.optJSONArray(field)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("title", "").nullIfEmpty() ?: continue
                        val id = o.optInt("term_id", 0).takeIf { it != 0 }?.toString() ?: continue
                        tags.add(MangaTag(name, id, source))
                    }
                }
            }
            addTags("genre"); addTags("theme"); addTags("demographic")
            result.optString("type", "").nullIfEmpty()?.let {
                tags.add(MangaTag(it.replaceFirstChar { ch -> ch.titlecase() }, it, source))
            }

            return@coroutineScope updated.copy(chapters = chapters, authors = authors, tags = tags)
        }

        return@coroutineScope manga.copy(chapters = chapters)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    // -------------------------
    // Efficient chapter streaming
    // -------------------------
    private suspend fun getChapters(
        manga: Manga,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxUnique: Int = DEFAULT_MAX_UNIQUE
    ): List<MangaChapter> {
        val hashId = extractHashFromUrl(manga.url) ?: throw ParseException("Invalid manga URL for $domain", manga.url)

        // Keep only the best (most recent created_at) JSONObject per chapter number
        val bestPerNumber = LinkedHashMap<Double, JSONObject>()

        var page = 1
        var lastPage = Int.MAX_VALUE
        while (page <= lastPage && page <= maxPages && bestPerNumber.size < maxUnique) {
            val url = buildApiUrl("manga/$hashId/chapters").apply {
                addQueryParameter("order[number]", "desc")
                addQueryParameter("limit", pageLimitPerRequest.toString())
                addQueryParameter("page", page.toString())
            }.build()

            val resp = try { webClient.httpGet(url).parseJson() } catch (e: Exception) { break }
            val items = resp.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val num = item.optDouble("number", Double.NaN)
                if (num.isNaN()) continue

                val existing = bestPerNumber[num]
                if (existing == null) {
                    bestPerNumber[num] = item
                } else {
                    val existingCreated = existing.optLong("created_at", 0L)
                    val newCreated = item.optLong("created_at", 0L)
                    if (newCreated > existingCreated) bestPerNumber[num] = item
                }
            }

            val pagination = resp.optJSONObject("result")?.optJSONObject("pagination")
            if (pagination != null) {
                lastPage = pagination.optInt("last_page", page)
            } else {
                if (items.length() < pageLimitPerRequest) lastPage = page
            }
            page++
        }

        // Build sorted list (newest first), map to MangaChapter
        val uniqueList = bestPerNumber.values
            .sortedByDescending { it.optDouble("number", 0.0) }

        val chapters = uniqueList.map { item ->
            val chapterId = item.optLong("chapter_id", 0L)
            val number = item.optDouble("number", 0.0).toFloat()
            val name = item.optString("name", "").nullIfEmpty()
            val createdAt = item.optLong("created_at", 0L)
            val scanlationGroup = item.optJSONObject("scanlation_group")
            val scanlatorName = scanlationGroup?.optString("name", null)?.nullIfEmpty()
            val groupId = item.optInt("scanlation_group_id", 0)

            val title = buildString {
                if (item.optString("volume", "0") != "0") append("Vol. ${item.optString("volume")} ")
                append("Ch. ${if (number == number.toLong().toFloat()) number.toLong() else number}")
                if (name != null) append(" - ").append(name)
                if (scanlatorName != null) append(" [").append(scanlatorName).append("]")
            }.trim()

            // Use chapterId+groupId in uid to distinguish duplicates by group
            val uid = if (chapterId != 0L) "$chapterId-$groupId" else UUID.randomUUID().toString()
            val branchVal = if (groupId != 0) groupId.toString() else null
            MangaChapter(
                id = generateUid(uid),
                title = if (title.isNotBlank()) title else "Chapter ${if (number == number.toLong().toFloat()) number.toLong() else number}",
                number = number,
                volume = item.optString("volume", "0").toIntOrNull() ?: 0,
                url = "/title/$hashId/$chapterId-chapter-${number.toInt()}",
                uploadDate = createdAt * 1000L,
                source = source,
                scanlator = scanlatorName,
                branch = branchVal
            )
        }

        // Return reversed to match existing callers expecting ascending order where necessary
        return chapters.reversed()
    }

    // -------------------------
    // Pages: robust script extraction with fallback to API
    // -------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // chapter.url expected like "/title/{hash}/{chapterId}-chapter-{n}"
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-").nullIfEmpty()
            ?: throw ParseException("Invalid chapter URL for $domain: ${chapter.url}", chapter.url)

        val chapterUrl = "https://$domain${chapter.url}"
        // First attempt: parse page HTML for embedded images array
        val doc = try { webClient.httpGet(chapterUrl).parseHtml() } catch (e: Exception) {
            // fallback to API-based retrieval
            return getPagesFromApi(chapterId)
        }

        val scripts = doc.select("script")
        // Regex to find the images array in JS. Matches "images":[ ... ] possibly with escaped quotes.
        val imagesArrayPattern = Regex("""["']?images["']?\s*:\s*(\[[^\]]*(?:\](?!\s*[,\]}])|[^\]]*)\])""", RegexOption.DOT_MATCHES_ALL)

        var imagesJson: JSONArray? = null
        for (script in scripts) {
            val txt = script.html()
            val m = imagesArrayPattern.find(txt) ?: continue
            val rawArray = m.groupValues.getOrNull(1) ?: continue
            // unescape stray \" sequences, keep valid JSON array
            val clean = rawArray.replace("\\\"", "\"").replace("\\\\/", "/")
            try {
                imagesJson = JSONArray(clean)
                break
            } catch (e: Exception) {
                // Try a looser extraction: find the first '[' ... matching ']' balanced substring
                try {
                    val start = txt.indexOf("[", m.range.first)
                    if (start >= 0) {
                        var depth = 1
                        var end = -1
                        var i = start + 1
                        var inString = false
                        var escape = false
                        while (i < txt.length) {
                            val ch = txt[i]
                            if (escape) { escape = false; i++; continue }
                            when (ch) {
                                '\\' -> escape = true
                                '"' -> inString = !inString
                                '[' -> if (!inString) depth++
                                ']' -> if (!inString) {
                                    depth--
                                    if (depth == 0) { end = i; break }
                                }
                            }
                            i++
                        }
                        if (end > start) {
                            val candidate = txt.substring(start, end + 1).replace("\\\"", "\"").replace("\\\\/", "/")
                            imagesJson = JSONArray(candidate)
                            break
                        }
                    }
                } catch (_: Exception) { /* continue to next script */ }
            }
        }

        if (imagesJson == null) {
            // fallback to API call which may contain images list
            return getPagesFromApi(chapterId)
        }

        val pages = ArrayList<MangaPage>(imagesJson.length())
        for (i in 0 until imagesJson.length()) {
            val img = imagesJson.optString(i, "").nullIfEmpty() ?: continue
            pages.add(MangaPage(id = generateUid("${chapterId}-$i"), url = img, preview = null, source = source))
        }
        return pages
    }

    private suspend fun getPagesFromApi(chapterId: String): List<MangaPage> {
        val apiUrl = buildApiUrl("chapters/$chapterId").build()
        val resp = try { webClient.httpGet(apiUrl).parseJson() } catch (e: Exception) { return emptyList() }
        val images = resp.optJSONObject("result")?.optJSONArray("images") ?: return emptyList()
        return (0 until images.length()).map { i ->
            MangaPage(id = generateUid("$chapterId-$i"), url = images.optString(i, ""), preview = null, source = source)
        }
    }

    // -------------------------
    // Tags
    // -------------------------
    private fun fetchAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("6", "Action", source),
        MangaTag("87264", "Adult", source),
        MangaTag("7", "Adventure", source),
        MangaTag("8", "Boys Love", source),
        MangaTag("9", "Comedy", source),
        MangaTag("10", "Crime", source),
        MangaTag("11", "Drama", source),
        MangaTag("87265", "Ecchi", source),
        MangaTag("12", "Fantasy", source),
        MangaTag("13", "Girls Love", source),
        MangaTag("87266", "Hentai", source),
        MangaTag("14", "Historical", source),
        MangaTag("15", "Horror", source),
        MangaTag("16", "Isekai", source),
        MangaTag("17", "Magical Girls", source),
        MangaTag("87267", "Mature", source),
        MangaTag("18", "Mecha", source),
        MangaTag("19", "Medical", source),
        MangaTag("20", "Mystery", source),
        MangaTag("21", "Philosophical", source),
        MangaTag("22", "Psychological", source),
        MangaTag("23", "Romance", source),
        MangaTag("24", "Sci-Fi", source),
        MangaTag("25", "Slice of Life", source),
        MangaTag("87268", "Smut", source),
        MangaTag("26", "Sports", source),
        MangaTag("27", "Superhero", source),
        MangaTag("28", "Thriller", source),
        MangaTag("29", "Tragedy", source),
        MangaTag("30", "Wuxia", source),
        MangaTag("31", "Aliens", source),
        MangaTag("32", "Animals", source),
        MangaTag("33", "Cooking", source),
        MangaTag("34", "Crossdressing", source),
        MangaTag("35", "Delinquents", source),
        MangaTag("36", "Demons", source),
        MangaTag("37", "Genderswap", source),
        MangaTag("38", "Ghosts", source),
        MangaTag("39", "Gyaru", source),
        MangaTag("40", "Harem", source),
        MangaTag("41", "Incest", source),
        MangaTag("42", "Loli", source),
        MangaTag("43", "Mafia", source),
        MangaTag("44", "Magic", source),
        MangaTag("45", "Martial Arts", source),
        MangaTag("46", "Military", source),
        MangaTag("47", "Monster Girls", source),
        MangaTag("48", "Monsters", source),
        MangaTag("49", "Music", source),
        MangaTag("50", "Ninja", source),
        MangaTag("51", "Office Workers", source),
        MangaTag("52", "Police", source),
        MangaTag("53", "Post-Apocalyptic", source),
        MangaTag("54", "Reincarnation", source),
        MangaTag("55", "Reverse Harem", source),
        MangaTag("56", "Samurai", source),
        MangaTag("57", "School Life", source),
        MangaTag("58", "Shota", source),
        MangaTag("59", "Supernatural", source),
        MangaTag("60", "Survival", source),
        MangaTag("61", "Time Travel", source),
        MangaTag("62", "Traditional Games", source),
        MangaTag("63", "Vampires", source),
        MangaTag("64", "Video Games", source),
        MangaTag("65", "Villainess", source),
        MangaTag("66", "Virtual Reality", source),
        MangaTag("67", "Zombies", source)
    )
}
