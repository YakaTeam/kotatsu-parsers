package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.Broken
import java.util.*

@Broken("Site is online but parser is broken — layout/API changed, needs rewrite")
@MangaSourceParser("KOMIKLOKAL", "KomikLokal", "id")
internal class KomikLokalParser(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKLOKAL, "komikmu.top", pageSize = 20, searchPageSize = 10) {
	override val datePattern = "MMM d, yyyy"
	override val sourceLocale: Locale = Locale.ENGLISH
}
