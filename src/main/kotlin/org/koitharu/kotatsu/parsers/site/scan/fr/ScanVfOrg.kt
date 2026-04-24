package org.koitharu.kotatsu.parsers.site.scan.fr

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.scan.ScanParser

@Broken("Site is online but parser is broken — layout/API changed, needs rewrite")
@MangaSourceParser("SCANVFORG", "ScanVf.org", "fr")
internal class ScanVfOrg(context: MangaLoaderContext) :
	ScanParser(context, MangaParserSource.SCANVFORG, "scanvf.org")
