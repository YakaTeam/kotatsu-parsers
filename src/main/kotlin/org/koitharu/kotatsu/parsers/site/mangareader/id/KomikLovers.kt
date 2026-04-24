package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.Broken
import java.util.*

@Broken("Cloudflare origin TLS error (5xx) — site misconfigured or dead") // The site's servers are not responding; it may be closed.
@MangaSourceParser("KOMIKLOVERS", "KomikLovers", "id")
internal class KomikLovers(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKLOVERS, "komiklovers.com", pageSize = 20, searchPageSize = 10) {
	override val sourceLocale: Locale = Locale.ENGLISH
	override val listUrl = "/komik"
}
