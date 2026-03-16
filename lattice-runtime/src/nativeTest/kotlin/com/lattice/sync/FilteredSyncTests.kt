package com.lattice.sync

import com.lattice.*
import kotlin.test.*

/**
 * Tests for filtered sync (per-table predicates).
 * Port of Swift FilteredSyncTests.swift.
 */
class FilteredSyncTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_SyncFilterCreation() {
        val filter = SyncFilter(
            entries = listOf(
                SyncFilterEntry(tableName = "Person"),
                SyncFilterEntry(tableName = "Dog", whereClause = "age > 5")
            )
        )
        assertEquals(2, filter.entries.size)
        assertEquals("Person", filter.entries[0].tableName)
        assertNull(filter.entries[0].whereClause)
        assertEquals("age > 5", filter.entries[1].whereClause)
    }

    @Test
    fun test_IpcTargetCreation() {
        val target = IpcTarget(
            channel = "com.myapp.shared",
            syncFilter = SyncFilter(
                entries = listOf(SyncFilterEntry("Person"))
            )
        )
        assertEquals("com.myapp.shared", target.channel)
        assertNull(target.socketPath)
        assertNotNull(target.syncFilter)
    }

    @Test
    fun test_SyncFilterEntryWithoutWhere() {
        val entry = SyncFilterEntry("Person")
        assertEquals("Person", entry.tableName)
        assertNull(entry.whereClause)
    }

    @Test
    fun test_SyncFilterEntryWithWhere() {
        val entry = SyncFilterEntry("Dog", "age > 5")
        assertEquals("Dog", entry.tableName)
        assertEquals("age > 5", entry.whereClause)
    }

    @Test
    fun test_EmptySyncFilter() {
        val filter = SyncFilter()
        assertTrue(filter.entries.isEmpty())
    }

    @Test
    fun test_SyncFilterMultipleEntries() {
        val filter = SyncFilter(entries = listOf(
            SyncFilterEntry("A"),
            SyncFilterEntry("B", "x = 1"),
            SyncFilterEntry("C")
        ))
        assertEquals(3, filter.entries.size)
    }

    @Test
    fun test_IpcTargetWithoutFilter() {
        val target = IpcTarget(channel = "ch1")
        assertEquals("ch1", target.channel)
        assertNull(target.syncFilter)
        assertNull(target.socketPath)
    }
}
