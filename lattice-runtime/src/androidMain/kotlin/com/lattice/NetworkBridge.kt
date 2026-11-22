package com.lattice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "LatticeSync"

object LatticeNetworkBridge {
    private var factoryHandle: Long = 0

    fun setGlobalFactory(factory: LatticeWebSocketFactory) {
        markNetworkFactoryRegistered()
        val jniFactory = JniWebSocketFactory(factory)
        factoryHandle = NativeBridge.createNetworkFactory(jniFactory)
        NativeBridge.setGlobalNetworkFactory(factoryHandle)
    }

    fun initializeDefault() {
        setGlobalFactory(KtorWebSocketFactory())
    }
}

internal class JniWebSocketFactory(
    private val delegate: LatticeWebSocketFactory
) : LatticeWebSocketFactory {
    override fun createWebSocket(): LatticeWebSocket =
        JniWebSocketWrapper(delegate.createWebSocket())
}

/**
 * Wrapper around LatticeWebSocket providing JNI-friendly method signatures.
 * Kept via consumer-rules.pro against R8 shrinking/obfuscation.
 */
internal class JniWebSocketWrapper(
    private val delegate: LatticeWebSocket
) : LatticeWebSocket by delegate {

    @Volatile
    private var clientHandle: Long = 0

    fun setClientHandle(handle: Long) {
        clientHandle = handle
    }

    fun clearClientHandle() {
        clientHandle = 0L
        delegate.onOpen = null
        delegate.onMessage = null
        delegate.onError = null
        delegate.onClose = null
        try {
            delegate.close()
        } catch (_: Throwable) {}
    }

    fun configurePin(sha256Hex: String): Boolean {
        return delegate.configureCertificatePin(sha256Hex)
    }

    fun connectWithHeaders(url: String, headersJson: String) {
        val headers = parseHeadersJson(headersJson)

        delegate.onOpen = {
            val h = clientHandle
            if (h != 0L) NativeBridge.triggerOnOpen(h)
        }
        delegate.onMessage = { type, data ->
            val h = clientHandle
            if (h != 0L) {
                val typeOrdinal = if (type == WebSocketMessageType.TEXT) 0 else 1
                NativeBridge.triggerOnMessage(h, typeOrdinal, data)
            }
        }
        delegate.onError = { error ->
            safeLogError(TAG, "WebSocket error: $error")
            val h = clientHandle
            if (h != 0L) NativeBridge.triggerOnError(h, error)
        }
        delegate.onClose = { code, reason ->
            val h = clientHandle
            if (h != 0L) NativeBridge.triggerOnClose(h, code, reason)
        }

        delegate.connect(url, headers)
    }

    fun stateOrdinal(): Int = delegate.state().ordinal

    fun sendBytes(type: Int, data: ByteArray) {
        val msgType = if (type == 0) WebSocketMessageType.TEXT else WebSocketMessageType.BINARY
        delegate.send(msgType, data)
    }

    private fun parseHeadersJson(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val jsonElement = Json.parseToJsonElement(json)
            if (jsonElement is JsonObject) {
                jsonElement.mapValues { (_, value) ->
                    if (value is JsonPrimitive) value.content else value.toString()
                }
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
