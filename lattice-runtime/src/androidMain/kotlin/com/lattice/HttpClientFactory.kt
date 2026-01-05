package com.lattice

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*

actual fun createDefaultHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        install(WebSockets)
    }
}
