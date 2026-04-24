package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Domain has no DNS records — site is gone")
@MangaSourceParser("PROJETOSCANLATOR", "ProjetoScanlator", "pt")
internal class ProjetoScanlator(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.PROJETOSCANLATOR, "projetoscanlator.com", 10) {
	override val datePattern: String = "dd/MM/yyyy"
}
