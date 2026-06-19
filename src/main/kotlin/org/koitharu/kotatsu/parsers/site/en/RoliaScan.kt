package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

@MangaSourceParser("ROLIASCAN", "Rolia Scan", "en")
internal class RoliaScan(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.ROLIASCAN, 24) {

	override val configKeyDomain = ConfigKey.Domain("roliascan.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val body = JSONObject().apply {
			put("page", page)
			put("search", filter.query.orEmpty())
			put("years", "[]")
			put("genres", buildJsonArrayString(filter.tags.map { it.key }))
			put(
				"types",
				buildJsonArrayString(
					filter.types.mapNotNull {
						when (it) {
							ContentType.MANGA -> "Manga"
							ContentType.MANHWA -> "Manhwa"
							ContentType.MANHUA -> "Manhua"
							else -> null
						}
					},
				),
			)
			put(
				"statuses",
				buildJsonArrayString(
					filter.states.mapNotNull {
						when (it) {
							MangaState.ONGOING -> "Ongoing"
							MangaState.FINISHED -> "Completed"
							MangaState.PAUSED -> "Hiatus"
							else -> null
						}
					},
				),
			)
			put(
				"sort",
				when (order) {
					SortOrder.UPDATED -> "release_desc"
					SortOrder.NEWEST -> "post_desc"
					SortOrder.POPULARITY -> "popular_desc"
					SortOrder.ALPHABETICAL -> "title_asc"
					SortOrder.ALPHABETICAL_DESC -> "title_desc"
					else -> "release_desc"
				},
			)
			put("genreMatchMode", "all")
		}
		val array = webClient.httpPost("https://$domain/wp-json/manga/v1/load", body).parseJsonArray()
		return array.mapJSON { obj ->
			val publicUrl = obj.getString("url")
			val relativeUrl = publicUrl.toRelativeUrl(domain)
			Manga(
				id = generateUid(relativeUrl),
				title = obj.getString("title"),
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = publicUrl,
				rating = obj.optString("score").toFloatOrNull()?.div(10f)?.coerceIn(0f, 1f)
					?: RATING_UNKNOWN,
				contentRating = null,
				coverUrl = obj.optString("cover").takeIf { it.isNotEmpty() },
				tags = emptySet(),
				state = parseStatus(obj.optString("status")),
				authors = emptySet(),
				source = source,
				description = obj.optString("description").takeIf { it.isNotEmpty() }
					?.let { Jsoup.parseBodyFragment(it).text() },
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val publicUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(publicUrl).parseHtml()
		val chapterListEl = doc.selectFirst("div.chapter-list[data-manga-id]")
			?: doc.selectFirst("[data-manga-id]")
		val mangaId = chapterListEl?.attr("data-manga-id")?.toLongOrNull()
		val statusText = chapterListEl?.attr("data-status")
		val tags = doc.select("a[href*=/tag/]").mapNotNullToSet { el ->
			val key = el.attr("href").trimEnd('/').substringAfterLast("/")
			val title = el.text().trim()
			if (key.isEmpty() || title.isEmpty()) null
			else MangaTag(title = title, key = key, source = source)
		}
		val description = doc.selectFirst("#description-content-tab")?.let { el ->
			Jsoup.parseBodyFragment(el.html()).text()
		}.orEmpty()
		val chapters = if (mangaId != null) fetchChapters(mangaId, publicUrl) else emptyList()
		return manga.copy(
			description = description.ifEmpty { manga.description },
			tags = tags.ifEmpty { manga.tags },
			state = parseStatus(statusText) ?: manga.state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.trimEnd('/').substringAfterLast('-').toLongOrNull()
			?: throw IllegalStateException("Can't find chapter id in ${chapter.url}")
		val apiUrl = "https://$domain/auth/chapter-content?chapter_id=$chapterId"
		val json = webClient.httpGet(apiUrl).parseJson()
		val images = json.optJSONArray("images") ?: return emptyList()
		return images.asTypedList<String>().map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchChapters(mangaId: Long, referer: String): List<MangaChapter> {
		val collected = ArrayList<JSONObject>()
		var offset = 0
		while (true) {
			val (token, timestamp) = generateApiToken()
			val url = urlBuilder().addPathSegments("auth/manga-chapters")
				.addQueryParameter("manga_id", mangaId.toString())
				.addQueryParameter("offset", offset.toString())
				.addQueryParameter("limit", PAGE_SIZE.toString())
				.addQueryParameter("order", "desc")
				.addQueryParameter("_t", token)
				.addQueryParameter("_ts", timestamp.toString())
			val headers = Headers.Builder()
				.add(CommonHeaders.REFERER, referer)
				.add(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
				.build()
			val json = webClient.httpGet(url.build(), headers).parseJson()
			val chapters = json.optJSONArray("chapters") ?: break
			if (chapters.length() == 0) break
			for (i in 0 until chapters.length()) {
				collected.add(chapters.getJSONObject(i))
			}
			if (!json.optBoolean("has_more")) break
			offset += chapters.length()
		}
		// API returns newest-first; reverse so chapter[0] is the oldest
		return collected.mapChapters(reversed = true) { i, obj ->
			val chapterUrl = obj.getString("url").toRelativeUrl(domain)
			val label = obj.optString("chapter")
			val title = obj.optString("title").takeIf { it.isNotEmpty() && it != "N/A" }
			MangaChapter(
				id = generateUid(chapterUrl),
				title = title,
				number = label.toFloatOrNull() ?: (i + 1f),
				volume = 0,
				url = chapterUrl,
				scanlator = obj.optString("group_name").takeIf { it.isNotEmpty() && it != "null" },
				uploadDate = 0L,
				branch = obj.optString("language").takeIf { it.isNotEmpty() && it != "en" },
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/browse/").parseHtml()
		return doc.select("button.genre-btn[data-value]").mapNotNullToSet { btn ->
			val key = btn.attr("data-value").trim()
			// The button contains a label div plus a count span; label is the first inner div's text
			val name = btn.selectFirst("div.flex.items-center")?.text()?.trim()
				?: btn.ownText().trim()
			if (key.isEmpty() || name.isEmpty()) null
			else MangaTag(title = name, key = key, source = source)
		}
	}

	private fun parseStatus(status: String?): MangaState? = when (status?.lowercase(Locale.ROOT)) {
		"ongoing", "publishing" -> MangaState.ONGOING
		"completed", "finished" -> MangaState.FINISHED
		"hiatus", "paused" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	/**
	 * Anti-scraping token used by /auth/manga-chapters.
	 * Mirrors generateToken() in the site's manga.js:
	 *   timestamp = floor(now/1000)
	 *   hour      = UTC "yyyyMMddHH"
	 *   token     = md5(timestamp + "mng_ch_" + hour).take(16)
	 */
	private fun generateApiToken(): Pair<String, Long> {
		val timestamp = System.currentTimeMillis() / 1000
		val hour = Instant.ofEpochSecond(timestamp)
			.atZone(ZoneOffset.UTC)
			.format(DateTimeFormatter.ofPattern("yyyyMMddHH"))
		val token = "${timestamp}mng_ch_$hour".md5().substring(0, 16)
		return token to timestamp
	}

	private fun buildJsonArrayString(values: List<String>): String {
		if (values.isEmpty()) return "[]"
		return values.joinToString(prefix = "[", postfix = "]") {
			"\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
		}
	}

	private companion object {
		const val PAGE_SIZE = 500
	}
}
