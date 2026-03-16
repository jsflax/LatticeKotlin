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
 */
interface Cancellable {
    /**
     * Cancel the observation.
     * After calling this, the observer callback will no longer be invoked.
     */
    fun cancel()

    /**
     * Whether this observation is still active.
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
}
