package com.lattice

import kotlinx.coroutines.CoroutineDispatcher

expect class LatticeScheduler(dispatcher: CoroutineDispatcher) {
    internal val nativeHandlePtr: Long
    fun close()
}
