package com.lattice

import com.lattice.capi.*
import kotlinx.cinterop.*

/**
 * Bridge that wraps a Kotlin LatticeWebSocket for use by the C++ sync layer.
 *
 * Usage:
 * ```kotlin
 * // Create factory for your platform's WebSocket implementation
 * val factory = object : LatticeWebSocketFactory {
 *     override fun createWebSocket() = MyPlatformWebSocket()
 * }
 *
 * // Register globally
 * LatticeNetworkBridge.setGlobalFactory(factory)
 *
 * // Or register with specific database
 * val db = Lattice.open("path/to/db")
 * LatticeNetworkBridge.setDatabaseFactory(db, factory)
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
object LatticeNetworkBridge {

    // Track active WebSocket bridges to prevent garbage collection
    private val activeWebSockets = mutableMapOf<Long, WebSocketBridge>()
    private var nextBridgeId = 1L

    // Track factory for global use
    private var globalFactoryBridge: FactoryBridge? = null

    /**
     * Set the global network factory.
     * This factory will be used by all Lattice databases for sync.
     */
    fun setGlobalFactory(factory: LatticeWebSocketFactory) {
        // Clean up old factory if exists
        globalFactoryBridge?.release()

        val bridge = FactoryBridge(factory)
        globalFactoryBridge = bridge
        lattice_set_network_factory(bridge.cFactory)
    }

    /**
     * Set a network factory for a specific database.
     */
    fun setDatabaseFactory(dbHandle: Long, factory: LatticeWebSocketFactory) {
        val dbPtr = dbHandle.toDbPtr() ?: return
        val bridge = FactoryBridge(factory)
        // Note: This currently uses global factory under the hood
        lattice_db_set_network_factory(dbPtr, bridge.cFactory)
    }

    private fun Long.toDbPtr(): CPointer<lattice_db_t>? {
        if (this == 0L) return null
        return interpretCPointer(NativePtr.NULL + this)
    }

    /**
     * Internal bridge wrapping a Kotlin factory for C interop.
     */
    internal class FactoryBridge(private val factory: LatticeWebSocketFactory) {
        private val stableRef = StableRef.create(this)

        val cFactory: CPointer<lattice_network_factory_t>

        init {
            cFactory = lattice_network_factory_create(
                stableRef.asCPointer(),
                staticCFunction(::createWebSocketCallback),
                staticCFunction(::destroyFactoryCallback)
            ) ?: throw IllegalStateException("Failed to create network factory")
        }

        fun release() {
            lattice_network_factory_release(cFactory)
            stableRef.dispose()
        }

        fun createWebSocket(): CPointer<lattice_websocket_client_t>? {
            val ws = factory.createWebSocket()
            val bridge = WebSocketBridge(ws)
            return bridge.cClient
        }
    }

    /**
     * Internal bridge wrapping a Kotlin WebSocket for C interop.
     */
    internal class WebSocketBridge(private val webSocket: LatticeWebSocket) {
        private val stableRef = StableRef.create(this)
        private val bridgeId = nextBridgeId++

        val cClient: CPointer<lattice_websocket_client_t>

        init {
            // Store reference to prevent GC
            activeWebSockets[bridgeId] = this

            // Create C WebSocket client with our callbacks
            cClient = lattice_websocket_client_create(
                stableRef.asCPointer(),
                staticCFunction(::connectCallback),
                staticCFunction(::disconnectCallback),
                staticCFunction(::stateCallback),
                staticCFunction(::sendCallback)
            ) ?: throw IllegalStateException("Failed to create websocket client")

            // Wire up event handlers
            webSocket.onOpen = { triggerOnOpen() }
            webSocket.onMessage = { type, data -> triggerOnMessage(type, data) }
            webSocket.onError = { error -> triggerOnError(error) }
            webSocket.onClose = { code, reason -> triggerOnClose(code, reason) }
        }

        fun release() {
            activeWebSockets.remove(bridgeId)
            stableRef.dispose()
        }

        // Called by C++ when it wants to connect
        fun connect(url: String, headersJson: String) {
            val headers = parseHeaders(headersJson)
            webSocket.connect(url, headers)
        }

        // Called by C++ when it wants to disconnect
        fun disconnect() {
            webSocket.disconnect()
        }

        // Called by C++ when it queries state
        fun state(): lattice_websocket_state_t {
            return when (webSocket.state()) {
                WebSocketState.CONNECTING -> LATTICE_WS_CONNECTING
                WebSocketState.OPEN -> LATTICE_WS_OPEN
                WebSocketState.CLOSING -> LATTICE_WS_CLOSING
                WebSocketState.CLOSED -> LATTICE_WS_CLOSED
            }
        }

        // Called by C++ when it wants to send
        fun send(type: lattice_websocket_msg_type_t, data: ByteArray) {
            val msgType = if (type == LATTICE_WS_MSG_TEXT)
                WebSocketMessageType.TEXT else WebSocketMessageType.BINARY
            webSocket.send(msgType, data)
        }

        // Trigger C++ callbacks when we receive events
        private fun triggerOnOpen() {
            lattice_websocket_client_trigger_on_open(cClient)
        }

        private fun triggerOnMessage(type: WebSocketMessageType, data: ByteArray) {
            val msgType = if (type == WebSocketMessageType.TEXT)
                LATTICE_WS_MSG_TEXT else LATTICE_WS_MSG_BINARY
            data.usePinned { pinned ->
                lattice_websocket_client_trigger_on_message(
                    cClient,
                    msgType,
                    pinned.addressOf(0).reinterpret(),
                    data.size.toULong()
                )
            }
        }

        private fun triggerOnError(error: String) {
            lattice_websocket_client_trigger_on_error(cClient, error)
        }

        private fun triggerOnClose(code: Int, reason: String) {
            lattice_websocket_client_trigger_on_close(cClient, code, reason)
        }

        private fun parseHeaders(json: String): Map<String, String> {
            // Simple JSON object parsing: {"key":"value","key2":"value2"}
            if (json.isEmpty() || json == "{}") return emptyMap()

            val result = mutableMapOf<String, String>()
            try {
                // Remove braces
                val content = json.trim().removePrefix("{").removeSuffix("}")
                if (content.isEmpty()) return result

                // Split by comma (outside quotes)
                var i = 0
                while (i < content.length) {
                    // Skip whitespace
                    while (i < content.length && content[i].isWhitespace()) i++
                    if (i >= content.length) break

                    // Parse key
                    if (content[i] != '"') break
                    i++
                    val keyEnd = content.indexOf('"', i)
                    if (keyEnd < 0) break
                    val key = content.substring(i, keyEnd)
                    i = keyEnd + 1

                    // Skip colon and whitespace
                    while (i < content.length && (content[i] == ':' || content[i].isWhitespace())) i++

                    // Parse value
                    if (i >= content.length || content[i] != '"') break
                    i++
                    val valueEnd = content.indexOf('"', i)
                    if (valueEnd < 0) break
                    val value = content.substring(i, valueEnd)
                    i = valueEnd + 1

                    result[key] = value

                    // Skip comma and whitespace
                    while (i < content.length && (content[i] == ',' || content[i].isWhitespace())) i++
                }
            } catch (e: Exception) {
                // On parse error, return empty map
            }
            return result
        }
    }
}

