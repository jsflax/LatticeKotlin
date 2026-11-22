package com.lattice

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Model(tableName = "jni_bgsync_notes")
class JniBgSyncNote {
    @Unique
    var noteId: String = ""
    var title: String = ""
    var revision: Int = 0
}

private class InMemoryRelayWebSocketFactory(
    var emitCatchUpComplete: Boolean = true,
) : LatticeWebSocketFactory {
    private val worker = Executors.newSingleThreadExecutor()
    val replayFrames = CopyOnWriteArrayList<String>()
    val uploadedFrames = CopyOnWriteArrayList<String>()

    fun shutdown() {
        worker.shutdownNow()
    }

    override fun createWebSocket(): LatticeWebSocket = object : LatticeWebSocket {
        private val currentState = AtomicReference(WebSocketState.CLOSED)
        private val closed = AtomicBoolean(false)

        override var onOpen: (() -> Unit)? = null
        override var onMessage: ((WebSocketMessageType, ByteArray) -> Unit)? = null
        override var onError: ((String) -> Unit)? = null
        override var onClose: ((Int, String) -> Unit)? = null

        override fun connect(url: String, headers: Map<String, String>) {
            closed.set(false)
            currentState.set(WebSocketState.CONNECTING)
            val snapshot = replayFrames.toList()
            val sendMarker = emitCatchUpComplete
            worker.execute {
                if (closed.get()) return@execute
                currentState.set(WebSocketState.OPEN)
                onOpen?.invoke()
                for (frame in snapshot) {
                    if (closed.get()) return@execute
                    onMessage?.invoke(WebSocketMessageType.TEXT, frame.encodeToByteArray())
                }
                if (sendMarker && !closed.get()) {
                    onMessage?.invoke(
                        WebSocketMessageType.TEXT,
                        """{"catchUpComplete":true}""".encodeToByteArray(),
                    )
                }
            }
        }

        override fun disconnect() {
            closed.set(true)
            currentState.set(WebSocketState.CLOSED)
        }

        override fun state(): WebSocketState = currentState.get()

        override fun send(type: WebSocketMessageType, data: ByteArray) {
            if (closed.get()) return
            val payload = data.decodeToString()
            uploadedFrames.add(payload)
            runCatching {
                val root = Json.parseToJsonElement(payload).jsonObject
                val entries = root["auditLog"]?.jsonArray ?: return@runCatching
                replayFrames.add(payload)
                val ids = entries.mapNotNull { it.jsonObject["globalId"]?.jsonPrimitive?.content }
                if (ids.isNotEmpty()) {
                    val quoted = ids.joinToString(",") { "\"$it\"" }
                    val ackJson = """{"kind":"ack","ack":[$quoted]}"""
                    worker.execute {
                        if (!closed.get()) {
                            onMessage?.invoke(WebSocketMessageType.TEXT, ackJson.encodeToByteArray())
                        }
                    }
                }
            }
        }

        override fun close() {
            disconnect()
        }
    }
}

class LatticeJni313BackgroundSyncTest {
    private val tempFiles = mutableListOf<File>()
    private val openDbs = mutableListOf<Lattice>()
    private lateinit var relay: InMemoryRelayWebSocketFactory

    private fun freshDbFile(prefix: String): File {
        val f = File.createTempFile("lattice_jni_313_${prefix}_", ".sqlite")
        f.delete()
        tempFiles += f
        return f
    }

    @BeforeTest
    fun setUp() {
        relay = InMemoryRelayWebSocketFactory()
        LatticeNetworkBridge.setGlobalFactory(relay)
    }

    @AfterTest
    fun tearDown() {
        openDbs.asReversed().forEach { runCatching { it.close() } }
        openDbs.clear()
        relay.shutdown()
        LatticeNetworkBridge.initializeDefault()
        tempFiles.forEach { f ->
            Lattice.deleteDatabase(f.absolutePath)
            File("${f.absolutePath}.sync.lock").delete()
        }
        tempFiles.clear()
    }

