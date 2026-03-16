package com.lattice

import kotlin.test.*

/**
 * Tests for complex multi-constraint queries combining where, orderBy, limit, and offset.
 * Also covers string operations, range queries, null checks, boolean operators,
 * deleteAll, and multi-field sorting.
 */
class CombinedQueryTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    // ============================================================
    // Helper to add a standard set of people for query tests
    // ============================================================
    private fun addStandardPeople(lattice: Lattice): List<Person> {
        return listOf(
            "Alice" to 30,
            "Bob" to 25,
            "Charlie" to 35,
            "Diana" to 28,
            "Eve" to 22
        ).map { (name, age) ->
            Person().apply {
                this.name = name
                this.age = age
            }.also { lattice.add(it) }
        }
    }

    // ============================================================
    // Combined where + orderBy + limit
    // ============================================================

    /**
     * Tests combined where + orderBy + limit query.
     */
    @Test
    fun test_CombinedWhereOrderByLimit() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        val results = lattice.objects(Person::class)

        // Test combined where + orderBy + limit
        val combined = results
            .where { it.int("age") gte 25 }
            .orderBy("age", SortOrder.DESCENDING)
            .limit(2)
        val combinedList = combined.toList()
        assertEquals(2, combinedList.size)
        assertEquals("Charlie", combinedList[0].name) // Oldest at 35
        assertEquals("Alice", combinedList[1].name)   // Second oldest at 30

        lattice.close()
    }

    // ============================================================
    // Multiple where clauses (AND semantics)
    // ============================================================

    /**
     * Tests chaining multiple where clauses with orderBy.
     * Multiple .where() calls should be combined with AND.
     */
    @Test
    fun test_MultipleFiltersWithOrderBy() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        val results = lattice.objects(Person::class)

        // Filter by age range then order by name
        val midAge = results
            .where { it.int("age") gte 25 }
            .where { it.int("age") lt 35 }
            .orderBy("name")

        val midAgeList = midAge.toList()
        // Should include Alice(30), Bob(25), Diana(28) but not Charlie(35) or Eve(22)
        assertEquals(3, midAgeList.size)
        assertEquals("Alice", midAgeList[0].name)
        assertEquals("Bob", midAgeList[1].name)
        assertEquals("Diana", midAgeList[2].name)

        lattice.close()
    }

    /**
     * Tests that chaining three where clauses narrows results correctly.
     */
    @Test
    fun test_ThreeChainedWheres() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        val results = lattice.objects(Person::class)
            .where { it.int("age") gte 22 }  // excludes nobody
            .where { it.int("age") lte 30 }  // excludes Charlie(35)
            .where { it.string("name").startsWith("A").not() } // excludes Alice
        val list = results.toList()

        // Should include Bob(25), Diana(28), Eve(22)
        assertEquals(3, list.size)
        val names = list.map { it.name }.toSet()
        assertTrue("Bob" in names)
        assertTrue("Diana" in names)
        assertTrue("Eve" in names)

        lattice.close()
    }

    // ============================================================
    // Where + orderBy + limit + offset combined
    // ============================================================

    /**
     * Tests orderBy + offset + limit for pagination.
     */
    @Test
    fun test_Pagination() {
        val lattice = testLattice(Person::class)

        // Add 10 people
        (1..10).forEach { i ->
            val person = Person()
            person.name = "Person ${i.toString().padStart(2, '0')}"
            person.age = i * 10
            lattice.add(person)
        }

        val results = lattice.objects(Person::class).orderBy("name")

        // Page 1: first 3
        val page1 = results.limit(3).toList()
        assertEquals(3, page1.size)
        assertEquals("Person 01", page1[0].name)
        assertEquals("Person 02", page1[1].name)
        assertEquals("Person 03", page1[2].name)

        // Page 2: next 3
        val page2 = results.offset(3).limit(3).toList()
        assertEquals(3, page2.size)
        assertEquals("Person 04", page2[0].name)
        assertEquals("Person 05", page2[1].name)
        assertEquals("Person 06", page2[2].name)

        lattice.close()
    }

    /**
     * Tests where + orderBy + limit + offset all combined.
     */
    @Test
    fun test_WhereOrderByLimitOffset() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Ages >= 25 sorted ascending: 25(Bob), 28(Diana), 30(Alice), 35(Charlie)
        // offset 1, limit 2 => Diana(28), Alice(30)
        val results = lattice.objects(Person::class)
            .where { it.int("age") gte 25 }
            .orderBy("age")
            .limit(2)
            .offset(1)
            .toList()

        assertEquals(2, results.size)
        assertEquals("Diana", results[0].name)
        assertEquals("Alice", results[1].name)

        lattice.close()
    }

    // ============================================================
    // Complex predicates: AND, OR, NOT
    // ============================================================

    /**
     * Tests AND predicate within a single where clause.
     */
    @Test
    fun test_AndPredicate() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Alice AND age > 25
        val results = lattice.objects(Person::class)
            .where { (it.string("name") eq "Alice") and (it.int("age") gt 25) }
        assertEquals(1, results.count)
        assertEquals("Alice", results.first()?.name)

        lattice.close()
    }

    /**
     * Tests OR predicate.
     */
    @Test
    fun test_OrPredicate() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // name == "Bob" OR age == 22 (Eve)
        val results = lattice.objects(Person::class)
            .where { (it.string("name") eq "Bob") or (it.int("age") eq 22) }
        assertEquals(2, results.count)

        val names = results.toList().map { it.name }.toSet()
        assertTrue("Bob" in names)
        assertTrue("Eve" in names)

        lattice.close()
    }

    /**
     * Tests NOT predicate.
     */
    @Test
    fun test_NotPredicate() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // NOT(name == "Alice")
        val results = lattice.objects(Person::class)
            .where { (it.string("name") eq "Alice").not() }
        assertEquals(4, results.count)

        val names = results.toList().map { it.name }.toSet()
        assertFalse("Alice" in names)

        lattice.close()
    }

    /**
     * Tests a complex nested predicate: (A AND B) OR C.
     */
    @Test
    fun test_ComplexNestedPredicate() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // (name starts with "A" AND age >= 30) OR (name == "Eve")
        val results = lattice.objects(Person::class)
            .where {
                ((it.string("name").startsWith("A")) and (it.int("age") gte 30)) or
                    (it.string("name") eq "Eve")
            }

        val names = results.toList().map { it.name }.toSet()
        // Alice (starts with A, age 30) and Eve
        assertTrue("Alice" in names)
        assertTrue("Eve" in names)
        assertEquals(2, names.size)

        lattice.close()
    }

    // ============================================================
    // String operations: contains, startsWith, endsWith, like
    // ============================================================

    /**
     * Tests string contains query.
     */
    @Test
    fun test_StringContains() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Names containing "li" => Alice, Charlie
        val results = lattice.objects(Person::class)
            .where { it.string("name").contains("li") }
        assertEquals(2, results.count)

        val names = results.toList().map { it.name }.toSet()
        assertTrue("Alice" in names)
        assertTrue("Charlie" in names)

        lattice.close()
    }

    /**
     * Tests string startsWith query.
     */
    @Test
    fun test_StringStartsWith() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Names starting with "Ch" => Charlie
        val results = lattice.objects(Person::class)
            .where { it.string("name").startsWith("Ch") }
        assertEquals(1, results.count)
        assertEquals("Charlie", results.first()?.name)

        lattice.close()
    }

    /**
     * Tests string endsWith query.
     */
    @Test
    fun test_StringEndsWith() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Names ending with "e" => Alice, Charlie, Eve
        val results = lattice.objects(Person::class)
            .where { it.string("name").endsWith("e") }
        assertEquals(3, results.count)

        val names = results.toList().map { it.name }.toSet()
        assertTrue("Alice" in names)
        assertTrue("Charlie" in names)
        assertTrue("Eve" in names)

        lattice.close()
    }

    /**
     * Tests string like query with SQL wildcards.
     */
    @Test
    fun test_StringLike() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // LIKE "___" matches 3-char names: Bob, Eve
        val results = lattice.objects(Person::class)
            .where { it.string("name").like("___") }
        assertEquals(2, results.count)

        val names = results.toList().map { it.name }.toSet()
        assertTrue("Bob" in names)
        assertTrue("Eve" in names)

        lattice.close()
    }

    // ============================================================
    // Range: between
    // ============================================================

    /**
     * Tests between query for integer range.
     */
    @Test
    fun test_Between() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Age between 25 and 30 inclusive: Bob(25), Diana(28), Alice(30)
        val results = lattice.objects(Person::class)
            .where { it.int("age").between(25, 30) }
        assertEquals(3, results.count)

        val names = results.toList().map { it.name }.toSet()
        assertTrue("Alice" in names)
        assertTrue("Bob" in names)
        assertTrue("Diana" in names)

        lattice.close()
    }

    /**
     * Tests inList query.
     */
    @Test
    fun test_InList() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Names in list
        val results = lattice.objects(Person::class)
            .where { it.string("name").inList("Alice", "Eve") }
        assertEquals(2, results.count)

        val names = results.toList().map { it.name }.toSet()
        assertTrue("Alice" in names)
        assertTrue("Eve" in names)

        lattice.close()
    }

    // ============================================================
    // Null checks: isNull(), isNotNull()
    // ============================================================

    /**
     * Tests isNull() and isNotNull() queries on optional fields.
     */
    @Test
    fun test_NullChecks() {
        val lattice = testLattice(AllTypesObject::class)

        val withValue = AllTypesObject()
        withValue.string = "hasValue"
        withValue.stringOpt = "I exist"

        val withNull = AllTypesObject()
        withNull.string = "hasNull"
        // stringOpt is null by default

        lattice.add(withValue)
        lattice.add(withNull)

        // IS NULL
        val nullResults = lattice.objects(AllTypesObject::class)
            .where { it.string("stringOpt").isNull() }
        assertEquals(1, nullResults.count)
        assertEquals("hasNull", nullResults.first()?.string)

        // IS NOT NULL
        val notNullResults = lattice.objects(AllTypesObject::class)
            .where { it.string("stringOpt").isNotNull() }
        assertEquals(1, notNullResults.count)
        assertEquals("hasValue", notNullResults.first()?.string)

        lattice.close()
    }

    // ============================================================
    // Delete with where clause
    // ============================================================

    /**
     * Tests deleteAll on filtered Results.
     */
    @Test
    fun test_DeleteWithWhere() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        assertEquals(5, lattice.objects(Person::class).count)

        // Delete people with age < 25 (only Eve at 22)
        val deleted = lattice.objects(Person::class)
            .where { it.int("age") lt 25 }
            .deleteAll()

        assertEquals(1, deleted)
        assertEquals(4, lattice.objects(Person::class).count)

        // Verify Eve is gone
        val remaining = lattice.objects(Person::class).toList().map { it.name }
        assertFalse("Eve" in remaining)

        lattice.close()
    }

    /**
     * Tests deleteAll with a more complex predicate.
     */
    @Test
    fun test_DeleteAllWithComplexPredicate() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Delete people whose name starts with "A" or age > 30
        val deleted = lattice.objects(Person::class)
            .where {
                (it.string("name").startsWith("A")) or (it.int("age") gt 30)
            }
            .deleteAll()

        // Alice (starts with A) and Charlie (age 35)
        assertEquals(2, deleted)
        assertEquals(3, lattice.objects(Person::class).count)

        lattice.close()
    }

    // ============================================================
    // Multi-field sorting
    // ============================================================

    /**
     * Tests sorting by two fields (orderBy chained twice).
     */
    @Test
    fun test_MultiFieldSort() {
        val lattice = testLattice(Person::class)

        // Add people with some duplicate ages
        listOf(
            "Alice" to 30,
            "Bob" to 25,
            "Charlie" to 30,
            "Diana" to 25,
            "Eve" to 30
        ).forEach { (name, age) ->
            Person().apply {
                this.name = name
                this.age = age
            }.also { lattice.add(it) }
        }

        // Sort by age ascending, then name ascending (tiebreaker)
        val results = lattice.objects(Person::class)
            .orderBy("age")
            .orderBy("name")
            .toList()

        assertEquals(5, results.size)
        // age 25: Bob, Diana
        assertEquals("Bob", results[0].name)
        assertEquals(25, results[0].age)
        assertEquals("Diana", results[1].name)
        assertEquals(25, results[1].age)
        // age 30: Alice, Charlie, Eve
        assertEquals("Alice", results[2].name)
        assertEquals(30, results[2].age)
        assertEquals("Charlie", results[3].name)
        assertEquals(30, results[3].age)
        assertEquals("Eve", results[4].name)
        assertEquals(30, results[4].age)

        lattice.close()
    }

    /**
     * Tests multi-field sort with mixed directions.
     */
    @Test
    fun test_MultiFieldSortMixedDirections() {
        val lattice = testLattice(Person::class)

        listOf(
            "Alice" to 30,
            "Bob" to 25,
            "Charlie" to 30,
            "Diana" to 25,
            "Eve" to 30
        ).forEach { (name, age) ->
            Person().apply {
                this.name = name
                this.age = age
            }.also { lattice.add(it) }
        }

        // Sort by age descending, then name ascending
        val results = lattice.objects(Person::class)
            .orderBy("age", SortOrder.DESCENDING)
            .orderBy("name")
            .toList()

        assertEquals(5, results.size)
        // age 30 first (desc): Alice, Charlie, Eve (name asc)
        assertEquals("Alice", results[0].name)
        assertEquals("Charlie", results[1].name)
        assertEquals("Eve", results[2].name)
        // age 25: Bob, Diana (name asc)
        assertEquals("Bob", results[3].name)
        assertEquals("Diana", results[4].name)

        lattice.close()
    }

    // ============================================================
    // Count with filters
    // ============================================================

    /**
     * Tests count on filtered results.
     */
    @Test
    fun test_CountWithFilters() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Total count
        assertEquals(5, lattice.objects(Person::class).count)

        // Count with where
        assertEquals(4, lattice.objects(Person::class)
            .where { it.int("age") gte 25 }
            .count)

        // Count with multiple wheres
        assertEquals(3, lattice.objects(Person::class)
            .where { it.int("age") gte 25 }
            .where { it.int("age") lt 35 }
            .count)

        // Count of empty result set
        assertEquals(0, lattice.objects(Person::class)
            .where { it.int("age") gt 100 }
            .count)

        lattice.close()
    }

    /**
     * Tests count with string predicates.
     */
    @Test
    fun test_CountWithStringPredicates() {
        val lattice = testLattice(Person::class)
        addStandardPeople(lattice)

        // Count names containing "li" => Alice, Charlie
        val containsLi = lattice.objects(Person::class)
            .where { it.string("name").contains("li") }
            .count
        assertEquals(2, containsLi)

        // Count names starting with capital letter D => Diana
        val startsD = lattice.objects(Person::class)
            .where { it.string("name").startsWith("D") }
            .count
        assertEquals(1, startsD)

        lattice.close()
    }

    // ============================================================
    // Boolean query
    // ============================================================

    /**
     * Tests querying by boolean fields.
     */
    @Test
    fun test_BooleanQuery() {
        val lattice = testLattice(Trip::class)

        val trip1 = Trip().apply { name = "Booked"; days = 7; budget = 1000.0; isBooked = true }
        val trip2 = Trip().apply { name = "Unbooked"; days = 5; budget = 500.0; isBooked = false }
        val trip3 = Trip().apply { name = "Also Booked"; days = 3; budget = 300.0; isBooked = true }

        lattice.add(trip1)
        lattice.add(trip2)
        lattice.add(trip3)

        val bookedResults = lattice.objects(Trip::class)
            .where { it.boolean("isBooked") eq true }
        assertEquals(2, bookedResults.count)

        val unbookedResults = lattice.objects(Trip::class)
            .where { it.boolean("isBooked") eq false }
        assertEquals(1, unbookedResults.count)
        assertEquals("Unbooked", unbookedResults.first()?.name)

        lattice.close()
    }

    // ============================================================
    // Comparison operators
    // ============================================================

    /**
     * Tests all comparison operators: eq, neq, lt, lte, gt, gte.
     */
    @Test
    fun test_ComparisonOperators() {
        val lattice = testLattice(Person::class)

        listOf(
            "Alice" to 10,
            "Bob" to 20,
            "Charlie" to 30
        ).forEach { (name, age) ->
            Person().apply {
                this.name = name
                this.age = age
            }.also { lattice.add(it) }
        }

        assertEquals(2, lattice.objects(Person::class).where { it.int("age") gte 20 }.count)
        assertEquals(2, lattice.objects(Person::class).where { it.int("age") lte 20 }.count)
        assertEquals(1, lattice.objects(Person::class).where { it.int("age") gt 20 }.count)
        assertEquals(1, lattice.objects(Person::class).where { it.int("age") lt 20 }.count)
        assertEquals(1, lattice.objects(Person::class).where { it.int("age") eq 20 }.count)
        assertEquals(2, lattice.objects(Person::class).where { it.int("age") neq 20 }.count)

        lattice.close()
    }

    // ============================================================
    // Double / Float queries
    // ============================================================

    /**
     * Tests querying by double fields.
     */
    @Test
    fun test_DoubleQuery() {
        val lattice = testLattice(Trip::class)

        val trip1 = Trip().apply { name = "Cheap"; days = 3; budget = 100.0; isBooked = false }
        val trip2 = Trip().apply { name = "Mid"; days = 5; budget = 500.0; isBooked = false }
        val trip3 = Trip().apply { name = "Expensive"; days = 10; budget = 5000.0; isBooked = false }

        lattice.add(trip1)
        lattice.add(trip2)
        lattice.add(trip3)

        val results = lattice.objects(Trip::class)
            .where { it.double("budget") gt 200.0 }
        assertEquals(2, results.count)

        val exactResults = lattice.objects(Trip::class)
            .where { it.double("budget") eq 500.0 }
        assertEquals(1, exactResults.count)
        assertEquals("Mid", exactResults.first()?.name)

        lattice.close()
    }
}
