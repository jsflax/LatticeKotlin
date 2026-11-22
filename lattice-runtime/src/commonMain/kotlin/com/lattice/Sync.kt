package com.lattice

import kotlinx.serialization.Serializable

@LatticeEnum
enum class Operation {
    INSERT,
    UPDATE,
    DELETE
}

/**
 * Sync authentication mode (mirrors `lattice::sync_authentication`).
 */
enum class SyncAuthMode(val code: Int) {
    /** Requires non-empty [LatticeConfiguration.authorizationToken]. */
    BEARER_TOKEN(0),
    /** Transport manages credentials externally (e.g. custom headers / IPC). */
    TRANSPORT_MANAGED(1)
}

/**
 * Controls how WebSocket sync lifecycle is started and owned on a [Lattice] store.
 */
enum class BackgroundSyncMode {
    /** Start continuous WebSocket sync immediately when the store opens (default). */
    AUTOMATIC,
    /** Open local SQLite storage immediately without starting WebSocket sync until [Lattice.resumeBackgroundSync] or [Lattice.syncOnce]. */
    DEFERRED,
    /** Application/OS-managed background sync lifecycle (`background_sync_managed = true`). Starts suspended until [Lattice.resumeBackgroundSync] or [Lattice.syncOnce]. */
    MANAGED
}

/**
 * Outcome of one bounded finite sync attempt ([Lattice.syncOnce]).
 */
enum class SyncOnceResult {
    /** Pending upload prefix and initial server catch-up replay both completed. */
    COMPLETED,
    /** Time budget expired or the attempt was cancelled before completion was confirmed. */
    BUDGET_EXHAUSTED,
    /** Another foreground or finite sync session already owns sync on this store; left running. */
    ALREADY_RUNNING,
    /** Store is closed or sync ownership is currently unavailable. */
    UNAVAILABLE
}

/**
 * Snapshot of upload/download sync progress across attached synchronizers.
 */
data class SyncProgress(
    val pendingUpload: Long = 0L,
    val totalUpload: Long = 0L,
    val acked: Long = 0L,
    val received: Long = 0L
)

@Model
class AuditLog {
    var operation: Operation = Operation.INSERT
    var tableName: String = ""
    var rowId: Long = 0L
    var rowGlobalId: String = ""
    var timestamp: Long = 0L
    var changed: String = "{}"
}

@Serializable
sealed class ServerSentEvent {
    @Serializable
    data class FullModel(
        val tableName: String,
        val globalId: String,
        val data: String
    ) : ServerSentEvent()

    @Serializable
    data class ModelChange(
        val tableName: String,
        val operation: String,
        val globalId: String,
        val data: String
    ) : ServerSentEvent()
}

@Serializable
data class SyncResponse(
    val events: List<ServerSentEvent>,
    val checkpoint: String? = null
)
