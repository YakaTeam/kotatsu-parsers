package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Domain hijacked — now serves a JS redirect to spam/ads")
@MangaSourceParser("DARKSCAN", "Dark-Scan", "en")
internal class DarkScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.DARKSCAN, "dark-scan.com")
