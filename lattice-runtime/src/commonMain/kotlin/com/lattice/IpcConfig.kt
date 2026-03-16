package com.lattice

/**
 * Configuration for IPC (Inter-Process Communication) sync.
 *
 * IPC sync allows multiple processes on the same machine to keep
 * their databases synchronized via Unix domain sockets.
 *
 * Example:
 * ```kotlin
 * val config = LatticeConfiguration(
 *     path = "app.db",
 * )
 * val ipcTargets = listOf(
 *     IpcTarget(channel = "com.myapp.shared"),
 *     IpcTarget(channel = "com.myapp.extension", socketPath = "/tmp/myapp.sock")
 * )
 * ```
 */
data class IpcTarget(
    /** Channel name for IPC sync. */
    val channel: String,
    /** Optional explicit socket path. When set, bypasses automatic resolution. */
    val socketPath: String? = null,
    /** Optional sync filter for this IPC target. */
    val syncFilter: SyncFilter? = null
)

/**
 * Sync filter for controlling which tables/rows are synced.
 *
 * Example:
 * ```kotlin
 * val filter = SyncFilter(
 *     entries = listOf(
 *         SyncFilterEntry(tableName = "Person"),
 *         SyncFilterEntry(tableName = "Dog", whereClause = "age > 5")
 *     )
 * )
 * ```
 */
data class SyncFilter(
    val entries: List<SyncFilterEntry> = emptyList()
)

/**
 * A single entry in a sync filter.
 * Specifies a table name and optional WHERE clause for row-level filtering.
 */
data class SyncFilterEntry(
    val tableName: String,
    val whereClause: String? = null
)
