package org.koitharu.kotatsu.parsers.site.es

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
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
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.util.EnumSet

@MangaSourceParser("BRAKEOUT", "Brakeout", "es", ContentType.HENTAI)
internal class Brakeout(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.BRAKEOUT) {

	override val configKeyDomain = ConfigKey.Domain("brakeout.xyz")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val genres = HashSet<MangaTag>()
		val arr = webClient.httpGet("https://$domain/api/estrenos").parseJsonArray()
		for (i in 0 until arr.length()) {
			val gens = arr.getJSONObject(i).optJSONArray("genders") ?: continue
			for (j in 0 until gens.length()) {
				val g = gens.getJSONObject(j)
				genres += MangaTag(
					title = g.getString("name").toTitleCase(sourceLocale),
					key = g.getInt("id").toString(),
					source = source,
				)
			}
		}
		return MangaListFilterOptions(
			availableTags = genres,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		)
	}

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val arr = webClient.httpGet("https://$domain/api/estrenos").parseJsonArray()
		val all = ArrayList<JSONObject>(arr.length())
		for (i in 0 until arr.length()) {
			all += arr.getJSONObject(i)
		}

		val filtered = all.asSequence().filter { proj ->
			if (!filter.query.isNullOrEmpty()) {
				val q = filter.query.lowercase()
				val name = proj.optString("nombre").lowercase()
				val author = proj.optString("autor").lowercase()
				val others = proj.optString("otherNames").lowercase()
				if (q !in name && q !in author && q !in others) return@filter false
			}
			if (filter.tags.isNotEmpty()) {
				val want = filter.tags.mapNotNull { it.key.toIntOrNull() }.toSet()
				val gens = proj.optJSONArray("genders") ?: return@filter false
				val has = (0 until gens.length()).any { gens.getJSONObject(it).getInt("id") in want }
				if (!has) return@filter false
			}
			if (filter.states.isNotEmpty()) {
				val estado = proj.optInt("estado", -1)
				val mapped = if (estado == 1) MangaState.ONGOING else MangaState.FINISHED
				if (mapped !in filter.states) return@filter false
			}
			true
		}

		val sorted = when (order) {
			SortOrder.NEWEST -> filtered.sortedByDescending { it.optString("created_at") }
			SortOrder.ALPHABETICAL -> filtered.sortedBy { it.optString("nombre").lowercase() }
			else -> filtered.sortedByDescending { it.optString("actualizacionCap") }
		}
		return sorted.map(::projectToManga).toList()
	}

	private fun projectToManga(proj: JSONObject): Manga {
		val id = proj.getInt("id")
		val slug = proj.getString("slug")
		val href = "/ver/$id/$slug"
		val tags = proj.optJSONArray("genders")?.let { gens ->
			(0 until gens.length()).mapNotNullToSet { i ->
				val g = gens.getJSONObject(i)
				MangaTag(
					title = g.getString("name").toTitleCase(sourceLocale),
					key = g.getInt("id").toString(),
					source = source,
				)
			}
		} ?: emptySet()
		val cover = proj.getStringOrNull("portada").orEmpty()
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = "https://$domain$href",
			title = proj.getString("nombre"),
			altTitles = setOfNotNull(proj.getStringOrNull("otherNames")?.takeIf(String::isNotBlank)),
			coverUrl = cover,
			largeCoverUrl = cover,
			description = proj.getStringOrNull("sinopsis"),
			rating = RATING_UNKNOWN,
			authors = setOfNotNull(proj.getStringOrNull("autor")?.takeIf(String::isNotBlank)),
			tags = tags,
			state = if (proj.optInt("estado", -1) == 1) MangaState.ONGOING else MangaState.FINISHED,
			source = source,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val seenUrls = HashSet<String>()
		val chapters = doc.select("a[href*=/ver/]").mapNotNull { a ->
			val href = a.attrAsRelativeUrl("href").trim('/')
			val parts = href.split('/')
			// Expected: ver/{id}/{slug}/{chapterN}
			if (parts.size != 4 || parts[0] != "ver") return@mapNotNull null
			val chNum = parts[3].toFloatOrNull() ?: return@mapNotNull null
			val url = "/$href"
			if (!seenUrls.add(url)) return@mapNotNull null
			chNum to url
		}.sortedBy { it.first }.mapIndexed { i, (num, url) ->
			MangaChapter(
				id = generateUid(url),
				title = "Capítulo ${if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()}",
				number = num,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}
		return manga.copy(chapters = chapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		// Reader page hosts <img> tags with src on cdn.statically.io / imgur / imageshack / media.brakeout
		return doc.select("img[src]").mapNotNull { img ->
			val src = img.attr("src")
			if (src.isBlank() ||
				!(src.contains("cdn.statically.io") ||
					src.contains("imgur.com") ||
					src.contains("imageshack") ||
					src.contains("media.brakeout"))
			) {
				return@mapNotNull null
			}
			MangaPage(
				id = generateUid(src),
				url = src,
				preview = null,
				source = source,
			)
		}
	}
}
