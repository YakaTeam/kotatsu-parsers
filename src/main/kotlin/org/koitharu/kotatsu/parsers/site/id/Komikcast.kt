package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKCAST, 12) {

	override val configKeyDomain = ConfigKey.Domain("v2.komikcast.fit")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)
	private val apiUrl = "be.komikcast.cc" // simulates API requests similar to web

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED, // latest + desc = default
		SortOrder.UPDATED_ASC, // latest + asc
		SortOrder.POPULARITY, // popularity
		SortOrder.POPULARITY_ASC, // popularity + asc
		SortOrder.RATING, // rating
		SortOrder.RATING_ASC, // rating + asc
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING, // ongoing
				MangaState.FINISHED, // finished
				MangaState.PAUSED, // hiatus
				MangaState.ABANDONED, // cancelled
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			)
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().host(apiUrl).apply {
			addPathSegment("series")

			// Query with keyword, testing...
			if (!filter.query.isNullOrEmpty()) {
				val keyword = filter.query.encodeKeyword()
				val filterValue = "title=like=\"$keyword\",nativeTitle=like=\"$keyword\""
				addEncodedQueryParameter("filter", filterValue)
			}

			// Tags
			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach {
					addEncodedQueryParameter("genreIds", it.title.encodeKeyword())
				}
			}

			// MangaState
			if (filter.states.isNotEmpty()) {
				filter.states.forEach {
					when (it) {
						MangaState.ONGOING -> addQueryParameter("status", "ongoing")
						MangaState.FINISHED -> addQueryParameter("status", "completed")
						MangaState.PAUSED -> addQueryParameter("status", "hiatus")
						MangaState.ABANDONED -> addQueryParameter("status", "cancelled")
						else -> {}
					}
				}
			}

			// ContentType
			if (filter.types.isNotEmpty()) {
				filter.types.forEach {
					when (it) {
						ContentType.MANGA -> addQueryParameter("format", "manga")
						ContentType.MANHWA -> addQueryParameter("format", "manhwa")
						ContentType.MANHUA -> addQueryParameter("format", "manhua")
						else -> {}
					}
				}
			}

			addQueryParameter("takeChapter", 2.toString())
			addQueryParameter("includeMeta", true.toString())

			// order
			when (order) {
				SortOrder.UPDATED_ASC -> {
					addQueryParameter("sort", "latest")
					addQueryParameter("sortOrder", "asc")
				}
				SortOrder.POPULARITY -> {
					addQueryParameter("sort", "popularity")
					addQueryParameter("sortOrder", "desc")
				}
				SortOrder.POPULARITY_ASC -> {
					addQueryParameter("sort", "popularity")
					addQueryParameter("sortOrder", "asc")
				}
				SortOrder.RATING -> {
					addQueryParameter("sort", "rating")
					addQueryParameter("sortOrder", "desc")
				}
				SortOrder.RATING_ASC -> {
					addQueryParameter("sort", "rating")
					addQueryParameter("sortOrder", "asc")
				}
				else -> {
					addQueryParameter("sort", "latest")
					addQueryParameter("sortOrder", "desc")
				}
			}

			addQueryParameter("take", pageSize.toString())
			addQueryParameter("page", page.toString())
		}

		val json = webClient.httpGet(url.build()).parseJson()
		return json.getJSONArray("data").mapJSON { it ->
			val seriesData = it.getJSONObject("data")
			val slug = seriesData.getString("slug")
			Manga(
				id = generateUid(slug),
				title = seriesData.getString("title"),
				altTitles = seriesData.getString("nativeTitle").split(',').mapToSet { it },
				authors = seriesData.getString("author").split(',').mapToSet { it },
				contentRating = null,
				coverUrl = seriesData.getString("coverImage"),
				largeCoverUrl = seriesData.getString("backgroundImage"),
				description = seriesData.getString("synopsis"),
				url = slug,
				publicUrl = "https://$domain/series/$slug",
				rating = seriesData.getFloatOrDefault("rating", RATING_UNKNOWN).div(2f),
				tags = seriesData.getJSONArray("genres").mapJSONToSet {
					MangaTag(
						title = it.getJSONObject("data").getString("name"),
						key = it.getInt("id").toString(),
						source = source,
					)
				},
				state = when (seriesData.getString("status")) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					"cancelled" -> MangaState.ABANDONED
					else -> null
				},
				source = source
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val chaptersUrl = urlBuilder().host(apiUrl)
			.addPathSegments("series/${manga.url}/chapters").build()
		val data = webClient.httpGet(chaptersUrl).parseJson().getJSONArray("data")

		val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT)
		val chapters = data.mapChapters(reversed = true) { i, data ->
			val chapterData = data.getJSONObject("data")
			val index = chapterData.getFloatOrDefault("index", i + 1f)
			val realIndex = if (index % 1.0 == 0.0) index.toInt().toString() else index.toString()
			val title = data.optString("title", "Chapter $realIndex")

			// fix invalid X character in A5 / A6
			val createdAt = data.getStringOrNull("createdAt")
				?.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")

			MangaChapter(
				id = generateUid(realIndex),
				title = title,
				url = "${manga.url}/chapters/$realIndex",
				number = realIndex.toFloat(),
				volume = 0,
				scanlator = null,
				uploadDate = chapterDateFormat.parseSafe(createdAt),
				branch = null,
				source = source
			)
		}

		return manga.copy(
			chapters = chapters
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = urlBuilder().host(apiUrl).addPathSegments("series/${chapter.url}")
		val json = webClient.httpGet(url.build()).parseJson()
			.getJSONObject("data").getJSONObject("data")
		return json.getJSONArray("images").asTypedList<String>().map {
			MangaPage(
				id = generateUid(it),
				url = it,
				preview = null,
				source = source
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = urlBuilder().host(apiUrl).addPathSegment("genres")
		val response = webClient.httpGet(url.build()).parseJson()
		return response.getJSONArray("data").mapJSONToSet {
			val data = it.getJSONObject("data")
			MangaTag(
				title = data.getString("name"),
				key = it.getInt("id").toString(),
				source = source,
			)
		}
	}

	private fun String.encodeKeyword(): String {
		return URLEncoder.encode(this, "UTF-8")
	}
}
