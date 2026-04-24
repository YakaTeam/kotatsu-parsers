package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Domain hijacked — now serves a JS redirect to spam/ads")
@MangaSourceParser("LUNARSCAN", "LunarrScan.com", "pt")
internal class LunarScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LUNARSCAN, "lunarrscan.com")
