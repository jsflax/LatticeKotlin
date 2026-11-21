package com.lattice

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

enum class WebSocketMessageType {
    TEXT,
    BINARY
}

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
    fun close() { disconnect() }
    fun state(): WebSocketState
    fun send(type: WebSocketMessageType, data: ByteArray)

    /**
     * Configure a lowercase 64-hex SHA-256 TLS leaf certificate pin before [connect].
     * Return true if this transport enforces the pin (or when [sha256Hex] is empty).
     */
    fun configureCertificatePin(sha256Hex: String): Boolean = sha256Hex.isEmpty()

    var onOpen: (() -> Unit)?
    var onMessage: ((type: WebSocketMessageType, data: ByteArray) -> Unit)?
    var onError: ((error: String) -> Unit)?
    var onClose: ((code: Int, reason: String) -> Unit)?
}

interface LatticeWebSocketFactory {
    fun createWebSocket(): LatticeWebSocket
}

/**
 * Ktor-based WebSocket implementation honoring LatticeCore 3.0.0+ receive-recovery
 * redials (calling [connect] while already OPEN without prior [disconnect]) and
 * per-connection SHA-256 leaf certificate pinning.
 */
class KtorWebSocket(
    private var client: HttpClient,
    private val scope: CoroutineScope,
    private val ownsClient: Boolean = false,
    private val ownsScope: Boolean = false
) : LatticeWebSocket {

    private val lock = ArenaLock()
    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var _state: WebSocketState = WebSocketState.CLOSED
    private var generation: Long = 0L
    private var activePinHex: String = ""
    private var sendChannel = Channel<Frame>(Channel.BUFFERED)

    override var onOpen: (() -> Unit)? = null
    override var onMessage: ((type: WebSocketMessageType, data: ByteArray) -> Unit)? = null
    override var onError: ((error: String) -> Unit)? = null
    override var onClose: ((code: Int, reason: String) -> Unit)? = null

    override fun configureCertificatePin(sha256Hex: String): Boolean {
        val normalized = sha256Hex.trim().lowercase()
        if (normalized == activePinHex) return true
        val pinnedClient = createHttpClientWithPin(normalized.ifEmpty { null }) ?: return false
        val oldClient = lock.withLock {
            val prev = if (ownsClient) client else null
            client = pinnedClient
            activePinHex = normalized
            prev
        }
        try {
            oldClient?.close()
        } catch (_: Exception) {}
        return true
    }

    override fun connect(url: String, headers: Map<String, String>) {
        // LatticeCore 3.0.0+ receive-recovery contract: connect() may be called while
        // OPEN/CONNECTING without a prior disconnect(). Supersede the old generation
        // atomically so its teardown never fires onClose/onError into the new dial.
        val (myGen, activeClient, mySendChannel) = lock.withLock {
            val myGenNext = ++generation
            val oldSession = session
            val oldJob = connectionJob
            val oldChannel = sendChannel
            session = null
            connectionJob = null
            oldChannel.close()
            sendChannel = Channel(Channel.BUFFERED)
            _state = WebSocketState.CONNECTING
            if (oldSession != null) {
                scope.launch {
                    try {
                        oldSession.close(CloseReason(CloseReason.Codes.NORMAL, "Redial"))
                    } catch (_: Exception) {}
                }
            }
            oldJob?.cancel()
            Triple(myGenNext, client, sendChannel)
        }

        val job = scope.launch {
            try {
                activeClient.webSocket(url, request = {
                    headers.forEach { (key, value) ->
                        this.headers.append(key, value)
                    }
                }) {
                    val stillCurrent = lock.withLock {
                        if (generation == myGen) {
                            session = this
                            _state = WebSocketState.OPEN
                            true
                        } else {
                            false
                        }
                    }
                    if (!stillCurrent) {
                        try { close(CloseReason(CloseReason.Codes.NORMAL, "Superseded")) } catch (_: Exception) {}
                        return@webSocket
                    }

                    onOpen?.invoke()

                    val senderJob = launch {
                        for (frame in mySendChannel) {
                            try {
                                send(frame)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val report = lock.withLock { generation == myGen }
                                if (report) onError?.invoke("Send failed: ${e.message}")
                            }
                        }
                    }

                    var sawCloseFrame = false
                    try {
                        for (frame in incoming) {
                            if (lock.withLock { generation != myGen }) break
                            when (frame) {
                                is Frame.Text -> {
                                    onMessage?.invoke(WebSocketMessageType.TEXT, frame.readBytes())
                                }
                                is Frame.Binary -> {
                                    onMessage?.invoke(WebSocketMessageType.BINARY, frame.readBytes())
                                }
                                is Frame.Close -> {
                                    sawCloseFrame = true
                                    val reason = closeReason.await()
                                    val fireClose = lock.withLock {
                                        if (generation == myGen) {
                                            _state = WebSocketState.CLOSED
                                            session = null
                                            true
                                        } else false
                                    }
                                    if (fireClose) {
                                        onClose?.invoke(reason?.code?.toInt() ?: 1000, reason?.message ?: "")
                                    }
                                }
                                else -> {}
                            }
                        }
                        if (!sawCloseFrame) {
                            val fireEofClose = lock.withLock {
                                if (generation == myGen && _state != WebSocketState.CLOSED) {
                                    _state = WebSocketState.CLOSED
                                    session = null
                                    true
                                } else false
                            }
                            if (fireEofClose) {
                                onClose?.invoke(1006, "Connection closed")
                            }
                        }
                    } finally {
                        senderJob.cancel()
                    }
                }
            } catch (_: CancellationException) {
                lock.withLock {
                    if (generation == myGen) {
                        _state = WebSocketState.CLOSED
                        session = null
                    }
                }
            } catch (e: Exception) {
                val fireError = lock.withLock {
                    if (generation == myGen) {
                        _state = WebSocketState.CLOSED
                        session = null
                        true
                    } else false
                }
                if (fireError) {
                    onError?.invoke(e.message ?: "Connection failed")
                }
            }
        }

        lock.withLock {
            if (generation == myGen) {
                connectionJob = job
            } else {
                job.cancel()
            }
        }
    }

    override fun disconnect() {
        val (oldSession, oldJob, oldChannel) = lock.withLock {
            generation++
            _state = WebSocketState.CLOSED
            val s = session
            val j = connectionJob
            val ch = sendChannel
            session = null
            connectionJob = null
            sendChannel = Channel(Channel.BUFFERED)
            Triple(s, j, ch)
        }
        oldChannel.close()
        if (oldSession != null) {
            scope.launch {
                try {
                    oldSession.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnect"))
                } catch (_: Exception) {}
            }
        }
        oldJob?.cancel()
    }

    override fun close() {
        disconnect()
        val clientToClose = lock.withLock {
            if (ownsClient) client else null
        }
        try {
            clientToClose?.close()
        } catch (_: Exception) {}
        if (ownsScope) {
            scope.cancel()
        }
    }

    override fun state(): WebSocketState = lock.withLock { _state }

    override fun send(type: WebSocketMessageType, data: ByteArray) {
        val frame = when (type) {
            WebSocketMessageType.TEXT -> Frame.Text(data.decodeToString())
            WebSocketMessageType.BINARY -> Frame.Binary(true, data)
        }
        val ch = lock.withLock { sendChannel }
        scope.launch {
            try {
                ch.send(frame)
            } catch (_: Exception) {}
        }
    }
}

expect fun createDefaultHttpClient(): HttpClient

/**
 * Creates a platform HttpClient enforcing [pinnedServerCertificateSha256] (lowercase 64-hex
 * SHA-256 of the server leaf certificate DER) when non-null, or returns null if the current
 * platform engine cannot enforce leaf certificate pinning.
 */
internal expect fun createHttpClientWithPin(pinnedServerCertificateSha256: String?): HttpClient?

class KtorWebSocketFactory : LatticeWebSocketFactory {
    override fun createWebSocket(): LatticeWebSocket {
        val client = createDefaultHttpClient()
        val scope = CoroutineScope(defaultQueryDispatcher + SupervisorJob())
        return KtorWebSocket(client, scope, ownsClient = true, ownsScope = true)
    }
}
