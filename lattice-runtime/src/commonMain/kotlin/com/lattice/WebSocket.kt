package com.lattice

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach

/**
 * WebSocket message type.
 */
enum class WebSocketMessageType {
    TEXT,
    BINARY
}

/**
 * WebSocket state.
 */
enum class WebSocketState {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED
}

/**
 * Interface for WebSocket clients used by Lattice sync.
 */
interface LatticeWebSocket {
    fun connect(url: String, headers: Map<String, String> = emptyMap())
    fun disconnect()
    fun state(): WebSocketState
    fun send(type: WebSocketMessageType, data: ByteArray)

    var onOpen: (() -> Unit)?
    var onMessage: ((type: WebSocketMessageType, data: ByteArray) -> Unit)?
    var onError: ((error: String) -> Unit)?
    var onClose: ((code: Int, reason: String) -> Unit)?
}

/**
 * Factory for creating WebSocket instances.
 */
interface LatticeWebSocketFactory {
    fun createWebSocket(): LatticeWebSocket
}

/**
 * Ktor-based WebSocket implementation.
 */
class KtorWebSocket(
    private val client: HttpClient,
    private val scope: CoroutineScope
) : LatticeWebSocket {

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var _state: WebSocketState = WebSocketState.CLOSED

    private val sendChannel = Channel<Frame>(Channel.BUFFERED)

    override var onOpen: (() -> Unit)? = null
    override var onMessage: ((type: WebSocketMessageType, data: ByteArray) -> Unit)? = null
    override var onError: ((error: String) -> Unit)? = null
    override var onClose: ((code: Int, reason: String) -> Unit)? = null

    override fun connect(url: String, headers: Map<String, String>) {
        println("KtorWebSocket: connect called, url=$url")
        if (_state == WebSocketState.CONNECTING || _state == WebSocketState.OPEN) {
            println("KtorWebSocket: already connecting/open, returning")
            return
        }

        _state = WebSocketState.CONNECTING
        println("KtorWebSocket: state=CONNECTING, launching coroutine")

        connectionJob = scope.launch {
            try {
                println("KtorWebSocket: calling client.webSocket...")
                client.webSocket(url, request = {
                    headers.forEach { (key, value) ->
                        this.headers.append(key, value)
                    }
                }) {
                    println("KtorWebSocket: webSocket block entered, connection open")
                    session = this
                    _state = WebSocketState.OPEN
                    println("KtorWebSocket: invoking onOpen callback")
                    onOpen?.invoke()

                    // Launch sender coroutine
                    val senderJob = launch {
                        sendChannel.consumeEach { frame ->
                            try {
                                println("KtorWebSocket: sending frame: $frame")
                                send(frame)
                            } catch (e: Exception) {
                                println("KtorWebSocket: send error: ${e.message}")
                                onError?.invoke("Send failed: ${e.message}")
                            }
                        }
                    }

                    // Receive messages
                    println("KtorWebSocket: starting receive loop")
                    try {
                        for (frame in incoming) {
                            println("KtorWebSocket: received frame: ${frame::class.simpleName}")
                            when (frame) {
                                is Frame.Text -> {
                                    val bytes = frame.readBytes()
                                    val text = bytes.decodeToString()
                                    println("KtorWebSocket: TEXT frame, size=${bytes.size}, content=$text")
                                    onMessage?.invoke(WebSocketMessageType.TEXT, bytes)
                                }
                                is Frame.Binary -> {
                                    val bytes = frame.readBytes()
                                    println("KtorWebSocket: BINARY frame, size=${bytes.size}")
                                    onMessage?.invoke(WebSocketMessageType.BINARY, bytes)
                                }
                                is Frame.Close -> {
                                    println("KtorWebSocket: CLOSE frame received")
                                    val reason = closeReason.await()
                                    _state = WebSocketState.CLOSED
                                    onClose?.invoke(reason?.code?.toInt() ?: 1000, reason?.message ?: "")
                                }
                                else -> {
                                    println("KtorWebSocket: other frame type: ${frame::class.simpleName}")
                                }
                            }
                        }
                    } finally {
                        println("KtorWebSocket: receive loop ended")
                        senderJob.cancel()
                    }
                }
            } catch (e: Exception) {
                println("KtorWebSocket: connection error: ${e.message}")
                e.printStackTrace()
                _state = WebSocketState.CLOSED
                onError?.invoke(e.message ?: "Connection failed")
            }
        }
    }

    override fun disconnect() {
        _state = WebSocketState.CLOSING
        scope.launch {
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnect"))
            } catch (e: Exception) {
                // Ignore close errors
            }
            connectionJob?.cancel()
            session = null
            _state = WebSocketState.CLOSED
        }
    }

    override fun state(): WebSocketState = _state

    override fun send(type: WebSocketMessageType, data: ByteArray) {
        val frame = when (type) {
            WebSocketMessageType.TEXT -> Frame.Text(data.decodeToString())
            WebSocketMessageType.BINARY -> Frame.Binary(true, data)
        }
        scope.launch {
            sendChannel.send(frame)
        }
    }
}

/**
 * Default factory using Ktor.
 */
expect fun createDefaultHttpClient(): HttpClient

class KtorWebSocketFactory(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : LatticeWebSocketFactory {

    private val client: HttpClient by lazy { createDefaultHttpClient() }

    override fun createWebSocket(): LatticeWebSocket {
        return KtorWebSocket(client, scope)
    }
}
