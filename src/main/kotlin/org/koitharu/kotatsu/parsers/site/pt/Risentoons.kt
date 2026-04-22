package org.koitharu.kotatsu.parsers.site.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("RISENTOONS", "Risentoons", "pt")
internal class Risentoons(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RISENTOONS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("risentoons.xyz")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		return if (query.isNotEmpty()) {
			if (page > 1) return emptyList()
			val doc = webClient.httpGet("https://$domain/search/?query=${query.urlEncoded()}").parseHtml()
			doc.select("a.search-result-card").mapNotNull { card ->
				val href = card.attrAsRelativeUrl("href").ifEmpty { return@mapNotNull null }
				Manga(
					id = generateUid(href),
					title = card.selectFirst("h3.search-result-title")?.text()?.trim().orEmpty(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = card.selectFirst("img.search-result-cover")?.attrAsAbsoluteUrl("src"),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		} else {
			val doc = webClient.httpGet("https://$domain/manga/todos/?page=$page").parseHtml()
			doc.select("article.comic-card").mapNotNull { card ->
				val link = card.selectFirst("a.comic-card-link") ?: return@mapNotNull null
				val href = link.attrAsRelativeUrl("href").ifEmpty { return@mapNotNull null }
				Manga(
					id = generateUid(href),
					title = card.selectFirst("h3.comic-card-title")?.text()?.trim().orEmpty(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = card.selectFirst("img.comic-card-image")?.attrAsAbsoluteUrl("src"),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val firstUrl = manga.url.toAbsoluteUrl(domain) + "?sort=asc&page=1"
		val doc = webClient.httpGet(firstUrl).parseHtml()
		val altTitles = doc.selectFirst("p.manga-alternative-titles")?.text()
			?.removeSurrounding("(", ")")
			?.split(';')
			?.mapNotNullTo(HashSet()) { it.trim().takeIf { s -> s.isNotEmpty() } }
			.orEmpty()
		val tags = doc.select("a.tag.genre-tag").mapToSet { a ->
			val title = a.text().trim()
			MangaTag(
				key = title.lowercase(sourceLocale),
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}
		val state = when (doc.selectFirst("span.tag.status-tag")?.text()?.trim()?.lowercase(Locale.ROOT)) {
			"em andamento" -> MangaState.ONGOING
			"completo", "concluído", "concluido", "finalizado" -> MangaState.FINISHED
			"pausado", "hiato" -> MangaState.PAUSED
			"cancelado" -> MangaState.ABANDONED
			else -> null
		}
		val description = doc.selectFirst("div.manga-description")?.html()
		val cover = doc.selectFirst("div.sidebar-cover-image img")?.attrAsAbsoluteUrl("src")
		val lastPage = doc.select("ul.pagination a.page-link[href*=page=]")
			.mapNotNull { it.attr("href").substringAfter("page=").substringBefore('&').toIntOrNull() }
			.maxOrNull() ?: 1
		val chapterDocs = ArrayList<org.jsoup.nodes.Document>(lastPage).apply { add(doc) }
		for (p in 2..lastPage) {
			chapterDocs.add(
				webClient.httpGet(manga.url.toAbsoluteUrl(domain) + "?sort=asc&page=$p").parseHtml(),
			)
		}
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
		val chapters = chapterDocs.flatMap { d -> d.select("ul.chapter-item-list > li.chapter-item") }
			.mapChapters { i, li ->
				val a = li.selectFirst("a.chapter-link") ?: return@mapChapters null
				val href = a.attrAsRelativeUrl("href").ifEmpty { return@mapChapters null }
				val label = li.selectFirst("span.chapter-number")?.text()?.trim().orEmpty()
				val numberFromUrl = href.trimEnd('/').substringAfterLast('/')
					.replace('-', '.')
					.toFloatOrNull()
				val number = numberFromUrl
					?: label.substringAfterLast(' ').replace('-', '.').toFloatOrNull()
					?: (i + 1f)
				val date = li.selectFirst("span.chapter-date")?.text()?.trim()
					?.substringAfterLast(' ')
				MangaChapter(
					id = generateUid(href),
					title = label.ifEmpty { "Capítulo ${i + 1}" },
					number = number,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(date),
					branch = null,
					source = source,
				)
			}
		return manga.copy(
			altTitles = altTitles,
			state = state,
			tags = tags,
			description = description,
			coverUrl = cover ?: manga.coverUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("canvas.chapter-image-canvas[data-src-url]").map { canvas ->
			val url = canvas.attrAsAbsoluteUrl("data-src-url")
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
