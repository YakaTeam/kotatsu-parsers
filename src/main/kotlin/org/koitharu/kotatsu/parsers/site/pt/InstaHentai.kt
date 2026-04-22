package org.koitharu.kotatsu.parsers.site.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("INSTAHENTAI", "InstaHentai", "pt", ContentType.HENTAI)
internal class InstaHentai(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.INSTAHENTAI, pageSize = 18) {

	override val configKeyDomain = ConfigKey.Domain("instahentai.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			if (!filter.query.isNullOrEmpty()) {
				append("/page/")
				append(page)
				append("/?s=")
				append(filter.query.urlEncoded())
			} else {
				val tagSlug = filter.tags.oneOrThrowIfMany()?.key
				if (tagSlug != null) {
					append("/generos/")
					append(tagSlug)
					append('/')
				} else {
					append("/melhores/")
				}
				append("page/")
				append(page)
				append('/')
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("article.card_item").mapNotNull { card ->
			val link = card.selectFirst("a[href*=/serie/]") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val title = card.selectFirst(".serie_title h3 a")?.text()?.trim()
				?: link.attr("aria-label").trim()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = card.selectFirst("img")?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/hentai-generos/").parseHtml()
		return buildSet {
			doc.select("a[href*=/generos/]").forEach { a ->
				val key = a.attr("href").trimEnd('/').substringAfterLast('/')
				val title = a.text().trim()
				if (key.isNotEmpty() && key != "generos" && title.isNotEmpty()) {
					add(
						MangaTag(
							key = key,
							title = title.toTitleCase(sourceLocale),
							source = source,
						),
					)
				}
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val authors = HashSet<String>()
		doc.select("a[href*=/autor/][rel=tag], a[href*=/arte/][rel=tag]").forEach { a ->
			val name = a.text().trim()
			if (name.isNotEmpty()) authors.add(name)
		}
		val tags = doc.select("a[href*=/generos/][rel=tag]").mapToSet { a ->
			MangaTag(
				key = a.attr("href").trimEnd('/').substringAfterLast('/'),
				title = a.text().trim().toTitleCase(sourceLocale),
				source = source,
			)
		}
		val description = doc.selectFirst("#textDiv")?.html()
		val chapters = doc.select("#section_cap > a").mapChapters { i, a ->
			val href = a.attrAsRelativeUrl("href")
			MangaChapter(
				id = generateUid(href),
				title = a.text().trim(),
				number = (i + 1).toFloat(),
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}
		return manga.copy(
			authors = authors,
			tags = tags,
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("#Imagens noscript img").mapNotNull { img ->
			val url = img.attrAsAbsoluteUrl("src").ifEmpty { return@mapNotNull null }
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
