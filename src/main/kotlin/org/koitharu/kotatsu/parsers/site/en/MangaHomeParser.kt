package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAHOME", "MangaHome", "en")
internal class MangaHomeParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAHOME, 30) {

	override val configKeyDomain = ConfigKey.Domain("www.mangahome.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
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
					if (tag != null) {
						append('/')
						append(tag.key)
						append('/')
					} else {
						append("/directory/")
					}
					append(page)
					append(".htm")
					append(
						when (order) {
							SortOrder.POPULARITY -> ""
							SortOrder.UPDATED -> "?last_chapter_time.za"
							SortOrder.ALPHABETICAL -> "?name.az"
							SortOrder.RATING -> "?rating.za"
							else -> ""
						},
					)
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		val root = doc.body().selectFirst("ul.manga-list")
			?: doc.body().selectFirst("ul.manga_pic_list")
			?: return emptyList()
		return root.select("li").mapNotNull { li ->
			val cover = li.selectFirst("a.post-cover") ?: li.selectFirst("a.manga_cover")
			val titleLink = li.selectFirst("p.title > a") ?: cover
			val href = (titleLink ?: cover)?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = titleLink?.attr("title")?.takeUnless { it.isBlank() }
				?: cover?.attr("title").orEmpty()
			Manga(
				id = generateUid(href),
				title = title,
				coverUrl = cover?.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
				source = MangaParserSource.MANGAHOME,
				altTitles = setOfNotNull(li.selectFirst("p.rename")?.text()?.trim()?.nullIfEmpty()),
				rating = li.selectFirst("span.star-score em")?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
				authors = emptySet(),
				state = null,
				tags = li.selectFirst("p.genre")?.select("a")?.mapToSet { a ->
					MangaTag(
						title = a.text().toTitleCase(),
						key = a.attrAsRelativeUrlOrNull("href")?.trim('/').orEmpty(),
						source = MangaParserSource.MANGAHOME,
					)
				}.orEmpty(),
				url = href,
				contentRating = null,
				publicUrl = href.toAbsoluteUrl(domain),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val top = doc.body().selectFirst("div.manga-detailtop")
		val middle = doc.body().selectFirst("div.manga-detailmiddle")
		val chapterList = doc.body().selectFirst("ul.detail-chlist")?.select("li")?.asReversed().orEmpty()
		val dateFormat = SimpleDateFormat("MMM dd,yyyy", Locale.US)

		val author = top?.selectFirst("p:contains(Author(s):) a")?.text()?.trim()
		val status = top?.selectFirst("p:contains(Status:)")?.ownText()?.trim()?.lowercase(Locale.ROOT)
		val altNameRaw = middle?.selectFirst("p:contains(Alternative Name:)")
			?.text()?.substringAfter("Alternative Name:", "")?.trim().orEmpty()
		val altTitles = altNameRaw.split(';', ',')
			.mapNotNullToSet { it.trim().nullIfEmpty() }

		return manga.copy(
			altTitles = altTitles.ifEmpty { manga.altTitles },
			authors = setOfNotNull(author),
			state = when (status) {
				"ongoing" -> MangaState.ONGOING
				"completed", "complete" -> MangaState.FINISHED
				else -> manga.state
			},
			tags = middle?.selectFirst("p:contains(Genre(s):)")?.select("a")?.mapToSet { a ->
				MangaTag(
					title = a.text().toTitleCase(),
					key = a.attrAsRelativeUrlOrNull("href")?.trim('/').orEmpty(),
					source = MangaParserSource.MANGAHOME,
				)
			}.orEmpty().ifEmpty { manga.tags },
			description = middle?.selectFirst("p#show")?.ownText()?.trim(),
			chapters = chapterList.mapChapters { i, li ->
				val a = li.selectFirst("a") ?: return@mapChapters null
				val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
				val name = a.selectFirst("span.mobile-none")?.text()?.trim()
					?: a.text().trim()
				MangaChapter(
					id = generateUid(href),
					url = href,
					source = MangaParserSource.MANGAHOME,
					number = href.substringAfterLast("/c").substringBefore('/')
						.toFloatOrNull() ?: (i + 1f),
					volume = 0,
					uploadDate = dateFormat.parseSafe(li.selectFirst("span.time")?.text()),
					title = name.nullIfEmpty(),
					scanlator = null,
					branch = null,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val inlineScript = doc.selectFirst("script:containsData(chapter_id)")?.data()
			?: throw ParseException("Chapter metadata not found", chapter.url)
		val chapterId = CHAPTER_ID_REGEX.find(inlineScript)?.groupValues?.get(1)
			?: throw ParseException("chapter_id not found", chapter.url)
		val imageCount = IMAGE_COUNT_REGEX.find(inlineScript)?.groupValues?.get(1)?.toIntOrNull()
			?: throw ParseException("imagecount not found", chapter.url)
		val chapterBase = chapter.url.removeSuffix("/")
			.let { if (it.endsWith(".html")) it.substringBeforeLast('/') else it }
		return (1..imageCount).map { idx ->
			val url = "$chapterBase/chapterfun.ashx?cid=$chapterId&page=$idx&key="
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = MangaParserSource.MANGAHOME,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val refererPath = page.url.substringBefore("/chapterfun.ashx")
		val headers = Headers.Builder()
			.set(CommonHeaders.REFERER, refererPath.toAbsoluteUrl(domain))
			.set(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
			.build()
		val response = webClient.httpGet(page.url.toAbsoluteUrl(domain), headers).parseRaw()
		val urls = unpackChapterImageUrls(response)
			?: throw ParseException("Failed to unpack image URL", page.url)
		return urls.firstOrNull()?.let {
			if (it.startsWith("//")) "https:$it" else it
		} ?: throw ParseException("No image URL in unpacked payload", page.url)
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/advsearch").parseHtml()
		return doc.select("ul.genres li").mapNotNullToSet { li ->
			val a = li.selectFirst("a") ?: return@mapNotNullToSet null
			val key = CLICK_GENRE_REGEX.find(a.attr("onclick"))?.groupValues?.get(1)
				?: return@mapNotNullToSet null
			MangaTag(
				title = li.attr("rel").nullIfEmpty() ?: a.text().toTitleCase(),
				key = key,
				source = MangaParserSource.MANGAHOME,
			)
		}
	}

	private fun unpackChapterImageUrls(packed: String): List<String>? {
		val unpacked = unpackJs(packed) ?: return null
		// Expected shape (approx):
		//   function dm5imagefun(){var pix="//host.cdn/store/..";var pvalue=["/img1.jpg","/img2.jpg"];
		//     for(...) { if(i==0){pvalue[i]=pix+pvalue[i];...} pvalue[i]=pix+pvalue[i]; } return pvalue}
		// The unpacker already expanded the dict — join pix with each pvalue entry.
		val pix = PIX_REGEX.find(unpacked)?.groupValues?.get(1).orEmpty()
		val pvalueBlock = PVALUE_REGEX.find(unpacked)?.groupValues?.get(1) ?: return null
		val entries = PVALUE_ENTRY_REGEX.findAll(pvalueBlock)
			.map { it.groupValues[1] }
			.toList()
		if (entries.isEmpty()) return null
		return entries.map { if (pix.isNotEmpty()) pix + it else it }
	}

	/**
	 * Minimal P.A.C.K.E.R. (Dean Edwards) unpacker. Handles the common
	 * `eval(function(p,a,c,k,e,d){...}('PAYLOAD',RADIX,COUNT,'sym1|sym2|...'.split('|'),0,{}))`
	 * wrapper used by the Fox-family chapterfun.ashx endpoint.
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
		private val CHAPTER_ID_REGEX = Regex("""chapter_id\s*=\s*(\d+)""")
		private val IMAGE_COUNT_REGEX = Regex("""imagecount\s*=\s*(\d+)""")
		private val CLICK_GENRE_REGEX = Regex("""clickGenre\s*\(\s*this\s*,\s*['"]([^'"]+)['"]""")
		private val PACKER_REGEX = Regex(
			"""\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\s*\(\s*'\|'\s*\)""",
		)
		private val PIX_REGEX = Regex("""pix\s*=\s*"([^"]+)"""")
		private val PVALUE_REGEX = Regex("""pvalue\s*=\s*\[([^\]]+)\]""")
		private val PVALUE_ENTRY_REGEX = Regex(""""([^"]+)"""")
	}
}
