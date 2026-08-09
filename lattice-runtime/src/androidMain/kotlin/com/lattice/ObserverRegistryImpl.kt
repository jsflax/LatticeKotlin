package com.lattice

/**
 * Guards [handleStates], every field of [HandleState] / [ObservationToken],
 * and the native observer calls made while it is held.
 *
 * Holding it across `NativeBridge.removeTableObserver` is deliberate: it is
 * what closes the window between "this handle is still open" and the JNI call
 * that dereferences it. [ObserverRegistry.invalidateObservers] takes the same
 * lock before `Lattice.close()` frees the database, so a cancel racing a close
 * either completes entirely before the close, or sees a closed handle and
 * does nothing.
 */
private val observerLock = Any()

/** Live registration state per native db handle. Guarded by [observerLock]. */
private val handleStates = HashMap<Long, HandleState>()

/**
 * Shared liveness flag for all observations on one database handle.
 *
 * Tokens hold a direct reference to their own state rather than looking the
 * handle up at cancel time, so a recycled native address (a new database
 * allocated where a closed one used to live) can never resurrect a stale
 * token or invalidate a fresh one.
 */
private class HandleState {
    var closed = false
    val tokens = HashSet<ObservationToken>()
}

private fun stateFor(dbHandle: Long): HandleState =
    handleStates.getOrPut(dbHandle) { HandleState() }

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

        // Register with native layer
        val contextHandle = NativeBridge.observeTable(dbHandle, tableName, nativeCallback)

        if (contextHandle == 0L) {
            throw LatticeException("Failed to register table observer")
        }

        return synchronized(observerLock) {
            val state = stateFor(dbHandle)
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

        // Register with native layer
        val contextHandle = NativeBridge.observeTable(dbHandle, tableName, nativeCallback)
        // Note: contextHandle (observer token) is intentionally not wrapped in a Cancellable here.
        // Callers track the token themselves (e.g. Lattice.auditLogObserverToken).
        return contextHandle
    }

    actual fun invalidateObservers(dbHandle: Long) {
        synchronized(observerLock) {
            // Drop the entry entirely: outstanding tokens keep their own
            // reference to the state, so they still see closed == true, while
            // a database later allocated at this same address starts clean.
            val state = handleStates.remove(dbHandle) ?: return
            state.closed = true
            state.tokens.clear()
        }
    }
}
