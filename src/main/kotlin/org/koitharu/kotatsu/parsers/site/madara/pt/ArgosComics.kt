package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.Broken

@Broken("Site moved to aniargos.com and is now login-gated — public listing no longer accessible without authentication")
@MangaSourceParser("ARGOSCOMICS", "ArgosComics", "pt")
internal class ArgosComics(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ARGOSCOMICS, "argoscomic.com")
