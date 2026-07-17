package com.lattice

import kotlin.test.*

/**
 * Tests for distinct and group queries.
 * Port of Swift DistinctTests.swift.
 */
class DistinctTests {

    @AfterTest
    fun tearDownTestLattices() {
        cleanupTestLattices()
    }

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_DistinctQuery() {
        val lattice = testLattice(Trip::class)

        // Add trips with duplicate names
        val t1 = Trip().apply { name = "Paris"; days = 5; budget = 1000.0; isBooked = true }
        val t2 = Trip().apply { name = "Paris"; days = 7; budget = 2000.0; isBooked = false }
        val t3 = Trip().apply { name = "Tokyo"; days = 10; budget = 3000.0; isBooked = true }
        val t4 = Trip().apply { name = "Tokyo"; days = 14; budget = 5000.0; isBooked = false }
        val t5 = Trip().apply { name = "London"; days = 3; budget = 500.0; isBooked = true }

        lattice.add(t1)
        lattice.add(t2)
        lattice.add(t3)
        lattice.add(t4)
        lattice.add(t5)

        assertEquals(5, lattice.objects(Trip::class).count)

        // Distinct by name should give 3 results
        val distinct = lattice.objects(Trip::class).distinct("name")
        assertEquals(3, distinct.count)

        lattice.close()
    }

    @Test
    fun test_GroupQuery() {
        val lattice = testLattice(Trip::class)

        val t1 = Trip().apply { name = "Trip A"; days = 5; budget = 1000.0; isBooked = true }
        val t2 = Trip().apply { name = "Trip B"; days = 7; budget = 2000.0; isBooked = true }
        val t3 = Trip().apply { name = "Trip C"; days = 10; budget = 3000.0; isBooked = false }

        lattice.add(t1)
        lattice.add(t2)
        lattice.add(t3)

        // Group by isBooked: 2 groups (true, false)
        val grouped = lattice.objects(Trip::class).group("isBooked")
        assertEquals(2, grouped.count)

        lattice.close()
    }

    // Port of Swift distinct_globalId_keyPathTracking
    @Test
    fun test_DistinctGlobalId() {
        val lattice = testLattice(Trip::class)
        val trip = Trip().apply { name = "test"; days = 1; budget = 100.0; isBooked = false }
        lattice.add(trip)

        // Distinct by globalId should return 1 (one unique globalId)
        val results = lattice.objects(Trip::class).distinct("globalId")
        assertEquals(1, results.count)

        lattice.close()
    }

    // Port of Swift distinct_count_singleDB
    @Test
    fun test_DistinctCount_SingleDB() {
        val lattice = testLattice(Trip::class)

        lattice.add(Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = false })
        lattice.add(Trip().apply { name = "alpha"; days = 2; budget = 200.0; isBooked = false })
        lattice.add(Trip().apply { name = "beta"; days = 3; budget = 300.0; isBooked = false })
        lattice.add(Trip().apply { name = "beta"; days = 4; budget = 400.0; isBooked = false })
        lattice.add(Trip().apply { name = "gamma"; days = 5; budget = 500.0; isBooked = false })

        // Without distinct: 5
        assertEquals(5, lattice.objects(Trip::class).count)

        // With distinct by name: 3 unique names
        assertEquals(3, lattice.objects(Trip::class).distinct("name").count)

