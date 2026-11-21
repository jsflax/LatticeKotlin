package com.lattice

/**
 * Defines a schema migration step.
 *
 * Additive column/table migrations use `Migration()` (empty [tableName]).
 * Row-transform migrations specify [tableName] and [transform].
 */
class Migration(
    val tableName: String = "",
    val transform: ((oldRow: Map<String, Any?>) -> Map<String, Any?>)? = null
)

/**
 * Legacy configuration alias retained for source compatibility; prefer [LatticeConfiguration].
 */
data class LatticeConfigurationWithMigration(
    val path: String? = null,
    val syncEndpoint: String? = null,
    val authorizationToken: String? = null,
    val targetSchemaVersion: Int = 1,
    val migrations: Map<Int, Migration> = emptyMap()
)
