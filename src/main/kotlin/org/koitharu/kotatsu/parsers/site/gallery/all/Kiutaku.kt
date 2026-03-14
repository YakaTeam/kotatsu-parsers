package org.koitharu.kotatsu.parsers.site.gallery.all

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.site.gallery.GalleryParser

@MangaSourceParser("KIUTAKU", "Kiutaku", type = ContentType.OTHER)
internal class Kiutaku(context: MangaLoaderContext) :
	GalleryParser(context, MangaParserSource.KIUTAKU, "kiutaku.com") {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url.toString()

		val headers = if (url.contains("wp-content")) {
			request.headers.newBuilder()
				.removeAll(CommonHeaders.REFERER)
				.build()
		} else {
			request.headers
		}

		val newRequest = request.newBuilder()
			.headers(headers)
			.build()

		return chain.proceed(newRequest)
	}
}
