package org.koitharu.kotatsu.parsers.site.madara.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MEHENTAI", "MeHentai", "vi")
internal class MeHentai(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MEHENTAI, "www.mehentai.tv") {
		override val withoutAjax = true
	}
