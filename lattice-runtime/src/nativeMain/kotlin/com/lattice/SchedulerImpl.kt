package com.lattice

import com.lattice.capi.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*

@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)
actual class LatticeScheduler actual constructor(
    private val dispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val stableRef = StableRef.create(this)
    internal val nativeHandle: CPointer<lattice_scheduler_t>
    internal actual val nativeHandlePtr: Long
    private var isClosed = false

    init {
        nativeHandle = lattice_scheduler_create(
            stableRef.asCPointer(),
            staticCFunction { contextPtr, callback, callbackContext ->
                val scheduler = contextPtr!!.asStableRef<LatticeScheduler>().get()
                scheduler.scope.launch {
                    callback?.invoke(callbackContext)
                }
            },
            staticCFunction { _ -> }
        ) ?: throw LatticeException("Failed to create scheduler")
        nativeHandlePtr = nativeHandle.rawValue.toLong()
    }

    actual fun close() {
        if (!isClosed) {
            isClosed = true
            scope.cancel()
            lattice_scheduler_release(nativeHandle)
            stableRef.dispose()
        }
    }
}
