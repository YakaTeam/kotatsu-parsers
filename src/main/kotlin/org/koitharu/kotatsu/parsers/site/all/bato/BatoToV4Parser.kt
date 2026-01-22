package org.koitharu.kotatsu.parsers.site.all.bato

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.all.BatoParser

@MangaSourceParser("BATOTOV4", "Bato.To v4")
internal class BatoToV4Parser(context: MangaLoaderContext):
	BatoParser(context, MangaParserSource.BATOTOV4, "bato.si") {

	override val configKeyDomain = ConfigKey.Domain(
		"bato.si",
		"battwo.com",
		"bato.to",
		"bato.ing",
	)
}
