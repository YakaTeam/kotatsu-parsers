package org.koitharu.kotatsu.parsers.site.all.bato

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.all.BatoParser

@MangaSourceParser("XBATCAT", "XBatCat")
internal class XBatCatParser(context: MangaLoaderContext):
	BatoParser(context, MangaParserSource.XBATCAT, "xbat.si") {

	override val configKeyDomain = ConfigKey.Domain(
		"xbat.si",
		"xbat.tv",
	)
}
