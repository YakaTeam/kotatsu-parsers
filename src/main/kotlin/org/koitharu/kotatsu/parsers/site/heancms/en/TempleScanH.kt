package org.koitharu.kotatsu.parsers.site.heancms.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource

@MangaSourceParser("TEMPLESCAN_H", "TempleScan +18", "en", ContentType.HENTAI)
internal class TempleScanH(context: MangaLoaderContext) :
	TempleScan(context, MangaParserSource.TEMPLESCAN_H) {

	override val configKeyDomain = ConfigKey.Domain("templescan.net")
}
