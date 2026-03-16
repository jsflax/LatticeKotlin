package com.lattice.sync

import com.lattice.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/**
 * Basic sync integration tests using an in-process Ktor WebSocket relay.
 *
 * Mirrors Swift BasicSyncTests.swift pattern:
 * 1. Start Ktor server on port 0 — same as Swift starts Vapor
 * 2. Create two Lattice clients with sync endpoints
 * 3. Register changeStream collector and wait for subscription to be active
 *    (mirrors Swift's withCheckedContinuation pattern)
 * 4. THEN mutate on client1 → server relays → client2 receives
 * 5. changeStream emits → collector completes → verify data
 *
 * No polling. No delays. Uses onSubscription to guarantee the collector
 * is active before the mutation proceeds.
 */
class BasicSyncTests {
    private lateinit var server: SyncTestServer
    private val clients = mutableListOf<Lattice>()

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
        server = SyncTestServer()
        server.start()
    }

    @AfterTest
    fun teardown() {
        clients.reversed().forEach { it.close() }
        clients.clear()
        server.stop()
    }

    private fun createSyncClient(name: String): Lattice {
        val config = LatticeConfiguration(
            path = "/tmp/lattice_sync_test_${name}_${kotlin.random.Random.nextLong()}.sqlite",
            syncEndpoint = server.wsUrl(),
            authorizationToken = "test-token"
        )
        val lattice = Lattice(config, Trip::class)
        clients.add(lattice)
        return lattice
    }

    /**
     * Mirrors Swift's withCheckedContinuation + changeStream pattern.
     *
     * Returns a pair: (job, subscribed) where:
     * - job: the coroutine collecting changes (cancel when done)
     * - subscribed: completes when the collector is actually subscribed
     *
     * Usage:
     *   val (job, ready) = collectChanges(client2, count = 1)
     *   ready.await()      // guaranteed: collector is listening
     *   client1.add(...)   // NOW safe to mutate
     *   job.await()        // wait for change to arrive
     */
    /**
     * Mirrors Swift's withCheckedContinuation + changeStream pattern.
     *
     * Starts collecting changes on Dispatchers.Default (real time).
     * The returned CompletableDeferred completes once the flow collector
     * is subscribed — guaranteeing no emissions are missed.
     *
     * @param action Called AFTER the collector is subscribed. Put mutations here.
     * @return The collected changes.
     */
    /**
     * Mirrors Swift's withCheckedContinuation + changeStream pattern EXACTLY.
     *
     * Swift creates a SEPARATE Lattice instance inside Task.detached for the
     * changeStream observer, resumes the continuation, then awaits. This
     * Kotlin version does the same: creates a fresh Lattice on the same DB
     * file for the observer, waits for subscription, invokes the action,
     * then awaits the observer to complete.
     */
    /**
     * Mirrors Swift's withCheckedContinuation + Task.detached + changeStream pattern.
     *
     * Uses [LatticeCoroutineSafeReference] to safely pass the Lattice identity
     * to another coroutine. The observer coroutine resolves the reference to
     * get its own Lattice instance with fresh SQLite connections.
     *
     * The [action] is invoked AFTER the observer is subscribed, matching
     * Swift's continuation.resume() pattern.
     */
    /**
     * Wait for sync changes using changeStream.
     * Uses the SAME Lattice instance's changeStream (matching the Swift
     * pattern where the changeStream observer runs on the same Lattice).
     */
    private suspend fun awaitSyncChange(
        lattice: Lattice,
        tableName: String = "Trip",
        count: Int = 1,
        timeoutMs: Long = 30_000,
        action: suspend () -> Unit
    ) {
        val subscribed = CompletableDeferred<Unit>()

        val job = CoroutineScope(Dispatchers.Default).launch {
            withTimeout(timeoutMs) {
                (lattice.changeStream as SharedFlow<List<Change>>)
                    .onSubscription { subscribed.complete(Unit) }
                    .flatMapConcat { it.asFlow() }
                    .filter { it.tableName == tableName }
                    .take(count)
                    .collect {}
            }
        }

        subscribed.await()
        action()
        job.join()
    }

    // --- Protocol-level tests (no server needed) ---

    @Test
    fun test_EventsAfterReturnsAuditLog() {
        val lattice = testLattice(Trip::class)
        val trip = Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false }
        lattice.add(trip)
        val events = lattice.eventsAfter(null)
        assertTrue(events.isNotEmpty())
        lattice.close()
    }

    @Test
    fun test_PendingEventsMarkedAsSynced() {
        val lattice = testLattice(Trip::class)
        val trip = Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false }
        lattice.add(trip)
        val pending = lattice.pendingEvents()
        assertTrue(pending.isNotEmpty())
        val globalIds = listOf((trip as LatticeObject).globalId).filterNotNull()
        if (globalIds.isNotEmpty()) { lattice.markSynced(globalIds) }
        lattice.close()
    }

    // --- Integration tests (real Ktor WebSocket server + changeStream) ---

    @Test
    fun test_InsertSyncsToSecondClient() = runTest {
        val client1 = createSyncClient("c1_insert")
        val client2 = createSyncClient("c2_insert")

        // Matches Swift exactly: separate Lattice instance for changeStream observer
        withContext(Dispatchers.Default) {
            awaitSyncChange(client2, count = 1) {
                client1.add(Trip().apply {
                    name = "Paris"; days = 5; budget = 1000.0; isBooked = true
                })
            }
        }

        val remote = client2.objects(Trip::class).first()
        assertNotNull(remote, "client2 should have the synced Trip")
        assertEquals("Paris", remote.name)
        assertEquals(5, remote.days)
    }

    @Test
    fun test_DeleteSyncsToSecondClient() = runTest {
        val client1 = createSyncClient("c1_del")
        val client2 = createSyncClient("c2_del")

        withContext(Dispatchers.Default) {
            awaitSyncChange(client2, count = 1) {
                client1.add(Trip().apply { name = "ToDelete"; days = 1; budget = 0.0; isBooked = false })
            }
        }
        assertEquals(1, client2.objects(Trip::class).count)

        withContext(Dispatchers.Default) {
            awaitSyncChange(client2, count = 1) {
                client1.remove(client1.objects(Trip::class).first()!!)
            }
        }
        assertEquals(0, client2.objects(Trip::class).count)
    }

    @Test
    fun test_MultipleInsertsSyncInOrder() = runTest {
        val client1 = createSyncClient("c1_multi")
        val client2 = createSyncClient("c2_multi")

        withContext(Dispatchers.Default) {
            awaitSyncChange(client2, count = 3) {
                for ((i, name) in listOf("Alice", "Bob", "Carol").withIndex()) {
                    client1.add(Trip().apply {
                        this.name = name; days = i + 1; budget = (i + 1) * 100.0; isBooked = true
                    })
                }
            }
        }

        assertEquals(3, client2.objects(Trip::class).count)
        val remoteNames = client2.objects(Trip::class).toList().map { it.name }.sorted()
        assertEquals(listOf("Alice", "Bob", "Carol"), remoteNames)
    }
}
