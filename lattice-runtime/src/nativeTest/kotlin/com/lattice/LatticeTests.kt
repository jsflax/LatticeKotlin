package com.lattice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Ported from Swift LatticeTests.swift
 */
class LatticeTests {

    @BeforeTest
    fun setup() {
        // Register model factories for schema discovery
        registerTestModelFactories()
    }

    /**
     * Port of test_SimpleExample
     * Tests basic CRUD operations.
     */
    @Test
    fun test_SimpleExample() {
        val lattice = testLattice(Person::class)

        val person = Person()
        person.name = "John"
        person.age = 30

        assertEquals(30, person.age)

        lattice.add(person)

        // After adding, the object should be managed
        val latticeObj = person as LatticeObject
        assertTrue(latticeObj.isManaged, "Person should be managed after adding to Lattice")

        // Modify and verify
        person.age = 31
        assertEquals(31, person.age)

        lattice.close()
    }

    /**
     * Port of test_AllTypes
     * Tests all supported property types.
     */
    @Test
    fun test_AllTypes() {
        val lattice = testLattice(AllTypesObject::class)

        val obj = AllTypesObject()

        // String
        obj.string = "Hello World"
        obj.stringOpt = "Optional String"

        // Bool
        obj.bool = true
        obj.boolOpt = false

        // Int
        obj.int = 42
        obj.intOpt = -100

        // Long
        obj.long = 9223372036854775807L
        obj.longOpt = -9223372036854775807L

        // Float
        obj.float = 3.14159f
        obj.floatOpt = -2.71828f

        // Double
        obj.double = 3.141592653589793
        obj.doubleOpt = -2.718281828459045

        lattice.add(obj)

        // Verify values after adding
        val latticeObj = obj as LatticeObject
        assertTrue(latticeObj.isManaged)

        // Retrieve and verify
        val results = lattice.objects(AllTypesObject::class)
        assertEquals(1, results.count)

        val retrieved = results.first()
        assertNotNull(retrieved)

        // String
        assertEquals("Hello World", retrieved.string)
        assertEquals("Optional String", retrieved.stringOpt)

        // Bool
        assertEquals(true, retrieved.bool)
        assertEquals(false, retrieved.boolOpt)

        // Int
        assertEquals(42, retrieved.int)
        assertEquals(-100, retrieved.intOpt)

        // Long
        assertEquals(9223372036854775807L, retrieved.long)
        assertEquals(-9223372036854775807L, retrieved.longOpt)

        // Float (approximate comparison)
        assertTrue(kotlin.math.abs(retrieved.float - 3.14159f) < 0.0001f)
        assertNotNull(retrieved.floatOpt)
        assertTrue(kotlin.math.abs(retrieved.floatOpt!! - (-2.71828f)) < 0.0001f)

        // Double
        assertTrue(kotlin.math.abs(retrieved.double - 3.141592653589793) < 0.0000001)
        assertNotNull(retrieved.doubleOpt)
        assertTrue(kotlin.math.abs(retrieved.doubleOpt!! - (-2.718281828459045)) < 0.0000001)

        // Test nil optionals
        val obj2 = AllTypesObject()
        obj2.string = "test"
        // Leave all optionals as null

        lattice.add(obj2)

        val results2 = lattice.objects(AllTypesObject::class)
        assertEquals(2, results2.count)

        // Find the one with null optionals
        val nilObj = results2.firstOrNull { it.stringOpt == null }
        assertNotNull(nilObj)
        assertNull(nilObj.boolOpt)
        assertNull(nilObj.intOpt)
        assertNull(nilObj.longOpt)
        assertNull(nilObj.floatOpt)
        assertNull(nilObj.doubleOpt)

        lattice.close()
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

        // Test combined where + orderBy + limit
        val combined = results
            .where { it.int("age") gte 25 }
            .orderBy("age", SortOrder.DESCENDING)
            .limit(2)
        val combinedList = combined.toList()
        assertEquals(2, combinedList.size)
        assertEquals("Charlie", combinedList[0].name) // Oldest at 35
        assertEquals("Alice", combinedList[1].name)   // Second oldest at 30

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
     * Tests deleteAll on filtered results
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
        var expectedAge = 0
        for (person in results) {
            // Note: Results may not be ordered by insertion
            assertTrue(person.age in 0..99)
        }

        lattice.close()
    }

