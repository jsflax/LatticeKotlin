package com.lattice

import kotlin.test.*

/**
 * Tests for migration support.
 * Port of Swift MigrationTests.swift.
 *
 * NOTE: Full migration tests require compiler plugin support for generating
 * old-schema descriptors. These are placeholder/unit tests for the Migration class.
 */
class MigrationTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_MigrationClassCreation() {
        val migration = Migration(
            tableName = "Person",
            transform = { oldRow ->
                val ageStr = oldRow["age"] as? String ?: "0"
                mapOf("age" to (ageStr.toIntOrNull() ?: 0))
            }
        )

        assertEquals("Person", migration.tableName)
        assertNotNull(migration.transform)
    }

    @Test
    fun test_MigrationTransform() {
        val migration = Migration(
            tableName = "Person",
            transform = { oldRow ->
                val firstName = oldRow["firstName"] as? String ?: ""
                val lastName = oldRow["lastName"] as? String ?: ""
                mapOf("fullName" to "$firstName $lastName")
            }
        )

        val oldRow = mapOf<String, Any?>("firstName" to "John", "lastName" to "Doe")
        val newValues = migration.transform!!(oldRow)
        assertEquals("John Doe", newValues["fullName"])
    }

    @Test
    fun test_MigrationWithoutTransform() {
        // Schema-only migration (column add/remove handled automatically by C++)
        val migration = Migration(tableName = "Person")
        assertEquals("Person", migration.tableName)
        assertNull(migration.transform)
    }

    @Test
    fun test_MigrationConfigCreation() {
        val config = LatticeConfigurationWithMigration(
            path = "test.db",
            targetSchemaVersion = 3,
            migrations = mapOf(
                2 to Migration("Person", transform = { it }),
                3 to Migration("Person", transform = { it })
            )
        )

        assertEquals("test.db", config.path)
        assertEquals(3, config.targetSchemaVersion)
        assertEquals(2, config.migrations.size)
    }

    @Test
    fun test_MigrationWithNullTransform() {
        val m = Migration(tableName = "Item", transform = null)
        assertEquals("Item", m.tableName)
        assertNull(m.transform)
    }

    @Test
    fun test_MigrationTransformExecutes() {
        val m = Migration(tableName = "Record") { old ->
            val score = (old["score"] as? String)?.toIntOrNull() ?: 0
            mapOf("score" to score * 2)
        }
        val result = m.transform!!(mapOf("score" to "15"))
        assertEquals(30, result["score"])
    }

    @Test
    fun test_MigrationConfigMultipleVersions() {
        val config = LatticeConfigurationWithMigration(
            path = "multi.db",
            targetSchemaVersion = 5,
            migrations = mapOf(
                2 to Migration("A"),
                3 to Migration("B"),
                4 to Migration("C"),
                5 to Migration("D")
            )
        )
        assertEquals(5, config.targetSchemaVersion)
        assertEquals(4, config.migrations.size)
        assertTrue(config.migrations.containsKey(2))
        assertTrue(config.migrations.containsKey(5))
    }

    @Test
    fun test_SchemaOnlyMigrationNoTransformNeeded() {
        // A migration with just a table name and no transform
        // represents a schema-only migration (add/remove columns)
        val m = Migration(tableName = "Widget")
        assertNull(m.transform)
        // This would be used as: migrations = {2: m} to bump version without data transform
        val config = LatticeConfigurationWithMigration(
            targetSchemaVersion = 2,
            migrations = mapOf(2 to m)
        )
        assertEquals(1, config.migrations.size)
    }
}
