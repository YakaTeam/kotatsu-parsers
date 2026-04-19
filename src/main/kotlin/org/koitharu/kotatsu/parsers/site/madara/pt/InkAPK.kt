package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("INKAPK", "InkAPK", "pt", ContentType.HENTAI)
internal class InkAPK(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.INKAPK, "inkapk.net") {
	override val listUrl = "obras/"
	override val tagPrefix = "obras-genre/"
}