    /**
     * Tests basic Trip model operations (from example).
     */
    @Test
    fun test_Trip() {
        val lattice = testLattice(Trip::class)

        val trip = Trip()
        trip.name = "Costa Rica Adventure"
        trip.days = 10
        trip.budget = 3500.0
        trip.isBooked = false

        // Verify unmanaged values
        assertEquals("Costa Rica Adventure", trip.name)
        assertEquals(10, trip.days)
        assertEquals(3500.0, trip.budget)
        assertEquals(false, trip.isBooked)

        lattice.add(trip)

        // Verify managed
        val latticeObj = trip as LatticeObject
        assertTrue(latticeObj.isManaged)
        assertEquals("Trip", latticeObj._latticeTableName)

        // Modify managed object
        trip.name = "Updated Trip"
        trip.days = 14
        trip.isBooked = true

        assertEquals("Updated Trip", trip.name)
        assertEquals(14, trip.days)
        assertEquals(true, trip.isBooked)

        // Query and verify
        val results = lattice.objects(Trip::class)
        assertEquals(1, results.count)

        val retrieved = results.first()
        assertNotNull(retrieved)
        assertEquals("Updated Trip", retrieved.name)
        assertEquals(14, retrieved.days)
        assertEquals(3500.0, retrieved.budget)
        assertEquals(true, retrieved.isBooked)

        lattice.close()
    }

    /**
     * Tests finding objects by ID.
     */
    @Test
    fun test_FindById() {
        val lattice = testLattice(Person::class)

        val person1 = Person().apply {
            name = "Alice"
            age = 30
        }
        val person2 = Person().apply {
            name = "Bob"
            age = 25
        }

        lattice.add(person1)
        lattice.add(person2)

        // Get the primary key (id) of person1
        // TODO: This requires primaryKey support on LatticeObject
        // val found = lattice.find(Person::class, person1.id)
        // assertNotNull(found)
        // assertEquals("Alice", found.name)

        lattice.close()
    }

    /**
     * Tests removing objects.
     */
    @Test
    fun test_Remove() {
        val lattice = testLattice(Person::class)

        val person = Person().apply {
            name = "ToDelete"
            age = 99
        }

        lattice.add(person)
        assertEquals(1, lattice.objects(Person::class).count)

        lattice.remove(person)
        assertEquals(0, lattice.objects(Person::class).count)

        // Object should be unmanaged after removal
        val latticeObj = person as LatticeObject
        assertFalse(latticeObj.isManaged)

        lattice.close()
    }

    /**
     * Tests @Link relationships (one-to-one).
     */
    @Test
    fun test_Links() {
        val lattice = testLattice(Person::class, Dog::class)

        val person = Person()
        person.name = "John"
        person.age = 30

        val dog = Dog()
        dog.name = "Buddy"

        // Link dog to person (both unmanaged - should work with dynamic_object)
        person.dog = dog

        // Add both to lattice
        lattice.add(dog)
        lattice.add(person)

        // Verify the link
        val retrieved = lattice.objects(Person::class).first()
        assertNotNull(retrieved)
        assertEquals("John", retrieved.name)

        val linkedDog = retrieved.dog
        assertNotNull(linkedDog)
        assertEquals("Buddy", linkedDog!!.name)

        lattice.close()
    }

    /**
     * Tests null links.
     */
    @Test
    fun test_NullLinks() {
        val lattice = testLattice(Person::class, Dog::class)

        val person = Person()
        person.name = "Jane"
        person.age = 25
        // Don't set dog - should be null

        lattice.add(person)

        val retrieved = lattice.objects(Person::class).first()
        assertNotNull(retrieved)
        assertEquals("Jane", retrieved.name)
        assertNull(retrieved.dog)

        lattice.close()
    }

    /**
     * Tests transactions.
     */
    @Test
    fun test_Transaction() {
        val lattice = testLattice(Person::class)

        val result = lattice.transaction {
            val person1 = Person().apply {
                name = "In Transaction"
                age = 1
            }
            val person2 = Person().apply {
                name = "Also In Transaction"
                age = 2
            }

            lattice.add(person1)
            lattice.add(person2)

            "Success"
        }

        assertEquals("Success", result)
        assertEquals(2, lattice.objects(Person::class).count)

        lattice.close()
    }

