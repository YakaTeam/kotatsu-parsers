package org.koitharu.kotatsu.parsers.site.madara.pt

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("HIPERCOOL", "Hipercool", "pt", ContentType.HENTAI)
internal class Hipercool(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HIPERCOOL, "hiper.cool", pageSize = 20) {

	override val tagPrefix = "manga-tag/"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
		.add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
		.add("Cache-Control", "max-age=0")
		.add("Connection", "keep-alive")
		.add("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
		.add("Sec-Ch-Ua-Mobile", "?0")
		.add("Sec-Ch-Ua-Platform", "\"Windows\"")
		.add("Sec-Fetch-Dest", "document")
		.add("Sec-Fetch-Mode", "navigate")
		.add("Sec-Fetch-Site", "none")
		.add("Sec-Fetch-User", "?1")
		.add("Upgrade-Insecure-Requests", "1")
		.build()
}
