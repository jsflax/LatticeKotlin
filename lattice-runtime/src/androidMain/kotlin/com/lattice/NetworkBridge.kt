package com.lattice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.*

private const val TAG = "LatticeSync"

/**
 * Android implementation of the network bridge for sync.
 *
 * This bridges between the Kotlin Ktor WebSocket and the C++ sync layer via JNI.
 */
object LatticeNetworkBridge {

    private var factoryHandle: Long = 0

    /**
     * Set the global network factory for sync.
     */
    fun setGlobalFactory(factory: LatticeWebSocketFactory) {
        Log.d(TAG, "setGlobalFactory called")
        // Wrap the factory to create JNI-compatible WebSockets
        val jniFactory = JniWebSocketFactory(factory)
        factoryHandle = NativeBridge.createNetworkFactory(jniFactory)
        Log.d(TAG, "createNetworkFactory returned handle: $factoryHandle")
        NativeBridge.setGlobalNetworkFactory(factoryHandle)
        Log.d(TAG, "setGlobalNetworkFactory completed")
    }

    /**
     * Initialize with the default Ktor-based factory.
     */
    fun initializeDefault() {
        Log.d(TAG, "initializeDefault called")
        setGlobalFactory(KtorWebSocketFactory())
    }
}

/**
 * Factory wrapper that creates JNI-compatible WebSocket wrappers.
 */
internal class JniWebSocketFactory(
    private val delegate: LatticeWebSocketFactory
) : LatticeWebSocketFactory {
    override fun createWebSocket(): LatticeWebSocket {
        Log.d(TAG, "JniWebSocketFactory.createWebSocket called")
        val ws = JniWebSocketWrapper(delegate.createWebSocket())
        Log.d(TAG, "JniWebSocketWrapper created")
        return ws
    }
}

/**
 * Wrapper around LatticeWebSocket that provides JNI-friendly method signatures.
 *
 * The JNI layer calls these specific methods:
 * - connectWithHeaders(url: String, headersJson: String)
 * - disconnect()
 * - stateOrdinal(): Int
 * - sendBytes(type: Int, data: ByteArray)
 */
internal class JniWebSocketWrapper(
    private val delegate: LatticeWebSocket
) : LatticeWebSocket by delegate {

    private var clientHandle: Long = 0

    fun setClientHandle(handle: Long) {
        Log.d(TAG, "setClientHandle: $handle")
        clientHandle = handle
    }

    /**
     * Connect with headers as JSON string (called by JNI).
     */
    fun connectWithHeaders(url: String, headersJson: String) {
        Log.d(TAG, "connectWithHeaders: url=$url, headers=$headersJson, clientHandle=$clientHandle")
        val headers = parseHeadersJson(headersJson)

        // Set up event handlers to trigger C++ callbacks
        delegate.onOpen = {
            Log.d(TAG, "onOpen triggered, clientHandle=$clientHandle")
            if (clientHandle != 0L) {
                NativeBridge.triggerOnOpen(clientHandle)
            }
        }
        delegate.onMessage = { type, data ->
            Log.d(TAG, "onMessage: type=$type, size=${data.size}")
            if (clientHandle != 0L) {
                val typeOrdinal = if (type == WebSocketMessageType.TEXT) 0 else 1
                NativeBridge.triggerOnMessage(clientHandle, typeOrdinal, data)
            }
        }
        delegate.onError = { error ->
            Log.e(TAG, "onError: $error")
            if (clientHandle != 0L) {
                NativeBridge.triggerOnError(clientHandle, error)
            }
        }
        delegate.onClose = { code, reason ->
            Log.d(TAG, "onClose: code=$code, reason=$reason")
            if (clientHandle != 0L) {
                NativeBridge.triggerOnClose(clientHandle, code, reason)
            }
        }

        delegate.connect(url, headers)
    }

    /**
     * Get state as ordinal (called by JNI).
     */
    fun stateOrdinal(): Int {
        val state = delegate.state()
        Log.d(TAG, "stateOrdinal: $state (${state.ordinal})")
        return state.ordinal
    }

    /**
     * Send with type as int (called by JNI).
     */
    fun sendBytes(type: Int, data: ByteArray) {
        Log.d(TAG, "sendBytes: type=$type, size=${data.size}")
        val msgType = if (type == 0) WebSocketMessageType.TEXT else WebSocketMessageType.BINARY
        delegate.send(msgType, data)
    }

    private fun parseHeadersJson(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()

        return try {
            val jsonElement = Json.parseToJsonElement(json)
            if (jsonElement is JsonObject) {
                jsonElement.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> value.content
                        else -> value.toString()
                    }
                }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
