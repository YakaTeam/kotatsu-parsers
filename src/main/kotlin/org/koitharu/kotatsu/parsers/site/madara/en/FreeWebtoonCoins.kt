package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Site is gone — root redirects to an unrelated domain")
@MangaSourceParser("FREEWEBTOONCOINS", "FreeWebtoonCoins", "en")
internal class FreeWebtoonCoins(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.FREEWEBTOONCOINS, "freewebtooncoins.com") {
	override val tagPrefix = "webtoon-genre/"
	override val listUrl = "webtoon/"
}
