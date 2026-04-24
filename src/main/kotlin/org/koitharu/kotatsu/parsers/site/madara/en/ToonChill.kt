package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Site is online but parser is broken — layout/API changed, needs rewrite")
@MangaSourceParser("TOONCHILL", "ToonChill", "en")
internal class ToonChill(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TOONCHILL, "toonchill.com", 32)
