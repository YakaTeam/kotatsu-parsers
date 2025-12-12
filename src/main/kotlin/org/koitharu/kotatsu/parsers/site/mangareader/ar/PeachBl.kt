package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("PEACHBL", "PeachBl", "ar", ContentType.HENTAI)
internal class PeachBl(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.PEACHBL, "peach-bl.com", pageSize = 20, searchPageSize = 10) {
	override val sourceLocale: Locale = Locale.ENGLISH
	override val listUrl = "" // Use root page instead of /manga

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					// New search URL structure: https://peach-bl.com/?post_type=webtoon&s=mary
					append("/?post_type=webtoon&s=")
					append(filter.query.urlEncoded())
					if (page > 1) {
						append("&paged=")
						append(page.toString())
					}
				}

				else -> {
					// New list URL structure: https://peach-bl.com/?s=
					append("/?s=")
					if (page > 1) {
						append("&paged=")
						append(page.toString())
					}

					// Add order parameter if needed
					when (order) {
						SortOrder.ALPHABETICAL -> append("&orderby=title&order=ASC")
						SortOrder.ALPHABETICAL_DESC -> append("&orderby=title&order=DESC")
						SortOrder.NEWEST -> append("&orderby=date&order=DESC")
						SortOrder.POPULARITY -> append("&orderby=meta_value_num&meta_key=_wp_manga_views&order=DESC")
						SortOrder.UPDATED -> append("&orderby=modified&order=DESC")
						else -> {}
					}

					filter.tags.forEach { tag ->
						append("&genre[]=")
						append(tag.key)
					}

					filter.tagsExclude.forEach { tag ->
						append("&genre[]=-")
						append(tag.key)
					}

					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let { state ->
							append("&status=")
							when (state) {
								MangaState.ONGOING -> append("ongoing")
								MangaState.FINISHED -> append("completed")
								MangaState.PAUSED -> append("on-hold")
								MangaState.ABANDONED -> append("canceled")
								else -> {}
							}
						}
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(".webtoon-card").mapNotNull { card ->
			val titleElement = card.selectFirst("h3.webtoon-title a") ?: return@mapNotNull null
			val coverLinkElement = card.selectFirst("a.cover-link") ?: return@mapNotNull null
			val imageElement = card.selectFirst(".cover-image")

			// Use the cover link URL which points to the correct webtoon page
			val relativeUrl = coverLinkElement.attrAsRelativeUrl("href")
			val title = titleElement.selectFirst(".title-text")?.text()
				?: titleElement.text().removePrefix("🇰🇷 ").removePrefix("🇯🇵 ").trim()

			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = title,
				altTitles = emptySet(),
				publicUrl = coverLinkElement.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				coverUrl = imageElement?.attrAsAbsoluteUrlOrNull("src"),
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		// Extract chapters from the webtoon details page
		val chapters = docs.select(".chapter-list .chapter-item a.chapter-link").mapChapters(reversed = true) { index, element ->
			val url = element.attrAsRelativeUrl("href")
			val chapterNumber = element.selectFirst(".chapter-number")?.text() ?: ""
			val chapterTitle = element.selectFirst(".chapter-title")?.text() ?: ""
			val title = if (chapterTitle.isNotBlank()) {
				"$chapterNumber - $chapterTitle".trim(' ', '-')
			} else {
				chapterNumber.takeIf { it.isNotBlank() } ?: element.text().trim()
			}

			// Parse upload date from chapter-date element (format: 2025.02.10)
			val dateText = element.selectFirst(".chapter-date")?.text()
			val uploadDate = dateText?.let { parseChapterDate(it) } ?: 0L

			MangaChapter(
				id = generateUid(url),
				title = title,
				url = url,
				number = extractChapterNumber(chapterNumber) ?: (index + 1f),
				volume = 0,
				uploadDate = uploadDate,
				scanlator = null,
				source = source,
				branch = null,
			)
		}

		// Extract manga details
		val title = docs.selectFirst("h1.webtoon-title, h1")?.text()?.trim() ?: manga.title
		val description = docs.select(".webtoon-summary, .description, .summary").text()?.takeIf { it.isNotBlank() }
		val coverUrl = docs.selectFirst(".webtoon-cover img, .cover-image")?.attrAsAbsoluteUrlOrNull("src")

		// Extract tags if available
		val tags = docs.select(".webtoon-tags a, .tags a, .genre a").mapNotNullToSet { tagElement ->
			val tagTitle = tagElement.text().trim()
			if (tagTitle.isNotBlank()) {
				MangaTag(
					key = tagTitle.lowercase(),
					title = tagTitle,
					source = source
				)
			} else null
		}

		return manga.copy(
			title = title,
			description = description,
			coverUrl = coverUrl ?: manga.coverUrl,
			tags = if (tags.isNotEmpty()) tags else manga.tags,
			chapters = chapters,
		)
	}

	private fun extractChapterNumber(title: String): Float? {
		// Extract number from Arabic chapter titles like "الفصل 1", "الفصل 10.5"
		val regex = Regex("""(\d+(?:\.\d+)?)""")
		return regex.find(title)?.groupValues?.get(1)?.toFloatOrNull()
	}

	private fun parseChapterDate(dateString: String): Long {
		return try {
			// Parse date format like "2025.02.10"
			val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.US)
			dateFormat.parse(dateString)?.time ?: 0L
		} catch (e: Exception) {
			0L
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()

		// Extract images from the chapter content
		val images = doc.select(".chapter-content img, #chapter-content img").mapNotNull { img ->
			val imageUrl = img.attrAsAbsoluteUrlOrNull("src")
			if (imageUrl != null) {
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			} else null
		}

		if (images.isEmpty()) {
			throw org.koitharu.kotatsu.parsers.exception.ParseException("No images found in chapter", chapterUrl)
		}

		return images
	}
}
