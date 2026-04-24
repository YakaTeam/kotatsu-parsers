package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.Broken

@Broken("Site is online but layout changed — parser no longer matches page structure")
@MangaSourceParser("GATEMANGA", "GateManga", "ar")
internal class GateManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.GATEMANGA, "gatemanga.com") {
	override val postReq = true
	override val datePattern = "d MMMM، yyyy"
	override val listUrl = "ar/"
	override val withoutAjax = true
}
