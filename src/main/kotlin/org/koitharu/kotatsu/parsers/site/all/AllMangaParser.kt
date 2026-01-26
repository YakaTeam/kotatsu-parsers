package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("ALLMANGA", "AllManga", "en")
internal class AllMangaParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ALLMANGA, pageSize = 26) {

	override val configKeyDomain = ConfigKey.Domain("allmanga.to")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			if (!filter.query.isNullOrEmpty()) {
				append("/search-manga?keyword=")
				append(filter.query.urlEncoded())
				append("&page=")
				append(page)
			} else {
				append("/search-manga?cty=ALL")
				append("&sort=")
				append(
					when (order) {
						SortOrder.UPDATED -> "Recent"
						SortOrder.POPULARITY -> "Popular"
						SortOrder.NEWEST -> "New"
						else -> "Recent"
					}
				)
				append("&page=")
				append(page)
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("a[href*='/manga/']").mapNotNull { element ->
			val href = element.attrOrNull("href") ?: return@mapNotNull null
			if (!href.contains("/manga/") || href.contains("/chapter-")) return@mapNotNull null
			
			val img = element.selectFirst("img")
			val title = element.selectFirst("h3, .title, span")?.text()
				?: img?.attr("alt")
				?: return@mapNotNull null

			val coverUrl = img?.src()

			Manga(
				id = generateUid(href),
				title = title.trim(),
				altTitles = emptySet(),
				url = href.toRelativeUrl(domain),
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy { it.id }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirst("h1, .title")?.text() ?: manga.title
		val description = doc.selectFirst(".description, .synopsis, [class*=desc]")?.text()
		val coverUrl = doc.selectFirst("img[src*=mcovers], img[src*=cover]")?.src() ?: manga.coverUrl

		val tags = doc.select("a[href*='/tags/'], a[href*='/genre/']").mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast("/").substringBefore("?"),
				title = a.text().trim(),
				source = source,
			)
		}

		val state = doc.body().text().let { text ->
			when {
				text.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
				text.contains("Completed", ignoreCase = true) -> MangaState.FINISHED
				else -> null
			}
		}

		val chapters = doc.select("a[href*='/chapter-']").mapChapters(reversed = true) { index, element ->
			val chapterHref = element.attrOrNull("href") ?: return@mapChapters null
			val chapterText = element.text()
			val chapterNum = chapterText.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (index + 1f)

			MangaChapter(
				id = generateUid(chapterHref),
				title = chapterText.trim().ifEmpty { "Chapter ${index + 1}" },
				number = chapterNum,
				volume = 0,
				url = chapterHref.toRelativeUrl(domain),
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			description = description,
			coverUrl = coverUrl,
			tags = tags,
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		return doc.select("img[src*=youtube-anime], img[data-src*=youtube-anime], .chapter-content img, .reading-content img").mapIndexed { index, img ->
			val url = img.src() ?: img.attr("data-src")
			MangaPage(
				id = generateUid("${chapter.url}/$index"),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun String.toRelativeUrl(domain: String): String {
		return this.removePrefix("https://$domain").removePrefix("http://$domain")
	}
}
