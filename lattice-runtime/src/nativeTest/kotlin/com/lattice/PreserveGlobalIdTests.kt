package com.lattice

import kotlin.test.*

/**
 * Tests for globalId preservation across operations.
 */
class PreserveGlobalIdTests {

    @AfterTest
    fun tearDownTestLattices() {
        cleanupTestLattices()
    }

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    /**
     * Tests that globalId is preserved after add and retrieve.
     */
    @Test
    fun test_GlobalIdPreservedAfterAdd() {
        val lattice = testLattice(Person::class)

        val person = Person()
        person.name = "Alice"
        person.age = 30

        lattice.add(person)

        val latticeObj = person as LatticeObject
        val globalId = latticeObj.globalId
        assertNotNull(globalId, "globalId should be set after add")
        assertTrue(globalId.isNotEmpty(), "globalId should not be empty")

        // Retrieve and verify globalId matches
        val retrieved = lattice.objects(Person::class).first()
        assertNotNull(retrieved)
        val retrievedObj = retrieved as LatticeObject
        assertEquals(globalId, retrievedObj.globalId, "globalId should be preserved on retrieval")

        lattice.close()
    }

    /**
     * Tests that globalId is preserved after update.
     */
    @Test
    fun test_GlobalIdPreservedAfterUpdate() {
        val lattice = testLattice(Person::class)

        val person = Person()
        person.name = "Bob"
        person.age = 25
        lattice.add(person)

        val latticeObj = person as LatticeObject
        val originalGlobalId = latticeObj.globalId

        // Update the object
        person.name = "Bob Updated"
        person.age = 26

        // Verify globalId did not change
        assertEquals(originalGlobalId, latticeObj.globalId, "globalId should not change after update")

        // Retrieve and verify
        val retrieved = lattice.objects(Person::class).first()
        assertNotNull(retrieved)
        val retrievedObj = retrieved as LatticeObject
        assertEquals(originalGlobalId, retrievedObj.globalId, "globalId should be preserved after update and retrieval")

        lattice.close()
    }

    /**
     * Tests that each object gets a unique globalId.
     */
    @Test
    fun test_UniqueGlobalIds() {
        val lattice = testLattice(Person::class)

        val person1 = Person().apply { name = "Alice"; age = 30 }
        val person2 = Person().apply { name = "Bob"; age = 25 }
        val person3 = Person().apply { name = "Charlie"; age = 35 }

        lattice.add(person1)
        lattice.add(person2)
        lattice.add(person3)

        val id1 = (person1 as LatticeObject).globalId
        val id2 = (person2 as LatticeObject).globalId
        val id3 = (person3 as LatticeObject).globalId

        assertNotEquals(id1, id2, "person1 and person2 should have different globalIds")
        assertNotEquals(id2, id3, "person2 and person3 should have different globalIds")
        assertNotEquals(id1, id3, "person1 and person3 should have different globalIds")

        lattice.close()
    }
}
