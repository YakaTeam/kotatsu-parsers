package org.koitharu.kotatsu.parsers.site.gallery.all

import org.koitharu.kotatsu.parsers.*
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.site.gallery.GalleryParser
import org.koitharu.kotatsu.parsers.util.rateLimit
import kotlin.time.Duration.Companion.seconds

@MangaSourceParser("KIUTAKU", "Kiutaku", type = ContentType.OTHER)
internal class Kiutaku(context: MangaLoaderContext) :
    GalleryParser(context, MangaParserSource.KIUTAKU, "kiutaku.com") {

	override val webClient: WebClient by lazy {
		val newHttpClient = context.httpClient.newBuilder()
			.rateLimit(permits = 3, period = 1.seconds)
			.build()

		OkHttpWebClient(newHttpClient, source)
	}
}
