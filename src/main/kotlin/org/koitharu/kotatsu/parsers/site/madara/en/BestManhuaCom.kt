package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Site is behind a Joken/Cowboy JWT JS-challenge bot-shield — needs in-app/WebView verification before delisting")
@MangaSourceParser("BESTMANHUACOM", "BestManhua.com", "en")
internal class BestManhuaCom(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.BESTMANHUACOM, "bestmanhua.com", 10) {
	override val withoutAjax = true
}
