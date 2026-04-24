package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("mangaruhu.com redirects to yomitranslate.com — operator continuity unconfirmed, needs manual verification before swap")
@MangaSourceParser("MANGARUHU", "MangaRuhu", "tr")
internal class MangaRuhu(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGARUHU, "mangaruhu.com", 16)
