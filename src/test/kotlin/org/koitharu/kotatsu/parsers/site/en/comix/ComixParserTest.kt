package org.koitharu.kotatsu.parsers.site.en.comix

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import kotlin.time.Duration.Companion.minutes

internal class ComixParserTest {

private val context = MangaLoaderContextMock
private val timeout = 2.minutes
private val source = MangaParserSource.COMIX

@Test
fun details_load_chapters_and_pages() = runTest(timeout = timeout) {
val parser = context.newParserInstance(source)
val manga = parser.getList(MangaSearchQuery.EMPTY).firstOrNull() ?: error("No manga found for $source")

val chapters = checkNotNull(parser.getDetails(manga).chapters) {
"No chapters for ${manga.publicUrl}"
}
assertTrue(chapters.isNotEmpty(), "No chapters found for ${manga.publicUrl}")

val loaded = loadFirstReadableChapter(parser, chapters)
assertTrue(loaded != null, "No readable chapter could be loaded for ${manga.publicUrl}")
}

private suspend fun loadFirstReadableChapter(
parser: MangaParser,
chapters: List<MangaChapter>,
): MangaChapter? {
for (chapter in chapters) {
val pages = runCatching { parser.getPages(chapter) }.getOrNull().orEmpty()
if (pages.isNotEmpty()) {
return chapter
}
}
return null
}
}
