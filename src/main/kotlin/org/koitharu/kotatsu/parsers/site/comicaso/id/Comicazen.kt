package org.koitharu.kotatsu.parsers.site.comicaso.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.comicaso.ComicasoParser

@MangaSourceParser("COMICAZEN", "Comicazen", "id")
internal class Comicazen(context: MangaLoaderContext) :
	ComicasoParser(context, MangaParserSource.COMICAZEN, "comicazen.com", pageSize = 16)
