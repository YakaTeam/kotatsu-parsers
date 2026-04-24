package org.koitharu.kotatsu.parsers.site.madara.all

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.*

@Broken("Site is online but parser is broken — layout/API changed, needs rewrite")
@MangaSourceParser("MANGATOP", "MangaTop", "", ContentType.HENTAI)
internal class MangaTop(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATOP, "mangatop.site") {
	override val datePattern = "d MMMM yyyy"
	override val sourceLocale: Locale = Locale.ENGLISH
	override val stylePage = ""
}
