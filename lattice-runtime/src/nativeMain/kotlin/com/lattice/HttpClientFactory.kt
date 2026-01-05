package com.lattice

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*

actual fun createDefaultHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(WebSockets)
    }
}
