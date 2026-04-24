package org.koitharu.kotatsu.parsers.site.zeistmanga.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.Broken

@Broken("Site is online but parser is broken — layout/API changed, needs rewrite")
@MangaSourceParser("SOLOOSCAN", "SolooScan", "pt")
internal class SolooScan(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.SOLOOSCAN, "solooscan.blogspot.com") {
	override val mangaCategory = "Recentes"
	override val sateOngoing: String = "Lançando"
	override val sateFinished: String = "Completo"
	override val sateAbandoned: String = "Dropado"
}
