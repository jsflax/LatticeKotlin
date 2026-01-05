package com.lattice

import kotlinx.serialization.Serializable

/**
 * Operation type for audit log entries.
 */
@LatticeEnum
enum class Operation {
    INSERT,
    UPDATE,
    DELETE
}

/**
 * AuditLog entry tracking database changes for sync.
 * Each INSERT, UPDATE, or DELETE creates an entry.
 */
@Model
class AuditLog {
    /** The type of operation: INSERT, UPDATE, or DELETE */
    var operation: Operation = Operation.INSERT

    /** The table name that was modified */
    var tableName: String = ""

    /** The local row ID of the modified object */
    var rowId: Long = 0L

    /** The global ID of the modified object (for sync identification) */
    var rowGlobalId: String = ""

    /** Unix timestamp when the change occurred (milliseconds) */
    var timestamp: Long = 0L

    /** JSON-encoded map of changed field names to their new values */
    var changed: String = "{}"
}

/**
 * Server-sent event types for sync protocol.
 */
@Serializable
sealed class ServerSentEvent {
    /**
     * Full model data sent when a client first connects or needs to resync.
     * Contains the complete JSON representation of an object.
     */
    @Serializable
    data class FullModel(
        val tableName: String,
        val globalId: String,
        val data: String  // JSON-encoded object data
    ) : ServerSentEvent()

    /**
     * Model change event sent when another client makes a modification.
     * Contains the change delta.
     */
    @Serializable
    data class ModelChange(
        val tableName: String,
        val operation: String,  // "INSERT", "UPDATE", or "DELETE"
        val globalId: String,
        val data: String  // JSON-encoded changed fields
    ) : ServerSentEvent()
}

/**
 * Response from the sync server containing events to apply.
 */
@Serializable
data class SyncResponse(
    val events: List<ServerSentEvent>,
    val checkpoint: String? = null
)
