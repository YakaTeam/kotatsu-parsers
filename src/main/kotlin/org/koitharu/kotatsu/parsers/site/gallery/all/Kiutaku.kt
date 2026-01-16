package org.koitharu.kotatsu.parsers.site.gallery.all

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.koitharu.kotatsu.parsers.*
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.network.rateLimit
import org.koitharu.kotatsu.parsers.site.gallery.GalleryParser
import kotlin.time.Duration.Companion.seconds

@MangaSourceParser("KIUTAKU", "Kiutaku", type = ContentType.OTHER)
internal class Kiutaku(context: MangaLoaderContext) :
    GalleryParser(context, MangaParserSource.KIUTAKU, "kiutaku.com") {

    companion object {
        private val mutex = Mutex()
        private var lastImageRequestTime = 0L
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")
    }

	override val webClient: WebClient by lazy {
		val newHttpClient = context.httpClient.newBuilder()
			.rateLimit(permits = 3, period = 1.seconds)
			.build()

		OkHttpWebClient(newHttpClient, source)
	}
}
