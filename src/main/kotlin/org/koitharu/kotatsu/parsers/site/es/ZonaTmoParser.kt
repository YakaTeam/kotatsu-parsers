package org.koitharu.kotatsu.parsers.site.es

import org.json.JSONArray
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONTo
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.json.toStringSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("ZONATMO", "ZonaTMO", "es", ContentType.MANGA)
internal class ZonaTmoParser(context: MangaLoaderContext) : PagedMangaParser(
	context,
	source = MangaParserSource.ZONATMO,
	pageSize = 12,
) {

	override val configKeyDomain = ConfigKey.Domain("zonatmo.to")

	private val apiUrl get() = "https://$domain/wp-api/api"
	private val uploadsUrl get() = "https://$domain/wp-content/uploads"
	private val cdnUrl get() = "https://cdn.$domain"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = GENRES.entries.mapTo(HashSet(GENRES.size)) { (id, name) ->
				MangaTag(key = id, title = name, source = source)
			},
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.DOUJINSHI,
				ContentType.ONE_SHOT,
				ContentType.NOVEL,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append(apiUrl)
			append("/listing/manga?page=").append(page)
			append("&postsPerPage=").append(pageSize)
			append("&orderBy=").append(
				when (order) {
					SortOrder.UPDATED -> "last_chapter_date"
					SortOrder.POPULARITY -> "vote_count"
					SortOrder.NEWEST -> "year_start"
					SortOrder.ALPHABETICAL -> "title"
					SortOrder.RATING -> "score"
					else -> "last_chapter_date"
				},
			)
			append("&order=").append(if (order == SortOrder.ALPHABETICAL) "asc" else "desc")

			filter.query?.takeIf { it.isNotEmpty() }?.let {
				append("&search=").append(it.urlEncoded())
			}
			filter.tags.oneOrThrowIfMany()?.let {
				append("&genres=").append(it.key)
			}
			filter.states.firstOrNull()?.let { state ->
				STATUS_REVERSE[state]?.let {
					append("&status=").append(it)
				}
			}
			filter.types.firstOrNull()?.let { type ->
				TYPE_REVERSE[type]?.let {
					append("&type=").append(it)
				}
			}
			filter.year.takeIf { it > 0 }?.let {
				append("&year_start_min=").append(it)
				append("&year_start_max=").append(it)
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val items = json.getJSONObject("data").getJSONArray("items")

		return items.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				url = slug,
				publicUrl = "https://$domain/manga/$slug",
				title = jo.getString("title"),
				altTitles = emptySet(),
				coverUrl = jo.getStringOrNull("cover")?.let { uploadsUrl + it },
				authors = emptySet(),
				tags = jo.optJSONArray("genres")?.tagsFromIds().orEmpty(),
				state = jo.optJSONArray("status")?.firstStateFromIds(),
				description = jo.getStringOrNull("overview"),
				contentRating = null,
				source = source,
				rating = jo.getFloatOrDefault("score", 0f)
					.takeIf { it > 0f }
					?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url
		val json = webClient.httpGet("$apiUrl/single/manga/$slug").parseJson()
		val jo = json.getJSONObject("data")

		val altTitles = buildSet {
			jo.optJSONArray("alt_titles")?.toStringSet()?.let(::addAll)
			jo.optJSONArray("synonyms")?.toStringSet()?.let(::addAll)
			jo.getStringOrNull("subtitle")?.takeIf { it.isNotEmpty() && it != manga.title }?.let(::add)
		}

		val authors = jo.optJSONArray("author")?.mapJSONToSet { it.getString("name") }.orEmpty()

		val rating = jo.getFloatOrDefault("score", 0f)
			.takeIf { it > 0f }
			?.div(10f)?.coerceIn(0f, 1f) ?: manga.rating

		return manga.copy(
			title = jo.getStringOrNull("title") ?: manga.title,
			altTitles = altTitles,
			coverUrl = jo.getStringOrNull("cover")?.let { uploadsUrl + it } ?: manga.coverUrl,
			description = jo.getStringOrNull("overview") ?: manga.description,
			tags = jo.optJSONArray("genres")?.tagsFromIds() ?: manga.tags,
			state = jo.optJSONArray("status")?.firstStateFromIds() ?: manga.state,
			authors = authors,
			rating = rating,
			chapters = fetchAllChapters(slug),
		)
	}

	private suspend fun fetchAllChapters(slug: String): List<MangaChapter> {
		val all = ArrayList<MangaChapter>()
		var page = 1
		var totalPages = 1
		do {
			val url = "$apiUrl/single/manga/$slug/chapters?page=$page&postsPerPage=50&order=asc"
			val json = webClient.httpGet(url).parseJson()
			val data = json.getJSONObject("data")
			data.getJSONArray("items").mapJSONTo(all) { ch ->
				val chapterSlug = ch.getString("slug")
				MangaChapter(
					id = generateUid("$slug/$chapterSlug"),
					title = ch.getStringOrNull("title"),
					number = ch.getStringOrNull("chapter_number")?.toFloatOrNull() ?: 0f,
					volume = 0,
					url = "$slug/$chapterSlug",
					scanlator = null,
					uploadDate = ch.getStringOrNull("release_date").parseDate(),
					branch = null,
					source = source,
				)
			}
			totalPages = data.optJSONObject("pagination")?.optInt("total_pages", 1) ?: 1
			page++
		} while (page <= totalPages)
		return all.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet("$apiUrl/single/manga/${chapter.url}").parseJson()
		val chapterJson = json.getJSONObject("data").getJSONObject("chapter")
		val jit = chapterJson.getString("jit")
		val images = chapterJson.getJSONArray("images")

		return (0 until images.length()).map { i ->
			val img = images.getJSONObject(i)
			val imageUrl = "$cdnUrl/manga/$jit/${img.getString("image_url")}"
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun JSONArray.tagsFromIds(): Set<MangaTag> =
		(0 until length()).mapNotNullTo(HashSet(length())) { i ->
			val id = optInt(i, -1).takeIf { it >= 0 }?.toString() ?: return@mapNotNullTo null
			val name = GENRES[id] ?: return@mapNotNullTo null
			MangaTag(key = id, title = name, source = source)
		}

	private fun JSONArray.firstStateFromIds(): MangaState? =
		(0 until length()).firstNotNullOfOrNull { i ->
			optInt(i, -1).takeIf { it >= 0 }?.let(STATUS_MAP::get)
		}

	private fun String?.parseDate(): Long {
		if (this.isNullOrEmpty()) return 0L
		return try {
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(this)?.time ?: 0L
		} catch (_: Exception) {
			0L
		}
	}

	private companion object {
		// Genres extracted from window.siteConfig.datas.genres on zonatmo.to
		val GENRES: Map<String, String> = mapOf(
			"2" to "Acción",
			"3" to "Aventura",
			"4" to "Comedia",
			"5" to "Fantasia",
			"6" to "Magia",
			"7" to "Sobrenatural",
			"8" to "Harem",
			"15" to "Drama",
			"16" to "Romance",
			"21" to "Ciencia Ficción",
			"22" to "Girls Love",
			"23" to "Vida Escolar",
			"26" to "Artes Marciales",
			"27" to "Realidad Virtual",
			"32" to "Ecchi",
			"33" to "Recuentos de la vida",
			"36" to "Psicológico",
			"37" to "Deporte",
			"40" to "Misterio",
			"41" to "Crimen",
			"46" to "Tragedia",
			"49" to "Thriller",
			"60" to "Reencarnación",
			"81" to "Historia",
			"82" to "Horror",
			"88" to "Demonios",
			"99" to "Samurái",
			"103" to "Boys Love",
			"111" to "Policiaco",
			"112" to "Supervivencia",
			"116" to "Superpoderes",
			"141" to "Oeste",
			"144" to "Mecha",
			"147" to "Realidad",
			"181" to "Gore",
			"183" to "Género Bender",
			"219" to "Niños",
			"342" to "Militar",
			"345" to "Vampiros",
			"356" to "Ciberpunk",
			"403" to "Musica",
			"470" to "Telenovela",
			"820" to "Parodia",
			"861" to "Apocalíptico",
			"1027" to "Familia",
			"1109" to "Guerra",
			"1168" to "Extranjero",
			"1464" to "Traps",
			"6198" to "Animación",
			"12871" to "Adulto",
		)

		// Status taxonomy IDs → MangaState
		val STATUS_MAP: Map<Int, MangaState> = mapOf(
			12 to MangaState.ONGOING,        // Publicándose
			19 to MangaState.FINISHED,       // Finalizado
			174 to MangaState.PAUSED,        // Pausado
			198 to MangaState.ABANDONED,     // Cancelado
			12856 to MangaState.ONGOING,     // En curso
			12866 to MangaState.ONGOING,     // OnGoing
			12869 to MangaState.ONGOING,     // publishing
			12874 to MangaState.FINISHED,    // Completado
		)

		// Reverse maps for filter → query param (use canonical ID per state)
		val STATUS_REVERSE: Map<MangaState, Int> = mapOf(
			MangaState.ONGOING to 12,
			MangaState.FINISHED to 19,
			MangaState.PAUSED to 174,
			MangaState.ABANDONED to 198,
		)

		// Type taxonomy IDs → query value
		val TYPE_REVERSE: Map<ContentType, Int> = mapOf(
			ContentType.MANGA to 14,
			ContentType.MANHWA to 87,
			ContentType.MANHUA to 31,
			ContentType.DOUJINSHI to 207,
			ContentType.ONE_SHOT to 12312,
			ContentType.NOVEL to 214,
		)
	}
}

