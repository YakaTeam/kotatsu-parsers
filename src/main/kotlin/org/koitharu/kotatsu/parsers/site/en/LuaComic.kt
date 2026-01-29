package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("LUASCANS", "LuaComic", "en")
internal class LuaComic(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LUASCANS, 40) {

	override val configKeyDomain = ConfigKey.Domain("luacomic.org")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	private val rscHeaders: Headers
		get() = Headers.Builder()
			.add("RSC", "1")
			.add("Accept", "text/x-component")
			.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (page > 1) return emptyList() // Single page site

		val doc = webClient.httpGet("https://$domain/").parseHtml()
		val seriesLinks = doc.select("a[href^=/series/]")

		val seen = mutableSetOf<String>()
		return seriesLinks.mapNotNull { link ->
			val href = link.attrAsRelativeUrl("href")
			if (!seen.add(href)) return@mapNotNull null

			val slug = href.removePrefix("/series/")
			if (slug.isEmpty() || slug == "series") return@mapNotNull null

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = "https://$domain$href",
				coverUrl = null,
				title = slug.replace("-", " ").toTitleCase(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}.distinctBy { it.id }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = "https://$domain${manga.url}"
		val rscResponse = webClient.httpGet(url, rscHeaders).parseRaw()

		// Extract title from h1 element pattern in RSC
		val title = TITLE_REGEX.find(rscResponse)?.groupValues?.get(1) ?: manga.title

		// Extract cover image
		val coverUrl = COVER_REGEX.find(rscResponse)?.groupValues?.get(1)

		// Extract description from dangerouslySetInnerHTML
		val description = DESC_REGEX.findAll(rscResponse)
			.map { it.groupValues[1] }
			.firstOrNull { !it.startsWith("body{") && it.length > 50 }
			?.replace("\\n", "\n")
			?.replace("\\\"", "\"")

		// Extract total chapters
		val totalChapters = TOTAL_CHAPTERS_REGEX.find(rscResponse)?.groupValues?.get(1)?.toIntOrNull() ?: 0

		// Extract status
		val state = when {
			rscResponse.contains("\"children\":\"Completed\"") -> MangaState.FINISHED
			rscResponse.contains("\"children\":\"Ongoing\"") -> MangaState.ONGOING
			rscResponse.contains("\"children\":\"Hiatus\"") -> MangaState.PAUSED
			rscResponse.contains("\"children\":\"Dropped\"") -> MangaState.ABANDONED
			else -> null
		}

		// Generate chapter list (chapters 1 to totalChapters)
		val chapters = (1..totalChapters).map { num ->
			MangaChapter(
				id = generateUid("${manga.url}/chapter-$num"),
				title = "Chapter $num",
				number = num.toFloat(),
				volume = 0,
				url = "${manga.url}/chapter-$num",
				uploadDate = 0L,
				scanlator = null,
				branch = null,
				source = source,
			)
		}.reversed()

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = "https://$domain${chapter.url}"
		val html = webClient.httpGet(url).parseRaw()

		// Extract page images from chapter page
		val images = PAGE_REGEX.findAll(html)
			.map { it.groupValues[1] }
			.filter { it.contains("/uploads/series/") }
			.distinct()
			.toList()

		return images.mapIndexed { index, imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private companion object {
		// Matches title in RSC: "children":"Title"} with h1 context
		val TITLE_REGEX = Regex(""""text-xl[^}]*?"children":"([^"]+)"""")
		// Matches cover image URL
		val COVER_REGEX = Regex("""(https://media\.luacomic\.org/file/[^"]+\.webp)""")
		// Matches description in dangerouslySetInnerHTML
		val DESC_REGEX = Regex(""""dangerouslySetInnerHTML":\{"__html":"([^"]+)"\}""")
		// Matches total chapters count
		val TOTAL_CHAPTERS_REGEX = Regex("""Total chapters[^}]+\}[^}]+\}[^}]+?"children":"(\d+)"""")
		// Matches page images
		val PAGE_REGEX = Regex("""(https://media\.luacomic\.org/file/[^"]+\.webp)""")
	}
}
