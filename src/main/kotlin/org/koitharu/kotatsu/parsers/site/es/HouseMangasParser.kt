package org.koitharu.kotatsu.parsers.site.es

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("HOUSEMANGAS", "HouseMangas", "es")
internal class HouseMangasParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.HOUSEMANGAS, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("visormanga.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// Site has no explicit sort options — catalog order is whatever /biblioteca returns.
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchGenres(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			val genre = filter.tags.firstOrNull()?.key
			if (genre != null) {
				append("/genero/")
				append(genre)
			} else {
				append("/biblioteca")
			}
			val query = filter.query?.takeIf(String::isNotBlank)?.urlEncoded()
			val params = buildList {
				if (page > 1) add("page=$page")
				if (query != null) add("search=$query")
			}
			if (params.isNotEmpty()) {
				append('?')
				append(params.joinToString("&"))
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.image-post").mapNotNull { item ->
			val link = item.selectFirst("a[href*=/manga/]") ?: return@mapNotNull null
			val href = link.attr("href").toRelativeUrl(domain)
			val img = link.selectFirst("img")
			val title = link.attr("title").ifEmpty { item.selectFirst(".chapter-title-updated")?.text().orEmpty() }
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				coverUrl = img?.attrAsAbsoluteUrl("src"),
				largeCoverUrl = null,
				description = null,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
				contentRating = ContentRating.ADULT,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("h1.h1-title-manga")?.text()?.trim() ?: manga.title
		val description = doc.selectFirst(".sinopsis p.content")?.text()
		val cover = doc.selectFirst(".front-page-image img")?.attrAsAbsoluteUrl("src")
			?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
			?: manga.coverUrl
		val genres = doc.select(".genres-manga-content a[href*=/genero/]").mapNotNullToSet { a ->
			val key = a.attr("href").substringAfterLast("/genero/").substringBefore("?")
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(key = key, title = a.text().trim().ifEmpty { key }, source = source)
		}
		val chapterEls = doc.select(".chapters-list li a[href*=/leer/]")
		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			tags = genres,
			chapters = chapterEls.mapChapters(reversed = true) { i, a ->
				val chapterUrl = a.attr("href").toRelativeUrl(domain)
				val label = a.selectFirst(".chapter-li-a")?.text()?.trim().orEmpty()
					.ifEmpty { a.text().trim() }
				val number = Regex("""([0-9]+(?:\.[0-9]+)?)""")
					.find(chapterUrl.substringAfterLast('-'))
					?.value?.toFloatOrNull() ?: (i + 1f)
				MangaChapter(
					id = generateUid(chapterUrl),
					title = label.ifEmpty { "Capítulo $number" },
					number = number,
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("#image-alls img.lazyload[data-src]").mapNotNull { img ->
			val url = img.attr("data-src").takeIf(String::isNotBlank) ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchGenres(): Set<MangaTag> {
		val doc = runCatching {
			webClient.httpGet("https://$domain/biblioteca").parseHtml()
		}.getOrNull() ?: return emptySet()
		return doc.select("a[href*=/genero/]").mapNotNullToSet { a ->
			val key = a.attr("href").substringAfterLast("/genero/").substringBefore("?")
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = a.text().trim().ifEmpty { key.replace('-', ' ') },
				source = source,
			)
		}
	}
}