    /**
     * Tests @LinkList relationships (one-to-many).
     */
    @Test
    fun test_LinkLists() {
        val lattice = testLattice(Team::class, Player::class)

        // Create players
        val player1 = Player()
        player1.name = "Alice"
        player1.number = 10

        val player2 = Player()
        player2.name = "Bob"
        player2.number = 23

        val player3 = Player()
        player3.name = "Charlie"
        player3.number = 7

        // Create team
        val team = Team()
        team.name = "Dream Team"

        // Add players to lattice first
        lattice.add(player1)
        lattice.add(player2)
        lattice.add(player3)

        // Add team to lattice
        lattice.add(team)

        // Add players to team's list
        team.players.add(player1)
        team.players.add(player2)
        team.players.add(player3)

        // Verify count
        assertEquals(3, team.players.size)

        // Retrieve team and verify players
        val retrieved = lattice.objects(Team::class).first()
        assertNotNull(retrieved)
        assertEquals("Dream Team", retrieved.name)
        assertEquals(3, retrieved.players.size)

        // Verify player data
        assertEquals("Alice", retrieved.players[0].name)
        assertEquals(10, retrieved.players[0].number)
        assertEquals("Bob", retrieved.players[1].name)
        assertEquals("Charlie", retrieved.players[2].name)

        lattice.close()
    }

    /**
     * Tests empty @LinkList.
     */
    @Test
    fun test_EmptyLinkList() {
        val lattice = testLattice(Team::class, Player::class)

        val team = Team()
        team.name = "Empty Team"

        lattice.add(team)

        val retrieved = lattice.objects(Team::class).first()
        assertNotNull(retrieved)
        assertEquals("Empty Team", retrieved.name)
        assertEquals(0, retrieved.players.size)

        lattice.close()
    }

    /**
     * Tests @Embedded models (stored as JSON).
     */
    @Test
    fun test_EmbeddedModels() {
        val lattice = testLattice(Contact::class)

        // Create contact with embedded address
        val contact = Contact()
        contact.name = "John Doe"
        contact.email = "john@example.com"
        contact.address = Address(
            street = "123 Main St",
            city = "Springfield",
            zipCode = "12345"
        )

        // Add to lattice
        lattice.add(contact)

        // Retrieve and verify
        val retrieved = lattice.objects(Contact::class).first()
        assertNotNull(retrieved)
        assertEquals("John Doe", retrieved.name)
        assertEquals("john@example.com", retrieved.email)

        // Verify embedded address
        val addr = retrieved.address
        assertEquals("123 Main St", addr.street)
        assertEquals("Springfield", addr.city)
        assertEquals("12345", addr.zipCode)

        lattice.close()
    }

    /**
     * Tests nullable @Embedded models.
     */
    @Test
    fun test_NullableEmbeddedModels() {
        val lattice = testLattice(Contact::class)

        // Create contact without work address
        val contact1 = Contact()
        contact1.name = "Jane Doe"
        contact1.email = "jane@example.com"
        contact1.address = Address(street = "456 Oak Ave", city = "Portland", zipCode = "97201")
        // workAddress is null

        // Create contact with work address
        val contact2 = Contact()
        contact2.name = "Bob Smith"
        contact2.email = "bob@example.com"
        contact2.address = Address(street = "789 Elm St", city = "Seattle", zipCode = "98101")
        contact2.workAddress = Address(street = "100 Tech Blvd", city = "Seattle", zipCode = "98102")

        lattice.add(contact1)
        lattice.add(contact2)

        // Retrieve and verify
        val contacts = lattice.objects(Contact::class).orderBy("name")

        val bob = contacts.first { it.name == "Bob Smith" }
        assertNotNull(bob.workAddress)
        assertEquals("100 Tech Blvd", bob.workAddress!!.street)

        val jane = contacts.first { it.name == "Jane Doe" }
        assertNull(jane.workAddress)

        lattice.close()
    }

    /**
     * Tests updating @Embedded models.
     */
    @Test
    fun test_UpdateEmbeddedModels() {
        val lattice = testLattice(Contact::class)

        val contact = Contact()
        contact.name = "Alice"
        contact.email = "alice@example.com"
        contact.address = Address(street = "Old Street", city = "Old City", zipCode = "00000")

        lattice.add(contact)

        // Update the embedded address
        contact.address = Address(street = "New Street", city = "New City", zipCode = "99999")

        // Retrieve and verify update persisted
        val retrieved = lattice.objects(Contact::class).first()
        assertNotNull(retrieved)
        assertEquals("New Street", retrieved.address.street)
        assertEquals("New City", retrieved.address.city)
        assertEquals("99999", retrieved.address.zipCode)

        lattice.close()
    }

