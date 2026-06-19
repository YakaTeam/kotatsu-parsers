package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlBuilder
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("NHENTAI", "NHentai.net", type = ContentType.HENTAI)
internal class NHentaiParser(context: MangaLoaderContext) :
	GalleryAdultsParser(context, MangaParserSource.NHENTAI, "nhentai.net", 25) {

	private val apiSuffix = "api/v2"

	// Required overrides for base class
	override val selectGallery = ""
	override val selectGalleryLink = ""
	override val selectGalleryTitle = ""
	override val selectGalleryImg = ""
	override val idImg = "none"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegments(apiSuffix)
		val isDefaultHome = order == SortOrder.UPDATED
			&& filter.query.isNullOrEmpty()
			&& filter.tags.isEmpty()
			&& filter.locale == null

		if (isDefaultHome) {
			url.addPathSegment("galleries")
			url.addQueryParameter("page", page.toString())
		} else {
			val query = buildString {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
					append(it)
					append(" ")
				}

				buildQuery(filter.tags, filter.locale)
					.takeIf { it.isNotEmpty() }
					?.let { append(it) }

				if (isEmpty()) append("pages:>0")
			}

			val sort = when (order) {
				SortOrder.POPULARITY -> "popular"
				SortOrder.POPULARITY_TODAY -> "popular-today"
				SortOrder.POPULARITY_WEEK -> "popular-week"
				else -> "date"
			}

			url.addPathSegment("search")
			url.addQueryParameter("query", query.urlEncoded())
			url.addQueryParameter("sort", sort)
			url.addQueryParameter("page", page.toString())
		}

		val json = webClient.httpGet(url.build()).parseJson()
		return json.optJSONArray("result").mapJSON {
			val id = it.getInt("id")
			val title = it.extractTitle()
			Manga(
				id = generateUid("/g/$id/"),
				title = title.cleanupTitle().ifEmpty { title },
				altTitles = emptySet(),
				url = "/g/$id/",
				publicUrl = "https://$domain/g/$id/",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = "https://t.$domain/${it.getThumbnailPath()}",
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val id = manga.url.removeSurrounding("/g/", "/")
		val obj = webClient.httpGet("https://$domain/$apiSuffix/galleries/$id").parseJson()
		val tags = obj.getJSONArray("tags")

		return manga.copy(
			tags = tags.mapJSON {
				MangaTag(it.getString("name"), it.getString("slug"), source)
			}.toSet(),
			authors = tags.mapJSONNotNull { it.takeIf {
				it.getString("type") == "artist" }?.getString("name")
			}.toSet(),
			description = "Pages: ${obj.optInt("num_pages")}",
			coverUrl = "https://t.$domain/${obj.getCoverPath()}",
			chapters = listOf(
				MangaChapter(
					id = manga.id,
					title = manga.title,
					number = 1f,
					volume = 0,
					url = manga.url,
					scanlator = null,
					uploadDate = obj.optLong("upload_date") * 1000,
					branch = null,
					source = source
				)
			)
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val id = chapter.url.removeSurrounding("/g/", "/")
		val response = webClient.httpGet("https://$domain/$apiSuffix/galleries/$id").parseJson()
		return response.getJSONArray("pages").mapJSON { page ->
			val path = page.getString("path")
			MangaPage(
				id = generateUid(path),
				url = "https://i.$domain/$path",
				preview = page.optString("thumbnail").takeIf { it.isNotBlank() }
					?.let { "https://t.$domain/$it" },
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	private fun JSONObject.extractTitle(): String {
		val titleObj = optJSONObject("title")
		return listOfNotNull(
			titleObj?.optString("english"),
			titleObj?.optString("pretty"),
			optString("english_title"),
			optString("japanese_title")
		).firstOrNull { it.isNotBlank() } ?: "Gallery ${optInt("id")}"
	}

	private fun JSONObject.getThumbnailPath(): String = optJSONObject("thumbnail")?.optString("path")
		?: optString("thumbnail").takeIf { it.isNotBlank() }
		?: "galleries/${optString("media_id")}/thumb.webp"

	private fun JSONObject.getCoverPath(): String = optJSONObject("cover")?.optString("path") ?: getThumbnailPath()

	private fun buildQuery(tags: Collection<MangaTag>, language: Locale?): String = buildString {
		tags.forEach { append("tag:\"${it.key}\" ") }
		language?.let { append("language:\"${it.toLanguagePath()}\" ") }
	}.trim()

	override fun Locale.toLanguagePath(): String = when (this) {
		Locale.ENGLISH -> "english"
		Locale.JAPANESE -> "japanese"
		Locale.CHINESE -> "chinese"
		else -> language
	}
}
