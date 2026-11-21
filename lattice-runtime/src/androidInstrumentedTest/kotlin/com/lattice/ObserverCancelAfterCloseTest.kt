package com.lattice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Model used only by this test. */
@Model
class ObservedNote {
    var text: String = ""
}

/**
 * Regression tests for cancelling an observation token after its [Lattice]
 * has been closed.
 *
 * These have to run on a device: the failure they pin down was a native
 * use-after-free reached through the JNI bridge (removing a table observer
 * locked a mutex inside the freed `lattice_db`), which a JVM unit test cannot
 * reach. Being a use-after-free it does not fail cleanly — reverting the guard
 * and running this suite kills the test process on some builds and wedges it
 * in `pthread_mutex_lock` on others — so a run that simply finishes is a large
 * part of the assertion.
 *
 * The shape mirrors the production trigger: a session teardown closes the
 * lattice while mounted observers are still unmounting, so each observer's
 * disposal calls cancel() afterwards. Disposal order there is not something
 * the app author controls, so the library has to absorb both orderings.
 */
@RunWith(AndroidJUnit4::class)
class ObserverCancelAfterCloseTest {

    private val createdPaths = mutableListOf<String>()

    private fun newLattice(): Lattice {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val path = File(cacheDir, "observer_cancel_${System.nanoTime()}.sqlite").absolutePath
        createdPaths += path
        return Lattice(path, ObservedNote::class)
    }

    @After
    fun deleteDatabases() {
        createdPaths.forEach { Lattice.deleteDatabase(it) }
        createdPaths.clear()
    }

    /**
     * close() then cancel() — the crashing order, in the shape it arrives in
     * production: one store is closed and its replacement opened before the
     * first store's observers finish unmounting.
     *
     * Opening the second lattice matters. A use-after-free only reliably
     * misbehaves once the freed block has been handed back out, so allocating
     * a fresh database (and its observers) between the close and the cancels
     * is what turns "probably fine" into a real exercise of the guard. It is
     * also the case the registry has to get right: the new database can land
     * on the recycled address, and stale tokens must not be able to reach it.
     *
     * Nothing is asserted before the cancels — the dangerous call has to run.
     */
    @Test
    fun cancelAfterClose_isASafeNoOp() {
        val closed = newLattice()
        val staleTokens = (1..8).map { closed.objects(ObservedNote::class).observe { } }
        closed.close()

        val replacement = newLattice()
        val liveTokens = (1..8).map { replacement.objects(ObservedNote::class).observe { } }

        // The actual regression: this used to SIGSEGV in pthread_mutex_lock,
        // locking a mutex inside the freed lattice_db.
        staleTokens.forEach { it.cancel() }
        // ...and cancelling is idempotent, however many times unmount runs.
        staleTokens.forEach { it.cancel() }

        staleTokens.forEach { assertFalse("stale token must be inactive", it.isActive) }
        liveTokens.forEach { assertTrue("live token must survive a stale cancel", it.isActive) }

        // The replacement is still usable, and its own observers still fire.
        val fired = CountDownLatch(1)
        val watcher = replacement.objects(ObservedNote::class).observe { fired.countDown() }
        replacement.add(ObservedNote().apply { text = "after the churn" })
        assertTrue("replacement lattice still observes", fired.await(5, TimeUnit.SECONDS))

        watcher.cancel()
        liveTokens.forEach { it.cancel() }
        replacement.close()
    }

    /** A closed lattice's tokens report themselves inactive immediately. */
    @Test
    fun closeMarksOutstandingTokensInactive() {
        val lattice = newLattice()
        val tokens = (1..4).map { lattice.objects(ObservedNote::class).observe { } }
        tokens.forEach { assertTrue("token should start active", it.isActive) }

        lattice.close()

        tokens.forEach { assertFalse("close() must invalidate outstanding tokens", it.isActive) }
    }

    /** Closing twice after observers were registered must also stay quiet. */
    @Test
    fun closeIsIdempotentWithObserversOutstanding() {
        val lattice = newLattice()
        val token = lattice.objects(ObservedNote::class).observe { }

        lattice.close()
        lattice.close()

        token.cancel()
        token.cancel()
        assertFalse(token.isActive)
    }

    /**
     * cancel() then close() — the still-supported happy path. The observer has
     * to fire while it is live, stop firing once cancelled, and the later
     * close() must not disturb the already-cancelled token.
     */
    @Test
    fun cancelBeforeClose_stopsCallbacksAndStaysSupported() {
        val lattice = newLattice()
        val notes = lattice.objects(ObservedNote::class)

        val changes = AtomicInteger(0)
        val fired = CountDownLatch(1)
        val token = notes.observe {
            changes.incrementAndGet()
            fired.countDown()
        }

        lattice.add(ObservedNote().apply { text = "before cancel" })
        assertTrue("observer should fire while active", fired.await(5, TimeUnit.SECONDS))

        val seenWhileActive = changes.get()
        token.cancel()
        assertFalse("cancelled token is inactive", token.isActive)

        lattice.add(ObservedNote().apply { text = "after cancel" })
        assertEquals("cancelled observer must not fire", seenWhileActive, changes.get())

        lattice.close()
        token.cancel()
    }