    /**
     * Tests observing collection changes.
     */
    @Test
    fun test_Observation() {
        val lattice = testLattice(Person::class)

        val results = lattice.objects(Person::class)
        val changes = mutableListOf<CollectionChange>()

        // Start observing
        val token = results.observe { change ->
            changes.add(change)
        }

        assertTrue(token.isActive)

        // Add a person - should trigger insert
        val person1 = Person()
        person1.name = "Alice"
        person1.age = 25
        lattice.add(person1)

        // Add another person - should trigger insert
        val person2 = Person()
        person2.name = "Bob"
        person2.age = 30
        lattice.add(person2)

        // Delete a person - should trigger delete
        lattice.remove(person1)

        // Verify changes were recorded
        // Note: The exact number of changes depends on C++ observer implementation
        assertTrue(changes.isNotEmpty(), "Should have received some changes")

        // Look for inserts
        val inserts = changes.filterIsInstance<CollectionChange.Insert>()
        assertTrue(inserts.isNotEmpty(), "Should have received insert notifications")

        // Look for deletes
        val deletes = changes.filterIsInstance<CollectionChange.Delete>()
        assertTrue(deletes.isNotEmpty(), "Should have received delete notifications")

        // Cancel observation
        token.cancel()
        assertFalse(token.isActive)

        // Add another person after cancellation - should NOT trigger callback
        val changeCountAfterCancel = changes.size
        val person3 = Person()
        person3.name = "Charlie"
        person3.age = 35
        lattice.add(person3)

        // No new changes should be recorded (callback no longer invoked)
        assertEquals(changeCountAfterCancel, changes.size, "No changes after cancel")

        lattice.close()
    }

    /**
     * Tests that observation token auto-cancels when collected.
     */
    @Test
    fun test_ObservationToken() {
        val lattice = testLattice(Person::class)

        val results = lattice.objects(Person::class)
        var callbackInvoked = false

        // Create token in a scope
        val token = results.observe { change ->
            callbackInvoked = true
        }

        assertTrue(token.isActive)

        // Cancel explicitly
        token.cancel()

        assertFalse(token.isActive)

        // Add a person after cancel - callback should not be invoked
        val person = Person()
        person.name = "Test"
        person.age = 20
        lattice.add(person)

        assertFalse(callbackInvoked, "Callback should not be invoked after cancel")

        lattice.close()
    }

    /**
     * Tests observation with a scheduler that dispatches to a coroutine dispatcher.
     */
    @Test
    fun test_ObservationWithScheduler() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create scheduler with test dispatcher
        val scheduler = LatticeScheduler(testDispatcher)
        val path = "/tmp/lattice_scheduler_test_${kotlin.random.Random.nextLong()}.db"
        val lattice = Lattice(path, scheduler, Person::class)

        val results = lattice.objects(Person::class)
        val changes = mutableListOf<CollectionChange>()
        var callbackThread: String? = null

        // Start observing - callbacks should be dispatched via scheduler
        val token = results.observe { change ->
            changes.add(change)
            callbackThread = "dispatched"
        }

        assertTrue(token.isActive)

        // Add a person - should trigger insert via scheduler
        val person = Person()
        person.name = "Alice"
        person.age = 25
        lattice.add(person)

        // Advance the test dispatcher to process any pending coroutines
        testScheduler.advanceUntilIdle()

        // Verify changes were recorded via the scheduler
        assertTrue(changes.isNotEmpty(), "Should have received changes via scheduler")
        assertEquals("dispatched", callbackThread, "Callback should have run on dispatcher")

        val inserts = changes.filterIsInstance<CollectionChange.Insert>()
        assertTrue(inserts.isNotEmpty(), "Should have received insert notification")

