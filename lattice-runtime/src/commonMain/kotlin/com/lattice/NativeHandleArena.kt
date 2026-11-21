package com.lattice

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Thrown when an operation races against or follows [Lattice.close].
 */
class LatticeClosedException(message: String = "Lattice database is closed") : LatticeException(message)

/**
 * Default background dispatcher for query execution and row-cache materialization
 * (`Dispatchers.IO` on Android/JVM and Native).
 */
internal expect val defaultQueryDispatcher: CoroutineDispatcher

/**
 * Platform mutex used by [NativeHandleArena] and [ObserverRegistry].
 */
internal expect class ArenaLock() {
    fun <T> withLock(block: () -> T): T
}

/**
 * Platform phantom-reference reaper (`PhantomReference` + daemon `ReferenceQueue`
 * on Android API 26+, `Cleaner` on Kotlin/Native) that releases native
 * `lattice_object_t*` and `lattice_link_list_t*` handles when wrapper objects
 * become unreachable before [Lattice.close], without ever closing `lattice_db_t*`.
 */
internal expect object NativePhantomReaper {
    fun registerObject(wrapper: Any, handle: Long, arena: NativeHandleArena?)
    fun registerLinkList(wrapper: Any, listHandle: Long, arena: NativeHandleArena?)
}

/**
 * Thread-safe per-[Lattice] arena of live `lattice_object_t*` and `lattice_link_list_t*`
 * handles. Resolves Issue #3 (`issuedObjectHandles` race against `close()`) and bounds
 * long-session memory via [NativePhantomReaper].
 */
internal class NativeHandleArena {
    private val lock = ArenaLock()
    private val objectHandles = HashSet<Long>()
    private val linkListHandles = HashSet<Long>()
    private var closed = false

    val isClosed: Boolean
        get() = lock.withLock { closed }

    /**
     * Adopts [newHandle] onto [obj], releasing any superseded unmanaged handle first.
     * If this arena is already closed, [newHandle] is immediately released and
     * [LatticeClosedException] is thrown.
     */
    fun <T : LatticeObject> adoptObject(obj: T, newHandle: Long): T {
        if (newHandle == 0L) return obj
        val prior = obj._latticeHandle
        if (prior != 0L && prior != newHandle) {
            NativeBridge.releaseObject(prior)
        }
        val accepted = lock.withLock {
            if (closed) {
                false
            } else {
                objectHandles.add(newHandle)
                true
            }
        }
        if (!accepted) {
            obj._latticeHandle = 0L
            NativeBridge.releaseObject(newHandle)
            throw LatticeClosedException()
        }
        obj._latticeHandle = newHandle
        NativePhantomReaper.registerObject(obj, newHandle, this)
        return obj
    }

    fun trackLinkList(wrapper: Any, listHandle: Long): Boolean {
        if (listHandle == 0L) return true
        val accepted = lock.withLock {
            if (closed) {
                false
            } else {
                linkListHandles.add(listHandle)
                true
            }
        }
        if (!accepted) {
            NativeBridge.linkListRelease(listHandle)
            return false
        }
        NativePhantomReaper.registerLinkList(wrapper, listHandle, this)
        return true
    }

    fun reapObject(handle: Long) {
        if (handle == 0L) return
        val shouldRelease = lock.withLock {
            !closed && objectHandles.remove(handle)
        }
        if (shouldRelease) {
            NativeBridge.releaseObject(handle)
        }
    }

    fun reapLinkList(listHandle: Long) {
        if (listHandle == 0L) return
        val shouldRelease = lock.withLock {
            !closed && linkListHandles.remove(listHandle)
        }
        if (shouldRelease) {
            NativeBridge.linkListRelease(listHandle)
        }
    }

    fun closeAndDrain() {
        val drained = lock.withLock {
            if (closed) {
                null
            } else {
                closed = true
                val o = objectHandles.toLongArray()
                val l = linkListHandles.toLongArray()
                objectHandles.clear()
                linkListHandles.clear()
                o to l
            }
        } ?: return
        val (objs, lists) = drained
        for (h in lists) {
            NativeBridge.linkListRelease(h)
        }
        for (h in objs) {
            NativeBridge.releaseObject(h)
        }
    }
}
