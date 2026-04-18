package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.json.JSONArray
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
        val isDefaultHome = order == SortOrder.UPDATED && filter.query.isNullOrEmpty() && filter.tags.isEmpty() && filter.locale == null

        val url = if (isDefaultHome) {
            "https://$domain/api/v2/galleries?page=$page"
        } else {
            val query = listOfNotNull(
                filter.query?.trim()?.takeIf { it.isNotEmpty() },
                buildQuery(filter.tags, filter.locale).takeIf { it.isNotEmpty() }
            ).joinToString(" ").ifEmpty { "pages:>0" }

            val sort = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.POPULARITY_TODAY -> "popular-today"
                SortOrder.POPULARITY_WEEK -> "popular-week"
                else -> "date"
            }
            "https://$domain/api/v2/search?query=${query.urlEncoded()}&sort=$sort&page=$page"
        }

        val json = webClient.httpGet(url).parseJson()
        val results = json.optJSONArray("result") ?: JSONArray()
        
        return (0 until results.length()).map { mapManga(results.getJSONObject(it)) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.removeSurrounding("/g/", "/")
        val obj = webClient.httpGet("https://$domain/api/v2/galleries/$id").parseJson()
        
        val tagsArray = obj.optJSONArray("tags") ?: JSONArray()
        val tagsList = (0 until tagsArray.length()).map { tagsArray.getJSONObject(it) }

        return manga.copy(
            tags = tagsList.map { MangaTag(it.getString("slug"), it.getString("name").toTitleCase(), source) }.toSet(),
            authors = tagsList.filter { it.getString("type") == "artist" }.map { it.getString("name").toTitleCase() }.toSet(),
            description = "Pages: ${obj.optInt("num_pages")}",
            coverUrl = "https://t.$domain/${obj.getCoverPath()}",
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
        val pagesArray = webClient.httpGet("https://$domain/api/v2/galleries/$id").parseJson().getJSONArray("pages")
        
        return (0 until pagesArray.length()).map { index ->
            val page = pagesArray.getJSONObject(index)
            MangaPage(
                id = generateUid("${chapter.url}${index + 1}"),
                url = "https://i.$domain/${page.getString("path")}",
                preview = page.optString("thumbnail").takeIf { it.isNotBlank() }?.let { "https://t.$domain/$it" },
                source = source
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private fun mapManga(obj: JSONObject): Manga {
        val id = obj.getInt("id")
        val title = obj.extractTitle()
        return Manga(
            id = generateUid("/g/$id/"),
            title = title.cleanupTitle().ifEmpty { title },
            altTitles = emptySet(),
            url = "/g/$id/",
            publicUrl = "https://$domain/g/$id/",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = "https://t.$domain/${obj.getThumbnailPath()}",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
    }

    private fun JSONObject.extractTitle(): String {
        val titleObj = optJSONObject("title")
        return listOfNotNull(
            titleObj?.optString("english"),
            titleObj?.optString("pretty"),
            optString("english_title"),
            optString("japanese_title")
        ).firstOrNull { it.isNotBlank() } ?: "Gallery ${optInt("id")}"
    }

    private fun JSONObject.getThumbnailPath(): String = optJSONObject("thumbnail")?.optString("path")
        ?: optString("thumbnail").takeIf { it.isNotBlank() }
        ?: "galleries/${optString("media_id")}/thumb.webp"

    private fun JSONObject.getCoverPath(): String = optJSONObject("cover")?.optString("path") ?: getThumbnailPath()

    private fun buildQuery(tags: Collection<MangaTag>, language: Locale?): String = buildString {
        tags.forEach { append("tag:\"${it.key}\" ") }
        language?.let { append("language:\"${it.toLanguagePath()}\" ") }
    }.trim()

    override fun Locale.toLanguagePath(): String = when (this) {
        Locale.ENGLISH -> "english"
        Locale.JAPANESE -> "japanese"
        Locale.CHINESE -> "chinese"
        else -> language
    }

    // Required overrides for base class
    override val selectGallery = ""
    override val selectGalleryLink = ""
    override val selectGalleryTitle = ""
    override val selectGalleryImg = ""
    override val idImg = "none"
}
