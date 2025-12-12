package org.koitharu.kotatsu.parsers.site.comicaso

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class ComicasoParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override val sourceLocale: Locale = Locale("id")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	protected open val datePattern = "dd MMM yyyy"
	protected var tagCache: ArrayMap<String, MangaTag>? = null
	protected val mutex = Mutex()

	private suspend fun fetchAvailableTags(): Set<MangaTag> = mutex.withLock {
		tagCache?.values?.toSet() ?: run {
			val url = "https://$domain/v2/all-series/"
			val doc = webClient.httpGet(url).parseHtml()
			val tags = doc.select("div.ng-genre-bar button.genre-btn").mapNotNullToSet { button ->
				val genre = button.attr("data-genre")
				if (genre.isEmpty() || genre == "all") return@mapNotNullToSet null
				val title = button.text().trim()
				MangaTag(
					key = genre,
					title = title.toTitleCase(sourceLocale),
					source = source,
				)
			}
			tagCache = tags.associateByTo(ArrayMap(tags.size)) { it.key }
			tags
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Latest Updates uses HTML parsing from homepage
		if (order == SortOrder.UPDATED && filter.query.isNullOrEmpty() && filter.tags.isEmpty() && filter.states.isEmpty()) {
			return getLatestUpdatesFromHtml(page)
		}

		// Popular and filtered results use JSON API
		val url = buildString {
			append("https://")
			append(domain)
			append("/wp-json/neoglass/v1/mangas")
			append("?paged=")
			append(page.toString())
			append("&per_page=")
			append(pageSize.toString())

			filter.query?.let {
				append("&s=")
				append(it.urlEncoded())
			}

			filter.tags.oneOrThrowIfMany()?.let {
				append("&genre=")
				append(it.key)
			}

			if (filter.states.isNotEmpty()) {
				filter.states.oneOrThrowIfMany()?.let {
					when (it) {
						MangaState.FINISHED -> append("&completed=1")
						else -> {}
					}
				}
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val items = json.getJSONArray("items")
		return parseMangaList(items)
	}

	private suspend fun getLatestUpdatesFromHtml(page: Int): List<Manga> {
		val url = "https://$domain/v2/?page=$page"
		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("div.ng-list div.ng-list-item").map { item ->
			val thumb = item.selectFirst("div.ng-list-thumb")
			val link = thumb?.selectFirstOrThrow("a")?.attrAsRelativeUrl("href") ?: return@map null
			val title = item.selectFirstOrThrow("div.ng-list-info a h3").text().trim()
			val coverUrl = thumb.selectFirst("img")?.src()

			Manga(
				id = generateUid(link),
				url = link,
				title = title,
				altTitles = emptySet(),
				publicUrl = link.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = coverUrl ?: "",
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.filterNotNull()
	}

	private fun parseMangaList(json: JSONArray): List<Manga> {
		val list = ArrayList<Manga>(json.length())
		for (i in 0 until json.length()) {
			val jo = json.getJSONObject(i)
			val slug = jo.getString("slug")
			val url = "/v2/manga/$slug/"
			list.add(
				Manga(
					id = generateUid(url),
					url = url,
					title = jo.getString("title"),
					altTitles = emptySet(),
					publicUrl = jo.getString("url"),
					rating = RATING_UNKNOWN,
					contentRating = if (isNsfwSource) ContentRating.ADULT else null,
					coverUrl = jo.getString("thumb"),
					tags = emptySet(),
					state = when (jo.optString("status")) {
						"on-going" -> MangaState.ONGOING
						"completed" -> MangaState.FINISHED
						else -> null
					},
					authors = emptySet(),
					source = source,
				),
			)
		}
		return list
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirstOrThrow("h1.ng-detail-title").text().trim()

		val altTitlesText = doc.selectFirst(".ng-meta-info p:contains(Alternative:)")
			?.ownText()
			?.trim()
		val altTitles = altTitlesText?.split("/", ",")
			?.mapNotNullToSet { it.trim().takeIf { s -> s.isNotEmpty() } }
			?: emptySet()

		val description = doc.selectFirst("p.ng-desc")?.text()?.trim()

		val state = doc.selectFirst(".ng-meta-info p:contains(Status:)")
			?.ownText()
			?.trim()
			?.let {
				when {
					it.contains("On-going", ignoreCase = true) -> MangaState.ONGOING
					it.contains("End", ignoreCase = true) -> MangaState.FINISHED
					else -> null
				}
			}

		val tags = doc.select(".ng-meta-row:contains(Genres:)")
			.text()
			.substringAfter("Genres:")
			.split(",")
			.mapNotNullToSet {
				val tagName = it.trim()
				if (tagName.isEmpty()) return@mapNotNullToSet null
				MangaTag(
					key = tagName.lowercase(sourceLocale),
					title = tagName.toTitleCase(sourceLocale),
					source = source,
				)
			}

		val type = doc.selectFirst(".ng-meta-info p:contains(Type:)")
			?.ownText()
			?.trim()
			?.lowercase(sourceLocale)

		val coverUrl = doc.selectFirst(".ng-detail-cover")
			?.attr("style")
			?.substringAfter("url('")
			?.substringBefore("')")

		val chapters = doc.select("ul.ng-chapter-list li.ng-chapter-item").mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a.ng-btn.ng-read-small") ?: return@mapChapters null
			val chapterUrl = a.attrAsRelativeUrl("href")
			val chapterTitle = li.selectFirst(".ng-chapter-title")?.text()?.trim()
			val dateText = li.selectFirst(".ng-chapter-date")?.text()?.trim()

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle ?: "Chapter ${i + 1}",
				number = (i + 1).toFloat(),
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = parseChapterDate(dateText),
				branch = null,
				source = source,
			)
		}.reversed()

		return manga.copy(
			title = title,
			altTitles = altTitles,
			description = description,
			coverUrl = coverUrl ?: manga.coverUrl,
			tags = tags,
			state = state,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.ng-chapter-images div.ng-chapter-image img").map { img ->
			val url = img.requireSrc()
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChapterDate(dateStr: String?): Long {
		if (dateStr.isNullOrEmpty()) return 0L

		val dateString = dateStr.trim()
		val calendar = Calendar.getInstance()

		return when {
			dateString.contains("hari", ignoreCase = true) -> {
				val days = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 1
				calendar.add(Calendar.DAY_OF_MONTH, -days)
				calendar.timeInMillis
			}
			dateString.contains("minggu", ignoreCase = true) -> {
				val weeks = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 1
				calendar.add(Calendar.WEEK_OF_YEAR, -weeks)
				calendar.timeInMillis
			}
			dateString.contains("bulan", ignoreCase = true) -> {
				val months = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 1
				calendar.add(Calendar.MONTH, -months)
				calendar.timeInMillis
			}
			else -> {
				try {
					SimpleDateFormat(datePattern, sourceLocale).parse(dateString)?.time ?: 0L
				} catch (e: Exception) {
					0L
				}
			}
		}
	}
}
