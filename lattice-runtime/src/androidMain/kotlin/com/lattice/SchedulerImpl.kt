package com.lattice

import kotlinx.coroutines.*

actual class LatticeScheduler actual constructor(
    private val dispatcher: CoroutineDispatcher
) {
    internal val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var isClosed = false
    internal actual val nativeHandlePtr: Long = 0L

    actual fun close() {
        if (!isClosed) {
            isClosed = true
            scope.cancel()
        }
    }
}