    /**
     * The unordered case: cancels and the close run concurrently, so some
     * cancels land before the native database is freed and some after. This is
     * what the liveness check being taken under the same lock as close() buys
     * — without it there is a window where the check passes and the handle is
     * freed before the JNI call dereferences it.
     */
    @Test
    fun cancelRacingClose_doesNotCrash() {
        repeat(25) {
            val lattice = newLattice()
            val notes = lattice.objects(ObservedNote::class)
            val tokens = (1..8).map { notes.observe { } }

            val start = CountDownLatch(1)
            val threads = tokens.map { token ->
                Thread {
                    start.await()
                    token.cancel()
                }
            } + Thread {
                start.await()
                lattice.close()
            }

            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            threads.forEach { assertFalse("thread should have finished", it.isAlive) }

            tokens.forEach { assertFalse(it.isActive) }
        }
    }

    /**
     * observe() after close() must not touch the freed database — not even to
     * register. It returns a token that is inactive from the start.
     */
    @Test
    fun observeAfterClose_returnsInactiveToken() {
        val lattice = newLattice()
        lattice.close()

        val token = lattice.objects(ObservedNote::class).observe { }
        assertFalse("a token minted after close starts inactive", token.isActive)
        token.cancel()
        token.cancel()
    }

    /**
     * The registration-side race, mirror image of [cancelRacingClose_doesNotCrash]:
     * threads hammer observe() while another closes the lattice mid-stream. An
     * observe that lost the race used to register against a database close()
     * was freeing (the original use-after-free through the other door), and
     * minted fresh registry state over the dead handle — poisoning a future
     * database recycled at the same address. Now the liveness check and the
     * JNI registration happen under the registry lock, so every observe either
     * completes against the live database (and close then invalidates it) or
     * sees the closed handle and registers nothing.
     *
     * After close wins, no observer may be left registered: every token must
     * be inactive, and a replacement database must observe cleanly.
     */
    @Test
    fun observeRacingClose_doesNotCrashOrLeakRegistrations() {
        repeat(20) {
            val lattice = newLattice()
            val notes = lattice.objects(ObservedNote::class)

            val tokens = Collections.synchronizedList(mutableListOf<Cancellable>())
            val start = CountDownLatch(1)
            val hammering = CountDownLatch(1)

            val observers = (1..4).map {
                Thread {
                    start.await()
                    repeat(16) { i ->
                        tokens += notes.observe { }
                        if (i == 0) hammering.countDown()
                    }
                }
            }
            val closer = Thread {
                start.await()
                // Close only once registration is provably under way, so the
                // two sides genuinely overlap.
                hammering.await()
                lattice.close()
            }

            (observers + closer).forEach { it.start() }
            start.countDown()
            (observers + closer).forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            (observers + closer).forEach { assertFalse("thread should have finished", it.isAlive) }

            // Whether a token registered before the close (invalidated) or
            // after it (inert), it must be dead now, and cancelling it must
            // stay a no-op.
            tokens.forEach { assertFalse("no observer may survive close", it.isActive) }
            tokens.forEach { it.cancel() }

            // A replacement database — possibly allocated at the recycled
            // native address — must start unpoisoned.
            val replacement = newLattice()
            val fired = CountDownLatch(1)
            val watcher = replacement.objects(ObservedNote::class).observe { fired.countDown() }
            replacement.add(ObservedNote().apply { text = "still alive" })
            assertTrue("replacement lattice must observe cleanly", fired.await(5, TimeUnit.SECONDS))
            watcher.cancel()
            replacement.close()
        }
    }

    /**
     * close() racing close(): the open->closed transition happens under the
     * registry lock, so exactly one caller tears the native database down and
     * the others return without touching it. Double-frees here used to be
     * possible because the guard was a plain non-volatile Boolean.
     */
    @Test
    fun closeRacingClose_isSafe() {
        repeat(20) {
            val lattice = newLattice()
            val token = lattice.objects(ObservedNote::class).observe { }

            val start = CountDownLatch(1)
            val threads = (1..4).map {
                Thread {
                    start.await()
                    lattice.close()
                }
            }

            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            threads.forEach { assertFalse("thread should have finished", it.isAlive) }

            assertFalse(token.isActive)
            token.cancel()
        }
    }
}
