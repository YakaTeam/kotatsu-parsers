package org.koitharu.kotatsu.parsers.site.all.mangafire.utils

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

public object SSLUtils {
	public val trustAllCerts: Array<TrustManager> = arrayOf(@Suppress("CustomX509TrustManager")
	object : X509TrustManager {
		override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
		override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
		override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
	})

	public val sslSocketFactory: SSLSocketFactory? = SSLContext.getInstance("SSL").apply {
		init(null, trustAllCerts, SecureRandom())
	}.socketFactory

	public val trustManager: X509TrustManager = trustAllCerts[0] as X509TrustManager
}
