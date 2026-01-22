package org.koitharu.kotatsu.parsers.site.all.mangafire

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.all.MangaFireParser

@MangaSourceParser("MANGAFIRE_JA", "MangaFire Japanese", "ja")
internal class MangaFireJa(context: MangaLoaderContext):
	MangaFireParser(context, MangaParserSource.MANGAFIRE_JA, "ja")
