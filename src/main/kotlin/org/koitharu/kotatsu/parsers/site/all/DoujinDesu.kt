package org.koitharu.kotatsu.parsers.site.all

import okhttp3.FormBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DOUJINDESU", "DoujinDesu", "id", ContentType.HENTAI)
internal class DoujinDesu(context: MangaLoaderContext) : 
	PagedMangaParser(context, MangaParserSource.DOUJINDESU, 20) {

	override val configKeyDomain = ConfigKey.Domain("doujindesu.tv")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.DOUJINSHI,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/manga/")
			append("?page=")
			append(page)

			when (order) {
				SortOrder.ALPHABETICAL -> append("&order=title")
				SortOrder.UPDATED -> append("&order=update")
				SortOrder.NEWEST -> append("&order=latest")
				SortOrder.POPULARITY -> append("&order=popular")
				else -> append("&order=update")
			}

			filter.query?.let {
				if (it.isNotEmpty()) {
					append("&title=")
					append(it.urlEncoded())
				}
			}

			filter.tags.forEach { tag ->
				append("&genre[]=")
				append(tag.key)
			}

			filter.states.oneOrThrowIfMany()?.let { state ->
				append("&status=")
				append(
					when (state) {
						MangaState.ONGOING -> "Publishing"
						MangaState.FINISHED -> "Finished"
						else -> ""
					}
				)
			}

			filter.types.oneOrThrowIfMany()?.let { type ->
				append("&type=")
				append(
					when (type) {
						ContentType.MANGA -> "Manga"
						ContentType.MANHWA -> "Manhwa"
						ContentType.DOUJINSHI -> "Doujinshi"
						else -> ""
					}
				)
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.entries article.entry").map { element ->
			val a = element.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val scoreText = element.selectFirst("div.score")?.text()
			val rating = scoreText?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
			
			Manga(
				id = generateUid(href),
				title = element.selectFirst("h3.title span")?.text() 
					?: a.attr("title"),
				altTitles = emptySet(),
				url = href,
				publicUrl = a.attrAsAbsoluteUrl("href"),
				rating = rating,
				contentRating = ContentRating.ADULT,
				coverUrl = element.selectFirst("figure.thumbnail img")?.src(),
				tags = emptySet(),
				state = when (element.selectFirst("div.status")?.text()?.lowercase()) {
					"finished" -> MangaState.FINISHED
					"publishing" -> MangaState.ONGOING
					else -> null
				},
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id"))
		
		val tags = doc.select("a[href*=/genre/]").mapToSet { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = a.text().trim(),
				source = source,
			)
		}

		val authors = doc.select("a[href*=/author/]").mapToSet { it.text().trim() }

		val chapters = doc.select("#chapter_list li").mapChapters(reversed = true) { index, element ->
			val a = element.selectFirst("a") ?: return@mapChapters null
			val href = a.attrAsRelativeUrl("href")
			val chapterText = element.selectFirst("chapter")?.text() ?: ""
			val chapterNum = chapterText.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (index + 1f)
			
			MangaChapter(
				id = generateUid(href),
				title = element.selectFirst(".lchx a")?.text() ?: chapterText,
				number = chapterNum,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(element.selectFirst(".date")?.text()),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			tags = tags,
			authors = authors,
			description = doc.selectFirst("div.excerpt")?.text(),
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val chapterId = doc.selectFirst("#reader")?.attr("data-id")
			?: throw IllegalStateException("Chapter ID not found")

		val body = FormBody.Builder()
			.add("id", chapterId)
			.build()

		val headers = okhttp3.Headers.Builder()
			.add("Referer", chapterUrl)
			.add("X-Requested-With", "XMLHttpRequest")
			.build()

		val response = webClient.httpPost(
			"https://$domain/themes/ajax/ch.php",
			body,
			headers,
		).parseHtml()

		return response.select("img").map { img ->
			val url = img.requireSrc()
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/genre/").parseHtml()
		return doc.select("a[href*=/genre/]").mapToSet { a ->
			val href = a.attr("href").removeSuffix("/")
			MangaTag(
				key = href.substringAfterLast("/"),
				title = a.text().trim(),
				source = source,
			)
		}
	}
}
