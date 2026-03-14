package org.koitharu.kotatsu.parsers.site.madara.pt

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.network.CommonHeaders
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
		.add(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
		.add(CommonHeaders.ACCEPT_LANGUAGE, "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
		.add(CommonHeaders.CACHE_CONTROL, "max-age=0")
		.add(CommonHeaders.CONNECTION, "keep-alive")
		.add(CommonHeaders.SEC_CH_UA, "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
		.add(CommonHeaders.SEC_CH_UA_MOBILE, "?0")
		.add(CommonHeaders.SEC_CH_UA_PLATFORM, "\"Windows\"")
		.add(CommonHeaders.SEC_FETCH_DEST, "document")
		.add(CommonHeaders.SEC_FETCH_MODE, "navigate")
		.add(CommonHeaders.SEC_FETCH_SITE, "none")
		.add(CommonHeaders.SEC_FETCH_USER, "?1")
		.add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
		.build()
}
