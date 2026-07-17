package com.lattice

import kotlin.test.*

/**
 * Tests for query filtering, sorting, pagination, and deleteAll.
 */
class ResultsTests {

    @AfterTest
    fun tearDownTestLattices() {
        cleanupTestLattices()
    }

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    /**
     * Port of test_ResultsQuery
     * Tests querying results.
     */
    @Test
    fun test_ResultsQuery() {
        val lattice = testLattice(Person::class)

        val person1 = Person()
        val person2 = Person()
        val person3 = Person()

        person1.name = "John"
        person1.age = 30
        person2.name = "Jane"
        person2.age = 25
        person3.name = "Tim"
        person3.age = 22

        val persons = lattice.objects(Person::class)
        assertEquals(0, persons.count)

        lattice.add(person1)
        lattice.add(person2)
        lattice.add(person3)

        // Results are live - same instance should reflect new count
        assertEquals(3, persons.count)

        // Test where clause
        val johnOrJane = persons.where {
            (it.string("name") eq "John") or (it.string("name") eq "Jane")
        }
        assertEquals(2, johnOrJane.count)

        // Test age filter
        val adults = persons.where { it.int("age") gte 25 }
        assertEquals(2, adults.count)

        lattice.close()
    }

    /**
     * Tests Query DSL - where, orderBy, limit, offset, first
     */
    @Test
    fun test_QueryDSL() {
        val lattice = testLattice(Person::class)

        // Add test data
        listOf(
            "Alice" to 30,
            "Bob" to 25,
            "Charlie" to 35,
            "Diana" to 28,
            "Eve" to 22
        ).forEach { (name, age) ->
            val person = Person()
            person.name = name
            person.age = age
            lattice.add(person)
        }

        val results = lattice.objects(Person::class)
        assertEquals(5, results.count)

        // Test orderBy ascending
        val orderedAsc = results.orderBy("name")
        assertEquals("Alice", orderedAsc.first()?.name)

        // Test orderBy descending
        val orderedDesc = results.orderBy("name", SortOrder.DESCENDING)
        assertEquals("Eve", orderedDesc.first()?.name)

        // Test limit
        val limited = results.limit(3)
        assertEquals(3, limited.toList().size)

        // Test offset
        val offset = results.orderBy("age").offset(2)
        val offsetList = offset.toList()
        assertTrue(offsetList.isNotEmpty())

        // Test first()
        val first = results.orderBy("age").first()
        assertNotNull(first)
        assertEquals("Eve", first.name) // Eve is youngest at 22

        // Test where with gte
        val over25 = results.where { it.int("age") gte 25 }
        assertEquals(4, over25.count)

        // Test where with lt
        val under30 = results.where { it.int("age") lt 30 }
        assertEquals(3, under30.count)

        // Test string contains
        val hasE = results.where { it.string("name").contains("e") }
        assertEquals(3, hasE.count) // Alice, Charlie, Eve

        // Test string startsWith
        val startsWithA = results.where { it.string("name").startsWith("A") }
        assertEquals(1, startsWithA.count)
        assertEquals("Alice", startsWithA.first()?.name)

        lattice.close()
    }

    /**
     * Tests deleteAll on filtered results.
     */
    @Test
    fun test_DeleteAll() {
        val lattice = testLattice(Person::class)

        // Add test data
        listOf("Alice", "Bob", "Charlie").forEach { name ->
            val person = Person()
            person.name = name
            person.age = 30
            lattice.add(person)
        }

        assertEquals(3, lattice.objects(Person::class).count)

        // Delete people whose name starts with 'B'
        val deleted = lattice.objects(Person::class)
            .where { it.string("name").startsWith("B") }
            .deleteAll()

        assertEquals(1, deleted)
        assertEquals(2, lattice.objects(Person::class).count)

        lattice.close()
    }

    /**
     * Port of test_BulkInsert
     * Tests bulk insert performance.
     */
    @Test
    fun test_BulkInsert() {
        val lattice = testLattice(Person::class)

        val people = (0 until 100).map { index ->
            Person().apply {
                name = "Person $index"
                age = index
            }
        }

        // Add all at once
        people.forEach { lattice.add(it) }

        val results = lattice.objects(Person::class)
        assertEquals(100, results.count)

        // Verify ordering
        for (person in results) {
            // Note: Results may not be ordered by insertion
            assertTrue(person.age in 0..99)
        }

        lattice.close()
    }

