package org.koitharu.kotatsu.parsers.site.zeistmanga.es

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser

@Broken("Domain has no DNS records — site is gone")
@MangaSourceParser("AIYUMANGASCANLATION", "AiyuManhua", "es")
internal class AiyuMangaScanlation(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.AIYUMANGASCANLATION, "www.aiyumanhua.com") {
	override val selectPage = "article.chapter img"
}
