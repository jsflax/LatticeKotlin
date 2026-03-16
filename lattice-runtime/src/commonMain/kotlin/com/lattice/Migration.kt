package com.lattice

import kotlin.reflect.KClass

/**
 * Defines a schema migration between two model versions.
 *
 * Migrations are used when the database schema changes between versions.
 * The C++ backend handles column additions/removals automatically, but
 * data transformations (e.g., splitting a name field into first/last)
 * require explicit migration blocks.
 *
 * Example:
 * ```kotlin
 * // V1 model
 * @Model class PersonV1 {
 *     var name: String = ""
 *     var age: String = ""  // Stored as String in V1
 * }
 *
 * // V2 model
 * @Model class PersonV2 {
 *     var name: String = ""
 *     var age: Int = 0  // Changed to Int in V2
 * }
 *
 * val migration = Migration(
 *     tableName = "Person",
 *     transform = { oldRow ->
 *         val ageStr = oldRow["age"] as? String ?: "0"
 *         mapOf("age" to ageStr.toIntOrNull() ?: 0)
 *     }
 * )
 *
 * val lattice = Lattice(
 *     path = dbPath,
 *     migration = mapOf(2 to migration),
 *     PersonV2::class
 * )
 * ```
 */
class Migration(
    /** The table name being migrated. */
    val tableName: String,
    /** Transform function: receives old row values, returns new values to set. */
    val transform: ((oldRow: Map<String, Any?>) -> Map<String, Any?>)? = null
)

/**
 * Configuration for Lattice database with migration support.
 */
data class LatticeConfigurationWithMigration(
    val path: String? = null,
    val syncEndpoint: String? = null,
    val authorizationToken: String? = null,
    val targetSchemaVersion: Int = 1,
    val migrations: Map<Int, Migration> = emptyMap()
)
