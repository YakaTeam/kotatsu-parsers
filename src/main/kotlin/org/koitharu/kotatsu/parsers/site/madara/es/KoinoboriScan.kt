package org.koitharu.kotatsu.parsers.site.madara.es

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Site moved from visorkoi.com (dead, CF 522) to koinoboriscan.com which fronts every request with a Joken/JWT JS interstitial and rate-limits to 429 after one request — requires browser-side challenge execution before Madara paths become reachable")
@MangaSourceParser("KOINOBORISCAN", "KoinoboriScan", "es")
internal class KoinoboriScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.KOINOBORISCAN, "koinoboriscan.com") {
	override val postReq = true
}
