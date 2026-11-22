package com.lattice

import com.lattice.sync.SyncTestServer
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@Model(tableName = "native_bgsync_tasks")
class NativeBgSyncTask {
    @Unique
    var taskKey: String = ""
    var summary: String = ""
    var done: Boolean = false
}

class Lattice313BackgroundSyncNativeTests {
    private val paths = mutableListOf<String>()
    private val dbs = mutableListOf<Lattice>()
    private var server: SyncTestServer? = null

    private fun freshPath(tag: String): String {
        val p = "/tmp/lattice_313_native_${tag}_${Random.nextLong().toULong().toString(16)}.sqlite"
        paths += p
        return p
    }

    @BeforeTest
    fun setup() {
        Lattice.registerFactory(NativeBgSyncTask::class) { NativeBgSyncTask() }
        Lattice.registerHydrationFactory(NativeBgSyncTask::class) { h ->
            NativeBgSyncTask(h, LatticeHydrationMarker)
        }
    }

    @AfterTest
    fun teardown() {
        dbs.asReversed().forEach { runCatching { it.close() } }
        dbs.clear()
        server?.stop()
        server = null
        paths.forEach { Lattice.deleteDatabase(it) }
        paths.clear()
    }

    @Test
    fun testManagedBackgroundSyncOnceAndLifecycleOverRealWebSocket() = runBlocking {
        val relay = SyncTestServer().also {
            it.start()
            server = it
        }

        // Peer A publishes an initial row over continuous sync, then disconnects
        val peerAPath = freshPath("peerA")
        val peerA = Lattice(
            LatticeConfiguration(
                path = peerAPath,
                syncEndpoint = relay.wsUrl(),
                authorizationToken = "native-token",
            ),
            NativeBgSyncTask::class,
        ).also { dbs += it }
        peerA.awaitSyncConnected(timeoutMs = 10_000)
        peerA.add(
            NativeBgSyncTask().apply {
                taskKey = "server-task-1"
                summary = "Seeded before background wake"
                done = false
            }
        )
        // Wait briefly for peerA upload ACK on relay
        var waited = 0
        while (peerA.syncProgress.acked < 1L && waited < 100) {
            kotlinx.coroutines.delay(30)
            waited++
        }
        peerA.close()

        // Peer B opens in MANAGED mode: starts suspended, stages an offline row, then runs finite syncOnce
        val peerBPath = freshPath("peerB")
        val peerB = Lattice(
            LatticeConfiguration(
                path = peerBPath,
                syncEndpoint = relay.wsUrl(),
                authorizationToken = "native-token",
                backgroundSyncManaged = true,
            ),
            NativeBgSyncTask::class,
        ).also { dbs += it }

        assertTrue(peerB.isBackgroundSyncManaged)
        assertEquals(0L, peerB.currentBackgroundSyncGeneration)
        assertFalse(peerB.isSyncConnected)

        peerB.add(
            NativeBgSyncTask().apply {
                taskKey = "mobile-offline-1"
                summary = "Queued while suspended"
                done = true
            }
        )

        val firstSync = peerB.syncOnce(maximumDurationSeconds = 10.0)
        assertEquals(SyncOnceResult.COMPLETED, firstSync)
        assertEquals(0L, peerB.currentBackgroundSyncGeneration)
        assertFalse(peerB.isSyncConnected)

        val keys = peerB.objects(NativeBgSyncTask::class).orderBy("taskKey").toList().map { it.taskKey }
        assertEquals(listOf("mobile-offline-1", "server-task-1"), keys)

        // Foreground resume -> ALREADY_RUNNING -> suspend -> syncOnce COMPLETED -> close -> UNAVAILABLE
        peerB.resumeBackgroundSync()
        assertTrue(peerB.currentBackgroundSyncGeneration > 0L)
        assertEquals(SyncOnceResult.ALREADY_RUNNING, peerB.syncOnce(maximumDurationSeconds = 5.0))

        peerB.suspendBackgroundSync()
        assertEquals(0L, peerB.currentBackgroundSyncGeneration)
        assertEquals(SyncOnceResult.COMPLETED, peerB.syncOnce(maximumDurationSeconds = 10.0))

        // Coroutine cancellation on a stalled WS endpoint settles promptly without leaking handles
        val stallPath = freshPath("stall")
        val stallDb = Lattice(
            LatticeConfiguration(
                path = stallPath,
                syncEndpoint = relay.wsUrl("stall"),
                authorizationToken = "native-token",
                backgroundSyncManaged = true,
            ),
            NativeBgSyncTask::class,
        ).also { dbs += it }
        assertEquals(SyncOnceResult.BUDGET_EXHAUSTED, stallDb.syncOnce(maximumDurationSeconds = 0.2))
        assertEquals(0L, stallDb.currentBackgroundSyncGeneration)

        val timedOut = withTimeoutOrNull(150L) {
            stallDb.syncOnce(maximumDurationSeconds = 15.0)
        }
        assertNull(timedOut)
        assertEquals(0L, stallDb.currentBackgroundSyncGeneration)

        // Immediate connection refusal surfaces LatticeException while still settling generation to 0
        val unreachablePath = freshPath("unreachable")
        val unreachableDb = Lattice(
            LatticeConfiguration(
                path = unreachablePath,
                syncEndpoint = "ws://127.0.0.1:1/sync",
                authorizationToken = "native-token",
                backgroundSyncManaged = true,
            ),
            NativeBgSyncTask::class,
        ).also { dbs += it }
        assertFailsWith<LatticeException> {
            unreachableDb.syncOnce(maximumDurationSeconds = 5.0)
        }
        assertEquals(0L, unreachableDb.currentBackgroundSyncGeneration)

        peerB.close()
        assertEquals(SyncOnceResult.UNAVAILABLE, peerB.syncOnce(maximumDurationSeconds = 5.0))
    }
}