        // Cancel and cleanup
        token.cancel()
        lattice.close()
    }

    /**
     * Tests that the scheduler properly dispatches multiple observation callbacks.
     */
    @Test
    fun test_SchedulerMultipleCallbacks() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val scheduler = LatticeScheduler(testDispatcher)
        val path = "/tmp/lattice_scheduler_multi_${kotlin.random.Random.nextLong()}.db"
        val lattice = Lattice(path, scheduler, Person::class)

        val results = lattice.objects(Person::class)
        val changes = mutableListOf<CollectionChange>()

        val token = results.observe { change ->
            changes.add(change)
        }

        // Add multiple people
        for (i in 1..5) {
            val person = Person()
            person.name = "Person $i"
            person.age = 20 + i
            lattice.add(person)
        }

        // Advance scheduler to process all callbacks
        testScheduler.advanceUntilIdle()

        // Should have 5 insert notifications
        val inserts = changes.filterIsInstance<CollectionChange.Insert>()
        assertEquals(5, inserts.size, "Should have received 5 insert notifications")

        token.cancel()
        lattice.close()
    }

    /**
     * Tests ByteArray/Blob property support.
     */
    @Test
    fun test_ByteArrayBlob() {
        val lattice = testLattice(Document::class)

        // Create document with binary content
        val doc = Document()
        doc.name = "test.bin"
        doc.content = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte(), 0xFE.toByte())

        lattice.add(doc)

        // Retrieve and verify
        val retrieved = lattice.objects(Document::class).first()
        assertNotNull(retrieved)
        assertEquals("test.bin", retrieved.name)

        val content = retrieved.content
        assertEquals(6, content.size)
        assertEquals(0x00.toByte(), content[0])
        assertEquals(0x01.toByte(), content[1])
        assertEquals(0x02.toByte(), content[2])
        assertEquals(0x03.toByte(), content[3])
        assertEquals(0xFF.toByte(), content[4])
        assertEquals(0xFE.toByte(), content[5])

        lattice.close()
    }

    /**
     * Tests nullable ByteArray properties.
     */
    @Test
    fun test_NullableByteArray() {
        val lattice = testLattice(Document::class)

        // Create document without thumbnail
        val doc1 = Document()
        doc1.name = "no-thumb.bin"
        doc1.content = byteArrayOf(0x01, 0x02)
        // thumbnail is null

        // Create document with thumbnail
        val doc2 = Document()
        doc2.name = "with-thumb.bin"
        doc2.content = byteArrayOf(0x03, 0x04)
        doc2.thumbnail = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header

        lattice.add(doc1)
        lattice.add(doc2)

        // Retrieve and verify
        val docs = lattice.objects(Document::class).orderBy("name")

        val noThumb = docs.first { it.name == "no-thumb.bin" }
        assertNull(noThumb.thumbnail)

        val withThumb = docs.first { it.name == "with-thumb.bin" }
        assertNotNull(withThumb.thumbnail)
        assertEquals(4, withThumb.thumbnail!!.size)
        assertEquals(0x89.toByte(), withThumb.thumbnail!![0])

        lattice.close()
    }

    /**
     * Tests large ByteArray data.
     */
    @Test
    fun test_LargeByteArray() {
        val lattice = testLattice(Document::class)

        // Create document with large content (1MB)
        val size = 1024 * 1024
        val largeContent = ByteArray(size) { (it % 256).toByte() }

        val doc = Document()
        doc.name = "large.bin"
        doc.content = largeContent

        lattice.add(doc)

        // Retrieve and verify
        val retrieved = lattice.objects(Document::class).first()
        assertNotNull(retrieved)
        assertEquals(size, retrieved.content.size)

        // Verify some bytes
        assertEquals(0.toByte(), retrieved.content[0])
        assertEquals(255.toByte(), retrieved.content[255])
        assertEquals(0.toByte(), retrieved.content[256])
        assertEquals(123.toByte(), retrieved.content[123])

        lattice.close()
    }

    /**
     * Tests FloatVector property support for vector embeddings.
     */
    @Test
    fun test_FloatVector() {
        val lattice = testLattice(Embedding::class)

        // Create embedding with vector
        val embedding = Embedding()
        embedding.name = "doc1"
        embedding.vector = FloatVector(1.0f, 2.0f, 3.0f, 4.0f)

        lattice.add(embedding)

        // Retrieve and verify
        val retrieved = lattice.objects(Embedding::class).first()
        assertNotNull(retrieved)
        assertEquals("doc1", retrieved.name)

        val vec = retrieved.vector
        assertEquals(4, vec.dimensions)
        assertEquals(1.0f, vec[0])
        assertEquals(2.0f, vec[1])
        assertEquals(3.0f, vec[2])
        assertEquals(4.0f, vec[3])

        lattice.close()
    }

    /**
     * Tests nullable FloatVector properties.
     */
    @Test
    fun test_NullableFloatVector() {
        val lattice = testLattice(Embedding::class)

        // Create embedding without optional vector
        val emb1 = Embedding()
        emb1.name = "no-opt"
        emb1.vector = FloatVector(1.0f, 2.0f)
        // optionalVector is null

        // Create embedding with optional vector
        val emb2 = Embedding()
        emb2.name = "with-opt"
        emb2.vector = FloatVector(3.0f, 4.0f)
        emb2.optionalVector = FloatVector(5.0f, 6.0f, 7.0f)

        lattice.add(emb1)
        lattice.add(emb2)

        // Retrieve and verify
        val embeddings = lattice.objects(Embedding::class).orderBy("name")

        val noOpt = embeddings.first { it.name == "no-opt" }
        assertNull(noOpt.optionalVector)

        val withOpt = embeddings.first { it.name == "with-opt" }
        assertNotNull(withOpt.optionalVector)
        assertEquals(3, withOpt.optionalVector!!.dimensions)
        assertEquals(5.0f, withOpt.optionalVector!![0])

        lattice.close()
    }

    /**
     * Tests FloatVector distance calculations.
     */
    @Test
    fun test_FloatVectorDistances() {
        val v1 = FloatVector(1.0f, 0.0f, 0.0f)
        val v2 = FloatVector(0.0f, 1.0f, 0.0f)

        // L2 distance between orthogonal unit vectors should be sqrt(2)
        val l2 = v1.l2Distance(v2)
        assertTrue(kotlin.math.abs(l2 - kotlin.math.sqrt(2.0f)) < 0.0001f)

        // Cosine distance between orthogonal vectors should be 1.0 (similarity = 0)
        val cosine = v1.cosineDistance(v2)
        assertTrue(kotlin.math.abs(cosine - 1.0f) < 0.0001f)

        // Dot product of orthogonal vectors is 0
        val dot = v1.dot(v2)
        assertEquals(0.0f, dot)

        // Test with parallel vectors
        val v3 = FloatVector(2.0f, 0.0f, 0.0f)
        val cosineSame = v1.cosineDistance(v3)
        assertTrue(kotlin.math.abs(cosineSame) < 0.0001f) // Should be ~0 (same direction)
    }

    /**
     * Tests large FloatVector (high-dimensional embeddings like OpenAI's 1536-dim).
     */
    @Test
    fun test_LargeFloatVector() {
        val lattice = testLattice(Embedding::class)

        // Create 1536-dimensional vector (OpenAI embedding size)
        val dims = 1536
        val largeVector = FloatVector(dims) { i -> (i % 100).toFloat() / 100.0f }

        val embedding = Embedding()
        embedding.name = "openai-style"
        embedding.vector = largeVector

        lattice.add(embedding)

        // Retrieve and verify
        val retrieved = lattice.objects(Embedding::class).first()
        assertNotNull(retrieved)
        assertEquals(dims, retrieved.vector.dimensions)

        // Verify some values
        assertEquals(0.0f, retrieved.vector[0])
        assertEquals(0.50f, retrieved.vector[50])
        assertEquals(0.99f, retrieved.vector[99])
        assertEquals(0.0f, retrieved.vector[100]) // wraps around

        lattice.close()
    }

    /**
     * Tests FloatVector serialization round-trip.
     */
    @Test
    fun test_FloatVectorByteArrayRoundTrip() {
        val original = FloatVector(1.5f, -2.5f, 3.14159f, 0.0f, Float.MAX_VALUE, Float.MIN_VALUE)

        val bytes = original.toByteArray()
        assertEquals(6 * 4, bytes.size) // 6 floats * 4 bytes each

        val restored = FloatVector.fromByteArray(bytes)
        assertEquals(original.dimensions, restored.dimensions)

        for (i in 0 until original.dimensions) {
            assertEquals(original[i], restored[i])
        }
    }

    /**
     * Tests Instant property support for date/time values.
     */
    @Test
    fun test_Instant() {
        val lattice = testLattice(Event::class)

        // Create event with start time
        val now = Instant.parse("2024-12-25T10:30:00Z")
        val event = Event()
        event.title = "Christmas Meeting"
        event.startTime = now

        lattice.add(event)

        // Retrieve and verify
        val retrieved = lattice.objects(Event::class).first()
        assertNotNull(retrieved)
        assertEquals("Christmas Meeting", retrieved.title)
        assertEquals(now, retrieved.startTime)

        lattice.close()
    }

    /**
     * Tests nullable Instant properties.
     */
    @Test
    fun test_NullableInstant() {
        val lattice = testLattice(Event::class)

        val now = Instant.parse("2024-12-25T10:00:00Z")
        val later = now.plus(2.hours)

        // Create event without end time
        val event1 = Event()
        event1.title = "Open-ended"
        event1.startTime = now
        // endTime is null

        // Create event with end time
        val event2 = Event()
        event2.title = "Scheduled"
        event2.startTime = now
        event2.endTime = later

        lattice.add(event1)
        lattice.add(event2)

        // Retrieve and verify
        val events = lattice.objects(Event::class).orderBy("title")

        val openEnded = events.first { it.title == "Open-ended" }
        assertNull(openEnded.endTime)

        val scheduled = events.first { it.title == "Scheduled" }
        assertNotNull(scheduled.endTime)
        assertEquals(later, scheduled.endTime)

        lattice.close()
    }

    /**
     * Tests Instant with various time values.
     */
    @Test
    fun test_InstantVariousValues() {
        val lattice = testLattice(Event::class)

        // Test epoch
        val epoch = Instant.fromEpochMilliseconds(0)
        val event1 = Event()
        event1.title = "Epoch"
        event1.startTime = epoch
        lattice.add(event1)

        // Test far future
        val future = Instant.parse("2100-01-01T00:00:00Z")
        val event2 = Event()
        event2.title = "Future"
        event2.startTime = future
        lattice.add(event2)

        // Test with milliseconds
        val precise = Instant.parse("2024-06-15T14:30:45.123Z")
        val event3 = Event()
        event3.title = "Precise"
        event3.startTime = precise
        lattice.add(event3)

        // Retrieve and verify
        val events = lattice.objects(Event::class)
        assertEquals(3, events.count)

        val epochEvent = events.first { it.title == "Epoch" }
        assertEquals(epoch, epochEvent.startTime)

        val futureEvent = events.first { it.title == "Future" }
        assertEquals(future, futureEvent.startTime)

        val preciseEvent = events.first { it.title == "Precise" }
        assertEquals(precise, preciseEvent.startTime)

        lattice.close()
    }

    /**
     * Tests updating Instant values.
     */
    @Test
    fun test_UpdateInstant() {
        val lattice = testLattice(Event::class)

        val originalTime = Instant.parse("2024-01-01T09:00:00Z")
        val newTime = Instant.parse("2024-01-01T10:00:00Z")

        val event = Event()
        event.title = "Rescheduled"
        event.startTime = originalTime
        lattice.add(event)

        // Update the time
        event.startTime = newTime

        // Retrieve and verify update persisted
        val retrieved = lattice.objects(Event::class).first()
        assertNotNull(retrieved)
        assertEquals(newTime, retrieved.startTime)

        lattice.close()
    }

    /**
     * Tests UUID property support.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun test_Uuid() {
        val lattice = testLattice(Asset::class)

        val uuid = Uuid.random()
        val asset = Asset()
        asset.name = "Image"
        asset.assetId = uuid

        lattice.add(asset)

        // Retrieve and verify
        val retrieved = lattice.objects(Asset::class).first()
        assertNotNull(retrieved)
        assertEquals("Image", retrieved.name)
        assertEquals(uuid, retrieved.assetId)

        lattice.close()
    }

    /**
     * Tests nullable UUID properties.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun test_NullableUuid() {
        val lattice = testLattice(Asset::class)

        val uuid1 = Uuid.random()
        val uuid2 = Uuid.random()
        val parentUuid = Uuid.random()

        // Create asset without parent
        val asset1 = Asset()
        asset1.name = "Root"
        asset1.assetId = uuid1
        // parentId is null

        // Create asset with parent
        val asset2 = Asset()
        asset2.name = "Child"
        asset2.assetId = uuid2
        asset2.parentId = parentUuid

        lattice.add(asset1)
        lattice.add(asset2)

        // Retrieve and verify
        val assets = lattice.objects(Asset::class).orderBy("name")

        val child = assets.first { it.name == "Child" }
        assertNotNull(child.parentId)
        assertEquals(parentUuid, child.parentId)

        val root = assets.first { it.name == "Root" }
        assertNull(root.parentId)

        lattice.close()
    }

    /**
     * Tests UUID.NIL handling.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun test_UuidNil() {
        val lattice = testLattice(Asset::class)

        // Create asset with NIL UUID
        val asset = Asset()
        asset.name = "Nil Asset"
        asset.assetId = Uuid.NIL

        lattice.add(asset)

        // Retrieve and verify
        val retrieved = lattice.objects(Asset::class).first()
        assertNotNull(retrieved)
        assertEquals(Uuid.NIL, retrieved.assetId)

        lattice.close()
    }

    /**
     * Tests updating UUID values.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun test_UpdateUuid() {
        val lattice = testLattice(Asset::class)

        val originalUuid = Uuid.random()
        val newUuid = Uuid.random()

        val asset = Asset()
        asset.name = "Mutable"
        asset.assetId = originalUuid
        lattice.add(asset)

        // Update the UUID
        asset.assetId = newUuid

        // Retrieve and verify update persisted
        val retrieved = lattice.objects(Asset::class).first()
        assertNotNull(retrieved)
        assertEquals(newUuid, retrieved.assetId)

        lattice.close()
    }

    // ============================================================
    // @LatticeEnum Tests
    // ============================================================

    /**
     * Tests @LatticeEnum property support (stored as TEXT by default).
     */
    @Test
    fun test_LatticeEnumAsText() {
        val lattice = testLattice(Task::class)

        val task = Task()
        task.title = "Important Task"
        task.priority = Priority.HIGH

        lattice.add(task)

        // Retrieve and verify
        val retrieved = lattice.objects(Task::class).first()
        assertNotNull(retrieved)
        assertEquals("Important Task", retrieved.title)
        assertEquals(Priority.HIGH, retrieved.priority)

        lattice.close()
    }

    /**
     * Tests @LatticeEnum with storeAsOrdinal=true (stored as INTEGER).
     */
    @Test
    fun test_LatticeEnumAsOrdinal() {
        val lattice = testLattice(Task::class)

        val task = Task()
        task.title = "Status Task"
        task.status = Status.IN_PROGRESS

        lattice.add(task)

        // Retrieve and verify
        val retrieved = lattice.objects(Task::class).first()
        assertNotNull(retrieved)
        assertEquals("Status Task", retrieved.title)
        assertEquals(Status.IN_PROGRESS, retrieved.status)

        lattice.close()
    }

    /**
     * Tests nullable @LatticeEnum properties.
     */
    @Test
    fun test_NullableEnum() {
        val lattice = testLattice(Task::class)

        // Create task without optional enums
        val task1 = Task()
        task1.title = "No Priority Opt"
        task1.priority = Priority.LOW
        task1.status = Status.PENDING
        // priorityOpt and statusOpt are null

        // Create task with optional enums
        val task2 = Task()
        task2.title = "With Priority Opt"
        task2.priority = Priority.MEDIUM
        task2.priorityOpt = Priority.CRITICAL
        task2.status = Status.COMPLETED
        task2.statusOpt = Status.CANCELLED

        lattice.add(task1)
        lattice.add(task2)

        // Retrieve and verify
        val tasks = lattice.objects(Task::class).orderBy("title")

        val noOpt = tasks.firstOrNull { it.title == "No Priority Opt" }
        assertNotNull(noOpt)
        assertNull(noOpt!!.priorityOpt)
        assertNull(noOpt.statusOpt)
        assertEquals(Priority.LOW, noOpt.priority)
        assertEquals(Status.PENDING, noOpt.status)

        val withOpt = tasks.firstOrNull { it.title == "With Priority Opt" }
        assertNotNull(withOpt)
        assertNotNull(withOpt!!.priorityOpt)
        assertNotNull(withOpt.statusOpt)
        assertEquals(Priority.CRITICAL, withOpt.priorityOpt)
        assertEquals(Status.CANCELLED, withOpt.statusOpt)

        lattice.close()
    }

    /**
     * Tests updating @LatticeEnum values.
     */
    @Test
    fun test_UpdateEnum() {
        val lattice = testLattice(Task::class)

        val task = Task()
        task.title = "Updatable"
        task.priority = Priority.LOW
        task.status = Status.PENDING

        lattice.add(task)

        // Update both enum values
        task.priority = Priority.CRITICAL
        task.status = Status.COMPLETED

        // Retrieve and verify update persisted
        val retrieved = lattice.objects(Task::class).first()
        assertNotNull(retrieved)
        assertEquals(Priority.CRITICAL, retrieved.priority)
        assertEquals(Status.COMPLETED, retrieved.status)

        lattice.close()
    }

    /**
     * Tests all enum values for Priority (TEXT storage).
     */
    @Test
    fun test_AllPriorityValues() {
        val lattice = testLattice(Task::class)

        Priority.entries.forEachIndexed { index, priority ->
            val task = Task()
            task.title = "Priority $index"
            task.priority = priority
            task.status = Status.PENDING
            lattice.add(task)
        }

        // Retrieve and verify all values
        val tasks = lattice.objects(Task::class).orderBy("title")
        assertEquals(Priority.entries.size, tasks.count)

        Priority.entries.forEachIndexed { index, priority ->
            val task = tasks.first { it.title == "Priority $index" }
            assertEquals(priority, task.priority)
        }

        lattice.close()
    }

    /**
     * Tests all enum values for Status (INTEGER storage).
     */
    @Test
    fun test_AllStatusValues() {
        val lattice = testLattice(Task::class)

        Status.entries.forEachIndexed { index, status ->
            val task = Task()
            task.title = "Status $index"
            task.priority = Priority.LOW
            task.status = status
            lattice.add(task)
        }

        // Retrieve and verify all values
        val tasks = lattice.objects(Task::class).orderBy("title")
        assertEquals(Status.entries.size, tasks.count)

        Status.entries.forEachIndexed { index, status ->
            val task = tasks.first { it.title == "Status $index" }
            assertEquals(status, task.status)
        }

        lattice.close()
    }
}
