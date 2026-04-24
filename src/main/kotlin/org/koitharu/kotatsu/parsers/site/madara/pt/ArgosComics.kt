package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.Broken

@Broken("Site rebuilt as Next.js SPA on aniargos.com and login-gated — every public path returns the /entrar login page; needs full non-Madara rewrite")
@MangaSourceParser("ARGOSCOMICS", "ArgosComics", "pt")
internal class ArgosComics(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ARGOSCOMICS, "aniargos.com")
