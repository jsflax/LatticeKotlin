package com.lattice

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*

actual fun createDefaultHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(WebSockets)
    }
}

internal actual fun createHttpClientWithPin(pinnedServerCertificateSha256: String?): HttpClient? {
    if (pinnedServerCertificateSha256.isNullOrBlank()) {
        return createDefaultHttpClient()
    }
    // Ktor CIO on Kotlin/Native does not expose raw leaf DER bytes for custom pinning;
    // return null so LatticeCore fails closed rather than silently ignoring a pin.
    return null
}
