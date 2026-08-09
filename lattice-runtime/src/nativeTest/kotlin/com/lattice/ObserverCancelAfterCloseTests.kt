package com.lattice

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
