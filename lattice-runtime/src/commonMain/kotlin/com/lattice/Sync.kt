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
