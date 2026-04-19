package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@Broken // mangagojo.com domain is dead (NXDOMAIN); operators scattered content across iframe-redirect chains (mangagojo.my -> freeonlinek.top -> weebrook.com) with no clean one-to-one replacement.
@MangaSourceParser("MANGAGOJO", "MangaGojo", "en")
internal class MangaGojo(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MANGAGOJO, "mangagojo.com", 30, 20)
