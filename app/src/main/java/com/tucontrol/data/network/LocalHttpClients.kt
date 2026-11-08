package com.tucontrol.data.network

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP clients for talking to TVs on the LAN.
 *
 * Smart TVs serve self-signed certificates that no CA can vouch for, so [localTls]
 * accepts any certificate. It must only ever be used for private LAN addresses.
 */
class LocalHttpClients {

    val plain: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    val localTls: OkHttpClient = plain.newBuilder()
        .sslSocketFactory(TrustAllTls.context.socketFactory, TrustAllTls.trustManager)
        .hostnameVerifier { _, _ -> true }
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
}

/** Trust manager for self-signed TV certificates on the local network. */
object TrustAllTls {
    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val context: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
}
