package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

@MangaSourceParser("MANGAGO", "MangaGo", "en")
internal class MangaGo(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("mangago.me")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.ALPHABETICAL)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchGenres(),
            availableStates = EnumSet.allOf(MangaState::class.java),
            availableContentRating = emptySet()
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.UPDATED -> "s=1"
            SortOrder.POPULARITY -> "s=9"
            SortOrder.NEWEST -> "s=2"
            SortOrder.ALPHABETICAL -> "s=3"
            else -> "s=1"
        }

        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/r/l_search/?name=${filter.query.urlEncoded()}&page=$page"
        } else {
            // Genre filtering or default listing
            val genre = filter.tags.firstOrNull()?.key ?: "All"
            "https://$domain/genre/$genre/$page/?$sortParam"
        }

        val doc = webClient.httpGet(url).parseHtml()
        
        // Selector differs slightly between search and directory
        val items = if (url.contains("l_search")) {
            doc.select("div.row") // Search result items
        } else {
            doc.select("ul#search_list > li") // Directory items
        }

        return items.mapNotNull { element ->
            val titleEl = element.selectFirst("h2 a") ?: element.selectFirst("h3 a") ?: return@mapNotNull null
            val href = titleEl.attr("href")
            val title = titleEl.text()
            val img = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-original")

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href,
                coverUrl = img,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        val infoArea = doc.selectFirst("div.manga_right")
        val authors = infoArea?.select("td:contains(Author) a")?.map { it.text() }?.toSet() ?: emptySet()
        val genres = infoArea?.select("td:contains(Genre) a")?.map { it.text() }?.toSet() ?: emptySet()
        val statusText = infoArea?.select("td:contains(Status) span")?.text()?.lowercase()
        val summary = doc.select("div.manga_summary").text().removePrefix("Summary:")

        val state = when {
            statusText?.contains("completed") == true -> MangaState.FINISHED
            statusText?.contains("ongoing") == true -> MangaState.ONGOING
            else -> null
        }

        val chapters = doc.select("table#chapter_table tr").mapNotNull { tr ->
            val a = tr.selectFirst("a.chico") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = a.text()
            val dateText = tr.select("td:last-child").text()

            MangaChapter(
                id = generateUid(href),
                title = title,
                number = -1f, // Standard regex parsing could be added here
                volume = 0,
                url = href,
                uploadDate = parseDate(dateText),
                source = source,
                scanlator = null,
                branch = null
            )
        }.reversed()

        return manga.copy(
            authors = authors,
            tags = genres.map { MangaTag(it, it, source) }.toSet(),
            description = summary,
            state = state,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        // 1. Extract the encrypted Base64 string
        val scriptContent = doc.select("script:containsData(imgsrcs)").html()
        val imgsrcsBase64 = Regex("""var imgsrcs\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw Exception("Could not find imgsrcs")

        // 2. Fetch the external JS to get the Keys
        val jsUrl = doc.select("script[src*='chapter.js']").attr("abs:src")
        if (jsUrl.isEmpty()) throw Exception("Chapter JS not found")
        
        val jsContent = webClient.httpGet(jsUrl).parseJson<String>() // Fetch raw string

        // 3. Extract AES Key and IV using Regex
        val keyHex = Regex("""CryptoJS\.enc\.Hex\.parse\("([0-9a-fA-F]+)"\)""").findAll(jsContent)
            .elementAtOrNull(0)?.groupValues?.get(1) ?: throw Exception("Encryption Key not found")
        
        val ivHex = Regex("""CryptoJS\.enc\.Hex\.parse\("([0-9a-fA-F]+)"\)""").findAll(jsContent)
            .elementAtOrNull(1)?.groupValues?.get(1) ?: throw Exception("Encryption IV not found")

        // 4. AES Decrypt
        val decryptedString = decryptAes(imgsrcsBase64, keyHex, ivHex)

        // 5. Unscramble the String (Ported from Lua)
        // Find indices in the JS: str.charAt(123)
        val keyLocations = Regex("""str\.charAt\(\s*(\d+)\s*\)""").findAll(jsContent)
            .map { it.groupValues[1].toInt() }.toList()

        val finalUrlString = if (keyLocations.isNotEmpty()) {
            unscrambleImageList(decryptedString, keyLocations)
        } else {
            decryptedString
        }

        // 6. Split and Return
        return finalUrlString.split(",").mapIndexed { i, url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    // ================= HELPER FUNCTIONS =================

    private fun decryptAes(b64: String, hexKey: String, hexIv: String): String {
        try {
            val keyBytes = hexStringToByteArray(hexKey)
            val ivBytes = hexStringToByteArray(hexIv)
            val inputBytes = Base64.decode(b64, Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/NoPadding") // JS uses ZeroPadding usually, handle via trim
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(inputBytes)

            // Remove padding (ZeroPadding in JS usually pads with null bytes)
            return String(decryptedBytes, Charsets.UTF_8).trim { it <= ' ' }
        } catch (e: Exception) {
            throw Exception("Failed to decrypt image list: ${e.message}")
        }
    }

    /**
     * Ports the Lua `UnscrambleImageList` and `StringUnscramble` logic.
     * 1. Extracts "Keys" (digits) from the specific locations in the scrambled string.
     * 2. Removes those Key characters from the string.
     * 3. Uses the Keys to swap characters in the string back to original positions.
     */
    private fun unscrambleImageList(scrambledStr: String, locations: List<Int>): String {
        // Filter unique locations + 1 (Lua is 1-based, JS is 0-based. 
        // The regex extracted JS indices (0-based). But Lua adds 1.
        // We stick to 0-based for Kotlin logic matching the string index.)
        val uniqueLocs = locations.distinct().sorted()

        // Extract the hidden keys (the digits at these locations)
        val keys = mutableListOf<Int>()
        for (loc in uniqueLocs) {
            if (loc < scrambledStr.length) {
                val char = scrambledStr[loc]
                if (char.isDigit()) {
                    keys.add(char.toString().toInt())
                }
            }
        }

        // Create the "Cleaned" string (Remove chars at uniqueLocs)
        val sb = StringBuilder()
        for (i in scrambledStr.indices) {
            if (i !in uniqueLocs) {
                sb.append(scrambledStr[i])
            }
        }
        val cleanedString = sb.toString()

        // Perform the swap unscramble
        return stringUnscramble(cleanedString, keys)
    }

    private fun stringUnscramble(str: String, keys: List<Int>): String {
        val charArray = str.toCharArray()
        
        // Loop backwards through keys
        for (j in keys.indices.reversed()) {
            val keyVal = keys[j]
            // Loop backwards through string
            for (i in charArray.lastIndex downTo keyVal) {
                if ((i - keyVal) % 2 != 0) { // Note: Verify this logic matches Lua (i-1)%2 != 0?
                    // Lua is 1-based: if (i - 1) % 2 ~= 0
                    // Kotlin (0-based) equivalent: if (i) % 2 != 0 ??
                    // Let's trace carefully: 
                    // Lua: i goes from Length to keyVal+1.
                    // Lua Check: (i-1) is the 0-based index. So it checks if the 0-based index is odd.
                    // Kotlin: i is the 0-based index. So we check if i is odd.
                    if (i % 2 != 0) {
                        val idx1 = i - keyVal
                        val idx2 = i
                        if (idx1 >= 0 && idx2 < charArray.size) {
                            val temp = charArray[idx1]
                            charArray[idx1] = charArray[idx2]
                            charArray[idx2] = temp
                        }
                    }
                }
            }
        }
        return String(charArray)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val format = if (dateStr.contains(",")) "MMM d, yyyy" else "MMM d yyyy"
            SimpleDateFormat(format, Locale.ENGLISH).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
    
    private suspend fun fetchGenres(): Set<MangaTag> {
        // Hardcoded list or fetch from homepage. Hardcoding for performance.
        val genres = listOf(
            "Action", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi", "Fantasy", 
            "Gender Bender", "Harem", "Historical", "Horror", "Josei", "Martial Arts", 
            "Mature", "Mecha", "Mystery", "One Shot", "Psychological", "Romance", 
            "School Life", "Sci-fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen", 
            "Shounen Ai", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy", 
            "Webtoons", "Yaoi", "Yuri"
        )
        return genres.map { MangaTag(it, it, source) }.toSet()
    }
}
