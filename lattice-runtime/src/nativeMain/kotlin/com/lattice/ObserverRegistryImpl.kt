package com.lattice

import com.lattice.capi.*
import kotlinx.cinterop.*
import kotlin.concurrent.AtomicInt
import platform.posix.sched_yield

/**
 * Guards [handleStates], every field of [HandleState] / [ObservationToken],
 * and the native observer calls made while it is held.
 *
 * Holding it across `lattice_db_observe_table` and
 * `lattice_db_remove_table_observer` is deliberate: it is what closes the
 * window between "this handle is still open" and the C call that dereferences
 * it — in both directions. [ObserverRegistry.invalidateObservers] takes the
 * same lock before `Lattice.close()` frees the database, so a cancel OR a
 * registration racing a close either completes entirely before the close, or
 * sees a closed handle and never touches native. Registration cannot use a
 * check-register-recheck scheme instead: undoing a registration that lost the
 * race would itself be a native call against a freed database.
 *
 * Lock order is singular: observerLock is always taken first, native calls
 * only happen inside it, and nothing native calls back into the registry.
 *
 * A spin lock rather than a pthread mutex: the critical sections are a handful
 * of map operations plus one C call, and contention only happens when a
 * database is torn down while observers are being registered or cancelled.
 */
private val observerLock = AtomicInt(0)

private inline fun <T> withObserverLock(block: () -> T): T {
    while (!observerLock.compareAndSet(0, 1)) {
        sched_yield()
    }
    try {
        return block()
    } finally {
        observerLock.value = 0
    }
}

/**
 * Live registration state per native db handle. Guarded by [observerLock].
 *
 * An entry exists exactly while the database is open: [ObserverRegistry.registerHandle]
 * creates it when `Lattice` opens the database, [ObserverRegistry.invalidateObservers]
 * removes it exactly once when the first close() wins. No entry means the
 * pointer must not be dereferenced.
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
 * Internal observation token that wraps the C++ observer.
 *
 * Cancellation is idempotent and close-aware: it no-ops when the registration
 * is already gone, and when the owning database has been closed.
 */
@OptIn(ExperimentalForeignApi::class)
private class ObservationToken(
    private val state: HandleState,
    private val db: CPointer<lattice_db_t>,
    private val tableName: String,
    private val observerToken: ULong,
    private val stableRef: StableRef<*>
) : Cancellable {
    /** Guarded by [observerLock]. */
    var registered = true

    override val isActive: Boolean
        get() = withObserverLock { registered && !state.closed }

    override fun cancel() {
        withObserverLock {
            // Already cancelled: nothing to remove.
            if (!registered) return@withObserverLock
            registered = false
            state.tokens.remove(this)
            // The database is gone. The native observer went with it, and the
            // pointer now refers to freed memory - do NOT dereference it.
            //
            // The StableRef is deliberately left alive: close() invalidates
            // registrations before it closes the database, so a mutation on
            // another thread can still fire the C callback in that window, and
            // disposing here would hand it a dead reference. Leaking it mirrors
            // the raw-observer path, which never disposes either.
            if (state.closed) return@withObserverLock
            lattice_db_remove_table_observer(db, tableName, observerToken)
            stableRef.dispose()
        }
    }
}

/**
 * Native implementation of ObserverRegistry using cinterop.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object ObserverRegistry {

    // Helper to convert Long to native pointer
    @Suppress("NOTHING_TO_INLINE")
    private inline fun Long.toNativePtr(): NativePtr = NativePtr.NULL + this

    private fun Long.toDbPtr(): CPointer<lattice_db_t>? {
        if (this == 0L) return null
        return interpretCPointer(this.toNativePtr())
    }

    actual fun registerHandle(dbHandle: Long) {
        if (dbHandle == 0L) return
        withObserverLock {
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
        val db = dbHandle.toDbPtr()
            ?: throw LatticeException("Invalid database handle")

        // Wrapper class to hold the callback
        class ObserverContext(val callback: (CollectionChange) -> Unit)

        val context = ObserverContext(callback)
        val stableRef = StableRef.create(context)

        return withObserverLock {
            // Liveness gate FIRST, native call second, both under the lock. A
            // handle with no state is closed (or was never opened): the native
            // database may already be freed, so it must not be dereferenced —
            // not even to register. Handing out a dead token here, rather than
            // minting fresh state over a dead handle, is also what keeps a
            // future database recycled at this address unpoisoned.
            val state = handleStates[dbHandle]
            if (state == null) {
                stableRef.dispose()
                return@withObserverLock InertToken
            }

            // Register C callback
            val token = lattice_db_observe_table(
                db,
                tableName,
                stableRef.asCPointer(),
                staticCFunction { contextPtr, operation, rowId, _ ->
                    val ctx = contextPtr!!.asStableRef<ObserverContext>().get()

                    // Convert C operation string to CollectionChange
                    val opStr = operation?.toKString() ?: return@staticCFunction

                    val change = when (opStr) {
                        "INSERT" -> CollectionChange.Insert(rowId)
                        "DELETE" -> CollectionChange.Delete(rowId)
                        "UPDATE" -> CollectionChange.Update(rowId)
                        else -> return@staticCFunction
                    }

                    ctx.callback(change)
                }
            )

            if (token == 0UL) {
                stableRef.dispose()
                throw LatticeException("Failed to register observer")
            }

            ObservationToken(state, db, tableName, token, stableRef)
                .also { state.tokens.add(it) }
        }
    }

    actual fun observeTableRaw(
        dbHandle: Long,
        tableName: String,
        callback: (operation: String, rowId: Long, globalId: String) -> Unit
    ): Long {
        val db = dbHandle.toDbPtr() ?: return 0L

        class RawObserverContext(val callback: (String, Long, String) -> Unit)

        val context = RawObserverContext(callback)
        val stableRef = StableRef.create(context)

        return withObserverLock {
            // Same liveness gate as observeTable: no state, no native call.
            if (handleStates[dbHandle] == null) {
                stableRef.dispose()
                return@withObserverLock 0L
            }

            val token = lattice_db_observe_table(
                db,
                tableName,
                stableRef.asCPointer(),
                staticCFunction { contextPtr, operation, rowId, globalId ->
                    val ctx = contextPtr!!.asStableRef<RawObserverContext>().get()
                    val opStr = operation?.toKString() ?: "UNKNOWN"
                    val gidStr = globalId?.toKString() ?: ""
                    ctx.callback(opStr, rowId, gidStr)
                }
            )

            if (token == 0UL) {
                stableRef.dispose()
                return@withObserverLock 0L
            }

            // The registration is NOT tracked in handleStates: nothing ever
            // removes a raw observer individually — it lives exactly as long
            // as the database and the native side tears it down when the
            // database closes. The StableRef is never disposed; it is one
            // small leak per raw observer (in practice one per Lattice, for
            // the AuditLog changeStream), accepted so a late C callback can
            // never fault on a dead reference.
            token.toLong()
        }
    }

    actual fun invalidateObservers(dbHandle: Long): Boolean {
        return withObserverLock {
            // Drop the entry entirely: outstanding tokens keep their own
            // reference to the state, so they still see closed == true, while
            // a database later allocated at this same address starts clean.
            // Removal doubles as the open->closed transition Lattice.close()
            // gates on: exactly one caller finds the entry present.
            val state = handleStates.remove(dbHandle) ?: return@withObserverLock false
            state.closed = true
            state.tokens.clear()
            true
        }
    }
}
