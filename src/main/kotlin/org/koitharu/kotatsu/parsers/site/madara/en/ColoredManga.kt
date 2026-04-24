package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Site is online but parser is broken — layout/API changed, needs rewrite")
@MangaSourceParser("COLORED_MANGA", "ColoredManga", "en")
internal class ColoredManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.COLORED_MANGA, "coloredmanga.net") {
	override val datePattern = "dd-MMM"
}
