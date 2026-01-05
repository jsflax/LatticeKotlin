package com.lattice

import com.lattice.capi.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*

/**
 * Native implementation of LatticeScheduler.
 * Dispatches observer callbacks to a coroutine dispatcher.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)
actual class LatticeScheduler actual constructor(
    private val dispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // StableRef to prevent GC
    private val stableRef = StableRef.create(this)

    // The native scheduler handle
    internal val nativeHandle: CPointer<lattice_scheduler_t>

    private var isClosed = false

    init {
        nativeHandle = lattice_scheduler_create(
            stableRef.asCPointer(),
            staticCFunction { contextPtr, callback, callbackContext ->
                // Get the scheduler from the stable ref
                val scheduler = contextPtr!!.asStableRef<LatticeScheduler>().get()

                // Dispatch the callback to the coroutine dispatcher
                scheduler.scope.launch {
                    // Call the C callback on the dispatcher's thread
                    callback?.invoke(callbackContext)
                }
            },
            staticCFunction { contextPtr ->
                // Destroy function - called when scheduler is released by C++
                // We handle cleanup in close() instead
            }
        ) ?: throw LatticeException("Failed to create scheduler")
    }

    /**
     * Release the scheduler resources.
     */
    actual fun close() {
        if (!isClosed) {
            isClosed = true
            scope.cancel()
            lattice_scheduler_release(nativeHandle)
            stableRef.dispose()
        }
    }
}
