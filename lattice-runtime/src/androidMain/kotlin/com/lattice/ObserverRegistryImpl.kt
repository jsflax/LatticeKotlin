package com.lattice

/**
 * Guards [handleStates], every field of [HandleState] / [ObservationToken],
 * and the native observer calls made while it is held.
 *
 * Holding it across `NativeBridge.observeTable` and
 * `NativeBridge.removeTableObserver` is deliberate: it is what closes the
 * window between "this handle is still open" and the JNI call that
 * dereferences it — in both directions. [ObserverRegistry.invalidateObservers]
 * takes the same lock before `Lattice.close()` frees the database, so a cancel
 * OR a registration racing a close either completes entirely before the close,
 * or sees a closed handle and never touches native. Registration cannot use a
 * check-register-recheck scheme instead: undoing a registration that lost the
 * race would itself be a native call against a freed database.
 *
 * Lock order is singular: observerLock is always taken first, native calls
 * only happen inside it, and nothing native calls back into the registry.
 */
private val observerLock = Any()

/**
 * Live registration state per native db handle. Guarded by [observerLock].
 *
 * An entry exists exactly while the database is open: [ObserverRegistry.registerHandle]
 * creates it when `Lattice` opens the database, [ObserverRegistry.invalidateObservers]
 * removes it exactly once when the first close() wins. No entry means the
 * handle must not be handed to JNI.
 */
private val handleStates = HashMap<Long, HandleState>()

/**
 * Shared liveness flag for all observations on one database handle.
 *
 * Tokens hold a direct reference to their own state rather than looking the
 * handle up at cancel time, so a recycled native address (a new database
 * allocated where a closed one used to live) can never resurrect a stale
 * token or invalidate a fresh one.
 *
 * [tokens] is a strong set: active tokens (and the callbacks they carry) are
 * retained here until cancelled or until the database closes. GC never
 * cancels an observation.
 */
private class HandleState {
    var closed = false
    val tokens = HashSet<ObservationToken>()
}

/**
 * Returned when observe() arrives after close() has already invalidated the
 * handle: nothing was registered natively, so there is nothing to cancel.
 */
private object InertToken : Cancellable {
    override fun cancel() {}
    override val isActive: Boolean get() = false
}

/**
 * Internal observation token that wraps the JNI observer.
 *
 * Cancellation is idempotent and close-aware: it no-ops when the registration
 * is already gone, and when the owning database has been closed.
 */
private class ObservationToken(
    private val state: HandleState,
    private val dbHandle: Long,
    private val tableName: String,
    private val contextHandle: Long
) : Cancellable {
    /** Guarded by [observerLock]. */
    var registered = true

    override val isActive: Boolean
        get() = synchronized(observerLock) { registered && !state.closed }

    override fun cancel() {
        synchronized(observerLock) {
            // Already cancelled: nothing to remove.
            if (!registered) return
            registered = false
            state.tokens.remove(this)
            // The database is gone. The native observer went with it, and the
            // handle now points at freed memory - do NOT hand it to JNI.
            if (state.closed) return
            NativeBridge.removeTableObserver(dbHandle, tableName, contextHandle)
        }
    }
}

/**
 * Android JNI implementation of ObserverRegistry.
 *
 * Uses JNI to register observers with the native Lattice library.
 * Callbacks from C are dispatched back to Kotlin through the JNI bridge.
 */
internal actual object ObserverRegistry {

    actual fun registerHandle(dbHandle: Long) {
        if (dbHandle == 0L) return
        synchronized(observerLock) {
            // A fresh database always starts with fresh state, even if the
            // native allocator recycled the address of a closed one.
            handleStates[dbHandle] = HandleState()
        }
    }

    actual fun observeTable(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        callback: (CollectionChange) -> Unit
    ): Cancellable {
        if (dbHandle == 0L) {
            throw LatticeException("Invalid database handle")
        }

        // Create the native callback that converts operation strings to CollectionChange
        val nativeCallback: (String, Long, String?) -> Unit = { operation, rowId, _ ->
            val change = when (operation) {
                "INSERT" -> CollectionChange.Insert(rowId)
                "DELETE" -> CollectionChange.Delete(rowId)
                "UPDATE" -> CollectionChange.Update(rowId)
                else -> null
            }
            change?.let { callback(it) }
        }

        return synchronized(observerLock) {
            // Liveness gate FIRST, native call second, both under the lock. A
            // handle with no state is closed (or was never opened): the native
            // database may already be freed, so it must not cross the JNI
            // boundary — not even to register. Handing out a dead token here,
            // rather than minting fresh state over a dead handle, is also what
            // keeps a future database recycled at this address unpoisoned.
            val state = handleStates[dbHandle] ?: return InertToken
            val contextHandle = NativeBridge.observeTable(dbHandle, tableName, nativeCallback)
            if (contextHandle == 0L) {
                throw LatticeException("Failed to register table observer")
            }
            ObservationToken(state, dbHandle, tableName, contextHandle)
                .also { state.tokens.add(it) }
        }
    }

    actual fun observeTableRaw(
        dbHandle: Long,
        tableName: String,
        callback: (operation: String, rowId: Long, globalId: String) -> Unit
    ): Long {
        if (dbHandle == 0L) return 0L

        // Create the native callback that forwards raw operation strings
        val nativeCallback: (String, Long, String?) -> Unit = { operation, rowId, globalId ->
            callback(operation, rowId, globalId ?: "")
        }

        return synchronized(observerLock) {
            // Same liveness gate as observeTable: no state, no JNI call.
            if (handleStates[dbHandle] == null) return 0L
            // The token is NOT tracked in handleStates: nothing ever removes a
            // raw observer individually. It lives exactly as long as the
            // database and is torn down natively when the database closes —
            // invalidateObservers neither knows about it nor needs to.
            NativeBridge.observeTable(dbHandle, tableName, nativeCallback)
        }
    }

    actual fun invalidateObservers(dbHandle: Long): Boolean {
        synchronized(observerLock) {
            // Drop the entry entirely: outstanding tokens keep their own
            // reference to the state, so they still see closed == true, while
            // a database later allocated at this same address starts clean.
            // Removal doubles as the open->closed transition Lattice.close()
            // gates on: exactly one caller finds the entry present.
            val state = handleStates.remove(dbHandle) ?: return false
            state.closed = true
            state.tokens.clear()
            return true
        }
    }
}
