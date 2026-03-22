package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import java.util.*
import kotlin.random.Random

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc", 20) {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val tagPrefix = "genres/"
	override val listUrl = "komik/"
	override val datePattern = "dd MMM yy"
	override val stylePage = ""
	override val sourceLocale: Locale = Locale.ENGLISH
	private val randomLength = Random.Default.nextInt(13, 21)
	private val randomString = generateRandomString(randomLength)
	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
		.add(CommonHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,id;q=0.8")
		.add(CommonHeaders.SEC_FETCH_DEST, "document")
		.add(CommonHeaders.SEC_FETCH_MODE, "navigate")
		.add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
		.add(CommonHeaders.SEC_FETCH_USER, "?1")
		.add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
		.add(CommonHeaders.X_REQUESTED_WITH, randomString)
		.build()

	private fun generateRandomString(length: Int): String {
		val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
		return (1..length)
			.map { charset.random() }
			.joinToString("")
	}
}
