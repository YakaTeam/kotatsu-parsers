package org.koitharu.kotatsu.parsers.site.all

import androidx.collection.ArraySet
import androidx.collection.SparseArrayCompat
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

private const val CHAPTERS_LIMIT = 99999

@MangaSourceParser("COMICKLIVE", "ComicK (Unofficial)")
internal class ComickFunParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMICK_FUN, 20) {

    override val configKeyDomain = ConfigKey.Domain("comick.live", "comick.art")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.RATING,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearRangeSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.OTHER,
        ),
        availableDemographics = EnumSet.of(
            Demographic.SHOUNEN,
            Demographic.SHOUJO,
            Demographic.SEINEN,
            Demographic.JOSEI,
            Demographic.NONE,
        ),
    )

    private var nextCursor: String? = null

    private fun isFilterEmpty(filter: MangaListFilter): Boolean {
        return filter.query.isNullOrEmpty() &&
            filter.tags.isEmpty() &&
            filter.tagsExclude.isEmpty() &&
            filter.demographics.isEmpty() &&
            filter.types.isEmpty() &&
            filter.states.isEmpty() &&
            filter.yearFrom == YEAR_UNKNOWN &&
            filter.yearTo == YEAR_UNKNOWN
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Handle special popular manga endpoint when sorting by popularity with no filters
        if (order == SortOrder.POPULARITY && isFilterEmpty(filter)) {
            return getPopularMangaList(page)
        }

        // Handle latest updates endpoint when sorting by updated with no filters
        if (order == SortOrder.UPDATED && isFilterEmpty(filter)) {
            return getLatestMangaList(page)
        }

        // Use evaluateJs for search and filtered results to bypass blocking
        return getFilteredResultsWithJs(page, order, filter)
    }

    private suspend fun getFilteredResultsWithJs(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (page == 1) {
            nextCursor = null
        }

        val url = urlBuilder()
            .scheme("https")
            .host(domain)
            .addPathSegment("api")
            .addPathSegment("search")
            .apply {
                addQueryParameter("order_by", when (order) {
                    SortOrder.NEWEST -> "created_at"
                    SortOrder.POPULARITY -> "view"
                    SortOrder.RATING -> "rating"
                    SortOrder.UPDATED -> "uploaded"
                    else -> "uploaded"
                })
                addQueryParameter("order_direction", "desc")

                filter.tags.forEach {
                    addQueryParameter("genres", it.key)
                }
                filter.tagsExclude.forEach {
                    addQueryParameter("excludes", it.key)
                }

                filter.demographics.forEach {
                    addQueryParameter("demographic", when (it) {
                        Demographic.SHOUNEN -> "shounen"
                        Demographic.SHOUJO -> "shoujo"
                        Demographic.SEINEN -> "seinen"
                        Demographic.JOSEI -> "josei"
                        else -> ""
                    })
                }

                filter.types.forEach {
                    addQueryParameter("country", when (it) {
                        ContentType.MANGA -> "jp"
                        ContentType.MANHWA -> "kr"
                        ContentType.MANHUA -> "cn"
                        ContentType.OTHER -> "others"
                        else -> ""
                    })
                }

                filter.states.oneOrThrowIfMany()?.let {
                    addQueryParameter("status", when (it) {
                        MangaState.ONGOING -> "1"
                        MangaState.FINISHED -> "2"
                        MangaState.ABANDONED -> "3"
                        MangaState.PAUSED -> "4"
                        else -> ""
                    })
                }

                if (filter.yearFrom != YEAR_UNKNOWN) {
                    addQueryParameter("from", filter.yearFrom.toString())
                }
                if (filter.yearTo != YEAR_UNKNOWN) {
                    addQueryParameter("to", filter.yearTo.toString())
                }

                addQueryParameter("showAll", "false")
                addQueryParameter("exclude_mylist", "false")

                filter.query?.let { query ->
                    if (query.trim().length < 3) {
                        throw IllegalArgumentException("Query must be at least 3 characters")
                    }
                    addQueryParameter("q", query.trim())
                }

                addQueryParameter("type", "comic")
                if (page > 1) {
                    nextCursor?.let { cursor ->
                        addQueryParameter("cursor", cursor)
                    }
                }
            }

        // Try direct API call first, fall back to evaluateJs if blocked
        val response = try {
            webClient.httpGet(url.build()).parseJson()
        } catch (_: Exception) {
            // If API is blocked, use evaluateJs as fallback
            return getSearchResultsViaHtml(page, order, filter)
        }

        // Handle pagination - cursor might not always be present
        nextCursor = response.optString("cursor").takeIf { it.isNotEmpty() && it != "null" }
        val data = response.getJSONArray("data")
        val mangaList = parseMangaList(data)

        // Additional check - if we got fewer results than expected, probably no more pages
        if (mangaList.size < 10) {
            nextCursor = null
        }

        return mangaList
    }

    private suspend fun getSearchResultsViaHtml(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Build search URL for HTML page (not API)
        val searchUrl = buildString {
            append("https://$domain/search")

            // Add search query if present
            filter.query?.let { query ->
                append("?q=")
                append(query.urlEncoded())
            }

            // For now, fallback to empty list if complex filters
            // TODO: Build proper HTML search URLs for genres/demographics
        }

        val script = """
            (() => {
                // Wait for search results to load
                const searchResults = document.querySelector('.search-results, .comics-grid, [class*="search"]');
                const comicCards = document.querySelectorAll('[class*="comic"], [class*="manga"], .search-result-item');


				const hasBlockedTitle = title.includes('access denied') || title.includes('just a moment');
				const hasCloudflareChallenge = document.querySelector('div.cf-wrapper') !== null ||
					document.querySelector('div[class*="cf-"]') !== null ||
					document.querySelector('script[src*="challenge-platform"]') !== null;

				if (hasBlockedTitle || hasCloudflareChallenge) {
					return "CLOUDFLARE_BLOCKED";
				}

                // Check if search results are loaded
                if (searchResults && comicCards.length > 0) {
                    window.stop();
                    const elementsToRemove = document.querySelectorAll('script, iframe, object, embed, style');
                    elementsToRemove.forEach(el => el.remove());
                    return document.documentElement.outerHTML;
                }

                return null; // Keep waiting for content to load
            })();
        """.trimIndent()

        val rawHtml = context.evaluateJs(searchUrl, script)
            ?: return emptyList() // Return empty if page fails to load

        val html = if (rawHtml.startsWith("\"") && rawHtml.endsWith("\"")) {
            rawHtml.substring(1, rawHtml.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        } else rawHtml

        // Parse search results from HTML (would need proper selectors)
        // For now return empty as this requires knowing ComicK's HTML structure
        return emptyList()
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private suspend fun getPopularMangaList(page: Int): List<Manga> {
        // Limit pages to match original Tachiyomi logic (1-6)
        if (page > 6) {
            return emptyList()
        }

        val url = urlBuilder()
            .scheme("https")
            .host(domain)
            .addPathSegment("api")
            .addPathSegment("comics")
            .addPathSegment("top")
            .apply {
                val days = when (page) {
                    1, 4 -> 7
                    2, 5 -> 30
                    3, 6 -> 90
                    else -> 7
                }
                val type = when (page) {
                    1, 2, 3 -> "follow"
                    4, 5, 6 -> "most_follow_new"
                    else -> "follow"
                }
                addQueryParameter("days", days.toString())
                addQueryParameter("type", type)
            }

        val response = webClient.httpGet(url.build()).parseJson()
        val data = response.getJSONArray("data")
        return parseMangaList(data)
    }

    private suspend fun getLatestMangaList(page: Int): List<Manga> {
        val url = urlBuilder()
            .scheme("https")
            .host(domain)
            .addPathSegment("api")
            .addPathSegment("chapters")
            .addPathSegment("latest")
            .apply {
                addQueryParameter("order", "new")
                addQueryParameter("page", page.toString())
            }

        val response = webClient.httpGet(url.build()).parseJson()
        val data = response.getJSONArray("data")

        // For latest chapters, we need to extract unique manga from chapter data
        val mangaMap = mutableMapOf<String, Manga>()
        val tagsMap = tagsArray.get()

        for (i in 0 until data.length()) {
            val jo = data.getJSONObject(i)
            val slug = jo.getString("slug")

            if (!mangaMap.containsKey(slug)) {
                mangaMap[slug] = Manga(
                    id = generateUid(slug),
                    title = jo.getString("title"),
                    altTitles = emptySet(),
                    url = slug,
                    publicUrl = "https://$domain/comic/$slug",
                    rating = RATING_UNKNOWN,
                    contentRating = when (jo.optString("content_rating")) {
                        "safe" -> ContentRating.SAFE
                        "suggestive" -> ContentRating.SUGGESTIVE
                        "erotica" -> ContentRating.ADULT
                        else -> ContentRating.SAFE
                    },
                    coverUrl = jo.getStringOrNull("default_thumbnail"),
                    largeCoverUrl = null,
                    description = null,
                    tags = jo.selectGenres(tagsMap),
                    state = if (jo.optBoolean("is_ended", false)) {
                        MangaState.FINISHED
                    } else {
                        MangaState.ONGOING
                    },
                    authors = emptySet(),
                    source = source,
                )
            }
        }

        val result = mangaMap.values.toList()

        // If we got very few unique manga, there might not be more pages
        if (result.size < 10) {
            return result
        }

        return result
    }

    private suspend fun parseMangaList(data: JSONArray): List<Manga> {
        val tagsMap = tagsArray.get()
        return data.mapJSON { jo ->
            val slug = jo.getString("slug")
            Manga(
                id = generateUid(slug),
                title = jo.getString("title"),
                altTitles = emptySet(),
                url = slug,
                publicUrl = "https://$domain/comic/$slug",
                rating = RATING_UNKNOWN, // Rating not available in search results
                contentRating = when (jo.optString("content_rating")) {
                    "safe" -> ContentRating.SAFE
                    "suggestive" -> ContentRating.SUGGESTIVE
                    "erotica" -> ContentRating.ADULT
                    else -> ContentRating.SAFE // Default to safe if empty or unknown
                },
                coverUrl = jo.getStringOrNull("default_thumbnail"),
                largeCoverUrl = null,
                description = null, // Description not available in search results
                tags = jo.selectGenres(tagsMap),
                state = if (jo.optBoolean("is_ended", false)) {
                    MangaState.FINISHED
                } else {
                    MangaState.ONGOING
                },
                authors = emptySet(), // Authors not available in search results
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$domain/comic/${manga.url}"
        val response = webClient.httpGet(url)
        val html = response.parseHtml()

        // Extract comic data from HTML script tag
        val comicDataElement = html.selectFirst("#comic-data")
            ?: throw IllegalArgumentException("Comic data not found")

        val comicDataJson = comicDataElement.data()
        val jo = JSONObject(comicDataJson)

        val alt = jo.optJSONArray("titles")?.asTypedList<JSONObject>()?.mapNotNullToSet {
            it.getStringOrNull("title")
        } ?: emptySet()

        val authors = jo.optJSONArray("authors")?.mapJSONNotNullToSet {
            it.getStringOrNull("name")
        } ?: emptySet()

        val artists = jo.optJSONArray("artists")?.mapJSONNotNullToSet {
            it.getStringOrNull("name")
        } ?: emptySet()

        return manga.copy(
            title = jo.getString("title"),
            altTitles = alt,
            contentRating = when (jo.getStringOrNull("content_rating")) {
                "suggestive" -> ContentRating.SUGGESTIVE
                "erotica" -> ContentRating.ADULT
                else -> ContentRating.SAFE
            },
            description = buildString {
                jo.getStringOrNull("desc")?.let { desc ->
                    // Parse HTML description
                    append(org.jsoup.Jsoup.parseBodyFragment(desc).wholeText())
                }

                if (alt.isNotEmpty()) {
                    append("\n\n Alternative Titles: \n")
                    alt.forEach {
                        append("- ", it.trim(), "\n")
                    }
                }
            }.trim(),
            tags = buildSet {
                // Add content type based on country
                when (jo.getStringOrNull("country")) {
                    "jp" -> add(MangaTag("manga", "Manga", source))
                    "cn" -> add(MangaTag("manhua", "Manhua", source))
                    "ko" -> add(MangaTag("manhwa", "Manhwa", source))
                }
                // Add genres
                jo.optJSONArray("genres")?.let { genreArray ->
                    for (i in 0 until genreArray.length()) {
                        val genre = genreArray.getJSONObject(i)
                        val genreInfo = genre.getJSONObject("genres")
                        add(MangaTag(
                            key = genreInfo.getString("slug"),
                            title = genreInfo.getString("name"),
                            source = source
                        ))
                    }
                }
            },
            authors = authors + artists,
            chapters = getChapters(manga.url),
        )
    }

    private suspend fun getChapters(mangaSlug: String): List<MangaChapter> {
        val url = "https://$domain/api/comics/$mangaSlug/chapter-list"
        var response = webClient.httpGet(url).parseJson()
        val chapters = response.getJSONArray("data").asTypedList<JSONObject>().toMutableList()

        // Handle pagination if needed
        var page = 2
        var hasNextPage = response.optJSONObject("pagination")?.optInt("last_page", 1)?.let { it > 1 } ?: false
        while (hasNextPage && page <= 10) { // Limit to prevent infinite loops
            response = webClient.httpGet("$url?page=$page").parseJson()
            chapters.addAll(response.getJSONArray("data").asTypedList())
            val pagination = response.optJSONObject("pagination")
            hasNextPage = pagination?.optInt("current_page", 1)?.let { current ->
                current < pagination.optInt("last_page", 1)
            } ?: false
            page++
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ENGLISH)

        // First pass: organize all chapters by number and analyze team completeness
        val chaptersByNumber = mutableMapOf<Float, MutableList<JSONObject>>()
        val teamStats = mutableMapOf<String, Int>() // Team name -> chapter count

        for (jo in chapters) {
            val chap = jo.optString("chap").takeIf { it.isNotBlank() } ?: "0"
            val lang = jo.optString("lang", "en")
            val chapterNumber = chap.toFloatOrNull() ?: 0f

            // Only process English chapters
            if (lang != "en") continue

            // Extract team name
            val groups = when {
                jo.has("group_name") -> {
                    val groupField = jo.opt("group_name")
                    when (groupField) {
                        is JSONArray -> groupField.asTypedList<String>().joinToString(", ")
                        is String -> groupField
                        else -> ""
                    }
                }
                else -> ""
            }

            // Track team stats
            if (groups.isNotBlank()) {
                teamStats[groups] = teamStats.getOrDefault(groups, 0) + 1
            }

            // Group by chapter number
            chaptersByNumber.getOrPut(chapterNumber) { mutableListOf() }.add(jo)
        }

        // Find the most complete team (has most chapters)
        val bestTeam = teamStats.maxByOrNull { it.value }?.key

        // If no team data available, we'll rely on upvotes and upload dates for selection

        // Second pass: create streamlined chapter list
        val finalChapters = mutableListOf<MangaChapter>()

        for (chapterNumber in chaptersByNumber.keys.sorted()) {
            val availableVersions = chaptersByNumber[chapterNumber] ?: continue

            // Pick best version for this chapter number
            val bestVersion = availableVersions.maxWithOrNull(compareBy { jo ->
                val groups = when {
                    jo.has("group_name") -> {
                        val groupField = jo.opt("group_name")
                        when (groupField) {
                            is JSONArray -> groupField.asTypedList<String>().joinToString(", ")
                            is String -> groupField
                            else -> ""
                        }
                    }
                    else -> ""
                }

                // Priority scoring system for best chapter selection
                var score = 0

                // 1. Strongly prefer the most complete team (1000 points)
                if (groups.isNotBlank() && groups == bestTeam) {
                    score += 1000
                } else if (bestTeam.isNullOrBlank() && groups.isNotBlank()) {
                    // If no clear best team, give moderate boost to any team vs no team
                    score += 500
                }

                // 2. Community preference via upvotes (up to 100 points)
                score += jo.optInt("up_count", 0) * 10

                // 3. Slight preference for recent uploads (up to 10 points)
                val createdAt = jo.optString("created_at")
                if (createdAt.isNotBlank()) {
                    try {
                        val uploadTime = dateFormat.parseSafe(createdAt)
                        val currentTime = System.currentTimeMillis()
                        val daysSinceUpload = ((currentTime - uploadTime) / (24 * 60 * 60 * 1000)).toInt()
                        // More recent uploads get higher score (max 10 points for uploads within 10 days)
                        score += maxOf(0, 10 - daysSinceUpload.coerceAtMost(10))
                    } catch (_: Exception) {
                        // Ignore date parsing errors, no points added
                    }
                }

                score
            }) ?: continue

            val vol = bestVersion.optString("vol").takeIf { it.isNotBlank() && it != "null" }
            val chap = bestVersion.optString("chap").takeIf { it.isNotBlank() } ?: "0"
            val title = bestVersion.optString("title").takeIf { it.isNotBlank() }
            val lang = bestVersion.optString("lang", "en")
            val createdAt = bestVersion.optString("created_at")

            // Handle group_name field (can be array or string)
            val groups = when {
                bestVersion.has("group_name") -> {
                    val groupField = bestVersion.opt("group_name")
                    when (groupField) {
                        is JSONArray -> groupField.asTypedList<String>().joinToString(", ")
                        is String -> groupField
                        else -> ""
                    }
                }
                else -> ""
            }

            val chapter = MangaChapter(
                id = generateUid(bestVersion.getString("hid")),
                title = buildString {
                    if (!vol.isNullOrBlank()) {
                        append("Vol. ", vol, " ")
                    }
                    append("Ch. ", chap)
                    if (!title.isNullOrBlank()) {
                        append(": ", title)
                    }
                },
                number = chapterNumber,
                volume = vol?.toIntOrNull() ?: 0,
                url = "/comic/$mangaSlug/${bestVersion.getString("hid")}-chapter-$chap-$lang",
                scanlator = groups.takeIf { it.isNotBlank() },
                uploadDate = dateFormat.parseSafe(createdAt),
                branch = null, // Remove branch to avoid team selection UI
                source = source,
            )

            finalChapters.add(chapter)
        }

        return finalChapters
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://$domain${chapter.url}"
        val response = webClient.httpGet(fullUrl)
        val html = response.parseHtml()

        // Extract page data from HTML script tag
        val svDataElement = html.selectFirst("#sv-data")
            ?: throw IllegalArgumentException("Page data not found")

        val pageDataJson = svDataElement.data()
        val jo = JSONObject(pageDataJson)

        val chapterData = jo.getJSONObject("chapter")
        val images = chapterData.getJSONArray("images")

        return images.mapJSON { imageData ->
            val imageUrl = imageData.getString("url")
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val slug = link.pathSegments.lastOrNull() ?: return null
        return resolver.resolveManga(this, url = slug, id = generateUid(slug))
    }

    private val tagsArray = suspendLazy(initializer = ::loadTags)

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val sparseArray = tagsArray.get()
        val set = ArraySet<MangaTag>(sparseArray.size())
        for (i in 0 until sparseArray.size()) {
            set.add(sparseArray.valueAt(i))
        }
        return set
    }

    private suspend fun loadTags(): SparseArrayCompat<MangaTag> {
        val response = webClient.httpGet("https://$domain/api/metadata").parseJson()
        val genres = response.getJSONArray("genres")
        val tags = SparseArrayCompat<MangaTag>(genres.length())

        for (i in 0 until genres.length()) {
            val jo = genres.getJSONObject(i)
            tags.append(
                jo.getInt("id"),
                MangaTag(
                    title = jo.getString("name").toTitleCase(Locale.ENGLISH),
                    key = jo.getString("slug"),
                    source = source,
                ),
            )
        }
        return tags
    }

    private fun JSONObject.selectGenres(tags: SparseArrayCompat<MangaTag>): Set<MangaTag> {
        val array = optJSONArray("genres") ?: return emptySet()
        val res = ArraySet<MangaTag>(array.length())
        for (i in 0 until array.length()) {
            // Handle both old format (int IDs) and new format (objects with slugs)
            val element = array.opt(i)
            val tag = when (element) {
                is Int -> tags[element] // Old format: direct ID lookup
                is JSONObject -> {
                    // New format: find by slug
                    val slug = element.optString("slug")
                    if (slug.isNotEmpty()) {
                        findTagBySlug(tags, slug)
                    } else {
                        null
                    }
                }
                else -> null
            }
            tag?.let { res.add(it) }
        }
        return res
    }

    private fun findTagBySlug(tags: SparseArrayCompat<MangaTag>, slug: String): MangaTag? {
        for (i in 0 until tags.size()) {
            val tag = tags.valueAt(i)
            if (tag.key == slug) {
                return tag
            }
        }
        return null
    }

    private fun JSONArray.joinToString(separator: String): String {
        return (0 until length()).joinToString(separator) { i -> getString(i) }
    }
}
