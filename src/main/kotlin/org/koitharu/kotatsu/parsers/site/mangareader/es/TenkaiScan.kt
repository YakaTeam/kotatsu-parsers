package org.koitharu.kotatsu.parsers.site.mangareader.es

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("TENKAISCAN", "FalcoScan", "es", ContentType.HENTAI)
internal class TenkaiScan(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TENKAISCAN, pageSize = 80) {

	override val configKeyDomain = ConfigKey.Domain("falcoscan.net")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (page > 1) return emptyList() // site does not paginate; all comics are returned on page 1
		val url = "https://$domain/comics".toHttpUrl().newBuilder().apply {
			if (!filter.query.isNullOrBlank()) addQueryParameter("search", filter.query)
		}.build()
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".card.blade-project-index").mapNotNull { card ->
			val link = card.parent() ?: return@mapNotNull null
			val href = link.attr("href").ifBlank { return@mapNotNull null }.toRelativeUrl(domain)
			val title = card.selectFirst("h4")?.text()?.trim().orEmpty()
			if (title.isBlank()) return@mapNotNull null
			val cover = card.selectFirst("img.img-details-list")?.absCoverUrl()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = cover,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val info = doc.selectFirst(".about")
		val author = info?.infoValue("Autor")
		val artist = info?.infoValue("Artista")
		val status = info?.infoValue("Status")
		val description = doc.selectFirst("h5:contains(Sinopsis)")
			?.nextElementSibling()
			?.text()
			?.trim()
		val cover = doc.selectFirst(".img-details-cover img, .img-cover img, .about img")?.absCoverUrl()
		val chapters = doc.select(".card-caps").mapIndexedNotNull { index, el ->
			val onclick = el.attr("onclick")
			val href = ONCLICK_HREF_REGEX.find(onclick)?.groupValues?.get(1)?.toRelativeUrl(domain)
				?: return@mapIndexedNotNull null
			val spans = el.select(".text-cap span")
			val name = spans.firstOrNull()?.text()?.trim().orEmpty()
			val dateStr = spans.getOrNull(1)?.text()?.trim().orEmpty()
			val number = CHAPTER_NUMBER_REGEX.find(name)?.value?.toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(href),
				title = name.ifBlank { "Capítulo ${index + 1}" },
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(dateStr),
				branch = null,
				source = source,
			)
		}.reversed() // page lists newest-first; return oldest-first
		return manga.copy(
			coverUrl = cover ?: manga.coverUrl,
			description = description,
			authors = setOfNotNull(author, artist),
			state = status?.toMangaState(),
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val images = doc.select("img.cap-img")
		if (images.isEmpty()) throw ParseException("No chapter pages found", chapter.url)
		return images.mapNotNull { img ->
			val url = img.attr("data-src").ifBlank { img.attr("src") }
				.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun Element.absCoverUrl(): String? {
		val src = attr("src").takeIf { it.isNotBlank() } ?: return null
		return src.toAbsoluteUrl(domain)
	}

	private fun Element.infoValue(label: String): String? {
		val p = select("p.sec").firstOrNull { it.text().trimStart().startsWith("$label:") }
			?: return null
		return p.ownText().trim().ifBlank { p.text().substringAfter(":").trim() }
			.takeIf { it.isNotBlank() }
	}

	private fun String.toMangaState(): MangaState? = when (lowercase(Locale.ROOT)) {
		"en emisión", "en emision", "en libertad" -> MangaState.ONGOING
		"completed", "completado", "finalizado" -> MangaState.FINISHED
		"canceled", "cancelled", "cancelado" -> MangaState.ABANDONED
		else -> null
	}

	private companion object {
		private val ONCLICK_HREF_REGEX = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
		private val CHAPTER_NUMBER_REGEX = Regex("""\d+(\.\d+)?""")
	}
}
