package org.koitharu.kotatsu.parsers.site.pizzareader

import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONIndexed
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.json.toStringSet
import java.text.SimpleDateFormat
import java.util.*

internal abstract class PizzaReaderParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
) : SinglePageMangaParser(context, source) {

	override val configKeyDomain = ConfigKey.Domain(domain)
	private val detailsCache = object : LinkedHashMap<String, Manga>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean {
			return size > DETAILS_CACHE_SIZE
		}
	}
	private val pagesCache = object : LinkedHashMap<String, List<MangaPage>>(128, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaPage>>?): Boolean {
			return size > PAGES_CACHE_SIZE
		}
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override open val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
	)

	@JvmField
	protected val ongoing: Set<String> = hashSetOf(
		"en cours",
		"ongoing",
		"on going",
		"in corso",
		"in corso (cadenza irregolare)",
		"in corso (irregolare)",
		"in corso (mensile)",
		"in corso (quindicinale)",
		"in corso (settimanale)",
		"in corso (bisettimanale)",
	)


	@JvmField
	protected val finished: Set<String> = hashSetOf(
		"terminé",
		"finished",
		"completed",
		"complete",
		"concluso",
		"completato",
	)

	@JvmField
	protected val paused: Set<String> = hashSetOf(
		"in pausa",
		"hiatus",
		"paused",
		"on hold",
		"sospeso",
		"in corso (in pausa)",
	)

	@JvmField
	protected val abandoned: Set<String> = hashSetOf(
		"droppato",
		"dropped",
		"abandoned",
		"cancelled",
		"canceled",
	)


	protected open val ongoingFilter = "in corso"
	protected open val completedFilter = "concluso"
	protected open val hiatusFilter = "in pausa"
	protected open val abandonedFilter = "droppato"

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val manga = ArrayList<MangaWithDate>()
		val selectedState = filter.states.oneOrThrowIfMany()
		val selectedRating = filter.contentRating.oneOrThrowIfMany()
		val includeTags = filter.tags.map { it.key.lowercase(Locale.ROOT) }
		val excludeTags = filter.tagsExclude.map { it.key.lowercase(Locale.ROOT) }

		when {
			!filter.query.isNullOrEmpty() -> {
				val jsonManga = webClient.httpGet("https://$domain/api/search/${filter.query.urlEncoded()}").parseJson()
					.getJSONArray("comics")
				for (i in 0 until jsonManga.length()) {
					val j = jsonManga.getJSONObject(i)
					val href = "/api" + j.getString("url")
					manga.add(
						MangaWithDate(
							manga = addManga(href, j),
							updateDate = parseMangaUpdateDate(j),
						),
					)
				}
			}

			else -> {
				val jsonManga = webClient.httpGet("https://$domain/api/comics").parseJson().getJSONArray("comics")
				for (i in 0 until jsonManga.length()) {

					val j = jsonManga.getJSONObject(i)
					val href = "/api" + j.getString("url")
					if (includeTags.isNotEmpty() || excludeTags.isNotEmpty()) {
						val genres = parseGenreKeys(j)
						if (includeTags.isNotEmpty() && includeTags.none { it in genres }) {
							continue
						}
						if (excludeTags.isNotEmpty() && excludeTags.any { it in genres }) {
							continue
						}
					}
					selectedState?.let { state ->
						if (!isMatchingState(j.getStringOrNull("status"), state)) {
							continue
						}
					}
					selectedRating?.let { rating ->
						val expected = when (rating) {
							ContentRating.SAFE -> 0
							ContentRating.ADULT -> 1
							else -> 0
						}
						if (j.getIntOrDefault("adult", 0) != expected) {
							continue
						}
					}
					manga.add(
						MangaWithDate(
							manga = addManga(href, j),
							updateDate = parseMangaUpdateDate(j),
						),
					)
				}
			}
		}

		return when (order) {
			SortOrder.UPDATED -> manga.sortedByDescending { it.updateDate }
			SortOrder.UPDATED_ASC -> manga.sortedBy { it.updateDate }
			SortOrder.ALPHABETICAL_DESC -> manga.sortedByDescending { it.manga.title.lowercase(Locale.ROOT) }
			else -> manga.sortedBy { it.manga.title.lowercase(Locale.ROOT) }
		}.map { it.manga }
	}

	private fun parseMangaUpdateDate(json: JSONObject): Long {
		val lastChapter = json.optJSONObject("last_chapter") ?: return 0L
		lastChapter.getStringOrNull("published_on")?.let { date ->
			parseDate(date)?.let { return it }
		}
		lastChapter.getStringOrNull("updated_at")?.let { date ->
			parseDate(date)?.let { return it }
		}
		return 0L
	}

	private fun isMatchingState(rawStatus: String?, state: MangaState): Boolean {
		val status = rawStatus?.trim()?.lowercase(Locale.ROOT) ?: return false
		return when (state) {
			MangaState.PAUSED -> status in paused || status.contains(hiatusFilter, ignoreCase = true)
			MangaState.ONGOING -> status in ongoing || status.contains(ongoingFilter, ignoreCase = true)
			MangaState.FINISHED -> status in finished || status.contains(completedFilter, ignoreCase = true)
			MangaState.ABANDONED -> status in abandoned || status.contains(abandonedFilter, ignoreCase = true)
			else -> false
		}
	}

	private fun parseGenreKeys(json: JSONObject): Set<String> {
		val genres = json.optJSONArray("genres") ?: return emptySet()
		if (genres.length() == 0) return emptySet()
		val result = HashSet<String>(genres.length() * 2)
		for (i in 0 until genres.length()) {
			val genreObject = genres.optJSONObject(i)
			if (genreObject != null) {
				genreObject.getStringOrNull("slug")?.lowercase(Locale.ROOT)?.let(result::add)
				genreObject.getStringOrNull("name")?.lowercase(Locale.ROOT)?.let(result::add)
				continue
			}
			genres.optString(i).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
				?.lowercase(Locale.ROOT)
				?.let(result::add)
		}
		return result
	}

	private fun addManga(href: String, j: JSONObject): Manga {
		val isNsfwSource = when (j.getIntOrDefault("adult", 0)) {
			0 -> false
			1 -> true
			else -> true
		}
		val author = j.getStringOrNull("author")?.takeIf { it.isNotBlank() }
		val comicPath = j.getStringOrNull("url")
		val altTitles = j.optJSONArray("alt_titles")?.toStringSet().orEmpty()
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = comicPath?.toAbsoluteUrl(domain) ?: href.toAbsoluteUrl(domain),
			coverUrl = j.getString("thumbnail"),
			title = j.getString("title"),
			description = j.getString("description"),
			altTitles = altTitles,
			rating = parseRating(j.opt("rating")),
			tags = emptySet(),
			authors = setOfNotNull(author),
			state = when (j.getStringOrNull("status")?.trim()?.lowercase(Locale.ROOT)) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in paused -> MangaState.PAUSED
				in abandoned -> MangaState.ABANDONED
				else -> null
			},
			source = source,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
		)
	}

	private fun parseRating(raw: Any?): Float {
		val numeric = when (raw) {
			is Number -> raw.toFloat()
			is String -> raw.toFloatOrNull()
			else -> null
		} ?: return RATING_UNKNOWN
		return numeric.div(10f)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		synchronized(detailsCache) {
			detailsCache[manga.url]?.let { return@coroutineScope it }
		}
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val json = webClient.httpGet(fullUrl).parseJson().getJSONObject("comic")
		val chapters = JSONArray(json.getJSONArray("chapters").asTypedList<JSONObject>().reversed())

		val details = manga.copy(
			tags = json.getJSONArray("genres").mapJSONToSet {
				MangaTag(
					key = it.getString("slug"),
					title = it.getString("name"),
					source = source,
				)
			},
			chapters = chapters.mapJSONIndexed { i, j ->
				val url = "/api" + j.getString("url").toRelativeUrl(domain)
				val fallbackNumber = i + 1f
				val number = parseChapterNumber(j, fallbackNumber)
				val name = j.getStringOrNull("full_title")
					?: buildChapterTitle(number, j.getStringOrNull("title"))
				val date = parseChapterDate(j)
				MangaChapter(
					id = generateUid(url),
					title = name,
					number = number,
					volume = j.optInt("volume", 0),
					url = url,
					scanlator = null,
					uploadDate = date,
					branch = null,
					source = source,
				)
				},
			)
		synchronized(detailsCache) {
			detailsCache[manga.url] = details
			detailsCache[details.url] = details
		}
		details
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		synchronized(pagesCache) {
			pagesCache[chapter.url]?.let { return it }
		}
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val pages = webClient.httpGet(fullUrl)
			.parseJson()
			.getJSONObject("chapter")
			.optJSONArray("pages")
			?: return emptyList()
		val result = ArrayList<MangaPage>(pages.length())
		for (i in 0 until pages.length()) {
			val url = pages.optString(i).trim()
			if (url.isEmpty()) {
				continue
			}
			val absoluteUrl = url.toAbsoluteUrl(domain)
			result.add(
				MangaPage(
					id = generateUid(absoluteUrl),
					url = absoluteUrl,
					preview = null,
					source = source,
				),
				)
			}
			if (result.isNotEmpty()) {
				synchronized(pagesCache) {
					pagesCache[chapter.url] = result
				}
			}
			return result
	}

	private fun parseChapterNumber(chapter: JSONObject, fallback: Float): Float {
		val rawChapter = chapter.getStringOrNull("chapter")
		val rawSubchapter = chapter.getStringOrNull("subchapter")
		if (!rawChapter.isNullOrBlank()) {
			if ('.' in rawChapter) {
				rawChapter.toFloatOrNull()?.let { return it }
			}
			if (!rawSubchapter.isNullOrBlank()) {
				"$rawChapter.$rawSubchapter".toFloatOrNull()?.let { return it }
			}
			rawChapter.toFloatOrNull()?.let { return it }
		}
		val fullChapter = chapter.getStringOrNull("full_chapter")
		if (!fullChapter.isNullOrBlank()) {
			CHAPTER_NUMBER_FROM_LABEL_REGEX.find(fullChapter)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
				return it
			}
			CHAPTER_NUMBER_LAST_REGEX.find(fullChapter)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { return it }
		}
		return fallback
	}

	private fun buildChapterTitle(number: Float, title: String?): String? {
		val cleanTitle = title?.trim().takeUnless { it.isNullOrEmpty() }
		val formatted = if (number % 1f == 0f) number.toInt().toString() else number.toString()
		return when {
			formatted == "0" -> cleanTitle
			cleanTitle == null -> "Chapter $formatted"
			else -> "Chapter $formatted : $cleanTitle"
		}
	}

	private fun parseChapterDate(chapter: JSONObject): Long {
		chapter.getStringOrNull("published_on")?.let { date ->
			parseDate(date)?.let { return it }
		}
		chapter.getStringOrNull("updated_at")?.let { date ->
			parseDate(date)?.let { return it }
		}
		return 0L
	}

	private fun parseDate(rawDate: String): Long? {
		for (pattern in CHAPTER_DATE_PATTERNS) {
			val dateFormat = SimpleDateFormat(pattern, Locale.US).apply {
				timeZone = TimeZone.getTimeZone("UTC")
			}
			dateFormat.parseSafe(rawDate).takeIf { it != 0L }?.let { return it }
		}
		return null
	}

	private companion object {
		private data class MangaWithDate(
			val manga: Manga,
			val updateDate: Long,
		)

		private val CHAPTER_DATE_PATTERNS = arrayOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
			"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
			"yyyy-MM-dd'T'HH:mm:ss'Z'",
			"yyyy-MM-dd HH:mm:ss",
		)
			private val CHAPTER_NUMBER_FROM_LABEL_REGEX = Regex("ch(?:apter)?\\.?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
			private val CHAPTER_NUMBER_LAST_REGEX = Regex("(\\d+(?:\\.\\d+)?)(?!.*\\d)")
			private const val DETAILS_CACHE_SIZE = 200
			private const val PAGES_CACHE_SIZE = 400
		}
}
