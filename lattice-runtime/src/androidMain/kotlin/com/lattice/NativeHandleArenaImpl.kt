package com.lattice

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val defaultQueryDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual class ArenaLock actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}

/**
 * Android API 26+ compatible PhantomReference + daemon ReferenceQueue reaper
 * (avoids `java.lang.ref.Cleaner`, which requires Android API 33+).
 */
internal actual object NativePhantomReaper {
    private val queue = ReferenceQueue<Any>()
    private val liveRefs = ConcurrentHashMap.newKeySet<HandlePhantomRef>()

    private class HandlePhantomRef(
        referent: Any,
        queue: ReferenceQueue<Any>,
        val handle: Long,
        val isLinkList: Boolean,
        val arena: NativeHandleArena?
    ) : PhantomReference<Any>(referent, queue) {
        fun cleanup() {
            liveRefs.remove(this)
            if (isLinkList) {
                if (arena != null) arena.reapLinkList(handle)
                else NativeBridge.linkListRelease(handle)
            } else {
                if (arena != null) arena.reapObject(handle)
                else NativeBridge.releaseObject(handle)
            }
        }
    }

    init {
        val thread = Thread({
            while (true) {
                try {
                    val ref = queue.remove() as? HandlePhantomRef
                    ref?.cleanup()
                } catch (_: InterruptedException) {
                    // Daemon continues until JVM/ART process exits
                } catch (_: Throwable) {
                }
            }
        }, "Lattice-PhantomReaper")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY + 1
        thread.start()
    }

    actual fun registerObject(wrapper: Any, handle: Long, arena: NativeHandleArena?) {
        if (handle == 0L) return
        liveRefs.add(HandlePhantomRef(wrapper, queue, handle, isLinkList = false, arena = arena))
    }

    actual fun registerLinkList(wrapper: Any, listHandle: Long, arena: NativeHandleArena?) {
        if (listHandle == 0L) return
        liveRefs.add(HandlePhantomRef(wrapper, queue, listHandle, isLinkList = true, arena = arena))
    }
}
