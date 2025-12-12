package org.koitharu.kotatsu.parsers.site.comicaso.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.comicaso.ComicasoParser

@MangaSourceParser("MEDUSASCANS", "Medusascans", "id", ContentType.HENTAI)
internal class Medusascans(context: MangaLoaderContext) :
	ComicasoParser(context, MangaParserSource.MEDUSASCANS, "medusascans.com", pageSize = 16)
