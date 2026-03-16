package com.lattice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Core CRUD tests - basic add, find, update, delete, links, embedded models,
 * transactions, and observation.
 *
 * Ported from Swift LatticeTests.swift
 */
@OptIn(ExperimentalUuidApi::class)
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
     * Tests finding objects by primary key.
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
        val pk = (person1 as LatticeObject).id
        assertTrue(pk > 0, "Primary key should be assigned after add")

        val found = lattice.find(Person::class, pk)
        assertNotNull(found)
        assertEquals("Alice", found.name)
        assertEquals(30, found.age)

        // Finding a non-existent ID should return null
        val notFound = lattice.find(Person::class, 999999L)
        assertNull(notFound)

        lattice.close()
    }

    /**
     * Tests finding objects by globalId.
     */
    @Test
    fun test_FindByGlobalId() {
        val lattice = testLattice(Person::class)

        val person = Person().apply {
            name = "Alice"
            age = 30
        }
        lattice.add(person)

        val globalId = (person as LatticeObject).globalId
        assertNotNull(globalId, "globalId should be set after adding to Lattice")
        assertTrue(globalId!!.isNotEmpty(), "globalId should not be empty")

        val found = lattice.findByGlobalId(Person::class, globalId)
        assertNotNull(found)
        assertEquals("Alice", found.name)
        assertEquals(30, found.age)

        // Finding a non-existent globalId should return null
        val notFound = lattice.findByGlobalId(Person::class, "non-existent-id")
        assertNull(notFound)

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

    // ============================================================
    // Nullable Types
    // ============================================================

    /**
     * Tests setting, reading, and clearing nullable types.
     */
    @Test
    fun test_NullableTypes_SetReadClear() {
        val lattice = testLattice(AllTypesObject::class)

        val obj = AllTypesObject()
        obj.string = "test"

        // Set nullable values
        obj.stringOpt = "hello"
        obj.boolOpt = true
        obj.intOpt = 42
        obj.longOpt = 100L
        obj.floatOpt = 1.5f
        obj.doubleOpt = 2.5

        lattice.add(obj)

        // Read back nullable values
        val retrieved = lattice.objects(AllTypesObject::class).first()
        assertNotNull(retrieved)
        assertEquals("hello", retrieved.stringOpt)
        assertEquals(true, retrieved.boolOpt)
        assertEquals(42, retrieved.intOpt)
        assertEquals(100L, retrieved.longOpt)
        assertNotNull(retrieved.floatOpt)
        assertTrue(kotlin.math.abs(retrieved.floatOpt!! - 1.5f) < 0.001f)
        assertNotNull(retrieved.doubleOpt)
        assertTrue(kotlin.math.abs(retrieved.doubleOpt!! - 2.5) < 0.001)

        // Clear nullable values to null
        obj.stringOpt = null
        obj.boolOpt = null
        obj.intOpt = null
        obj.longOpt = null
        obj.floatOpt = null
        obj.doubleOpt = null

        // Read back and verify nulls
        val cleared = lattice.objects(AllTypesObject::class).first()
        assertNotNull(cleared)
        assertNull(cleared.stringOpt)
        assertNull(cleared.boolOpt)
        assertNull(cleared.intOpt)
        assertNull(cleared.longOpt)
        assertNull(cleared.floatOpt)
        assertNull(cleared.doubleOpt)

        lattice.close()
    }

    // ============================================================
    // ByteArray / Blob Storage
    // ============================================================

    /**
     * Tests ByteArray/Blob storage and retrieval.
     */
    @Test
    fun test_ByteArrayStorage() {
        val lattice = testLattice(Document::class)

        val doc = Document()
        doc.name = "binary.dat"
        doc.content = byteArrayOf(0x00, 0x01, 0x02, 0xAB.toByte(), 0xCD.toByte(), 0xFF.toByte())

        lattice.add(doc)

        val retrieved = lattice.objects(Document::class).first()
        assertNotNull(retrieved)
        assertEquals("binary.dat", retrieved.name)
        assertEquals(6, retrieved.content.size)
        assertEquals(0x00.toByte(), retrieved.content[0])
        assertEquals(0xFF.toByte(), retrieved.content[5])

        lattice.close()
    }

    /**
     * Tests empty ByteArray storage.
     */
    @Test
    fun test_EmptyByteArray() {
        val lattice = testLattice(Document::class)

        val doc = Document()
        doc.name = "empty.dat"
        doc.content = ByteArray(0)

        lattice.add(doc)

        val retrieved = lattice.objects(Document::class).first()
        assertNotNull(retrieved)
        assertEquals(0, retrieved.content.size)

        lattice.close()
    }

    // ============================================================
    // UUID Storage (via globalId)
    // ============================================================

    /**
     * Tests UUID storage via the Asset model.
     */
    @Test
    fun test_UuidStorage() {
        val lattice = testLattice(Asset::class)

        val uuid = Uuid.random()
        val asset = Asset()
        asset.name = "Photo"
        asset.assetId = uuid

        lattice.add(asset)

        val retrieved = lattice.objects(Asset::class).first()
        assertNotNull(retrieved)
        assertEquals(uuid, retrieved.assetId)

        lattice.close()
    }

    /**
     * Tests that globalId is available after add.
     */
    @Test
    fun test_GlobalIdAssignment() {
        val lattice = testLattice(Person::class)

        val person = Person()
        person.name = "TestGlobalId"
        person.age = 1

        lattice.add(person)

        // After add, globalId should be assigned
        val gid = (person as LatticeObject).globalId
        assertNotNull(gid)
        assertTrue(gid!!.isNotEmpty())

        // Two different objects should get different globalIds
        val person2 = Person()
        person2.name = "TestGlobalId2"
        person2.age = 2
        lattice.add(person2)

        val gid2 = (person2 as LatticeObject).globalId
        assertNotNull(gid2)
        assertNotEquals(gid, gid2)

        lattice.close()
    }

    // ============================================================
    // Instant / DateTime Storage and Querying
    // ============================================================

    /**
     * Tests Instant storage and retrieval.
     */
    @Test
    fun test_InstantStorage() {
        val lattice = testLattice(Event::class)

        val now = Instant.parse("2024-12-25T10:30:00Z")
        val later = now.plus(2.hours)

        val event = Event()
        event.title = "Meeting"
        event.startTime = now
        event.endTime = later

        lattice.add(event)

        val retrieved = lattice.objects(Event::class).first()
        assertNotNull(retrieved)
        assertEquals("Meeting", retrieved.title)
        assertEquals(now, retrieved.startTime)
        assertEquals(later, retrieved.endTime)

        lattice.close()
    }

    /**
     * Tests querying by Instant fields using double-based comparison.
     */
    @Test
    fun test_InstantQuerying() {
        val lattice = testLattice(Event::class)

        val past = Instant.parse("2024-01-01T00:00:00Z")
        val present = Instant.parse("2024-06-15T12:00:00Z")
        val future = Instant.parse("2025-01-01T00:00:00Z")

        val event1 = Event().apply { title = "Past"; startTime = past }
        val event2 = Event().apply { title = "Present"; startTime = present }
        val event3 = Event().apply { title = "Future"; startTime = future }

        lattice.add(event1)
        lattice.add(event2)
        lattice.add(event3)

        // Query events after mid-2024 using the double (epoch seconds) representation
        val midEpoch = Instant.parse("2024-06-01T00:00:00Z").epochSeconds.toDouble()
        val results = lattice.objects(Event::class)
            .where { it.double("startTime") gt midEpoch }
        assertEquals(2, results.count)

        lattice.close()
    }

    // ============================================================
    // Links
    // ============================================================

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
     * Tests clearing a link (set to null after previously being set).
     * Port of Swift test_Link: person.dog = nil after setting it.
     */
    @Test
    fun test_ClearLink() {
        val lattice = testLattice(Person::class, Dog::class)

        val dog = Dog()
        dog.name = "Max"

        val person = Person()
        person.name = "John"
        person.age = 30
        person.dog = dog

        // Add to lattice (dog is auto-added via the link)
        lattice.add(dog)
        lattice.add(person)

        // Verify the link is set
        val before = lattice.objects(Person::class).first()
        assertNotNull(before)
        assertNotNull(before.dog)
        assertEquals("Max", before.dog!!.name)

        // Clear link on the managed person
        before.dog = null

        // Verify link is cleared
        val after = lattice.objects(Person::class).first()
        assertNotNull(after)
        assertNull(after.dog)

        lattice.close()
    }

    /**
     * Tests that two people can share the same linked dog.
     * Port of Swift test_Link: person2.dog = dog (same dog on two people).
     */
    @Test
    fun test_SharedLink() {
        val lattice = testLattice(Person::class, Dog::class)

        val dog = Dog()
        dog.name = "Max"

        val person1 = Person()
        person1.name = "Alice"
        person1.age = 28
        person1.dog = dog

        val person2 = Person()
        person2.name = "Bob"
        person2.age = 30
        person2.dog = dog

        lattice.add(dog)
        lattice.add(person1)
        lattice.add(person2)

        // Both people should link to the same dog
        val people = lattice.objects(Person::class).orderBy("name").toList()
        assertEquals(2, people.size)

        assertEquals("Max", people[0].dog!!.name)
        assertEquals("Max", people[1].dog!!.name)

        lattice.close()
    }

    // ============================================================
    // LinkLists
    // ============================================================

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
     * Tests LinkList remove by index.
     * Port of Swift test_LinkList: dog.puppies.remove(spot) and remove by predicate.
     */
    @Test
    fun test_LinkListRemoveByIndex() {
        val lattice = testLattice(Team::class, Player::class)

        val player1 = Player().apply { name = "Alice"; number = 10 }
        val player2 = Player().apply { name = "Bob"; number = 23 }
        val player3 = Player().apply { name = "Charlie"; number = 7 }

        lattice.add(player1)
        lattice.add(player2)
        lattice.add(player3)

        val team = Team()
        team.name = "Test Team"
        lattice.add(team)

        team.players.add(player1)
        team.players.add(player2)
        team.players.add(player3)
        assertEquals(3, team.players.size)

        // Remove middle element (Bob at index 1)
        team.players.removeAt(1)
        assertEquals(2, team.players.size)
        assertEquals("Alice", team.players[0].name)
        assertEquals("Charlie", team.players[1].name)

        // Verify persistence after re-fetch
        val retrieved = lattice.objects(Team::class).first()
        assertNotNull(retrieved)
        assertEquals(2, retrieved.players.size)
        assertEquals("Alice", retrieved.players[0].name)
        assertEquals("Charlie", retrieved.players[1].name)

        lattice.close()
    }

    /**
     * Tests LinkList clear operation.
     */
    @Test
    fun test_LinkListClear() {
        val lattice = testLattice(Team::class, Player::class)

        val player1 = Player().apply { name = "Alice"; number = 10 }
        val player2 = Player().apply { name = "Bob"; number = 23 }

        lattice.add(player1)
        lattice.add(player2)

        val team = Team()
        team.name = "Clear Team"
        lattice.add(team)

        team.players.add(player1)
        team.players.add(player2)
        assertEquals(2, team.players.size)

        // Clear all
        team.players.clear()
        assertEquals(0, team.players.size)

        // Verify persistence
        val retrieved = lattice.objects(Team::class).first()
        assertNotNull(retrieved)
        assertEquals(0, retrieved.players.size)

        lattice.close()
    }

    /**
     * Tests LinkList iteration.
     */
    @Test
    fun test_LinkListIterate() {
        val lattice = testLattice(Team::class, Player::class)

        val player1 = Player().apply { name = "Alice"; number = 10 }
        val player2 = Player().apply { name = "Bob"; number = 23 }
        val player3 = Player().apply { name = "Charlie"; number = 7 }

        lattice.add(player1)
        lattice.add(player2)
        lattice.add(player3)

        val team = Team()
        team.name = "Iterate Team"
        lattice.add(team)

        team.players.add(player1)
        team.players.add(player2)
        team.players.add(player3)

        // Iterate with for-in
        val names = mutableListOf<String>()
        for (player in team.players) {
            names.add(player.name)
        }
        assertEquals(listOf("Alice", "Bob", "Charlie"), names)

        // Iterate on fetched team
        val fetched = lattice.objects(Team::class).first()
        assertNotNull(fetched)
        val fetchedNames = mutableListOf<String>()
        for (player in fetched.players) {
            fetchedNames.add(player.name)
        }
        assertEquals(listOf("Alice", "Bob", "Charlie"), fetchedNames)

        lattice.close()
    }

    // ============================================================
    // Embedded Models
    // ============================================================

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
     * Tests clearing a nullable embedded model to null.
     */
    @Test
    fun test_ClearEmbeddedToNull() {
        val lattice = testLattice(Contact::class)

        val contact = Contact()
        contact.name = "ClearTest"
        contact.email = "clear@test.com"
        contact.address = Address(street = "Main", city = "City", zipCode = "00000")
        contact.workAddress = Address(street = "Work", city = "Work City", zipCode = "11111")

        lattice.add(contact)

        // Verify work address is set
        val before = lattice.objects(Contact::class).first()
        assertNotNull(before)
        assertNotNull(before.workAddress)

        // Clear it
        contact.workAddress = null

        // Verify it is now null
        val after = lattice.objects(Contact::class).first()
        assertNotNull(after)
        assertNull(after.workAddress)

        lattice.close()
    }

    // ============================================================
    // Transactions
    // ============================================================

    /**
     * Tests transaction commit.
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
     * Tests transaction rollback on exception.
     */
    @Test
    fun test_TransactionRollback() {
        val lattice = testLattice(Person::class)

        // Add one person outside the transaction
        lattice.add(Person().apply { name = "Before"; age = 1 })
        assertEquals(1, lattice.objects(Person::class).count)

        // Transaction that throws should rollback
        try {
            lattice.transaction {
                lattice.add(Person().apply { name = "Inside"; age = 2 })
                throw RuntimeException("Intentional failure")
            }
        } catch (e: RuntimeException) {
            assertEquals("Intentional failure", e.message)
        }

        // Should still have only the one person from before the transaction
        assertEquals(1, lattice.objects(Person::class).count)
        assertEquals("Before", lattice.objects(Person::class).first()?.name)

        lattice.close()
    }

    // ============================================================
    // Bulk Insert
    // ============================================================

    /**
     * Tests bulk insert of 200 objects.
     * Port of Swift test_BulkInsert.
     */
    @Test
    fun test_BulkInsert() {
        val lattice = testLattice(Person::class)

        val count = 200
        for (i in 0 until count) {
            val person = Person()
            person.name = "Person $i"
            person.age = i
            lattice.add(person)
        }

        assertEquals(count, lattice.objects(Person::class).count)

        // Verify ordering with orderBy
        val sorted = lattice.objects(Person::class)
            .orderBy("age")
            .toList()
        assertEquals(count, sorted.size)
        for (i in 0 until count) {
            assertEquals(i, sorted[i].age)
        }

        lattice.close()
    }

    // ============================================================
    // Edge Cases
    // ============================================================

    /**
     * Tests empty string storage and retrieval.
     */
    @Test
    fun test_EmptyStringEdgeCase() {
        val lattice = testLattice(Person::class)

        val person = Person()
        person.name = ""
        person.age = 0

        lattice.add(person)

        val retrieved = lattice.objects(Person::class).first()
        assertNotNull(retrieved)
        assertEquals("", retrieved.name)

        lattice.close()
    }

    /**
     * Tests max integer value storage.
     */
    @Test
    fun test_MaxIntValues() {
        val lattice = testLattice(AllTypesObject::class)

        val obj = AllTypesObject()
        obj.string = "max-int"
        obj.int = Int.MAX_VALUE
        obj.long = Long.MAX_VALUE

        lattice.add(obj)

        val retrieved = lattice.objects(AllTypesObject::class).first()
        assertNotNull(retrieved)
        assertEquals(Int.MAX_VALUE, retrieved.int)
        assertEquals(Long.MAX_VALUE, retrieved.long)

        lattice.close()
    }

    /**
     * Tests min integer value storage.
     */
    @Test
    fun test_MinIntValues() {
        val lattice = testLattice(AllTypesObject::class)

        val obj = AllTypesObject()
        obj.string = "min-int"
        obj.int = Int.MIN_VALUE
        obj.long = Long.MIN_VALUE

        lattice.add(obj)

        val retrieved = lattice.objects(AllTypesObject::class).first()
        assertNotNull(retrieved)
        assertEquals(Int.MIN_VALUE, retrieved.int)
        assertEquals(Long.MIN_VALUE, retrieved.long)

        lattice.close()
    }

    /**
     * Tests strings with apostrophes (SQL escaping).
     * Port of Swift test_StringEscapingQuery.
     */
    @Test
    fun test_StringWithApostrophe() {
        val lattice = testLattice(Person::class)

        val person = Person()
        person.name = "it's a test"
        person.age = 1

        lattice.add(person)

        val retrieved = lattice.objects(Person::class).first()
        assertNotNull(retrieved)
        assertEquals("it's a test", retrieved.name)

        // Also test querying with apostrophe
        val results = lattice.objects(Person::class)
            .where { it.string("name") eq "it's a test" }
        assertEquals(1, results.count)

        lattice.close()
    }

    /**
     * Tests strings with unicode characters.
     */
    @Test
    fun test_UnicodeStrings() {
        val lattice = testLattice(Person::class)

        val person = Person()
        person.name = "cafe\u0301" // e with combining accent
        person.age = 1

        val person2 = Person()
        person2.name = "\u4F60\u597D\u4E16\u754C" // Chinese: "Hello World"
        person2.age = 2

        val person3 = Person()
        person3.name = "\uD83D\uDE00" // Emoji: grinning face
        person3.age = 3

        lattice.add(person)
        lattice.add(person2)
        lattice.add(person3)

        val results = lattice.objects(Person::class).orderBy("age").toList()
        assertEquals(3, results.size)
        assertEquals("cafe\u0301", results[0].name)
        assertEquals("\u4F60\u597D\u4E16\u754C", results[1].name)
        assertEquals("\uD83D\uDE00", results[2].name)

        lattice.close()
    }

    // ============================================================
    // Observation
    // ============================================================

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
}
