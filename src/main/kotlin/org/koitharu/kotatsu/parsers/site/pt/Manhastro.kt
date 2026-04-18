package org.koitharu.kotatsu.parsers.site.pt

import androidx.collection.ArraySet
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHASTRO", "Manhastro", "pt")
internal class Manhastro(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHASTRO, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("manhastro.net")

	override val sourceLocale: Locale = Locale("pt", "BR")

	private val apiBase = "https://api2.manhastro.net"
	private val imageProxyPrefix = "https://"

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
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val all = allMangasCache.get()
		var filtered: List<JSONObject> = all
		if (!filter.query.isNullOrEmpty()) {
			val q = filter.query.lowercase(sourceLocale)
			filtered = filtered.filter { m ->
				(m.getStringOrNull("titulo")?.lowercase(sourceLocale)?.contains(q) == true) ||
					(m.getStringOrNull("titulo_brasil")?.lowercase(sourceLocale)?.contains(q) == true)
			}
		}
		filter.tags.firstOrNull()?.let { tag ->
			filtered = filtered.filter { m -> m.genreList().any { it.equals(tag.key, ignoreCase = true) } }
		}
		val sorted = when (order) {
			SortOrder.ALPHABETICAL -> filtered.sortedBy { it.getStringOrNull("titulo")?.lowercase(sourceLocale) ?: "" }
			SortOrder.POPULARITY -> filtered.sortedByDescending { it.optInt("views_mes", 0) }
			SortOrder.UPDATED -> filtered.sortedByDescending { it.getStringOrNull("ultimo_capitulo") ?: "" }
			else -> filtered
		}
		val from = (page - 1) * pageSize
		if (from >= sorted.size) return emptyList()
		val slice = sorted.subList(from, minOf(from + pageSize, sorted.size))
		return slice.map(::mangaFromJson)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.trimStart('/').substringBefore('/')
		val cached = allMangasCache.get().firstOrNull { it.optInt("manga_id", -1).toString() == mangaId }
		val chaptersJson = webClient.httpGet("$apiBase/dados/$mangaId").parseJson()
		val chaptersArr = chaptersJson.optJSONArray("data") ?: JSONArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

		val chapters = chaptersArr.mapJSONNotNull { obj ->
			val chapId = obj.optInt("capitulo_id", -1).takeIf { it > 0 } ?: return@mapJSONNotNull null
			val name = obj.getStringOrNull("capitulo_nome") ?: "Capítulo"
			val number = extractChapterNumber(name)
			val url = "/chapter/$chapId"
			MangaChapter(
				id = generateUid(url),
				title = name,
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = obj.getStringOrNull("capitulo_data")
					?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L,
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }

		val description = cached?.let {
			it.getStringOrNull("descricao_brasil")?.takeUnless(String::isBlank)
				?: it.getStringOrNull("descricao")
		}
		val altTitle = cached?.getStringOrNull("titulo_brasil")?.takeUnless { it.equals(manga.title, ignoreCase = true) }
		val genres = cached?.genreList().orEmpty()
		val tags = genres.mapTo(ArraySet(genres.size)) { name ->
			MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
		}

		return manga.copy(
			altTitles = setOfNotNull(altTitle),
			description = description,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapId = chapter.url.trimStart('/').substringAfterLast('/')
		val json = webClient.httpGet("$apiBase/paginas/$chapId").parseJson()
		val data = json.optJSONObject("data") ?: return emptyList()
		val chapterObj = data.optJSONObject("chapter") ?: return emptyList()
		val baseUrl = chapterObj.getStringOrNull("baseUrl")?.trimEnd('/') ?: return emptyList()
		val hash = chapterObj.getStringOrNull("hash")?.trim('/') ?: return emptyList()
		val files = chapterObj.optJSONArray("data") ?: return emptyList()
		val result = ArrayList<MangaPage>(files.length())
		for (i in 0 until files.length()) {
			val file = files.getString(i)
			val url = "$baseUrl/$hash/$file"
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

	private val allMangasCache = suspendLazy(initializer = ::loadAllMangas)

	private suspend fun loadAllMangas(): List<JSONObject> {
		val json = webClient.httpGet("$apiBase/dados").parseJson()
		val arr = json.optJSONArray("data") ?: return emptyList()
		val list = ArrayList<JSONObject>(arr.length())
		for (i in 0 until arr.length()) {
			list.add(arr.getJSONObject(i))
		}
		return list
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val all = allMangasCache.get()
		val names = ArraySet<String>()
		for (obj in all) {
			for (g in obj.genreList()) names.add(g)
		}
		return names.mapTo(ArraySet(names.size)) { name ->
			MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
		}
	}

	private fun mangaFromJson(obj: JSONObject): Manga {
		val id = obj.optInt("manga_id", 0)
		val relUrl = "/manga/$id"
		val title = obj.getStringOrNull("titulo").orEmpty()
		val altTitle = obj.getStringOrNull("titulo_brasil")?.takeUnless { it.equals(title, ignoreCase = true) }
		val cover = obj.getStringOrNull("imagem")?.let { normalizeCoverUrl(it) }
		val tags = obj.genreList().mapTo(ArraySet()) { name ->
			MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
		}
		return Manga(
			id = generateUid(relUrl),
			title = title,
			altTitles = setOfNotNull(altTitle),
			url = relUrl,
			publicUrl = "https://$domain$relUrl",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = cover,
			tags = tags,
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			source = source,
		)
	}

	private fun normalizeCoverUrl(raw: String): String {
		return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else imageProxyPrefix + raw
	}

	private fun JSONObject.genreList(): List<String> {
		val raw = getStringOrNull("generos") ?: return emptyList()
		return runCatching {
			val arr = JSONArray(raw)
			val list = ArrayList<String>(arr.length())
			for (i in 0 until arr.length()) {
				val item = arr.optString(i).trim()
				if (item.isNotEmpty()) list.add(item)
			}
			list
		}.getOrDefault(emptyList())
	}

	private fun extractChapterNumber(name: String): Float {
		val match = Regex("(\\d+(?:[.,]\\d+)?)").find(name) ?: return 0f
		return match.groupValues[1].replace(',', '.').toFloatOrNull() ?: 0f
	}
}
