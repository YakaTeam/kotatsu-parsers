package org.koitharu.kotatsu.parsers.site.all.mangafire

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.all.MangaFireParser

@MangaSourceParser("MANGAFIRE_EN", "MangaFire English", "en")
internal class MangaFireEn(context: MangaLoaderContext):
	MangaFireParser(context, MangaParserSource.MANGAFIRE_EN, "en")
