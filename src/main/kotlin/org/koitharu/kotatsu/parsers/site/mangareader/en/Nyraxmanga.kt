package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("NYRAXMANGA", "Nyraxmanga", "en")
internal class Nyraxmanga(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.NYRAXMANGA, "nyraxmanga.com", pageSize = 20, searchPageSize = 10)
