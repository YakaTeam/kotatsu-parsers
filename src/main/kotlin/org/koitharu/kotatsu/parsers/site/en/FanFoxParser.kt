package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("FANFOX", "FanFox", "en")
internal class FanFoxParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.FANFOX, 50) {

	override val configKeyDomain = ConfigKey.Domain("fanfox.net")

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
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Cookie", "isAdult=1")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search?name=")
					append(filter.query.urlEncoded())
					append("&page=")
					append(page)
				}

				else -> {
					val tag = filter.tags.oneOrThrowIfMany()
					append("/directory/")
					if (tag != null) {
						append(tag.key)
						append('/')
					}
					if (page > 1) {
						append(page)
						append(".html")
					}
					append(
						when (order) {
							SortOrder.UPDATED -> "?latest"
							SortOrder.NEWEST -> "?news"
							SortOrder.ALPHABETICAL -> "?az"
							SortOrder.RATING -> "?rating"
							else -> "?latest"
						},
					)
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		val items = doc.body().select("ul.manga-list-1-list > li")
			.ifEmpty { doc.body().select("ul.manga-list-4-list > li") }
		return items.mapNotNull { li ->
			val titleLink = li.selectFirst("p.manga-list-1-item-title > a")
				?: li.selectFirst("p.manga-list-4-item-title > a")
				?: return@mapNotNull null
			val href = titleLink.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val cover = li.selectFirst("img.manga-list-1-cover")
				?: li.selectFirst("img.manga-list-4-cover")
			val statusText = li.selectFirst("p.manga-list-4-show-tag-list-2 a")
				?.text()?.trim()?.lowercase(Locale.ROOT)
			Manga(
				id = generateUid(href),
				title = titleLink.attr("title").trim().ifEmpty { titleLink.text().trim() },
				coverUrl = cover?.attrAsAbsoluteUrlOrNull("src"),
				source = MangaParserSource.FANFOX,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				authors = emptySet(),
				state = when (statusText) {
					"ongoing" -> MangaState.ONGOING
					"completed", "complete" -> MangaState.FINISHED
					else -> null
				},
				tags = emptySet(),
				url = href,
				contentRating = null,
				publicUrl = href.toAbsoluteUrl(domain),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val info = doc.body().selectFirst("div.detail-info-right")
		val status = info?.selectFirst("span.detail-info-right-title-tip")?.text()?.trim()?.lowercase(Locale.ROOT)
		val rating = info?.selectFirst("span.item-score")?.text()?.toFloatOrNull()?.div(10f) ?: manga.rating
		val authors = info?.select("p.detail-info-right-say a")?.mapNotNullToSet {
			it.text().trim().nullIfEmpty()
		}.orEmpty()
		val tags = info?.select("p.detail-info-right-tag-list a")?.mapToSet { a ->
			MangaTag(
				title = a.text().toTitleCase(),
				key = a.attrAsRelativeUrlOrNull("href")?.removePrefix("/directory/")?.trim('/').orEmpty(),
				source = MangaParserSource.FANFOX,
			)
		}.orEmpty()
		val description = doc.body().selectFirst("p.fullcontent")?.text()?.trim()
			?: info?.selectFirst("p.detail-info-right-content")?.text()?.trim()
		val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
		val chapterList = doc.body().select("ul.detail-main-list > li").asReversed()
		return manga.copy(
			rating = rating,
			authors = authors.ifEmpty { manga.authors },
			state = when (status) {
				"ongoing" -> MangaState.ONGOING
				"completed", "complete" -> MangaState.FINISHED
				else -> manga.state
			},
			tags = tags.ifEmpty { manga.tags },
			description = description,
			chapters = chapterList.mapChapters { i, li ->
				val a = li.selectFirst("a") ?: return@mapChapters null
				val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
				val title = a.selectFirst("p.title3")?.text()?.trim()
					?: a.attr("title").trim().ifEmpty { a.text().trim() }
				val dateText = a.selectFirst("p.title2")?.text()?.trim()
				MangaChapter(
					id = generateUid(href),
					url = href,
					source = MangaParserSource.FANFOX,
					number = href.substringAfterLast("/c").substringBefore('/')
						.toFloatOrNull() ?: (i + 1f),
					volume = 0,
					uploadDate = dateFormat.parseSafe(dateText),
					title = title.nullIfEmpty(),
					scanlator = null,
					branch = null,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val body = webClient.httpGet(chapterUrl).parseRaw()
		val unpacked = unpackJs(body)
			?: throw ParseException("Packed JS not found on chapter page", chapter.url)
		val imageUrls = NEW_IMGS_REGEX.find(unpacked)?.groupValues?.get(1)?.let { block ->
			NEW_IMG_ENTRY_REGEX.findAll(block).map { it.groupValues[1] }.toList()
		}.orEmpty()
		if (imageUrls.isEmpty()) {
			throw ParseException("No image URLs in unpacked payload", chapter.url)
		}
		return imageUrls.map { raw ->
			val url = if (raw.startsWith("//")) "https:$raw" else raw
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = MangaParserSource.FANFOX,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/directory/").parseHtml()
		// The genre list sits under the GENRES filter block; skip the STATUS section
		// entries (updated / completed / ongoing) which share the /directory/<key>/ URL shape.
		val excluded = setOf("updated", "completed", "ongoing")
		return doc.select("div.browse-bar-filter-list ul li a[href^=/directory/]")
			.mapNotNullToSet { a ->
				val key = a.attr("href").removePrefix("/directory/").trim('/')
				if (key.isEmpty() || key.endsWith(".html") || key.contains('?') || key.contains('/') || key in excluded) {
					return@mapNotNullToSet null
				}
				MangaTag(
					title = a.attr("title").ifEmpty { a.text() }.toTitleCase(),
					key = key,
					source = MangaParserSource.FANFOX,
				)
			}
	}

	/**
	 * Minimal P.A.C.K.E.R. (Dean Edwards) unpacker. FanFox embeds the chapter's
	 * image URL list directly in the chapter page as:
	 *   eval(function(p,a,c,k,e,d){...}('PAYLOAD',RADIX,COUNT,'sym1|sym2|...'.split('|'),0,{}))
	 * The unpacked payload contains `var newImgs=[...signed URLs...]`.
	 */
	private fun unpackJs(src: String): String? {
		val match = PACKER_REGEX.find(src) ?: return null
		val payload = match.groupValues[1]
			.replace("\\'", "'")
			.replace("\\\\", "\\")
		val radix = match.groupValues[2].toIntOrNull() ?: return null
		val symbols = match.groupValues[4].split('|')
		var result = payload
		for (i in symbols.indices.reversed()) {
			val sym = symbols[i]
			if (sym.isEmpty()) continue
			val token = encodeBase(i, radix)
			result = Regex("\\b" + Regex.escape(token) + "\\b").replace(result, sym)
		}
		return result
	}

	private fun encodeBase(num: Int, radix: Int): String {
		if (num == 0) return "0"
		val buf = StringBuilder()
		var n = num
		while (n > 0) {
			val c = n % radix
			buf.append(
				when {
					c < 10 -> ('0' + c)
					c < 36 -> ('a' + (c - 10))
					else -> ('A' + (c - 36))
				},
			)
			n /= radix
		}
		return buf.reverse().toString()
	}

	companion object {
		private val PACKER_REGEX = Regex(
			"""\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\s*\(\s*'\|'\s*\)""",
		)
		private val NEW_IMGS_REGEX = Regex("""newImgs\s*=\s*\[([^\]]+)\]""")
		private val NEW_IMG_ENTRY_REGEX = Regex("""'([^']+)'""")
	}
}
