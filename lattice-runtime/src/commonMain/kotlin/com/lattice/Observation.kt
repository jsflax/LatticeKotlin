package com.lattice

/**
 * Represents a change to a collection of objects.
 * Mirrors Swift's Results.CollectionChange.
 */
sealed class CollectionChange {
    /**
     * An object was inserted.
     * @param rowId The primary key of the inserted object
     */
    data class Insert(val rowId: Long) : CollectionChange()

    /**
     * An object was deleted.
     * @param rowId The primary key of the deleted object
     */
    data class Delete(val rowId: Long) : CollectionChange()

    /**
     * An object was updated.
     * @param rowId The primary key of the updated object
     */
    data class Update(val rowId: Long) : CollectionChange()
}

/**
 * A token that can be used to cancel an observation by calling [cancel].
 *
 * Mirrors Swift's AnyCancellable pattern, with one deliberate difference:
 * there is no deinit-style auto-cancel. See the retention contract below.
 *
 * ### Lifetime contract
 *
 * Tokens may be cancelled after the [Lattice] they came from is closed.
 * Cancelling a token whose lattice is closed is a safe no-op, as is
 * cancelling the same token twice. Callers therefore never have to
 * order their teardown against [Lattice.close] — which matters on UI
 * frameworks where a store may be released before the views observing
 * it are unmounted.
 *
 * ### Retention contract
 *
 * The observer registry holds a strong reference to every active token
 * (and its callback) until the token is cancelled or the owning [Lattice]
 * is closed. Garbage collection never cancels an observation: dropping the
 * last reference to a token without calling [cancel] leaves the observer
 * registered — and its callback firing — until the lattice closes.
 */
interface Cancellable {
    /**
     * Cancel the observation.
     * After calling this, the observer callback will no longer be invoked.
     *
     * Idempotent and close-aware: calling it more than once, or after the
     * owning [Lattice] has been closed, is a no-op.
     */
    fun cancel()

    /**
     * Whether this observation is still active.
     *
     * Becomes `false` once the token is cancelled *or* the owning [Lattice]
     * is closed.
     */
    val isActive: Boolean
}

/**
 * Platform-specific observation registry.
 * Handles registering table observers with the native layer.
 */
internal expect object ObserverRegistry {
    /**
     * Declare [dbHandle] as a live, open database.
     *
     * Called by the [Lattice] constructor as soon as the native database
     * exists, before any observer can be registered against it. Everything
     * else the registry does keys off this liveness entry: [observeTable] and
     * [observeTableRaw] refuse to touch native for a handle that has no entry
     * (it is either closed or was never opened — in both cases the pointer
     * must not be dereferenced), and [invalidateObservers] removes the entry
     * exactly once. Registering a handle resets any state a previous database
     * left behind, so a native allocation recycled at the same address starts
     * clean.
     */
    fun registerHandle(dbHandle: Long)

    /**
     * Register an observer for a table.
     *
     * The liveness check and the native registration happen together under
     * the registry lock — the same lock [invalidateObservers] takes — so a
     * registration can never race [Lattice.close] into a freed database. If
     * the handle is already closed (or was never registered), no native call
     * is made and an already-inactive no-op token is returned.
     *
     * @param dbHandle The database handle
     * @param tableName The table to observe
     * @param whereClause Optional filter (for future use)
     * @param callback The callback to invoke on changes
     * @return A cancellable token; inactive from the start when the handle
     *         is already closed
     */
    fun observeTable(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        callback: (CollectionChange) -> Unit
    ): Cancellable

    /**
     * Register a raw table observer that receives operation, rowId, globalId strings.
     * Used for AuditLog observation (changeStream).
     *
     * Registers under the same lock and liveness gate as [observeTable], so it
     * cannot race a close into a freed database either. Unlike [observeTable],
     * the registration is NOT tracked: no [Cancellable] is handed out,
     * [invalidateObservers] does not know about it, and it is only torn down
     * natively when the database itself is closed.
     *
     * @return Observer token (0 on failure, or when the handle is closed)
     */
    fun observeTableRaw(
        dbHandle: Long,
        tableName: String,
        callback: (operation: String, rowId: Long, globalId: String) -> Unit
    ): Long

    /**
     * Transition [dbHandle] from open to closed, invalidating every
     * [Cancellable] token handed out by [observeTable] for it. Raw observers
     * from [observeTableRaw] are not tracked here — they are torn down
     * natively with the database itself.
     *
     * Called by [Lattice.close] *before* the native database is closed and
     * released. Every outstanding token for this handle is marked dead while
     * the registry lock is held, so a later `cancel()` can never forward the
     * now-dangling handle across the native boundary (which would lock a
     * mutex inside freed memory — a hard SIGSEGV).
     *
     * The ordering matters: token liveness checks, native de-registrations
     * ([Cancellable.cancel]) and native registrations ([observeTable],
     * [observeTableRaw]) all happen under the registry lock, so any of them
     * that passed the liveness check has finished its native call before this
     * function returns, and every one started after it sees a dead handle and
     * never touches native.
     *
     * @return `true` if this call performed the open→closed transition —
     *         exactly one caller per registered handle ever sees `true`.
     *         [Lattice.close] uses this as its idempotence gate: only the
     *         winner tears the native database down, making double-close and
     *         close-racing-close safe under the same lock that guards
     *         observation.
     */
    fun invalidateObservers(dbHandle: Long): Boolean
}
