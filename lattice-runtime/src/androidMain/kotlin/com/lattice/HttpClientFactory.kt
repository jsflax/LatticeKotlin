package com.lattice

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

actual fun createDefaultHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        install(WebSockets)
    }
}

internal actual fun createHttpClientWithPin(pinnedServerCertificateSha256: String?): HttpClient? {
    if (pinnedServerCertificateSha256.isNullOrBlank()) {
        return createDefaultHttpClient()
    }
    val expectedHex = pinnedServerCertificateSha256.trim().lowercase()
    if (expectedHex.length != 64 || !expectedHex.all { it in '0'..'9' || it in 'a'..'f' }) {
        return null
    }

    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Empty server certificate chain")
            }
            val leaf = chain[0]
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val actualHex = digest.joinToString("") { "%02x".format(it) }
            if (actualHex != expectedHex) {
                throw CertificateException(
                    "TLS certificate SHA-256 pin mismatch: expected=$expectedHex actual=$actualHex"
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }

    return HttpClient(OkHttp) {
        install(WebSockets)
        engine {
            config {
                sslSocketFactory(sslContext.socketFactory, trustManager)
                // Self-signed LAN host certificates (e.g. Orbital Mac Companion :7842) are
                // authenticated cryptographically by the exact leaf DER SHA-256 pin above.
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}
