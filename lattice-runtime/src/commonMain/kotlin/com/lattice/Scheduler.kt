package com.lattice

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A scheduler that dispatches observer callbacks to a coroutine dispatcher.
 *
 * Example:
 * ```kotlin
 * val scheduler = LatticeScheduler(Dispatchers.Main)
 * val lattice = Lattice("/path/to/db", scheduler, Person::class)
 *
 * // Observation callbacks now run on Dispatchers.Main
 * results.observe { change ->
 *     // Safe to update UI here
 * }
 * ```
 */
expect class LatticeScheduler(dispatcher: CoroutineDispatcher) {
    /**
     * Release the scheduler resources.
     * This is called automatically when the Lattice is closed.
     */
    fun close()
}
