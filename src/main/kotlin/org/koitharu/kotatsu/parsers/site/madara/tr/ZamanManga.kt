package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Domain parked — landing page only, no manga content")
@MangaSourceParser("ZAMANMANGA", "ZamanManga", "tr")
internal class ZamanManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ZAMANMANGA, "zamanmanga.com") {
	override val datePattern = "dd MMMM yyyy"
}