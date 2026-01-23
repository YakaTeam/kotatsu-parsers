package org.koitharu.kotatsu.parsers.site.ru

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("ACOMICS", "AComics", "ru", ContentType.COMICS)
internal class AComics(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ACOMICS, pageSize = 10) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
	)

	override val configKeyDomain = ConfigKey.Domain("acomics.ru")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	init {
		paginator.firstPage = 0
		searchPaginator.firstPage = 0
		context.cookieJar.insertCookies(domain, "ageRestrict=18")
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getOrCreateTagMap().values.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					if (page > 0) {
						return emptyList()
					}
					append("/search?keyword=")
					append(filter.query.urlEncoded())
				}

				else -> {
					append("/comics?ratings[]=1&ratings[]=2&ratings[]=3&ratings[]=4&ratings[]=5&ratings[]=6&skip=")
					append(page * 10)
					append("&sort=")
					append(
						when (order) {
							SortOrder.UPDATED -> "last_update"
							SortOrder.ALPHABETICAL -> "serial_name"
							SortOrder.POPULARITY -> "subscr_count"
							else -> "last_update"
						},
					)

					if (filter.tags.isNotEmpty()) {
						filter.tags.forEach { tag ->
							append("&categories[]=")
							append(tag.key)
						}
					}

					if (filter.states.isNotEmpty()) {
						append("&updatable=")
						append(
							filter.states.oneOrThrowIfMany().let {
								when (it) {
									MangaState.ONGOING -> "yes"
									MangaState.FINISHED -> "no"
									else -> "0"
								}
							},
						)
					}
				}
			}
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(docs: Document): List<Manga> {
		return docs.select("section.serial-card").map { card ->
			val titleLink = card.selectFirstOrThrow("h2.title a")
			val href = titleLink.attrAsAbsoluteUrl("href")
			val url = "$href/about"
			val coverImg = card.selectFirst(".cover img")
			val coverUrl = coverImg?.attr("data-real-src")?.toAbsoluteUrl(domain)
				?: coverImg?.src()?.toAbsoluteUrl(domain).orEmpty()
			Manga(
				id = generateUid(url),
				url = url,
				title = titleLink.text(),
				altTitles = emptySet(),
				publicUrl = href,
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private var tagCache: ArrayMap<String, MangaTag>? = null
	private val mutex = Mutex()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val tagMap = ArrayMap<String, MangaTag>()
		val doc = webClient.httpGet("https://$domain/comics").parseHtml()
		val categoryInputs = doc.select("fieldset.categories label")
		for (label in categoryInputs) {
			val input = label.selectFirst("input[name=categories[]]") ?: continue
			val value = input.attr("value")
			val name = label.text().trim()
			if (name.isEmpty() || value.isEmpty()) continue
			tagMap[name] = MangaTag(
				title = name,
				key = value,
				source = source,
			)
		}
		tagCache = tagMap
		return@withLock tagMap
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val tagMap = getOrCreateTagMap()
		val tags = doc.select("p.serial-about-badges a.category").mapNotNullToSet { el ->
			val categoryName = el.text().trim()
			tagMap[categoryName]
		}
		val author = doc.selectFirst("p.serial-about-authors a")?.text()
		val description = doc.selectFirst("section.serial-about-text")?.text()

		val chapterUrl = manga.url.replace("/about", "/")
		return manga.copy(
			tags = tags,
			description = description,
			authors = setOfNotNull(author),
			chapters = listOf(
				MangaChapter(
					id = manga.id,
					title = manga.title,
					number = 1f,
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = 0,
					branch = null,
					source = source,
				),
			),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain) + "1").parseHtml()
		val navigator = doc.selectFirst("nav.reader-navigator")
		val totalPages = navigator?.attr("data-issue-count")?.toIntOrNull()
			?: doc.selectFirst("h1.reader-issue-title span.number-without-name")
				?.text()?.substringAfterLast("/")?.trim()?.toIntOrNull()
			?: 1
		return (1..totalPages).map {
			val url = chapter.url + it
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.selectFirst("section.reader-issue img.issue")?.requireSrc()
			?: doc.selectFirst("section.reader-issue a img")?.requireSrc()
			?: throw Exception("Image not found")
	}
}
