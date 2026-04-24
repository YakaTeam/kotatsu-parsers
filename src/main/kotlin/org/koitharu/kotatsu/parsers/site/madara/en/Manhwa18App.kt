package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Domain moved to manga18.app but /manga/ path returns 404 — URL structure changed, parser needs path and selector rewrite")
@MangaSourceParser("MANHWA18APP", "Manhwa18.app", "en", ContentType.HENTAI)
internal class Manhwa18App(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHWA18APP, "manhwa18.app") {
	override val postReq = true
}