        lattice.close()
    }

    // Port of Swift distinct_preservesWhereClause
    @Test
    fun test_DistinctPreservesWhereClause() {
        val lattice = testLattice(Trip::class)

        lattice.add(Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = false })
        lattice.add(Trip().apply { name = "alpha"; days = 2; budget = 200.0; isBooked = false })
        lattice.add(Trip().apply { name = "beta"; days = 3; budget = 300.0; isBooked = false })
        lattice.add(Trip().apply { name = "beta"; days = 4; budget = 400.0; isBooked = false })
        lattice.add(Trip().apply { name = "gamma"; days = 5; budget = 500.0; isBooked = false })

        // WHERE + distinct: only "alpha" name trips, deduplicated
        val results = lattice.objects(Trip::class)
            .where { it.string("name") eq "alpha" }
            .distinct("name")
        assertEquals(1, results.count)
        assertEquals("alpha", results.first()?.name)

        lattice.close()
    }

    // Port of Swift distinct_withGroupBy_attachedDBs
    @Test
    fun test_DistinctWithGroupBy_AttachedDBs() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        // Simulate overlapping items across two DBs with same globalId
        val shared1 = Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = true }
        local.add(shared1)
        val gid1 = shared1.globalId!!
        synced.add(Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = true }, preservingGlobalId = gid1)

        val shared2 = Trip().apply { name = "beta"; days = 2; budget = 200.0; isBooked = false }
        local.add(shared2)
        val gid2 = shared2.globalId!!
        synced.add(Trip().apply { name = "beta"; days = 2; budget = 200.0; isBooked = false }, preservingGlobalId = gid2)

        // Unique to synced
        synced.add(Trip().apply { name = "alpha"; days = 3; budget = 300.0; isBooked = true })

        // Attach synced to local
        local.attaching(synced)

        // Without distinct: 5 rows (2 local + 3 synced)
        assertEquals(5, local.objects(Trip::class).count)

        // distinct by globalId: 3 unique globalIds
        assertEquals(3, local.objects(Trip::class).distinct("globalId").count)

        // distinct by globalId + group by name:
        // After dedup: shared1(alpha), shared2(beta), unique(alpha) = 3 rows
        // After group by name: alpha, beta = 2 groups
        assertEquals(2, local.objects(Trip::class).distinct("globalId").group("name").count)

        local.close()
        synced.close()
    }

    // Port of Swift distinct_attachedDBs_deduplicatesByGlobalId
    @Test
    fun test_DistinctAttachedDBs_DeduplicatesByGlobalId() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        // Simulate the same item existing in both DBs (same globalId)
        val trip = Trip().apply { name = "shared trip"; days = 5; budget = 1000.0; isBooked = true }
        local.add(trip)
        val globalId = trip.globalId!!

        // Insert with same globalId into synced DB
        synced.add(Trip().apply { name = "shared trip"; days = 5; budget = 1000.0; isBooked = true }, preservingGlobalId = globalId)

        // Attach synced to local
        local.attaching(synced)

        // Without distinct, UNION ALL returns both
        assertEquals(2, local.objects(Trip::class).count)

        // With distinct by globalId, deduplicates to 1
        val results = local.objects(Trip::class).distinct("globalId")
        assertEquals(1, results.count)

        local.close()
        synced.close()
    }

    // Port of Swift distinct_attachedDBs_count
    @Test
    fun test_DistinctAttachedDBs_Count() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        // 2 items in local, 1 overlapping + 1 unique in synced
        val item1 = Trip().apply { name = "shared"; days = 1; budget = 100.0; isBooked = false }
        local.add(item1)
        val gid1 = item1.globalId!!

        val item2 = Trip().apply { name = "local only"; days = 2; budget = 200.0; isBooked = false }
        local.add(item2)

        // Same globalId as item1
        synced.add(Trip().apply { name = "shared"; days = 1; budget = 100.0; isBooked = false }, preservingGlobalId = gid1)

        // Unique to synced
        synced.add(Trip().apply { name = "synced only"; days = 3; budget = 300.0; isBooked = false })

        local.attaching(synced)

        // Without distinct: 4 (2 local + 2 synced)
        assertEquals(4, local.objects(Trip::class).count)

        // With distinct: 3 (shared deduped + local only + synced only)
        assertEquals(3, local.objects(Trip::class).distinct("globalId").count)

        local.close()
        synced.close()
    }

    // Port of Swift distinct_attachedDBs_withWhereClause
    @Test
    fun test_DistinctAttachedDBs_WithWhereClause() {
        val local = testLattice(Trip::class)
        val synced = testLattice(Trip::class)

        // Overlapping item
        val shared = Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = true }
        local.add(shared)
        val gid = shared.globalId!!

        synced.add(Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = true }, preservingGlobalId = gid)

        // Non-overlapping
        local.add(Trip().apply { name = "beta"; days = 2; budget = 200.0; isBooked = false })
        synced.add(Trip().apply { name = "beta"; days = 3; budget = 300.0; isBooked = false })

        local.attaching(synced)

        // Filter to alpha + distinct: should be 1
        val alphaResults = local.objects(Trip::class)
            .where { it.string("name") eq "alpha" }
            .distinct("globalId")
        assertEquals(1, alphaResults.count)

        // Filter to beta + distinct: 2 (different globalIds)
        val betaResults = local.objects(Trip::class)
            .where { it.string("name") eq "beta" }
            .distinct("globalId")
        assertEquals(2, betaResults.count)

        local.close()
        synced.close()
    }

    // Port of Swift distinct_chainingPreserved_throughSortedBy
    @Test
    fun test_DistinctChainingPreserved_ThroughOrderBy() {
        val lattice = testLattice(Trip::class)

        lattice.add(Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = false })
        lattice.add(Trip().apply { name = "alpha"; days = 2; budget = 200.0; isBooked = false })
        lattice.add(Trip().apply { name = "beta"; days = 3; budget = 300.0; isBooked = false })

        val results = lattice.objects(Trip::class)
            .distinct("name")
            .orderBy("name")
        assertEquals(2, results.count)

        lattice.close()
    }

    // Port of Swift distinct_noEffect_whenAlreadyUnique
    @Test
    fun test_DistinctNoEffect_WhenAlreadyUnique() {
        val lattice = testLattice(Trip::class)

        lattice.add(Trip().apply { name = "alpha"; days = 1; budget = 100.0; isBooked = false })
        lattice.add(Trip().apply { name = "beta"; days = 2; budget = 200.0; isBooked = false })
        lattice.add(Trip().apply { name = "gamma"; days = 3; budget = 300.0; isBooked = false })

        // All names unique — distinct should return same count
        val results = lattice.objects(Trip::class).distinct("name")
        assertEquals(3, results.count)

        lattice.close()
    }
}
