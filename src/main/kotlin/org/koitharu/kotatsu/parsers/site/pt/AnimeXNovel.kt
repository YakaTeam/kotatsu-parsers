package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("ANIMEXNOVEL", "AnimeXNovel", "pt")
internal class AnimeXNovel(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ANIMEXNOVEL, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("animexnovel.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	// Top-level WP category IDs that contain the series we expose.
	// Parent 21 = "Mangá", parent 337 = "Manhua".
	private val seriesParents = setOf(21L, 337L)

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val all = fetchAllSeries(filter.query)
		val sorted = when (order) {
			SortOrder.POPULARITY -> all.sortedByDescending { it.postCount }
			SortOrder.ALPHABETICAL -> all.sortedBy { it.name.lowercase(Locale.ROOT) }
			else -> all.sortedByDescending { it.id } // UPDATED proxy: newest series first
		}
		val from = (page - 1) * pageSize
		if (from >= sorted.size) return emptyList()
		return sorted.subList(from, minOf(from + pageSize, sorted.size)).map { it.toManga() }
	}

	private data class SeriesCategory(
		val id: Long,
		val parent: Long,
		val slug: String,
		val name: String,
		val postCount: Int,
	) {
		val typeSegment: String get() = if (parent == 337L) "manhua" else "manga"
		val relativeUrl: String get() = "/$typeSegment/$slug/"
	}

	private fun SeriesCategory.toManga(): Manga = Manga(
		id = generateUid(relativeUrl),
		title = name,
		altTitles = emptySet(),
		url = relativeUrl,
		publicUrl = relativeUrl.toAbsoluteUrl(domain),
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)

	private suspend fun fetchAllSeries(query: String?): List<SeriesCategory> {
		val url = "https://$domain/wp-json/wp/v2/categories".toHttpUrl().newBuilder()
			.addQueryParameter("per_page", "100")
			.apply {
				if (!query.isNullOrBlank()) addQueryParameter("search", query)
			}
			.build()
		val response = webClient.httpGet(url).parseJsonArray()
		val result = ArrayList<SeriesCategory>()
		for (i in 0 until response.length()) {
			val cat = response.getJSONObject(i)
			val parent = cat.optLong("parent")
			if (parent !in seriesParents) continue
			result += SeriesCategory(
				id = cat.getLong("id"),
				parent = parent,
				slug = cat.getString("slug"),
				name = cat.getString("name"),
				postCount = cat.optInt("count"),
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val categoryId = doc.selectFirst(".axn-chapters-container[data-categoria]")
			?.attr("data-categoria")
			?.takeIf { it.isNotBlank() }
			?: throw ParseException("Could not find chapter category ID", manga.url)

		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
			?: doc.selectFirst(".spnc-entry-content img")?.src()
		val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
			?: doc.selectFirst("meta[name=description]")?.attr("content")
		val author = doc.selectFirst("li:contains(Autor:)")?.text()
			?.substringAfter(":")?.trim()
		val artist = doc.selectFirst("li:contains(Arte:)")?.text()
			?.substringAfter(":")?.trim()

		return manga.copy(
			coverUrl = cover,
			description = description,
			authors = setOfNotNull(author?.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() }),
			chapters = fetchChapters(categoryId),
		)
	}

	private suspend fun fetchChapters(categoryId: String): List<MangaChapter> {
		val all = ArrayList<MangaChapter>()
		var page = 1
		while (true) {
			val url = "https://$domain/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
				.addQueryParameter("categories", categoryId)
				.addQueryParameter("orderby", "date")
				.addQueryParameter("order", "asc")
				.addQueryParameter("per_page", "100")
				.addQueryParameter("page", page.toString())
				.build()
			val arr: JSONArray = try {
				webClient.httpGet(url).parseJsonArray()
			} catch (_: Exception) {
				break
			}
			if (arr.length() == 0) break
			for (i in 0 until arr.length()) {
				val item = arr.getJSONObject(i)
				val link = item.getString("link").toRelativeUrl(domain)
				if (!link.contains("capitulo", ignoreCase = true)) continue
				val rendered = item.getJSONObject("title").getString("rendered")
				val title = cleanTitle(rendered)
				val slug = item.optString("slug")
				val number = CHAPTER_REGEX.findAll(slug).lastOrNull()?.value?.toFloatOrNull()
					?: (all.size + 1).toFloat()
				val uploadDate = dateFormat.parseSafe(item.optString("date").take(10))
				all += MangaChapter(
					id = generateUid(link),
					title = title,
					number = number,
					volume = 0,
					url = link,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source,
				)
			}
			if (arr.length() < 100) break
			page++
		}
		return all
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val container = doc.selectFirst(".spice-block-img-gallery")
			?: doc.selectFirst(".wp-block-gallery")
			?: doc.selectFirst(".spnc-entry-content")
			?: throw ParseException("Page container not found", chapter.url)
		return container.select("img").mapNotNull { img ->
			val url = img.src() ?: img.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun cleanTitle(rendered: String): String {
		val decoded = rendered
			.replace("&#8211;", "–")
			.replace("&#8217;", "'")
			.replace("&amp;", "&")
			.replace("&quot;", "\"")
		return decoded.substringAfter("–").trim().ifBlank { decoded.trim() }
	}

	private companion object {
		private val CHAPTER_REGEX = Regex("""\d+(\.\d+)?""")
	}
}
