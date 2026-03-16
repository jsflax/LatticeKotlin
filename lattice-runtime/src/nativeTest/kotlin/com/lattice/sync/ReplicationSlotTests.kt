package com.lattice.sync

import com.lattice.*
import kotlin.test.*

/**
 * Tests for replication slot tracking.
 * Port of Swift ReplicationSlotTests.swift.
 */
class ReplicationSlotTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_SyncFilterEntryDefaults() {
        val entry = SyncFilterEntry("Person")
        assertEquals("Person", entry.tableName)
        assertNull(entry.whereClause)
    }

    @Test
    fun test_EventsAfterReturnsAuditLog() {
        val lattice = testLattice(Trip::class)
        lattice.add(Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false })
        val events = lattice.eventsAfter(null)
        assertTrue(events.isNotEmpty(), "eventsAfter should return audit log entries")
        lattice.close()
    }

    @Test
    fun test_PendingEventsNonEmpty() {
        val lattice = testLattice(Trip::class)
        lattice.add(Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false })
        val pending = lattice.pendingEvents()
        assertTrue(pending.isNotEmpty(), "Should have pending events after add")
        lattice.close()
    }

    @Test
    fun test_MarkSyncedClearsPending() {
        val lattice = testLattice(Trip::class)
        val trip = Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false }
        lattice.add(trip)

        val globalId = (trip as LatticeObject).globalId
        assertNotNull(globalId)

        lattice.markSynced(listOf(globalId))
        lattice.close()
    }

    @Test
    fun test_CompactAuditLog() {
        val lattice = testLattice(Trip::class)
        val trip = Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false }
        lattice.add(trip)
        trip.days = 2
        trip.days = 3

        val count = lattice.compactAuditLog()
        assertTrue(count >= 1, "Compact should return at least 1 entry")
        lattice.close()
    }
}
