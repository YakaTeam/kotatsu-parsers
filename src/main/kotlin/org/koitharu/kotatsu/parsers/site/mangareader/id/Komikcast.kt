package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKCAST, 10) {

	override val configKeyDomain = ConfigKey.Domain("v1.komikcast.fit")

	private val apiDomain = "be.komikcast.fit"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(apiDomain)
			append("/series?page=")
			append(page)

			when (order) {
				SortOrder.POPULARITY -> append("&sort=popular")
				else -> {} // Default is updated
			}

			if (!filter.query.isNullOrEmpty()) {
				append("&title=")
				append(filter.query.urlEncoded())
			}
		}

		val response = webClient.httpGet(url).parseJson()
		val data = response.getJSONArray("data")

		return data.mapJSON { jo ->
			val seriesData = jo.getJSONObject("data")
			val slug = seriesData.getString("slug")
			val coverUrl = seriesData.getStringOrNull("coverImage")

			Manga(
				id = generateUid(slug),
				url = "/series/$slug",
				publicUrl = "https://$domain/series/$slug",
				title = seriesData.getString("title"),
				altTitles = setOfNotNull(seriesData.getStringOrNull("nativeTitle")),
				coverUrl = coverUrl,
				rating = seriesData.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat() / 10f,
				contentRating = ContentRating.SAFE,
				authors = setOfNotNull(seriesData.getStringOrNull("author")),
				state = when (seriesData.getStringOrNull("status")?.lowercase()) {
					"ongoing" -> MangaState.ONGOING
					"completed", "finished" -> MangaState.FINISHED
					else -> null
				},
				tags = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/")
		val url = "https://$apiDomain/series/$slug"

		val response = webClient.httpGet(url).parseJson()
		val data = response.getJSONObject("data")
		val seriesData = data.getJSONObject("data")

		// Get chapters
		val chaptersUrl = "https://$apiDomain/series/$slug/chapters"
		val chaptersResponse = webClient.httpGet(chaptersUrl).parseJson()
		val chaptersData = chaptersResponse.getJSONArray("data")

		val chapters = chaptersData.mapJSON { jo ->
			val chapterData = jo.getJSONObject("data")
			val index = chapterData.getInt("index")
			val chapterUrl = "/series/$slug/chapters/$index"

			MangaChapter(
				id = generateUid(chapterUrl),
				url = chapterUrl,
				title = chapterData.getStringOrNull("title") ?: "Chapter $index",
				number = index.toFloat(),
				volume = 0,
				scanlator = null,
				uploadDate = parseDate(jo.getStringOrNull("createdAt")),
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }

		// Parse genres
		val genresArray = seriesData.optJSONArray("genres")
		val tags = if (genresArray != null) {
			genresArray.mapJSON { genreObj ->
				val genreData = genreObj.getJSONObject("data")
				MangaTag(
					key = genreObj.getInt("id").toString(),
					title = genreData.getString("name"),
					source = source,
				)
			}.toSet()
		} else {
			emptySet()
		}

		return manga.copy(
			title = seriesData.getString("title"),
			altTitles = setOfNotNull(seriesData.getStringOrNull("nativeTitle")),
			description = seriesData.getStringOrNull("synopsis"),
			coverUrl = seriesData.getStringOrNull("coverImage") ?: manga.coverUrl,
			largeCoverUrl = seriesData.getStringOrNull("backgroundImage"),
			authors = setOfNotNull(seriesData.getStringOrNull("author")),
			state = when (seriesData.getStringOrNull("status")?.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed", "finished" -> MangaState.FINISHED
				else -> null
			},
			rating = seriesData.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat() / 10f,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// URL format: /series/{slug}/chapters/{index}
		val parts = chapter.url.split("/")
		val slug = parts[2]
		val index = parts[4]

		val url = "https://$apiDomain/series/$slug/chapters/$index"
		val response = webClient.httpGet(url).parseJson()
		val data = response.getJSONObject("data")
		val chapterData = data.getJSONObject("data")
		val imagesArray = chapterData.getJSONArray("images")

		return (0 until imagesArray.length()).map { i ->
			val imageUrl = imagesArray.getString(i)
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://$apiDomain/genres"
		return try {
			val response = webClient.httpGet(url).parseJson()
			val data = response.getJSONArray("data")
			data.mapJSON { jo ->
				val genreData = jo.getJSONObject("data")
				MangaTag(
					key = jo.getInt("id").toString(),
					title = genreData.getString("name"),
					source = source,
				)
			}.toSet()
		} catch (e: Exception) {
			emptySet()
		}
	}

	private fun parseDate(dateString: String?): Long {
		if (dateString.isNullOrEmpty()) return 0
		return try {
			// Format: 2026-02-01T01:23:15.843+07:00
			java.time.OffsetDateTime.parse(dateString).toInstant().toEpochMilli()
		} catch (e: Exception) {
			0
		}
	}
}
