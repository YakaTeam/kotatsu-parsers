package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAKATANA", "MangaKatana", "en")
internal class MangaKatana(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAKATANA, pageSize = 55) {

	override val configKeyDomain = ConfigKey.Domain("mangakatana.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrEmpty() -> buildString {
				append("https://")
				append(domain)
				if (page > 1) append("/page/").append(page)
				append("/?search=1&q=")
				append(filter.query.urlEncoded())
			}

			else -> buildString {
				append("https://")
				append(domain)
				append("/manga")
				if (page > 1) append("/page/").append(page)
				append("?filter=1")
				append("&include=")
				if (filter.tags.isNotEmpty()) {
					append(filter.tags.joinToString(separator = "_") { it.key })
				}
				append("&exclude=")
				if (filter.tagsExclude.isNotEmpty()) {
					append(filter.tagsExclude.joinToString(separator = "_") { it.key })
				}
				append("&chapters=1")
				append("&order=")
				append(
					when (order) {
						SortOrder.UPDATED -> "latest"
						SortOrder.NEWEST -> "new"
						SortOrder.POPULARITY -> "numc"
						else -> "az"
					},
				)
				filter.states.firstOrNull()?.let { state ->
					append("&status=")
					append(
						when (state) {
							MangaState.ABANDONED -> "0"
							MangaState.ONGOING -> "1"
							MangaState.FINISHED -> "2"
							else -> ""
						},
					)
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div#book_list div.item").mapNotNull { item ->
			val link = item.selectFirst("div.text h3.title a") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val img = item.selectFirst("div.media div.wrap_img img")
			val coverUrl = img?.attrOrNull("data-src") ?: img?.attrOrNull("src")
			val state = when (item.selectFirst("div.media div.status")?.className()?.substringAfterLast(' ')) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"cancelled" -> MangaState.ABANDONED
				else -> null
			}
			Manga(
				id = generateUid(href),
				title = link.text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = state,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val book = doc.selectFirst("div#single_book") ?: return manga
		val title = book.selectFirst("h1.heading")?.text() ?: manga.title
		val cover = book.selectFirst("div.cover img")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl
		val altTitles = book.selectFirst("div.alt_name")?.text()
			?.split(" ; ")
			?.mapNotNullToSet { it.trim().takeUnless(String::isEmpty) }
			.orEmpty()
		val authors = book.select("div.authors a.author").mapToSet { it.text() }
		val tags = book.select("div.genres a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('/'),
				title = a.text().toTitleCase(sourceLocale),
				source = source,
			)
		}
		val state = when (book.selectFirst("div.value.status")?.className()?.substringAfterLast(' ')) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"cancelled" -> MangaState.ABANDONED
			else -> null
		}
		val description = book.selectFirst("div.summary p")?.html()
		val dateFormat = SimpleDateFormat("MMM-dd-yyyy", Locale.ENGLISH)
		val chaptersRaw = book.select("div.chapters table tr").mapNotNull { tr ->
			val link = tr.selectFirst("div.chapter a") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val name = link.text()
			val dateText = tr.selectFirst("div.update_time")?.text()
			val number = chapterNumberRegex.find(name)?.value?.toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(href),
				title = name,
				url = href,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}
		return manga.copy(
			title = title,
			coverUrl = cover,
			altTitles = altTitles,
			authors = authors,
			tags = tags,
			state = state,
			description = description,
			chapters = chaptersRaw.reversed(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val script = doc.select("script").firstOrNull { imageArrayRegex.containsMatchIn(it.data()) }?.data()
			?: return emptyList()
		val match = imageArrayRegex.find(script) ?: return emptyList()
		val body = match.groupValues[1]
		return urlRegex.findAll(body).map { m ->
			val url = m.groupValues[1]
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}.toList()
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/manga").parseHtml()
		return doc.select("div#filter_tool input[name=include_genre_chk]").mapToSet { input ->
			val key = input.attr("value")
			val title = input.parent()?.parent()?.selectFirst("span.name")?.text()
				?: key.replace('-', ' ').toTitleCase(sourceLocale)
			MangaTag(key = key, title = title, source = source)
		}
	}

	private companion object {
		// matches: var thzq=['https://...','https://...',];
		// captures the inside of the first long array (non-`ytaw` single-element loader array)
		val imageArrayRegex = Regex("""var\s+\w+\s*=\s*\[((?:'[^']*'\s*,\s*){2,}[^\]]*)]""")
		val urlRegex = Regex("""'(https?://[^']+)'""")
		val chapterNumberRegex = Regex("""\d+(?:\.\d+)?""")
	}
}
