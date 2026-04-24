package org.koitharu.kotatsu.parsers.site.hotcomics.de

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.hotcomics.HotComicsParser
import java.util.Locale

@Broken("Domain has no DNS records — site is gone")
@MangaSourceParser("TOOMICS", "Toomics", "de")
internal class Toomics(context: MangaLoaderContext) :
	HotComicsParser(context, MangaParserSource.TOOMICS, "toomics.top/de") {
	override val sourceLocale: Locale = Locale.ENGLISH
	override val isSearchSupported = false
}
