package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken // atemporal.cloud is offline (connection refused) and no successor domain has surfaced publicly.
@MangaSourceParser("ATEMPORAL", "Atemporal", "pt")
internal class Atemporal(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ATEMPORAL, "atemporal.cloud") {
	override val datePattern: String = "d 'de' MMMM 'de' yyyy"
	override val withoutAjax = true
}
