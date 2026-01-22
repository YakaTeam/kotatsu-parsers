package org.koitharu.kotatsu.parsers.site.all.mangafire

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.all.MangaFireParser

@MangaSourceParser("MANGAFIRE_PTBR", "MangaFire Portuguese (Brazil)", "pt")
internal class MangaFirePtBr(context: MangaLoaderContext) :
	MangaFireParser(context, MangaParserSource.MANGAFIRE_PTBR, "pt-br")
