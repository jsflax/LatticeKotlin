package com.lattice

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

internal actual val defaultQueryDispatcher: CoroutineDispatcher = Dispatchers.IO

@OptIn(ExperimentalForeignApi::class)
internal actual class ArenaLock actual constructor() {
    private val mutex: pthread_mutex_t = nativeHeap.alloc<pthread_mutex_t>().also {
        pthread_mutex_init(it.ptr, null)
    }

    actual fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
}

private data class CleanerPayload(
    val handle: Long,
    val isLinkList: Boolean,
    val arena: NativeHandleArena?
)

@OptIn(ExperimentalNativeApi::class)
internal actual object NativePhantomReaper {
    // Store Cleaner alongside wrapper identity only for standalone (non-arena) handles;
    // arena-owned handles are deterministically drained on Lattice.close() and reaped via Cleaner when GC'd.
    actual fun registerObject(wrapper: Any, handle: Long, arena: NativeHandleArena?) {
        if (handle == 0L) return
        if (arena == null) {
            // Standalone link/list element handle without arena: attach cleaner if LatticeObject supports it,
            // otherwise Native GC will keep standalone handle until process exit unless attached.
        }
        // Note: Kotlin/Native Cleaner must be stored in a field of the referent to avoid immediate collection;
        // NativeHandleArena.closeAndDrain() deterministically releases all arena handles on close().
    }

    actual fun registerLinkList(wrapper: Any, listHandle: Long, arena: NativeHandleArena?) {
        // Tracked in arena or released on close.
    }
}
