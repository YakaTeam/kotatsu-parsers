package org.koitharu.kotatsu.parsers.site.mangareader.pt

import androidx.collection.ArraySet
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getDoubleOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("SSSSCANLATOR", "YomuComics", "pt")
internal class SssScanlator(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SSSSCANLATOR, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("yomu.com.br")

	override val sourceLocale: Locale = Locale("pt", "BR")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/api/library?page=")
			append(page.toString())
			append("&limit=")
			append(pageSize.toString())
			append("&sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "recent"
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL -> "title"
					else -> "recent"
				},
			)
			if (!filter.query.isNullOrEmpty()) {
				append("&search=")
				append(filter.query.urlEncoded())
			}
			filter.tags.firstOrNull()?.let { tag ->
				append("&genre=")
				append(tag.key.urlEncoded())
			}
			filter.states.oneOrThrowIfMany()?.let { state ->
				append("&status=")
				append(
					when (state) {
						MangaState.ONGOING -> "Ongoing"
						MangaState.FINISHED -> "Completed"
						MangaState.PAUSED -> "Hiatus"
						else -> ""
					},
				)
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				append("&type=")
				append(
					when (type) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> ""
					},
				)
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val data = json.getJSONArray("data")
		return data.mapJSON { obj ->
			val slug = obj.getString("slug")
			val relUrl = "/obra/$slug"
			Manga(
				id = generateUid(relUrl),
				title = obj.getString("title"),
				altTitles = emptySet(),
				url = relUrl,
				publicUrl = "https://$domain$relUrl",
				rating = obj.getDoubleOrDefault("rating", -10.0).let {
					if (it < 0) RATING_UNKNOWN else (it / 10.0).toFloat()
				},
				contentRating = null,
				coverUrl = obj.getStringOrNull("cover"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val html = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseRaw()
		val rsc = extractRscPayload(html)
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", sourceLocale)

		val description = extractJsonString(rsc, "description")
		val author = extractJsonString(rsc, "author")
		val artist = extractJsonString(rsc, "artist")
		val coverImage = extractJsonString(rsc, "coverImage")

		val chaptersJson = extractJsonArray(rsc)
		val chapters = chaptersJson?.mapJSONNotNull { obj ->
			val id = obj.getStringOrNull("id") ?: return@mapJSONNotNull null
			val number = obj.getDoubleOrDefault("number", 0.0).toFloat()
			val chapUrl = "/api/chapters?id=$id"
			MangaChapter(
				id = generateUid(chapUrl),
				title = obj.getStringOrNull("title"),
				number = number,
				volume = 0,
				url = chapUrl,
				scanlator = obj.getStringOrNull("scanName")?.takeUnless { it == "Desconhecido" },
				uploadDate = obj.getStringOrNull("releaseDate")?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L,
				branch = null,
				source = source,
			)
		}?.sortedBy { it.number }?.toList().orEmpty()

		val authors = buildSet {
			author?.takeUnless { it.isBlank() }?.let(::add)
			artist?.takeUnless { it.isBlank() || it == author }?.let(::add)
		}

		return manga.copy(
			description = description,
			authors = authors,
			coverUrl = coverImage ?: manga.coverUrl,
			largeCoverUrl = coverImage ?: manga.largeCoverUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseJson()
		val content = json.getJSONObject("chapter").getJSONArray("content")
		val result = ArrayList<MangaPage>(content.length())
		for (i in 0 until content.length()) {
			val url = content.getString(i)
			result.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				),
			)
		}
		return result
	}

	private val tagsCache = suspendLazy(initializer = ::loadTags)

	private suspend fun fetchTags(): Set<MangaTag> = tagsCache.get()

	private suspend fun loadTags(): Set<MangaTag> {
		val arr = webClient.httpGet("https://$domain/api/genres").parseJsonArray()
		val result = ArraySet<MangaTag>(arr.length())
		for (i in 0 until arr.length()) {
			val name = arr.getString(i)
			result.add(
				MangaTag(
					title = name.toTitleCase(sourceLocale),
					key = name,
					source = source,
				),
			)
		}
		return result
	}

	private fun extractRscPayload(html: String): String {
		val regex = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
		val builder = StringBuilder()
		for (match in regex.findAll(html)) {
			builder.append(decodeEscapes(match.groupValues[1]))
		}
		return builder.toString()
	}

	private fun decodeEscapes(input: String): String {
		val sb = StringBuilder(input.length)
		var i = 0
		while (i < input.length) {
			val c = input[i]
			if (c == '\\' && i + 1 < input.length) {
				when (val n = input[i + 1]) {
					'n' -> sb.append('\n')
					't' -> sb.append('\t')
					'r' -> sb.append('\r')
					'"' -> sb.append('"')
					'\\' -> sb.append('\\')
					'/' -> sb.append('/')
					'b' -> sb.append('\b')
					'f' -> sb.append('\u000C')
					'u' -> if (i + 5 < input.length) {
						val hex = input.substring(i + 2, i + 6)
						runCatching { sb.append(hex.toInt(16).toChar()) }.getOrElse { sb.append(n) }
						i += 4
					} else {
						sb.append(n)
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

	private fun extractJsonString(text: String, key: String): String? {
		val pattern = Regex("\"" + Regex.escape(key) + "\":\"((?:[^\"\\\\]|\\\\.)*)\"")
		val m = pattern.find(text) ?: return null
		return decodeEscapes(m.groupValues[1]).takeUnless { it.isBlank() }
	}

	private fun extractJsonArray(text: String, key: String): org.json.JSONArray? {
		val keyPattern = "\"chapters\":["
		val startIdx = text.indexOf(keyPattern)
		if (startIdx < 0) return null
		var i = startIdx + keyPattern.length - 1 // position at '['
		var depth = 0
		var inStr = false
		var escape = false
		val arrStart = i
		while (i < text.length) {
			val c = text[i]
			if (inStr) {
				if (escape) escape = false
				else if (c == '\\') escape = true
				else if (c == '"') inStr = false
			} else {
				when (c) {
					'"' -> inStr = true
					'[' -> depth++
					']' -> {
						depth--
						if (depth == 0) {
							val slice = text.substring(arrStart, i + 1)
							return runCatching { org.json.JSONArray(slice) }.getOrNull()
						}
					}
				}
			}
			i++
		}
		return null
	}
}
