package org.koitharu.kotatsu.parsers.site.madara.es

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Domain has no DNS records — site is gone")
@MangaSourceParser("RIGHTDARKSCAN", "RightDarkScan", "es")
internal class RightdarkScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.RIGHTDARKSCAN, "rsdleft.com", 10)
