@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package org.koitharu.kotatsu.parsers.compat

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.generateUid
import uy.kohesive.injekt.Injekt

/**
 * Adapter that wraps a Tachiyomi [HttpSource] and exposes it as a Kotatsu parser via [AbstractMangaParser].
 * Enables Tachiyomi extension sources to be used within the Kotatsu/Usagi ecosystem.
 */
@OptIn(InternalParsersApi::class)
internal class TachiyomiSourceAdapter(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val tachiyomiSource: HttpSource,
) : AbstractMangaParser(context, source) {

    init {
        // Inject MangaLoaderContext's OkHttpClient into Tachiyomi's NetworkHelper
        // so extensions use the same client (cookies, CF-bypass interceptors, etc.)
        NetworkHelper.setClient(context.httpClient)

        // Register Application context so Injekt.get<Application>() works
        // (used by extensions that access WebView or SharedPreferences)
        // We use reflection to grab the real Android Application context from ActivityThread
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentApplicationMethod.invoke(null) as? Application
            if (app != null) {
                Injekt.addSingleton<Application>(app)
            } else {
                Injekt.addSingleton<Application>(Application())
            }
        }.onFailure {
            Injekt.addSingleton<Application>(Application())
        }
    }

    override val configKeyDomain: ConfigKey.Domain by lazy {
        val host = runCatching {
            java.net.URI(tachiyomiSource.baseUrl).host
                ?: tachiyomiSource.baseUrl.toHostOnly()
        }.getOrDefault(tachiyomiSource.baseUrl.toHostOnly())
        ConfigKey.Domain(host)
    }

    override val availableSortOrders: Set<SortOrder> = buildSet {
        add(SortOrder.POPULARITY)
        add(SortOrder.UPDATED)
        if (tachiyomiSource.supportsLatest) {
            add(SortOrder.NEWEST)
        }
    }

    @InternalParsersApi
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    // ============================== List ===================================

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
        val page = (offset / PAGE_SIZE) + 1

        val mangasPage: MangasPage = withContext(Dispatchers.IO) {
            when {
                !query.isNullOrEmpty() -> {
                    val filterList = tachiyomiSource.getFilterList()
                    tachiyomiSource.fetchSearchManga(page, query, filterList)
                        .toBlocking().single()
                }
                order == SortOrder.NEWEST && tachiyomiSource.supportsLatest -> {
                    tachiyomiSource.fetchLatestUpdates(page).toBlocking().single()
                }
                else -> {
                    tachiyomiSource.fetchPopularManga(page).toBlocking().single()
                }
            }
        }

        return mangasPage.mangas.map { it.toManga() }
    }

    // ============================== Details ================================

    override suspend fun getDetails(manga: Manga): Manga {
        val sManga = manga.toSManga()

        return withContext(Dispatchers.IO) {
            val details = tachiyomiSource.fetchMangaDetails(sManga).toBlocking().single()
            val chapters = tachiyomiSource.fetchChapterList(sManga).toBlocking().single()

            manga.copy(
                title = details.title.ifEmpty { manga.title },
                altTitles = emptySet(),
                coverUrl = details.thumbnail_url ?: manga.coverUrl,
                largeCoverUrl = details.thumbnail_url,
                description = details.description,
                authors = setOfNotNull(details.author, details.artist)
                    .filter { it.isNotBlank() }.toSet(),
                tags = parseGenreTags(details.genre),
                state = mapStatus(details.status),
                chapters = chapters.mapIndexed { index, sChapter ->
                    sChapter.toMangaChapter(index, chapters.size)
                },
            )
        }
    }

    // ============================== Pages ==================================

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val sChapter = SChapter.create().apply {
            url = chapter.url
            name = chapter.title ?: ""
            chapter_number = chapter.number
        }

        val pages = withContext(Dispatchers.IO) {
            tachiyomiSource.fetchPageList(sChapter).toBlocking().single()
        }

        return pages.map { page ->
            val imgUrl = page.imageUrl ?: page.url
            MangaPage(
                id = generateUid(imgUrl.ifEmpty { "${chapter.url}#${page.index}" }),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val url = page.url
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "https://$domain$url"
    }

    // ============================== Filter Options =========================

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    // ============================== Headers ================================

    override fun getRequestHeaders(): Headers = tachiyomiSource.headers

    // ============================== Interceptor ============================

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())

    // ============================== Private Helpers ========================

    private fun SManga.toManga(): Manga {
        val mangaUrl = this.url
        val publicUrl = if (mangaUrl.startsWith("http")) mangaUrl
        else "${tachiyomiSource.baseUrl}$mangaUrl"

        return Manga(
            id = generateUid(mangaUrl),
            title = this.title,
            altTitles = emptySet(),
            url = mangaUrl,
            publicUrl = publicUrl,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = this.thumbnail_url,
            tags = parseGenreTags(this.genre),
            state = mapStatus(this.status),
            authors = setOfNotNull(this.author, this.artist)
                .filter { it.isNotBlank() }.toSet(),
            source = source,
        )
    }

    private fun Manga.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.url
        title = this@toSManga.title
        thumbnail_url = this@toSManga.coverUrl
    }

    private fun SChapter.toMangaChapter(index: Int, total: Int): MangaChapter {
        return MangaChapter(
            id = generateUid(this.url),
            title = this.name.ifBlank { null },
            number = if (this.chapter_number > 0f) this.chapter_number else (total - index).toFloat(),
            volume = 0,
            url = this.url,
            scanlator = this.scanlator,
            uploadDate = this.date_upload,
            branch = null,
            source = source,
        )
    }

    private fun parseGenreTags(genreStr: String?): Set<MangaTag> {
        if (genreStr.isNullOrBlank()) return emptySet()
        return genreStr.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapTo(mutableSetOf()) { genre ->
                MangaTag(title = genre, key = genre.lowercase(), source = source)
            }
    }

    private fun mapStatus(status: Int): MangaState? = when (status) {
        SManga.ONGOING -> MangaState.ONGOING
        SManga.COMPLETED -> MangaState.FINISHED
        SManga.ON_HIATUS -> MangaState.PAUSED
        SManga.CANCELLED -> MangaState.ABANDONED
        else -> null
    }

    private fun String.toHostOnly(): String =
        removePrefix("https://").removePrefix("http://").substringBefore("/")

    companion object {
        private const val PAGE_SIZE = 20
    }
}