    // Port of Swift testQuery_In
    @Test
    fun test_QueryIn() {
        val lattice = testLattice(Person::class)
        val person = Person().apply { name = "TestPerson"; age = 10 }
        lattice.add(person)

        // IN with matching value
        assertEquals(1, lattice.objects(Person::class).where {
            it.int("age").`in`(listOf(5, 10, 15))
        }.count)

        // IN with non-matching values
        assertEquals(0, lattice.objects(Person::class).where {
            it.int("age").`in`(listOf(5, 15, 20))
        }.count)

        // IN with globalId
        val globalId = person.globalId
        assertNotNull(globalId)
        assertEquals(1, lattice.objects(Person::class).where {
            it.string("globalId").`in`(listOf("fake-id-1", "fake-id-2", globalId))
        }.count)

        lattice.close()
    }

    // Port of Swift test_ObjectWillChange_FiresOnInsert (using observe callback)
    @Test
    fun test_ObserveFiresOnInsert() {
        val lattice = testLattice(Person::class)
        val results = lattice.objects(Person::class)

        var changeReceived = false
        val token = results.observe { change ->
            changeReceived = true
        }

        lattice.add(Person().apply { name = "New"; age = 25 })

        // Observer fires synchronously on the mutation thread
        assertTrue(changeReceived, "Observer should fire on insert")
        assertEquals(1, results.count)

        token.cancel()
        lattice.close()
    }

    // Port of Swift test_ObjectWillChange_FiresOnDelete (using observe callback)
    @Test
    fun test_ObserveFiresOnDelete() {
        val lattice = testLattice(Person::class)
        val person = Person().apply { name = "ToDelete"; age = 30 }
        lattice.add(person)

        val results = lattice.objects(Person::class)
        assertEquals(1, results.count)

        var changeReceived = false
        val token = results.observe { change ->
            changeReceived = true
        }

        lattice.remove(person)

        assertTrue(changeReceived, "Observer should fire on delete")
        assertEquals(0, results.count)

        token.cancel()
        lattice.close()
    }

    // Port of Swift test_WriteWhileIterating
    @Test
    fun test_WriteWhileIterating() {
        val lattice = testLattice(Person::class)

        val people = (0 until 100).map { index ->
            Person().apply {
                name = "Person $index"
                age = index
            }
        }
        people.forEach { lattice.add(it) }

        val results = lattice.objects(Person::class)

        // Iterating while writing to the same table should not crash
        for (person in results) {
            person.age = 5000
        }

        // Verify all ages updated
        val updated = lattice.objects(Person::class)
        for (person in updated) {
            assertEquals(5000, person.age)
        }

        lattice.close()
    }

    // Additional: test chaining where + orderBy + limit + offset
    @Test
    fun test_ChainingWhereOrderByLimitOffset() {
        val lattice = testLattice(Person::class)

        listOf(
            "Alice" to 30,
            "Bob" to 25,
            "Charlie" to 35,
            "Diana" to 28,
            "Eve" to 22
        ).forEach { (name, age) ->
            val person = Person()
            person.name = name
            person.age = age
            lattice.add(person)
        }

        // Chain: where age >= 25, order by age ascending, limit 2, offset 1
        val results = lattice.objects(Person::class)
            .where { it.int("age") gte 25 }
            .orderBy("age")
            .limit(2)
            .offset(1)
            .toList()

        // Ages >= 25 sorted: 25(Bob), 28(Diana), 30(Alice), 35(Charlie)
        // offset 1, limit 2 => Diana(28), Alice(30)
        assertEquals(2, results.size)
        assertEquals("Diana", results[0].name)
        assertEquals("Alice", results[1].name)

        lattice.close()
    }

    // Additional: test toList snapshot semantics
    @Test
    fun test_ToListSnapshot() {
        val lattice = testLattice(Person::class)

        lattice.add(Person().apply { name = "Alice"; age = 30 })
        lattice.add(Person().apply { name = "Bob"; age = 25 })

        val snapshot = lattice.objects(Person::class).orderBy("name").toList()
        assertEquals(2, snapshot.size)
        assertEquals("Alice", snapshot[0].name)
        assertEquals("Bob", snapshot[1].name)

        // Adding another person doesn't change the snapshot
        lattice.add(Person().apply { name = "Charlie"; age = 35 })
        assertEquals(2, snapshot.size) // snapshot is frozen

        // But live results reflect the change
        assertEquals(3, lattice.objects(Person::class).count)

        lattice.close()
    }
}
