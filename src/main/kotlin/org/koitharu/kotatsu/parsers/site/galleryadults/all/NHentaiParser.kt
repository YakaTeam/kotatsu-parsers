package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.*
import java.time.Duration
import java.util.*

@MangaSourceParser("NHENTAI", "NHentai.net", type = ContentType.HENTAI)
internal class NHentaiParser(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.NHENTAI, "nhentai.net", 25) {

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY, SortOrder.POPULARITY_WEEK)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Use galleries endpoint ONLY for the default homepage (Recent)
        val isDefaultRecentHome = order == SortOrder.UPDATED && 
                                  filter.query.isNullOrEmpty() && 
                                  filter.tags.isEmpty() && 
                                  filter.locale == null

        val apiUrl = if (isDefaultRecentHome) {
            "https://$domain/api/v2/galleries?page=$page"
        } else {
            val query = buildString {
                if (!filter.query.isNullOrEmpty()) append(filter.query).append(" ")
                append(buildQuery(filter.tags, filter.locale))
            }.trim()

            // Search requires at least one query; use pages:>0 to list all items for sorting
            val finalQuery = if (query.isEmpty()) "pages:>0" else query

            val sort = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.POPULARITY_TODAY -> "popular-today"
                SortOrder.POPULARITY_WEEK -> "popular-week"
                else -> "date"
            }
            "https://$domain/api/v2/search?query=${finalQuery.urlEncoded()}&sort=$sort&page=$page"
        }

        val response = webClient.httpGet(apiUrl).parseJson()
        val results = if (response.has("result")) response.getJSONArray("result") else return emptyList()
        
        val mangaList = mutableListOf<Manga>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val id = obj.getInt("id")
            val titleObj = obj.optJSONObject("title")
            
            // Safeguard: String must not be empty
            val rawTitle = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("pretty")?.takeIf { it.isNotBlank() }
                ?: obj.optString("english_title").takeIf { it.isNotBlank() }
                ?: obj.optString("japanese_title").takeIf { it.isNotBlank() }
                ?: "Gallery $id"

            val displayTitle = rawTitle.cleanupTitle().let { if (it.isEmpty()) rawTitle else it }

            mangaList.add(Manga(
                id = generateUid("/g/$id/"),
                title = displayTitle,
                altTitles = emptySet(),
                url = "/g/$id/",
                publicUrl = "https://$domain/g/$id/",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                // FIXED: 404 cover fix - using exact path from JSON
                coverUrl = "https://t.nhentai.net/${obj.getThumbnailPath()}",
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            ))
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.removeSurrounding("/g/", "/")
        val obj = webClient.httpGet("https://$domain/api/v2/galleries/$id").parseJson()
        
        val tags = mutableSetOf<MangaTag>()
        val authors = mutableSetOf<String>()
        
        val tagsArray = obj.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tagObj = tagsArray.getJSONObject(i)
                val type = tagObj.optString("type")
                val name = tagObj.optString("name")
                val slug = tagObj.optString("slug").takeIf { it.isNotBlank() } ?: name.urlEncoded()
                
                if (slug.isNotBlank()) {
                    if (type == "artist") authors.add(name.toTitleCase())
                    tags.add(MangaTag(slug, name.toTitleCase(), source))
                }
            }
        }

        return manga.copy(
            tags = tags,
            authors = authors,
            description = "Pages: ${obj.optInt("num_pages")}",
            // FIXED: 404 cover fix - using exact path from detail JSON
            coverUrl = "https://t.nhentai.net/${obj.getCoverPath()}",
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 1f,
                    volume = 0,
                    url = manga.url,
                    scanlator = null,
                    uploadDate = obj.optLong("upload_date") * 1000,
                    branch = null,
                    source = source
                )
            )
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.removeSurrounding("/g/", "/")
        val obj = webClient.httpGet("https://$domain/api/v2/galleries/$id").parseJson()
        val pagesArray = obj.getJSONArray("pages")
        
        val pages = mutableListOf<MangaPage>()
        for (i in 0 until pagesArray.length()) {
            val pageObj = pagesArray.getJSONObject(i)
            val path = pageObj.optString("path")
            val thumbPath = pageObj.optString("thumbnail")
            
            if (path.isNotBlank()) {
                pages.add(MangaPage(
                    id = generateUid("${chapter.url}${i + 1}"),
                    url = "https://i.nhentai.net/$path",
                    preview = if (thumbPath.isNotBlank()) "https://t.nhentai.net/$thumbPath" else null,
                    source = source
                ))
            }
        }
        return pages
    }

    // Overridden to return URL directly and prevent JSoup ValidationException (idImg must not be empty)
    override suspend fun getPageUrl(page: MangaPage): String = page.url

    // Helper functions for 404 cover fix
    private fun JSONObject.getThumbnailPath(): String {
        val thumbObj = optJSONObject("thumbnail")
        return thumbObj?.optString("path") 
            ?: optString("thumbnail").takeIf { it.isNotBlank() }
            ?: "galleries/${optString("media_id")}/thumb.webp"
    }

    private fun JSONObject.getCoverPath(): String {
        val coverObj = optJSONObject("cover")
        return coverObj?.optString("path") ?: getThumbnailPath()
    }

    // Mandatory overrides for GalleryAdultsParser
    override val selectGallery = ""
    override val selectGalleryLink = ""
    override val selectGalleryTitle = ""
    override val selectGalleryImg = ""
    override val idImg = "none"

    private fun buildQuery(tags: Collection<MangaTag>, language: Locale?): String {
        return buildString {
            tags.forEach { append("tag:\"${it.key}\" ") }
            language?.let { append("language:\"${it.toLanguagePath()}\" ") }
        }.trim()
    }

    override fun Locale.toLanguagePath(): String = when (this) {
        Locale.ENGLISH -> "english"
        Locale.JAPANESE -> "japanese"
        Locale.CHINESE -> "chinese"
        else -> language
    }
}