    @Test
    fun `managed background sync starts suspended completes finite syncOnce off caller thread and settles`() = runBlocking {
        // Seed initial server catch-up frame from an isolated peer database via finite syncOnce
        val seedFile = freshDbFile("seed")
        val seedDb = Lattice(
            LatticeConfiguration(
                path = seedFile.absolutePath,
                syncEndpoint = "ws://relay.invalid/notes",
                authorizationToken = "bearer-test-token",
                backgroundSyncManaged = true,
            ),
            JniBgSyncNote::class,
        ).also { openDbs += it }
        seedDb.add(
            JniBgSyncNote().apply {
                noteId = "remote-1"
                title = "Synced from server"
                revision = 3
            }
        )
        assertEquals(SyncOnceResult.COMPLETED, seedDb.syncOnce(maximumDurationSeconds = 5.0))
        assertEquals(1, relay.replayFrames.size)
        seedDb.close()
        relay.uploadedFrames.clear()

        val clientFile = freshDbFile("client")
        val db = Lattice(
            LatticeConfiguration(
                path = clientFile.absolutePath,
                syncEndpoint = "ws://relay.invalid/notes",
                authorizationToken = "bearer-test-token",
                backgroundSyncManaged = true,
            ),
            JniBgSyncNote::class,
        ).also { openDbs += it }

        assertTrue(db.isBackgroundSyncManaged)
        assertEquals(BackgroundSyncMode.MANAGED, db.configuration.backgroundSyncMode)
        assertEquals(0L, db.currentBackgroundSyncGeneration)
        assertFalse(db.isSyncConnected)

        db.add(
            JniBgSyncNote().apply {
                noteId = "local-1"
                title = "Created offline"
                revision = 1
            }
        )

        val outcome = db.syncOnce(maximumDurationSeconds = 5.0)
        assertEquals(SyncOnceResult.COMPLETED, outcome)
        assertEquals(0L, db.currentBackgroundSyncGeneration)
        assertFalse(db.isSyncConnected)

        val notes = db.objects(JniBgSyncNote::class).orderBy("noteId").toList()
        assertEquals(listOf("local-1", "remote-1"), notes.map { it.noteId })
        assertTrue(relay.uploadedFrames.isNotEmpty())
    }

    @Test
    fun `resumeBackgroundSync suspendBackgroundSync alreadyRunning and unavailable contracts`() = runBlocking {
        val clientFile = freshDbFile("lifecycle")
        val db = Lattice(
            LatticeConfiguration(
                path = clientFile.absolutePath,
                syncEndpoint = "ws://relay.invalid/lifecycle",
                authorizationToken = "bearer-test-token",
                backgroundSyncMode = BackgroundSyncMode.MANAGED,
            ),
            JniBgSyncNote::class,
        ).also { openDbs += it }

        db.resumeBackgroundSync()
        val activeGen = db.currentBackgroundSyncGeneration
        assertTrue(activeGen > 0L)

        // While foreground sync owns a generation, syncOnce leaves it running and returns ALREADY_RUNNING
        assertEquals(SyncOnceResult.ALREADY_RUNNING, db.syncOnce(maximumDurationSeconds = 5.0))
        assertEquals(activeGen, db.currentBackgroundSyncGeneration)

        // Suspending foreground sync releases the generation while keeping the primary Lattice handle open
        db.suspendBackgroundSync()
        assertEquals(0L, db.currentBackgroundSyncGeneration)
        assertFalse(db.isClosed)

        // Finite syncOnce now runs cleanly on the retained suspended store
        assertEquals(SyncOnceResult.COMPLETED, db.syncOnce(maximumDurationSeconds = 5.0))
        assertEquals(0L, db.currentBackgroundSyncGeneration)

        // Invalid budgets throw LatticeException
        assertFailsWith<LatticeException> {
            db.syncOnce(maximumDurationSeconds = 0.0)
        }

        // Closed store returns UNAVAILABLE
        db.close()
        assertEquals(SyncOnceResult.UNAVAILABLE, db.syncOnce(maximumDurationSeconds = 5.0))
    }

    @Test
    fun `budget exhaustion and coroutine cancellation interrupt native syncOnce and settle generation`() = runBlocking {
        relay.emitCatchUpComplete = false
        val clientFile = freshDbFile("cancel")
        val db = Lattice(
            LatticeConfiguration(
                path = clientFile.absolutePath,
                syncEndpoint = "ws://relay.invalid/cancel",
                authorizationToken = "bearer-test-token",
                backgroundSyncManaged = true,
            ),
            JniBgSyncNote::class,
        ).also { openDbs += it }

        assertEquals(SyncOnceResult.BUDGET_EXHAUSTED, db.syncOnce(maximumDurationSeconds = 0.08))
        assertEquals(0L, db.currentBackgroundSyncGeneration)

        // Structured coroutine timeout cancels native sync_once well before the 15s budget
        val startNs = System.nanoTime()
        val timedOut = withTimeoutOrNull(120L) {
            db.syncOnce(maximumDurationSeconds = 15.0)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertNull(timedOut)
        assertTrue(elapsedMs < 3_000L, "Expected prompt cancellation settlement, took ${elapsedMs}ms")
        assertEquals(0L, db.currentBackgroundSyncGeneration)

        // Explicit Deferred cancellation also throws CancellationException after native settlement
        val deferred = async(Dispatchers.Default) {
            db.syncOnce(maximumDurationSeconds = 15.0)
        }
        while (db.currentBackgroundSyncGeneration == 0L) {
            delay(5L)
        }
        deferred.cancel()
        assertFailsWith<CancellationException> {
            deferred.await()
        }
        assertEquals(0L, db.currentBackgroundSyncGeneration)
    }
}
