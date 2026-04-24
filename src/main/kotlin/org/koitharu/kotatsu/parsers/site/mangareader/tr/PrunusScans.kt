package org.koitharu.kotatsu.parsers.site.mangareader.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.Broken

@Broken("Domain has no DNS records — site is gone")
@MangaSourceParser("PRUNUSSCANS", "PrunusScans", "tr")
internal class PrunusScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.PRUNUSSCANS, "prunusscans.com", pageSize = 20, searchPageSize = 10)
