package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.jsoup.internal.StringUtil
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("NHENTAI", "NHentai.net", type = ContentType.HENTAI)
internal class NHentaiParser(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.NHENTAI, "nhentai.net", 25) {

    override val selectGallery = "div.index-container:not(.index-popular) .gallery, .search-container .gallery, #related-container .gallery"
    override val selectGalleryLink = "a"
    override val selectGalleryTitle = ".caption"
    override val selectGalleryImg = "img"
    
    override val pathTagUrl = "/tags/?page="
    override val selectTags = ".tag-container"
    override val selectTag = ".tag-container:contains(Tags:) span.tags"
    override val selectAuthor = ".tag-container:contains(Artists:) span.name"
    override val selectLanguageChapter = ".tag-container:contains(Languages:) span.tags a:not(.tag-17249) span.name" 
    
    override val idImg = "image-container"

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY, SortOrder.POPULARITY_WEEK)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
        availableLocales = setOf(Locale.ENGLISH, Locale.JAPANESE, Locale.CHINESE),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            
            val isSearch = !filter.query.isNullOrEmpty() || filter.tags.isNotEmpty() || order != SortOrder.UPDATED
            
            if (isSearch) {
                append("/search/?q=")
                val queryParts = mutableListOf<String>()
                
                // Handle numeric ID search
                val numericQuery = filter.query?.trim()
                if (numericQuery != null && numericQuery.matches("\\d+".toRegex())) {
                    try {
                        val title = fetchMangaTitle("https://$domain/g/$numericQuery/")
                        queryParts.add(title)
                    } catch (e: Exception) {
                        queryParts.add(numericQuery)
                    }
                } else if (!filter.query.isNullOrEmpty()) {
                    queryParts.add(filter.query!!)
                }

                // Add tags and language to query
                val tagQuery = buildQuery(filter.tags, filter.locale)
                if (tagQuery.isNotEmpty()) {
                    queryParts.add(tagQuery)
                }

                append(queryParts.joinToString(" ").urlEncoded())

                when (order) {
                    SortOrder.POPULARITY -> append("&sort=popular")
                    SortOrder.POPULARITY_TODAY -> append("&sort=popular-today")
                    SortOrder.POPULARITY_WEEK -> append("&sort=popular-week")
                    else -> {}
                }
            } else {
                // Default homepage
                append("/")
            }

            if (page > 1) {
                append(if (contains("?")) "&" else "?")
                append("page=$page")
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private suspend fun fetchMangaTitle(url: String): String {
        val doc = webClient.httpGet(url).parseHtml()
        return (doc.selectFirst("h1.title") ?: doc.selectFirst(".title"))!!.text()
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(selectGallery).map { div ->
            val a = div.selectFirstOrThrow(selectGalleryLink)
            val href = a.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = div.select(selectGalleryTitle).text().cleanupTitle(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = div.selectFirstOrThrow(selectGalleryImg).src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
        // SvelteKit sites often lazy load, but the image-container img src is usually present in the DOM
        return doc.requireElementById(idImg).selectFirstOrThrow("img").requireSrc()
    }

    override fun Element.parseTags() = select("a").mapToSet {
        // Extract tag name from the link or the .name span
        val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
        val name = it.selectFirst(".name")?.text() ?: it.text()
        MangaTag(
            key = key,
            title = name.toTitleCase(sourceLocale),
            source = source,
        )
    }

    private fun buildQuery(tags: Collection<MangaTag>, language: Locale?): String {
        val joiner = StringUtil.StringJoiner(" ")
        tags.forEach { tag ->
            joiner.add("tag:\"${tag.key}\"")
        }
        language?.let { lc ->
            joiner.add("language:\"${lc.toLanguagePath()}\"")
        }
        return joiner.complete()
    }
    
    override fun Locale.toLanguagePath(): String = when (this) {
        Locale.ENGLISH -> "english"
        Locale.JAPANESE -> "japanese"
        Locale.CHINESE -> "chinese"
        else -> language
    }
}