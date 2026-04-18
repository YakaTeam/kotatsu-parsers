package org.koitharu.kotatsu.parsers.site.madara.es

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Origin server dead — visorkoi.com returns HTTP 525 (SSL handshake failure to origin)")
@MangaSourceParser("KOINOBORISCAN", "KoinoboriScan", "es")
internal class KoinoboriScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.KOINOBORISCAN, "visorkoi.com") {
	override val postReq = true
}
