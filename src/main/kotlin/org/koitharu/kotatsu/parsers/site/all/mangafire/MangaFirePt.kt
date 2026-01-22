package org.koitharu.kotatsu.parsers.site.all.mangafire

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.all.MangaFireParser

@MangaSourceParser("MANGAFIRE_PT", "MangaFire Portuguese", "pt")
internal class MangaFirePt(context: MangaLoaderContext):
	MangaFireParser(context, MangaParserSource.MANGAFIRE_PT, "pt")
