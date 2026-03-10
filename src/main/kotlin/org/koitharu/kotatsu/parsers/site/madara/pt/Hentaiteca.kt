package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Website is down!")
@MangaSourceParser("HENTAITECA", "Hentaiteca", "pt", ContentType.HENTAI)
internal class Hentaiteca(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HENTAITECA, "hentaiteca.net", pageSize = 10) {
	override val datePattern = "MM/dd/yyyy"
	override val tagPrefix = "genero/"
}
