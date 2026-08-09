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
 * A token that can be used to cancel an observation.
 * The observation is automatically cancelled when this token is collected
 * or when cancel() is called explicitly.
 *
 * Mirrors Swift's AnyCancellable pattern.
 *
 * ### Lifetime contract
 *
 * Tokens may be cancelled after the [Lattice] they came from is closed.
 * Cancelling a token whose lattice is closed is a safe no-op, as is
 * cancelling the same token twice. Callers therefore never have to
 * order their teardown against [Lattice.close] — which matters on UI
 * frameworks where a store may be released before the views observing
 * it are unmounted.
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
     * Register an observer for a table.
     *
     * @param dbHandle The database handle
     * @param tableName The table to observe
     * @param whereClause Optional filter (for future use)
     * @param callback The callback to invoke on changes
     * @return A cancellable token
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
     * @return Observer token (0 on failure)
     */
    fun observeTableRaw(
        dbHandle: Long,
        tableName: String,
        callback: (operation: String, rowId: Long, globalId: String) -> Unit
    ): Long

    /**
     * Invalidate every observation still registered against [dbHandle].
     *
     * Called by [Lattice.close] *before* the native database is closed and
     * released. Every outstanding [Cancellable] handed out for this handle is
     * marked dead while the registry lock is held, so a later `cancel()` can
     * never forward the now-dangling handle across the native boundary
     * (which would lock a mutex inside freed memory — a hard SIGSEGV).
     *
     * The ordering matters: because a token's liveness check and its native
     * de-registration happen together under the registry lock, any `cancel()`
     * that passed the check has finished its native call before this function
     * returns, and every `cancel()` after it is a no-op.
     */
    fun invalidateObservers(dbHandle: Long)
}
