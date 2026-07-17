package com.lattice

import kotlin.test.*

/**
 * Tests for database attachment (cross-DB queries).
 * Port of Swift AttachTests.swift.
 */
class AttachTests {

    @AfterTest
    fun tearDownTestLattices() {
        cleanupTestLattices()
    }

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_AttachDatabase() {
        val db1 = testLattice(Trip::class)
        val db2 = testLattice(Trip::class)

        // Add different trips to each database
        val t1 = Trip().apply { name = "Paris"; days = 5; budget = 1000.0; isBooked = true }
        val t2 = Trip().apply { name = "Tokyo"; days = 10; budget = 3000.0; isBooked = false }

        db1.add(t1)
        db2.add(t2)

        assertEquals(1, db1.objects(Trip::class).count)
        assertEquals(1, db2.objects(Trip::class).count)

        // Attach db2 to db1
        db1.attaching(db2)

        // After attaching, db1 should see both trips (via UNION ALL)
        val combined = db1.objects(Trip::class)
        assertEquals(2, combined.count)

        db1.close()
        db2.close()
    }

    @Test
    fun test_AttachWithDistinct() {
        val db1 = testLattice(Trip::class)
        val db2 = testLattice(Trip::class)

        // Add same-named trip to both databases
        val t1 = Trip().apply { name = "Paris"; days = 5; budget = 1000.0; isBooked = true }
        val t2 = Trip().apply { name = "Paris"; days = 7; budget = 2000.0; isBooked = false }

        db1.add(t1)
        db2.add(t2)

        db1.attaching(db2)

        // Without distinct: 2 results
        assertEquals(2, db1.objects(Trip::class).count)

        // With distinct by name: 1 result
        val distinct = db1.objects(Trip::class).distinct("name")
        assertEquals(1, distinct.count)

        db1.close()
        db2.close()
    }

    // Port of Swift attach_plainInsert_localLattice
    @Test
    fun test_AttachPlainInsert_LocalLattice() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        // Attach synced to local
        local.attaching(synced)

        // INSERT into local should still work
        val trip = Trip().apply { name = "local item"; days = 5; budget = 1000.0; isBooked = true }
        local.add(trip)
        assertTrue(trip.id != 0L)

        // Visible through both local (which now includes union) and synced
        assertEquals(1, local.objects(Trip::class).count)

        local.close()
        synced.close()
    }

    // Port of Swift attach_plainInsert_syncedLattice
    @Test
    fun test_AttachPlainInsert_SyncedLattice() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        // Attach synced to local
        local.attaching(synced)

        // INSERT into synced directly (separate connection, no ATTACH)
        val trip = Trip().apply { name = "synced item"; days = 3; budget = 500.0; isBooked = false }
        synced.add(trip)
        assertTrue(trip.id != 0L)

        // Visible through synced
        assertEquals(1, synced.objects(Trip::class).count)
        // Visible through local (UNION ALL includes synced)
        assertEquals(1, local.objects(Trip::class).count)

        local.close()
        synced.close()
    }

    // Port of Swift attach_plainInsert_bothDBs_unionAll
    @Test
    fun test_AttachPlainInsert_BothDBs_UnionAll() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        local.attaching(synced)

        local.add(Trip().apply { name = "local item"; days = 5; budget = 1000.0; isBooked = true })
        synced.add(Trip().apply { name = "synced item"; days = 3; budget = 500.0; isBooked = false })

        // Query through attached local should see both via UNION ALL
        assertEquals(2, local.objects(Trip::class).count)
        // Each DB sees only its own (synced is standalone)
        assertEquals(1, synced.objects(Trip::class).where { it.string("name") eq "synced item" }.count)
        assertEquals(0, synced.objects(Trip::class).where { it.string("name") eq "local item" }.count)

        local.close()
        synced.close()
    }

    // Port of Swift attach_vecInsert_localLattice
    @Test
    fun test_AttachVecInsert_LocalLattice() {
        val local = testLattice(Embedding::class)
        val synced = testLattice(Embedding::class)

        // Attach synced to local — creates UNION ALL TEMP views
        local.attaching(synced)

        // INSERT into local with a vector field
        val item = Embedding().apply {
            name = "local vec item"
            vector = FloatVector(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f))
        }
        local.add(item)
        assertTrue(item.id != 0L)

        local.close()
        synced.close()
    }

    // Port of Swift attach_vecInsert_syncedLattice
    @Test
    fun test_AttachVecInsert_SyncedLattice() {
        val local = testLattice(Embedding::class)
        val synced = testLattice(Embedding::class)

        local.attaching(synced)

        // INSERT into synced directly (no ATTACH views, should work)
        val item = Embedding().apply {
            name = "synced vec item"
            vector = FloatVector(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f))
        }
        synced.add(item)
        assertTrue(item.id != 0L)

        local.close()
        synced.close()
    }

    // Port of Swift attach_deleteFromSynced_throughQueryLattice
    @Test
    fun test_AttachDeleteFromSynced() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        synced.add(Trip().apply { name = "to delete"; days = 1; budget = 100.0; isBooked = false })

        local.attaching(synced)

        // Fetch through attached local (UNION ALL) and verify it's visible
        val item = local.objects(Trip::class).where { it.string("name") eq "to delete" }.first()
        assertNotNull(item)

        // Delete from synced directly
        synced.objects(Trip::class).where { it.string("name") eq "to delete" }.deleteAll()

        assertEquals(0, synced.objects(Trip::class).count)
        assertEquals(0, local.objects(Trip::class).count)

        local.close()
        synced.close()
    }

    // Port of Swift attach_updateInSynced_throughQueryLattice
    @Test
    fun test_AttachUpdateInSynced() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        val trip = Trip().apply { name = "original"; days = 1; budget = 100.0; isBooked = false }
        synced.add(trip)

        local.attaching(synced)

        // Update the trip's name via the synced lattice
        val fetched = synced.objects(Trip::class).first()
        assertNotNull(fetched)
        fetched.name = "updated"

        // Verify update persists
        assertEquals("updated", synced.objects(Trip::class).first()?.name)

        local.close()
        synced.close()
    }
}
