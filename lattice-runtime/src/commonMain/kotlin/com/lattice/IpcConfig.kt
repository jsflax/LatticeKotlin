package com.lattice

import kotlinx.serialization.Serializable

/**
 * Configuration for IPC (Inter-Process Communication) sync.
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
 */
data class SyncFilter(
    val entries: List<SyncFilterEntry> = emptyList()
) {
    constructor(vararg entries: SyncFilterEntry) : this(entries.toList())

    companion object {
        /** Sync all tables EXCEPT [excludedTables] given [registeredTables]. */
        fun excluding(registeredTables: Iterable<String>, vararg excludedTables: String): SyncFilter {
            val excluded = excludedTables.toSet()
            return SyncFilter(
                registeredTables.filter { it !in excluded }.map { SyncFilterEntry(tableName = it) }
            )
        }
    }

    internal fun toJson(): String {
        val sb = StringBuilder("[")
        entries.forEachIndexed { idx, e ->
            if (idx > 0) sb.append(',')
            sb.append("{\"table\":\"").append(escapeFilterJson(e.tableName)).append("\"")
            val pred = e.whereClause
            if (!pred.isNullOrEmpty()) {
                sb.append(",\"predicate\":\"").append(escapeFilterJson(pred)).append("\"")
            }
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }
}

/**
 * A single entry in a sync filter.
 * Specifies a table name and optional SQL WHERE predicate for row-level filtering.
 */
@Serializable
data class SyncFilterEntry(
    val tableName: String,
    val whereClause: String? = null
)

private fun escapeFilterJson(s: String): String = buildString(s.length) {
    for (ch in s) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}
