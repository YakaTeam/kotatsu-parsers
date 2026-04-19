package org.koitharu.kotatsu.parsers.site.ar

import androidx.collection.ArraySet
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.AZORAMOON, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("azoramoon.com")

	override val sourceLocale: Locale = Locale("ar")

	private val apiBase = "https://api.azoramoon.com"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsCache.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANHWA, ContentType.MANHUA, ContentType.MANGA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val all = allPostsCache.get()
		var filtered: List<JSONObject> = all

		if (!filter.query.isNullOrEmpty()) {
			val q = filter.query.lowercase(sourceLocale)
			filtered = filtered.filter { p ->
				p.getStringOrNull("postTitle")?.lowercase(sourceLocale)?.contains(q) == true ||
					p.getStringOrNull("alternativeTitles")?.lowercase(sourceLocale)?.contains(q) == true ||
					p.getStringOrNull("slug")?.lowercase(sourceLocale)?.contains(q) == true
			}
		}

		filter.tags.firstOrNull()?.let { tag ->
			filtered = filtered.filter { p ->
				val genres = p.optJSONArray("genres") ?: return@filter false
				(0 until genres.length()).any { i ->
					genres.optJSONObject(i)?.optInt("id", -1).toString() == tag.key
				}
			}
		}

		filter.states.oneOrThrowIfMany()?.let { state ->
			val target = when (state) {
				MangaState.ONGOING -> "ONGOING"
				MangaState.FINISHED -> "COMPLETED"
				else -> null
			}
			if (target != null) {
				filtered = filtered.filter { it.getStringOrNull("seriesStatus") == target }
			}
		}

		filter.types.oneOrThrowIfMany()?.let { type ->
			val target = when (type) {
				ContentType.MANHWA -> "MANHWA"
				ContentType.MANHUA -> "MANHUA"
				ContentType.MANGA -> "MANGA"
				else -> null
			}
			if (target != null) {
				filtered = filtered.filter { it.getStringOrNull("seriesType") == target }
			}
		}

		val sorted = when (order) {
			SortOrder.ALPHABETICAL -> filtered.sortedBy { it.getStringOrNull("postTitle")?.lowercase(sourceLocale).orEmpty() }
			SortOrder.NEWEST -> filtered.sortedByDescending { it.getStringOrNull("createdAt").orEmpty() }
			SortOrder.RATING -> filtered.sortedByDescending { it.getFloatOrDefault("averageRating", 0f) }
			SortOrder.UPDATED -> filtered.sortedByDescending { it.getStringOrNull("lastChapterAddedAt").orEmpty() }
			else -> filtered
		}

		val from = (page - 1) * pageSize
		if (from >= sorted.size) return emptyList()
		val slice = sorted.subList(from, minOf(from + pageSize, sorted.size))
		return slice.map(::mangaFromJson)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val postId = manga.url.trimStart('/').substringBefore('/')
		val cached = allPostsCache.get().firstOrNull { it.optInt("id", -1).toString() == postId }

		val json = webClient.httpGet("$apiBase/api/chapters?postId=$postId&take=999").parseJson()
		val chaptersArr = json.optJSONObject("post")?.optJSONArray("chapters") ?: JSONArray()
		val slug = manga.url.trimStart('/').substringAfter('/').substringBefore('/').ifEmpty {
			cached?.getStringOrNull("slug").orEmpty()
		}
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT)

		val chapters = chaptersArr.mapJSONNotNull { obj ->
			if (!obj.optBoolean("isAccessible", false)) return@mapJSONNotNull null
			val chapSlug = obj.getStringOrNull("slug") ?: return@mapJSONNotNull null
			val number = obj.optDouble("number", Double.NaN).let { if (it.isNaN()) 0f else it.toFloat() }
			val name = obj.getStringOrNull("title").orEmpty().ifEmpty { "الفصل $number" }
			val url = "/series/$slug/$chapSlug"
			val scanlator = obj.optJSONObject("createdBy")?.getStringOrNull("name")
			val uploadDate = obj.getStringOrNull("createdAt")
				?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() }
				?: 0L
			MangaChapter(
				id = generateUid(url),
				title = name,
				number = number,
				volume = 0,
				url = url,
				scanlator = scanlator,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }

		val description = cached?.getStringOrNull("description")
		val altTitle = cached?.getStringOrNull("alternativeTitles")
			?.takeUnless(String::isBlank)
			?.takeUnless { it.equals(manga.title, ignoreCase = true) }
		val state = when (cached?.getStringOrNull("seriesStatus")) {
			"ONGOING" -> MangaState.ONGOING
			"COMPLETED" -> MangaState.FINISHED
			else -> null
		}
		val genres = cached?.optJSONArray("genres")
		val tags = if (genres != null) {
			genres.mapJSON { g ->
				MangaTag(
					title = g.getStringOrNull("name")?.toTitleCase(sourceLocale).orEmpty(),
					key = g.optInt("id").toString(),
					source = source,
				)
			}.filter { it.title.isNotEmpty() }.toSet()
		} else {
			emptySet()
		}
		val authors = listOfNotNull(
			cached?.getStringOrNull("author"),
			cached?.getStringOrNull("artist"),
			cached?.optJSONObject("createdby")?.getStringOrNull("name"),
		).filter { it.isNotBlank() }.toSet()

		return manga.copy(
			altTitles = setOfNotNull(altTitle),
			description = description,
			tags = tags,
			state = state,
			authors = authors,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trimStart('/').split('/')
		// Expected path: /series/{mangaSlug}/{chapterSlug}
		if (parts.size < 3) return emptyList()
		val mangaSlug = parts[1]
		val chapterSlug = parts[2]
		val json = webClient.httpGet(
			"$apiBase/api/chapter?mangaslug=${mangaSlug.urlEncoded()}&chapterslug=${chapterSlug.urlEncoded()}",
		).parseJson()
		val chapterObj = json.optJSONObject("chapter") ?: return emptyList()
		val images = chapterObj.optJSONArray("images") ?: return emptyList()
		val indices = (0 until images.length()).sortedBy { images.getJSONObject(it).optInt("order", it) }
		return indices.mapNotNull { idx ->
			val img = images.getJSONObject(idx)
			val url = img.getStringOrNull("url")?.takeUnless(String::isBlank) ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private val allPostsCache = suspendLazy(initializer = ::loadAllPosts)
	private val tagsCache = suspendLazy(initializer = ::loadTags)

	private suspend fun loadAllPosts(): List<JSONObject> {
		val json = webClient.httpGet("$apiBase/api/posts?page=1&perPage=5000").parseJson()
		val arr = json.optJSONArray("posts") ?: return emptyList()
		val list = ArrayList<JSONObject>(arr.length())
		for (i in 0 until arr.length()) {
			val p = arr.optJSONObject(i) ?: continue
			if (p.optBoolean("isNovel", false)) continue
			if (p.getStringOrNull("postStatus") != "PUBLIC") continue
			list.add(p)
		}
		return list
	}

	private suspend fun loadTags(): Set<MangaTag> {
		val json = runCatching { webClient.httpGet("$apiBase/api/genres").parseJsonArray() }.getOrNull()
		val result = ArraySet<MangaTag>()
		if (json != null) {
			for (i in 0 until json.length()) {
				val obj = json.optJSONObject(i) ?: continue
				val id = obj.optInt("id", -1).takeIf { it >= 0 } ?: continue
				val name = obj.getStringOrNull("name")?.takeUnless(String::isBlank) ?: continue
				result.add(
					MangaTag(
						title = name.toTitleCase(sourceLocale),
						key = id.toString(),
						source = source,
					),
				)
			}
		}
		if (result.isEmpty()) {
			// Fallback: derive tag set from the full posts catalogue
			for (p in allPostsCache.get()) {
				val genres = p.optJSONArray("genres") ?: continue
				for (i in 0 until genres.length()) {
					val g = genres.optJSONObject(i) ?: continue
					val id = g.optInt("id", -1).takeIf { it >= 0 } ?: continue
					val name = g.getStringOrNull("name")?.takeUnless(String::isBlank) ?: continue
					result.add(
						MangaTag(
							title = name.toTitleCase(sourceLocale),
							key = id.toString(),
							source = source,
						),
					)
				}
			}
		}
		return result
	}

	private fun mangaFromJson(obj: JSONObject): Manga {
		val id = obj.optInt("id", 0)
		val slug = obj.getStringOrNull("slug").orEmpty()
		val relUrl = "/$id/$slug"
		val title = obj.getStringOrNull("postTitle").orEmpty()
		val altTitle = obj.getStringOrNull("alternativeTitles")
			?.takeUnless(String::isBlank)
			?.takeUnless { it.equals(title, ignoreCase = true) }
		val cover = obj.getStringOrNull("featuredImage")?.takeUnless(String::isBlank)
		val state = when (obj.getStringOrNull("seriesStatus")) {
			"ONGOING" -> MangaState.ONGOING
			"COMPLETED" -> MangaState.FINISHED
			else -> null
		}
		val tags = obj.optJSONArray("genres")?.let { arr ->
			val set = ArraySet<MangaTag>(arr.length())
			for (i in 0 until arr.length()) {
				val g = arr.optJSONObject(i) ?: continue
				val gid = g.optInt("id", -1).takeIf { it >= 0 } ?: continue
				val name = g.getStringOrNull("name")?.takeUnless(String::isBlank) ?: continue
				set.add(
					MangaTag(
						title = name.toTitleCase(sourceLocale),
						key = gid.toString(),
						source = source,
					),
				)
			}
			set
		} ?: emptySet()
		val rating = obj.getFloatOrDefault("averageRating", -1f).let {
			if (it < 0f) RATING_UNKNOWN else (it / 10f).coerceIn(0f, 1f)
		}
		return Manga(
			id = generateUid(relUrl),
			title = title,
			altTitles = setOfNotNull(altTitle),
			url = relUrl,
			publicUrl = "https://$domain/series/$slug",
			rating = rating,
			contentRating = null,
			coverUrl = cover,
			tags = tags,
			state = state,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			source = source,
		)
	}
}
