package org.koitharu.kotatsu.parsers.site.mangareader.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.Broken

@Broken("Site is online but parser is broken — layout/API changed, needs rewrite")
@MangaSourceParser("ALUCARDSCANS", "AlucardScans", "tr")
internal class AlucardScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.ALUCARDSCANS, "alucardscans.com", 20, 10) {
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = true,
		)
}
