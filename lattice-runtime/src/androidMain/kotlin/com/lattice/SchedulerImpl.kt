package com.lattice

import kotlinx.coroutines.*

/**
 * Android implementation of LatticeScheduler.
 * Dispatches observer callbacks to a coroutine dispatcher.
 *
 * Note: The native scheduler functionality requires JNI integration.
 * For now, this provides basic coroutine-based scheduling.
 */
actual class LatticeScheduler actual constructor(
    private val dispatcher: CoroutineDispatcher
) {
    internal val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var isClosed = false

    /**
     * Release the scheduler resources.
     */
    actual fun close() {
        if (!isClosed) {
            isClosed = true
            scope.cancel()
        }
    }
}