// Static callback functions for C interop

@OptIn(ExperimentalForeignApi::class)
private fun createWebSocketCallback(userData: COpaquePointer?): CPointer<lattice_websocket_client_t>? {
    if (userData == null) return null
    val bridge = userData.asStableRef<LatticeNetworkBridge.FactoryBridge>().get()
    return bridge.createWebSocket()
}

@OptIn(ExperimentalForeignApi::class)
private fun destroyFactoryCallback(userData: COpaquePointer?) {
    if (userData == null) return
    val ref = userData.asStableRef<LatticeNetworkBridge.FactoryBridge>()
    ref.get().release()
    ref.dispose()
}

@OptIn(ExperimentalForeignApi::class)
private fun connectCallback(userData: COpaquePointer?, url: CPointer<ByteVar>?, headersJson: CPointer<ByteVar>?) {
    if (userData == null) return
    val bridge = userData.asStableRef<LatticeNetworkBridge.WebSocketBridge>().get()
    val urlStr = url?.toKString() ?: return
    val headers = headersJson?.toKString() ?: "{}"
    bridge.connect(urlStr, headers)
}

@OptIn(ExperimentalForeignApi::class)
private fun disconnectCallback(userData: COpaquePointer?) {
    if (userData == null) return
    val bridge = userData.asStableRef<LatticeNetworkBridge.WebSocketBridge>().get()
    bridge.disconnect()
}

@OptIn(ExperimentalForeignApi::class)
private fun stateCallback(userData: COpaquePointer?): lattice_websocket_state_t {
    if (userData == null) return LATTICE_WS_CLOSED
    val bridge = userData.asStableRef<LatticeNetworkBridge.WebSocketBridge>().get()
    return bridge.state()
}

@OptIn(ExperimentalForeignApi::class)
private fun sendCallback(userData: COpaquePointer?, type: lattice_websocket_msg_type_t, data: CPointer<UByteVar>?, dataSize: ULong) {
    if (userData == null) return
    val bridge = userData.asStableRef<LatticeNetworkBridge.WebSocketBridge>().get()

    val bytes = if (data != null && dataSize > 0u) {
        ByteArray(dataSize.toInt()) { i -> data[i].toByte() }
    } else {
        ByteArray(0)
    }

    bridge.send(type, bytes)
}
