package org.koitharu.kotatsu.parsers.site.heancms.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("TEMPLESCAN", "TempleScan", "en")
internal class TempleScan(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TEMPLESCAN, pageSize = 20, searchPageSize = 10) {

	override val configKeyDomain = ConfigKey.Domain("templetoons.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return if (!filter.query.isNullOrEmpty()) {
			getSearchList(filter.query, page)
		} else {
			getBrowseList(page, order)
		}
	}

	private suspend fun getSearchList(query: String, page: Int): List<Manga> {
		val url = "https://$domain/api/search?q=${query.urlEncoded()}&page=$page&limit=10"
		val json = webClient.httpGet(url).parseJson()
		val projects = json.optJSONArray("projects") ?: return emptyList()
		return projects.mapJSON { j -> parseListingItem(j) }
	}

	private suspend fun getBrowseList(page: Int, order: SortOrder): List<Manga> {
		// /comics returns the full catalog inside the Next.js hydration payload.
		// We parse it once and slice client-side since the site does not expose a
		// server-paginated JSON listing.
		val doc = webClient.httpGet("https://$domain/comics").parseHtml()
		val all = extractAllComics(doc.html()) ?: return emptyList()
		val comparator = when (order) {
			SortOrder.POPULARITY -> compareByDescending<JSONObject> { it.optInt("total_views") }
			SortOrder.NEWEST -> compareByDescending<JSONObject> { it.optString("created_at") }
			SortOrder.ALPHABETICAL -> compareBy<JSONObject> { it.optString("title").lowercase() }
			else -> compareByDescending<JSONObject> { it.optString("update_chapter") }
		}
		val sorted = all.sortedWith(comparator)
		val from = (page - 1) * pageSize
		if (from >= sorted.size) return emptyList()
		val to = minOf(from + pageSize, sorted.size)
		return sorted.subList(from, to).map(::parseListingItem)
	}

	private fun extractAllComics(html: String): List<JSONObject>? {
		// Payload lives in self.__next_f.push([1, "..."]) chunks. Join, unescape,
		// locate "allComics": [...], then parse that array.
		val pushes = NEXT_F_REGEX.findAll(html).map { it.groupValues[1] }.toList()
		if (pushes.isEmpty()) return null
		val joined = pushes.joinToString("").unescapeJsString()
		val marker = "\"allComics\":["
		val markerIdx = joined.indexOf(marker)
		if (markerIdx < 0) return null
		val start = markerIdx + marker.length - 1
		val end = findMatchingBracket(joined, start) ?: return null
		return JSONArray(joined.substring(start, end)).let { arr ->
			(0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
		}
	}

	private fun parseListingItem(j: JSONObject): Manga {
		val slug = j.getString("series_slug")
		val publicUrl = "https://$domain/comic/$slug"
		val thumbnail = j.getStringOrNull("thumbnail").orEmpty()
		val altNames = j.getStringOrNull("alternative_names")
			?.split(',', '|')
			?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
			?.toSet()
			.orEmpty()
		return Manga(
			id = generateUid(slug),
			url = slug,
			publicUrl = publicUrl,
			title = j.getString("title"),
			altTitles = altNames,
			coverUrl = thumbnail,
			largeCoverUrl = thumbnail,
			description = null,
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			state = parseStatus(j.getStringOrNull("status")),
			authors = emptySet(),
			source = source,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url
		val json = webClient.httpGet("https://$domain/api/comic/$slug").parseJson()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
		val seasons = json.optJSONArray("Season") ?: JSONArray()
		val chapters = mutableListOf<JSONObject>()
		for (i in 0 until seasons.length()) {
			val s = seasons.optJSONObject(i) ?: continue
			val chs = s.optJSONArray("Chapter") ?: continue
			for (k in 0 until chs.length()) {
				chs.optJSONObject(k)?.let(chapters::add)
			}
		}
		// API returns chapters newest-first; reverse to oldest-first for numbering.
		chapters.reverse()
		val author = json.getStringOrNull("author")
		return manga.copy(
			title = json.getStringOrNull("title") ?: manga.title,
			description = json.getStringOrNull("description"),
			state = parseStatus(json.getStringOrNull("status")) ?: manga.state,
			coverUrl = json.getStringOrNull("thumbnail") ?: manga.coverUrl,
			authors = setOfNotNull(author?.takeIf(String::isNotBlank)),
			chapters = chapters.mapChapters { i, it ->
				val chapterSlug = it.getString("chapter_slug")
				val rawCreatedAt = it.getStringOrNull("created_at")?.substringBefore('.')
				MangaChapter(
					id = generateUid("$slug/$chapterSlug"),
					title = it.getStringOrNull("chapter_name") ?: chapterSlug,
					number = i + 1f,
					volume = 0,
					url = "/comic/$slug/$chapterSlug",
					scanlator = null,
					uploadDate = dateFormat.parseSafe(rawCreatedAt),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// chapter.url is "/comic/{series_slug}/{chapter_slug}"; the JSON equivalent
		// lives under /api/comic/{series_slug}/{chapter_slug}.
		val apiUrl = "https://$domain/api${chapter.url}"
		val json = webClient.httpGet(apiUrl).parseJson()
		val images = json.optJSONObject("chapter_data")?.optJSONArray("images") ?: return emptyList()
		return (0 until images.length()).mapNotNull { i ->
			val url = images.optString(i).takeIf(String::isNotBlank) ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseStatus(value: String?): MangaState? = when (value) {
		"Ongoing" -> MangaState.ONGOING
		"Completed" -> MangaState.FINISHED
		"Dropped" -> MangaState.ABANDONED
		"Hiatus" -> MangaState.PAUSED
		else -> null
	}

	private fun findMatchingBracket(s: String, openIdx: Int): Int? {
		var depth = 0
		var inString = false
		var escaped = false
		var i = openIdx
		while (i < s.length) {
			val c = s[i]
			when {
				inString -> when {
					escaped -> escaped = false
					c == '\\' -> escaped = true
					c == '"' -> inString = false
				}
				c == '"' -> inString = true
				c == '[' -> depth++
				c == ']' -> {
					depth--
					if (depth == 0) return i + 1
				}
			}
			i++
		}
		return null
	}

	private fun String.unescapeJsString(): String {
		val sb = StringBuilder(length)
		var i = 0
		while (i < length) {
			val c = this[i]
			if (c == '\\' && i + 1 < length) {
				when (val n = this[i + 1]) {
					'n' -> sb.append('\n')
					't' -> sb.append('\t')
					'r' -> sb.append('\r')
					'"' -> sb.append('"')
					'\\' -> sb.append('\\')
					'/' -> sb.append('/')
					'b' -> sb.append('\b')
					'f' -> sb.append('\u000C')
					'u' -> if (i + 5 < length) {
						val hex = substring(i + 2, i + 6)
						runCatching { hex.toInt(16).toChar() }.getOrNull()?.let(sb::append)
						i += 4
					}
					else -> sb.append(n)
				}
				i += 2
			} else {
				sb.append(c)
				i++
			}
		}
		return sb.toString()
	}

	private companion object {
		private val NEXT_F_REGEX = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
	}
}
