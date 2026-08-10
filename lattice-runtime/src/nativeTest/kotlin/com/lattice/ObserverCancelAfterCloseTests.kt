package com.lattice

import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.posix.sched_yield

/**
 * Regression tests for cancelling an observation token after its [Lattice]
 * has been closed.
 *
 * The Android instrumented suite covers the same contract over the JNI bridge
 * (where it was originally reported as a SIGSEGV); this is the cinterop side
 * of the same guard, where `lattice_db_remove_table_observer` would otherwise
 * dereference a freed database. A passing run is most of the assertion: an
 * unguarded cancel takes the whole test process down.
 */
class ObserverCancelAfterCloseTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @AfterTest
    fun tearDown() {
        cleanupTestLattices()
    }

    /**
     * close() then cancel() — the crashing order, with a replacement database
     * opened in between so the freed block has been handed back out by the
     * time the stale tokens are cancelled. Nothing is asserted before the
     * cancels: the dangerous call has to run.
     */
    @Test
    fun test_cancelAfterClose_isSafeNoOp() {
        val closed = testLattice(Person::class)
        val staleTokens = (1..4).map { closed.objects(Person::class).observe { } }
        closed.close()

        val replacement = testLattice(Person::class)
        val liveTokens = (1..4).map { replacement.objects(Person::class).observe { } }

        staleTokens.forEach { it.cancel() }
        staleTokens.forEach { it.cancel() }

        staleTokens.forEach { assertFalse(it.isActive, "stale token must be inactive") }
        liveTokens.forEach { assertTrue(it.isActive, "live token must survive a stale cancel") }

        var fired = 0
        val watcher = replacement.objects(Person::class).observe { fired++ }
        replacement.add(Person().apply { name = "after the churn" })
        assertTrue(fired > 0, "replacement lattice still observes")

        watcher.cancel()
        liveTokens.forEach { it.cancel() }
        replacement.close()
    }

    /** A closed lattice's tokens report themselves inactive immediately. */
    @Test
    fun test_closeMarksOutstandingTokensInactive() {
        val lattice = testLattice(Person::class)
        val tokens = (1..4).map { lattice.objects(Person::class).observe { } }
        tokens.forEach { assertTrue(it.isActive, "token should start active") }

        lattice.close()

        tokens.forEach { assertFalse(it.isActive, "close() must invalidate outstanding tokens") }
    }

    /** cancel() then close() — the still-supported happy path. */
    @Test
    fun test_cancelBeforeClose_stopsCallbacks() {
        val lattice = testLattice(Person::class)
        val people = lattice.objects(Person::class)

        var changes = 0
        val token = people.observe { changes++ }

        lattice.add(Person().apply { name = "before cancel" })
        assertTrue(changes > 0, "observer should fire while active")

        val seenWhileActive = changes
        token.cancel()
        assertFalse(token.isActive, "cancelled token is inactive")

        lattice.add(Person().apply { name = "after cancel" })
        assertEquals(seenWhileActive, changes, "cancelled observer must not fire")

        lattice.close()
        token.cancel()
    }

    /** Closing twice with observers outstanding must also stay quiet. */
    @Test
    fun test_closeIsIdempotentWithObserversOutstanding() {
        val lattice = testLattice(Person::class)
        val token = lattice.objects(Person::class).observe { }

        lattice.close()
        lattice.close()

        token.cancel()
        token.cancel()
        assertFalse(token.isActive)
    }

    /**
     * observe() after close() must not touch the freed database — not even to
     * register. It returns a token that is inactive from the start.
     */
    @Test
    fun test_observeAfterClose_returnsInertToken() {
        val lattice = testLattice(Person::class)
        lattice.close()

        val token = lattice.objects(Person::class).observe { }
        assertFalse(token.isActive, "a token minted after close starts inactive")
        token.cancel()
        token.cancel()
    }

    /**
     * The registration-side race, mirror image of the cancel race: one worker
     * hammers observe() while this thread closes the lattice mid-stream. An
     * observe that loses the race used to register against a database that
     * close() was freeing (the original use-after-free through the other
     * door), and minted fresh registry state over the dead handle — poisoning
     * a future database recycled at the same address. Now the liveness check
     * and the native registration happen under the registry lock, so every
     * observe either completes against the live database (and close then
     * invalidates it) or sees the closed handle and registers nothing.
     *
     * After close wins, no observer may be left registered: every token from
     * the hammering worker must be inactive, and a replacement database must
     * observe cleanly.
     */
    @OptIn(ObsoleteWorkersApi::class)
    @Test
    fun test_observeRacingClose_doesNotCrashOrLeakRegistrations() {
        val worker = Worker.start()
        try {
            repeat(20) {
                val lattice = testLattice(Person::class)
                val results = lattice.objects(Person::class)

                val started = AtomicInt(0)
                val future = worker.execute(
                    TransferMode.SAFE,
                    { Pair(results, started) }
                ) { (r, startedFlag) ->
                    val tokens = ArrayList<Cancellable>(64)
                    repeat(64) { i ->
                        tokens += r.observe { }
                        if (i == 0) startedFlag.value = 1
                    }
                    tokens
                }

                // Close only once the hammering is provably under way, so the
                // two sides genuinely overlap.
                while (started.value == 0) sched_yield()
                lattice.close()

                val tokens = future.result
                // Whether a token registered before the close (invalidated) or
                // after it (inert), it must be dead now, and cancelling it must
                // stay a no-op.
                tokens.forEach { assertFalse(it.isActive, "no observer may survive close") }
                tokens.forEach { it.cancel() }

                // A replacement database — possibly allocated at the recycled
                // native address — must start unpoisoned.
                val replacement = testLattice(Person::class)
                var fired = 0
                val watcher = replacement.objects(Person::class).observe { fired++ }
                replacement.add(Person().apply { name = "still alive" })
                assertTrue(fired > 0, "replacement lattice must observe cleanly")
                watcher.cancel()
                replacement.close()
            }
        } finally {
            worker.requestTermination().result
        }
    }

    /**
     * close() racing close(): the open->closed transition happens under the
     * registry lock, so exactly one caller tears the native database down and
     * the other returns without touching it. Double-frees here used to be
     * possible because the guard was a plain non-volatile Boolean.
     */
    @OptIn(ObsoleteWorkersApi::class)
    @Test
    fun test_closeRacingClose_isSafe() {
        val worker = Worker.start()
        try {
            repeat(20) {
                val lattice = testLattice(Person::class)
                val token = lattice.objects(Person::class).observe { }

                val go = AtomicInt(0)
                val future = worker.execute(
                    TransferMode.SAFE,
                    { Pair(lattice, go) }
                ) { (l, goFlag) ->
                    while (goFlag.value == 0) sched_yield()
                    l.close()
                }

                go.value = 1
                lattice.close()
                future.result

                assertFalse(token.isActive)
                token.cancel()
            }
        } finally {
            worker.requestTermination().result
        }
    }
}
