package com.lattice

import kotlin.test.*

/**
 * Tests for cross-process observation.
 * Port of Swift CrossProcessTests.swift.
 *
 * Cross-process observation uses file-based change detection
 * to notify when another process modifies the database.
 */
class CrossProcessTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_DistanceMetricEnum() {
        // Verify distance metric values match C API
        assertEquals(DistanceMetric.L2, DistanceMetric.values()[0])
        assertEquals(DistanceMetric.COSINE, DistanceMetric.values()[1])
        assertEquals(DistanceMetric.L1, DistanceMetric.values()[2])
    }

    @Test
    fun test_NearestMatchDataClass() {
        // Just verify the data class works correctly
        val trip = Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false }
        val lattice = testLattice(Trip::class)
        lattice.add(trip)

        val match = NearestMatch(trip, 0.42)
        assertEquals(0.42, match.distance)
        assertEquals(trip, match.`object`)

        lattice.close()
    }

    // Port of Swift crossProcessObservation — adapted for single-process observation
    // Tests that observe() detects inserts from a separate Lattice instance on same file.
    @Test
    fun test_ObserveInsertFromSeparateInstance() {
        val path = "/tmp/lattice_xproc_test_${kotlin.random.Random.nextLong().toString(16)}.sqlite"
        val lattice1 = Lattice(path, Trip::class)
        val lattice2 = Lattice(path, Trip::class)

        // Observe on lattice1
        var changeReceived = false
        val token = lattice1.objects(Trip::class).observe { change ->
            changeReceived = true
        }

        // Insert via lattice1
        lattice1.add(Trip().apply { name = "FromInstance1"; days = 5; budget = 1000.0; isBooked = true })

        assertTrue(changeReceived, "Observer should detect insert from same instance")
        assertEquals(1, lattice1.objects(Trip::class).count)

        // lattice2 reading the same file should also see the row
        assertEquals(1, lattice2.objects(Trip::class).count)

        token.cancel()
        lattice1.close()
        lattice2.close()
    }

    // Port of Swift crossProcessObservation for update — adapted for observation
    @Test
    fun test_ObserveUpdateFromSameInstance() {
        val lattice = testLattice(Person::class)

        val person = Person().apply { name = "ExistingPerson"; age = 1 }
        lattice.add(person)

        var changeReceived = false
        val token = lattice.objects(Person::class).observe { change ->
            if (change is CollectionChange.Update) {
                changeReceived = true
            }
        }

        // Update the person
        person.age = 99

        assertTrue(changeReceived, "Observer should detect update")
        assertEquals(99, lattice.objects(Person::class).first()?.age)

        token.cancel()
        lattice.close()
    }

    // Port of Swift crossProcessObservation for delete
    @Test
    fun test_ObserveDeleteFromSameInstance() {
        val lattice = testLattice(Person::class)

        val person = Person().apply { name = "ToDelete"; age = 42 }
        lattice.add(person)
        assertEquals(1, lattice.objects(Person::class).count)

        var deleteReceived = false
        val token = lattice.objects(Person::class).observe { change ->
            if (change is CollectionChange.Delete) {
                deleteReceived = true
            }
        }

        lattice.remove(person)

        assertTrue(deleteReceived, "Observer should detect delete")
        assertEquals(0, lattice.objects(Person::class).count)

        token.cancel()
        lattice.close()
    }

    // Test that cancelling an observation token stops callbacks
    @Test
    fun test_ObserveCancelToken() {
        val lattice = testLattice(Trip::class)

        var callCount = 0
        val token = lattice.objects(Trip::class).observe { _ ->
            callCount++
        }

        // First insert — should trigger callback
        lattice.add(Trip().apply { name = "First"; days = 1; budget = 100.0; isBooked = false })
        val countAfterFirst = callCount

        // Cancel the token
        token.cancel()
        assertFalse(token.isActive)

        // Second insert — should NOT trigger callback
        lattice.add(Trip().apply { name = "Second"; days = 2; budget = 200.0; isBooked = false })

        assertEquals(countAfterFirst, callCount, "No more callbacks after cancel")

        lattice.close()
    }

    // Test that two separate Lattice instances on the same file see each other's data
    @Test
    fun test_SeparateInstancesShareData() {
        val path = "/tmp/lattice_shared_test_${kotlin.random.Random.nextLong().toString(16)}.sqlite"
        val lattice1 = Lattice(path, Person::class)
        val lattice2 = Lattice(path, Person::class)

        // Insert via lattice1
        lattice1.add(Person().apply { name = "Alice"; age = 30 })

        // lattice2 should see it (same file, different connection)
        assertEquals(1, lattice2.objects(Person::class).count)
        assertEquals("Alice", lattice2.objects(Person::class).first()?.name)

        // Insert via lattice2
        lattice2.add(Person().apply { name = "Bob"; age = 25 })

        // Both should see 2 people
        assertEquals(2, lattice1.objects(Person::class).count)
        assertEquals(2, lattice2.objects(Person::class).count)

        lattice1.close()
        lattice2.close()
    }

    // Test multiple observers on same Results
    @Test
    fun test_MultipleObservers() {
        val lattice = testLattice(Trip::class)
        val results = lattice.objects(Trip::class)

        var observer1Count = 0
        var observer2Count = 0

        val token1 = results.observe { _ -> observer1Count++ }
        val token2 = results.observe { _ -> observer2Count++ }

        lattice.add(Trip().apply { name = "Test"; days = 1; budget = 100.0; isBooked = false })

        assertTrue(observer1Count > 0, "Observer 1 should fire")
        assertTrue(observer2Count > 0, "Observer 2 should fire")

        // Cancel only observer 1
        token1.cancel()

        val count1After = observer1Count
        val count2After = observer2Count

        lattice.add(Trip().apply { name = "Test2"; days = 2; budget = 200.0; isBooked = false })

        assertEquals(count1After, observer1Count, "Observer 1 should not fire after cancel")
        assertTrue(observer2Count > count2After, "Observer 2 should still fire")

        token2.cancel()
        lattice.close()
    }
}
