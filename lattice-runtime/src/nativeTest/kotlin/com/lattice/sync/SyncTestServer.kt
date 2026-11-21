package com.lattice.sync

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process WebSocket relay server for sync integration tests.
 *
 * Mirrors Swift's Vapor test server pattern:
 * - Starts on port 0 (OS assigns free port)
 * - On client message (auditLog): sends ACK, broadcasts to other clients
 * - On connect: optionally sends catch-up events
 *
 * Usage:
 * ```kotlin
 * val server = SyncTestServer()
 * val port = server.start()
 *
 * val client1 = Lattice(path = "...", syncEndpoint = "ws://localhost:$port/sync", ...)
 * val client2 = Lattice(path = "...", syncEndpoint = "ws://localhost:$port/sync", ...)
 *
 * // ... test sync ...
 *
 * server.stop()
 * ```
 */
class SyncTestServer {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    var port: Int = 0
        private set

    // Connected WebSocket sessions and retained catch-up frames for late joiners
    private val sessions = mutableListOf<DefaultWebSocketServerSession>()
    private val auditHistory = mutableListOf<String>()
    private val mutex = Mutex()

    /**
     * Start the relay server on a free port.
     * @return The assigned port number
     */
    fun start(): Int {
        val srv = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/sync") {
                    // Register this session and replay any prior auditLog frames
                    val catchUp = mutex.withLock {
                        sessions.add(this)
                        auditHistory.toList()
                    }
                    for (prior in catchUp) {
                        try {
                            send(Frame.Text(prior))
                        } catch (_: Exception) {}
                    }

                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> handleMessage(frame.readText(), this)
                                is Frame.Binary -> handleMessage(frame.readBytes().decodeToString(), this)
                                else -> {}
                            }
                        }
                    } finally {
                        // Unregister on disconnect
                        mutex.withLock { sessions.remove(this) }
                    }
                }
            }
        }.start(wait = false)
        server = srv

        // Read the assigned port from the engine
        // Give it a moment to bind, then read the resolved port
        platform.posix.usleep(500_000u) // 500ms
        port = kotlinx.coroutines.runBlocking {
            srv.engine.resolvedConnectors().firstOrNull()?.port ?: 0
        }

        if (port == 0) {
            stop()
            throw IllegalStateException("Server failed to bind to a port")
        }

        println("SyncTestServer started on port $port")
        return port
    }

    /**
     * Stop the relay server.
     */
    fun stop() {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        server = null
        println("SyncTestServer stopped")
    }

    /**
     * Get the WebSocket URL for connecting to this server.
     */
    fun wsUrl(path: String = "sync"): String {
        check(port > 0) { "Server not started" }
        return "ws://127.0.0.1:$port/$path"
    }

    /**
     * Handle an incoming message from a client.
     *
     * Protocol (matching Swift Vapor relay):
     * 1. Parse JSON, check "kind" field
     * 2. If auditLog: extract globalIds, send ACK back, broadcast to other clients
     * 3. If ack: just absorb (no relay needed)
     */
    private suspend fun handleMessage(text: String, sender: DefaultWebSocketServerSession) {
        try {
            // Quick JSON parse to get "kind"
            val isAuditLog = text.contains("\"kind\":\"auditLog\"") || text.contains("\"kind\": \"auditLog\"")
            val isAck = text.contains("\"kind\":\"ack\"") || text.contains("\"kind\": \"ack\"")

            if (isAuditLog) {
                // Extract globalIds for ACK (simple regex approach)
                val globalIds = Regex("\"globalId\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .toList()

                // Send ACK back to sender (before broadcast, matching Swift)
                if (globalIds.isNotEmpty()) {
                    val ackJson = buildAckJson(globalIds)
                    sender.send(Frame.Text(ackJson))
                }

                // Record for catch-up and broadcast to all OTHER connected clients
                val others = mutex.withLock {
                    auditHistory.add(text)
                    sessions.filter { it != sender }
                }
                for (session in others) {
                    try {
                        session.send(Frame.Text(text))
                    } catch (_: Exception) {
                        // Client disconnected, ignore
                    }
                }
            }
            // ACK messages are absorbed (not relayed)
        } catch (e: Exception) {
            println("SyncTestServer: Error handling message: ${e.message}")
        }
    }

    private fun buildAckJson(globalIds: List<String>): String {
        val idsArray = globalIds.joinToString(",") { "\"$it\"" }
        return """{"kind":"ack","ack":[$idsArray]}"""
    }
}
