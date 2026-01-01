package org.koitharu.kotatsu.parsers.site.all

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

@Broken("Fix getDetails, fetchTags, getPages")
@MangaSourceParser("WEEBDEX", "WeebDex")
internal class WeebDex(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WEEBDEX, 28) {

	private val cdnDomain = "srv.notdelta.xyz"
	override val configKeyDomain = ConfigKey.Domain("weebdex.org")

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ADDED, // no args
		SortOrder.ADDED_ASC, // no args with asc order
		SortOrder.RELEVANCE, // relevance
		SortOrder.UPDATED, // updatedAt
		SortOrder.NEWEST, // createdAt
		SortOrder.NEWEST_ASC, // createdAt with asc
		SortOrder.ALPHABETICAL, // title with asc
		SortOrder.ALPHABETICAL_DESC, // title
		SortOrder.RATING, // rating
		SortOrder.RATING_ASC, // rating with asc
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isYearRangeSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentRating = EnumSet.allOf(ContentRating::class.java),
			availableLocales = setOf(
				Locale.ENGLISH,
				Locale("af"), // Afrikaans
				Locale("sq"), // Albanian
				Locale("ar"), // Arabic
				Locale("az"), // Azerbaijani
				Locale("eu"), // Basque
				Locale("be"), // Belarusian
				Locale("bn"), // Bengali
				Locale("bg"), // Bulgarian
				Locale("my"), // Burmese
				Locale("ca"), // Catalan
				Locale.CHINESE,
				Locale("zh-hk"), // Chinese (Traditional)
				Locale("cv"), // Chuvash
				Locale("hr"), // Croatian
				Locale("cs"), // Czech
				Locale("da"), // Danish
				Locale("nl"), // Dutch
				Locale("eo"), // Esperanto
				Locale("et"), // Estonian
				Locale("tl"), // Filipino
				Locale("fi"), // Finnish
				Locale.FRENCH,
				Locale("ka"), // Georgian
				Locale.GERMAN,
				Locale("el"), // Greek
				Locale("he"), // Hebrew
				Locale("hi"), // Hindi
				Locale("hu"), // Hungarian
				Locale("id"), // Indonesian
				Locale("jv"), // Javanese
				Locale("ga"), // Irish
				Locale.ITALIAN,
				Locale.JAPANESE,
				Locale("kk"), // Kazakh
				Locale.KOREAN,
				Locale("la"), // Latin
				Locale("lt"), // Lithuanian
				Locale("ms"), // Malay
				Locale("mn"), // Mongolian
				Locale("ne"), // Nepali
				Locale("no"), // Norwegian
				Locale("fa"), // Persian (Farsi)
				Locale("pl"), // Polish
				Locale("pt"), // Portuguese
				Locale("pt-br"), // Portuguese (Brazil)
				Locale("ro"), // Romanian
				Locale("ru"), // Russian
				Locale("sr"), // Serbian
				Locale("sk"), // Slovak
				Locale("sl"), // Slovenian
				Locale("es"), // Spanish
				Locale("es-la"), // Spanish (LATAM)
				Locale("sv"), // Swedish
				Locale("tam"), // Tamil
				Locale("te"), // Telugu
				Locale("th"), // Thai
				Locale("tr"), // Turkish
				Locale("uk"), // Ukrainian
				Locale("ur"), // Urdu
				Locale("uz"), // Uzbek
				Locale("vi"), // Vietnamese
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegment("manga")
			// Paging
			.addQueryParameter("limit", pageSize.toString())
			.addQueryParameter("page", page.toString())

		// SortOrder mapping
		when (order) {
			SortOrder.ADDED_ASC -> url.addQueryParameter("order", "asc")
			SortOrder.RELEVANCE -> url.addQueryParameter("sort", "relevance")
			SortOrder.UPDATED -> url.addQueryParameter("sort", "updatedAt")
			SortOrder.NEWEST -> url.addQueryParameter("sort", "createdAt")
			SortOrder.NEWEST_ASC -> {
				url.addQueryParameter("sort", "createdAt")
				url.addQueryParameter("order", "asc")
			}
			SortOrder.ALPHABETICAL -> {
				url.addQueryParameter("sort", "title")
				url.addQueryParameter("order", "asc")
			}
			SortOrder.ALPHABETICAL_DESC -> {
				url.addQueryParameter("sort", "title")
				url.addQueryParameter("order", "desc")
			}
			SortOrder.RATING -> {
				url.addQueryParameter("sort", "followedCount")
				url.addQueryParameter("order", "desc")
			}
			SortOrder.RATING_ASC -> {
				url.addQueryParameter("sort", "followedCount")
				url.addQueryParameter("order", "asc")
			}
			else -> {} // ADDED
		}

		// Keyword
		if (!filter.query.isNullOrEmpty()) {
			url.addQueryParameter("title", filter.query.urlEncoded())
		}

		// Content rating
		if (!filter.contentRating.isEmpty()) {
			filter.contentRating.forEach {
				when (it) {
					ContentRating.SAFE -> url.addQueryParameter("contentRating", "safe")
					ContentRating.SUGGESTIVE -> url.addQueryParameter("contentRating", "suggestive")
					ContentRating.ADULT -> {
						url.addQueryParameter("contentRating", "erotica")
						url.addQueryParameter("contentRating", "pornographic")
					}
				}
			}
		}

		// States
		filter.states.forEach { state ->
			when (state) {
				MangaState.ONGOING -> url.addQueryParameter("status", "ongoing")
				MangaState.FINISHED -> url.addQueryParameter("status", "completed")
				MangaState.PAUSED -> url.addQueryParameter("status", "hiatus")
				MangaState.ABANDONED -> url.addQueryParameter("status", "cancelled")
				else -> {}
			}
		}

		// Tags (Genres)
		if (!filter.tags.isEmpty()) {
			filter.tags.forEach {
				url.addQueryParameter("tag", it.key)
			}
		}

		// Exclude tags (Genres)
		if (!filter.tagsExclude.isEmpty()) {
			filter.tagsExclude.forEach {
				url.addQueryParameter("tagx", it.key)
			}
		}

		// Search by language (Translated languages)
		filter.locale?.let {
			url.addQueryParameter("hasChapters", "true")
			url.addQueryParameter("availableTranslatedLang", it.language)
		}

		// Search by Year (From - To)
		if (filter.yearFrom != YEAR_UNKNOWN) {
			url.addQueryParameter("yearFrom", filter.yearFrom.toString())
		}

		if (filter.yearTo != YEAR_UNKNOWN) {
			url.addQueryParameter("yearTo", filter.yearTo.toString())
		}

		// Author search
		if (!filter.author.isNullOrBlank()) {
			url.addQueryParameter("authorOrArtist", filter.author)
		}

		val response = webClient.httpGet(url.toString().toAbsoluteUrl("api.$domain")).parseJson()
		return response.getJSONArray("data").mapJSON { jo ->
			val id = jo.getString("id")
			val title = jo.getString("title")
			val coverId = jo.getJSONObject("relationships")
				.getJSONObject("cover").getString("id")

			val tags = jo.optJSONArray("tags")?.mapJSONNotNull {
				MangaTag(
					key = it.getString("id"),
					title = it.getString("name"),
					source = source,
				)
			}?.toSet() ?: emptySet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = emptySet(),
				url = id,
				publicUrl = "https://$domain/title/$id/"
					+ title.splitByWhitespace().joinToString("-") { it },
				coverUrl = "/covers/$id/$coverId.256.webp".toAbsoluteUrl(cdnDomain),
				largeCoverUrl = "/covers/$id/$coverId.512.webp".toAbsoluteUrl(cdnDomain),
				contentRating = when (jo.getString("content_rating")) {
					"safe" -> ContentRating.SAFE
					"suggestive" -> ContentRating.SUGGESTIVE
					"erotica", "pornographic" -> ContentRating.ADULT
					else -> null
				},
				tags = tags,
				state = when (jo.getString("status")) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					"cancelled" -> MangaState.ABANDONED
					else -> null
				},
				description = jo.getString("description"),
				authors = emptySet(),
				rating = RATING_UNKNOWN,
				source = source,
			)
		}
	}

	private fun parseMangaJson(json: JSONObject): Manga {
		val id = json.getString("id")
		val title = json.getString("title")
		val desc = json.optString("description")
		val statusStr = json.optString("status")

		val relationships = json.optJSONObject("relationships")

		var coverUrl: String? = null
		var largeCoverUrl: String? = null
		val coverObj = relationships?.optJSONObject("cover")
		if (coverObj != null) {
			val coverId = coverObj.getString("id")
			coverUrl = "https://$cdnDomain/covers/$id/$coverId.256.webp"
			largeCoverUrl = "https://$cdnDomain/covers/$id/$coverId.512.webp"
		}

		val authors = mutableSetOf<String>()
		val authorArr = relationships?.optJSONArray("authors")
		if (authorArr != null) {
			for (i in 0 until authorArr.length()) {
				authors.add(authorArr.getJSONObject(i).getString("name"))
			}
		}

		val tags = mutableSetOf<MangaTag>()
		val tagsArr = relationships?.optJSONArray("tags")
		if (tagsArr != null) {
			for (i in 0 until tagsArr.length()) {
				val t = tagsArr.getJSONObject(i)
				val tId = t.getString("id")
				val tName = t.getString("name")
				tags.add(MangaTag(key = tId, title = tName, source = source))
			}
		}

		val demo = json.optString("demographic")
		if (demo.isNotEmpty() && demo != "null") {
			tags.add(MangaTag(key = demo, title = demo.replaceFirstChar { it.uppercase() }, source = source))
		}

		val state = when (statusStr) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"cancelled" -> MangaState.ABANDONED
			else -> null
		}

		val ratingStr = json.optString("contentRating")
		val contentRating = when(ratingStr) {
			"erotica", "pornographic" -> ContentRating.ADULT
			"suggestive" -> ContentRating.SUGGESTIVE
			else -> ContentRating.SAFE
		}

		return Manga(
			id = generateUid(id),
			url = "/manga/$id",
			publicUrl = "https://$domain/title/$id",
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			tags = tags,
			authors = authors,
			state = state,
			source = source,
			description = desc,
			contentRating = contentRating
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = "api.$domain/manga/${manga.id}"
		val response = webClient.httpGet(mangaUrl).parseJson()
		val baseManga = parseMangaJson(response)

		val allChapters = ArrayList<MangaChapter>()
		var page = 1
		val langParam = ""

		while (true) {
			val chaptersUrl = "$mangaUrl/chapters?limit=100&page=$page&order=desc$langParam"
			val chResponse = webClient.httpGet(chaptersUrl).parseJson()
			val data = chResponse.optJSONArray("data") ?: break

			if (data.length() == 0) break

			for (i in 0 until data.length()) {
				val ch = data.getJSONObject(i)
				val id = ch.getString("id")

				val vol = ch.optString("volume")
				val chapNum = ch.optString("chapter")
				val title = ch.optString("title")
				val lang = ch.optString("language")

				// Groups
				val groups = mutableListOf<String>()
				val rels = ch.optJSONObject("relationships")
				val groupArr = rels?.optJSONArray("groups")
				if (groupArr != null) {
					for (k in 0 until groupArr.length()) {
						groups.add(groupArr.getJSONObject(k).getString("name"))
					}
				}
				val scanlator = if (groups.isNotEmpty()) groups.joinToString(", ") else null

				// Title Formatting
				val volStr = if (vol.isNotEmpty() && vol != "null") "Vol. $vol " else ""
				val chStr = if (chapNum.isNotEmpty() && chapNum != "null") "Ch. $chapNum" else ""
				val titleStr = if (title.isNotEmpty() && title != "null") " - $title" else ""

				var fullTitle = "$volStr$chStr$titleStr".trim()
				if (fullTitle.isEmpty()) fullTitle = "Oneshot"

				if (lang.isNotEmpty()) fullTitle += " [$lang]"

				val numFloat = chapNum.toFloatOrNull() ?: -1f
				val dateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
					.parseSafe(ch.optString("updatedAt"))

				allChapters.add(
					MangaChapter(
						id = generateUid(id),
						title = fullTitle,
						number = numFloat,
						volume = vol.toIntOrNull() ?: 0,
						url = "/chapter/$id",
						uploadDate = dateStr,
						source = source,
						scanlator = scanlator,
						branch = null
					)
				)
			}

			val total = chResponse.optInt("total", 0)
			val limit = chResponse.optInt("limit", 100)
			val totalPages = ceil(total.toDouble() / limit).toInt()

			if (page >= totalPages) break
			page++
		}

		return baseManga.copy(chapters = allChapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/")
		val url = "api.$domain/chapter/$chapterId"

		val response = webClient.httpGet(url).parseJson()
		val node = response.getString("node")
		val data = response.getJSONArray("data")

		return (0 until data.length()).map { i ->
			val pageObj = data.getJSONObject(i)
			val filename = pageObj.getString("name")
			val imageUrl = "$node/data/$chapterId/$filename"

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source
			)
		}
	}

	private fun fetchTags(): Set<MangaTag> {
		val commonTags = listOf(
			"Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery",
			"Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural",
			"Thriller", "Tragedy", "Yaoi", "Yuri", "Mecha", "Isekai"
		)
		return commonTags.map { MangaTag(it, it, source) }.toSet()
	}
}
