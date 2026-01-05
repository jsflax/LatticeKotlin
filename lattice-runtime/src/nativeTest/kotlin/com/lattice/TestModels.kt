package com.lattice

import kotlin.random.Random
import kotlin.reflect.KClass
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Test models for LatticeKotlin tests.
 * These mirror the Swift test models.
 */

// Register model factories for schema discovery
// In production, the compiler plugin would generate this
@OptIn(ExperimentalUuidApi::class)
fun registerTestModelFactories() {
    Lattice.registerFactory(Person::class) { Person() }
    Lattice.registerFactory(Dog::class) { Dog() }
    Lattice.registerFactory(Trip::class) { Trip() }
    Lattice.registerFactory(AllTypesObject::class) { AllTypesObject() }
    Lattice.registerFactory(Team::class) { Team() }
    Lattice.registerFactory(Player::class) { Player() }
    Lattice.registerFactory(Contact::class) { Contact() }
    Lattice.registerFactory(Document::class) { Document() }
    Lattice.registerFactory(Embedding::class) { Embedding() }
    Lattice.registerFactory(Event::class) { Event() }
    Lattice.registerFactory(Asset::class) { Asset() }
    Lattice.registerFactory(Task::class) { Task() }
    Lattice.registerFactory(SimpleSyncObject::class) { SimpleSyncObject() }

    // Register embedded model serializers
    registerEmbeddedSerializer(Address.serializer()) { Address() }
}

/**
 * Helper function to create a Lattice instance with a unique temporary path.
 * This mirrors Swift's testLattice() helper for test isolation.
 */
fun testLattice(vararg types: KClass<out LatticeObject>): Lattice {
    // Generate unique path to ensure test isolation
    val randomSuffix = Random.nextLong().toString(16)
    val path = "/tmp/lattice_test_${randomSuffix}.sqlite"
    return Lattice(path, *types)
}

@Model
class Person {
    var name: String = ""
    var age: Int = 0

    @Link
    var dog: Dog? = null
}

@Model
class Dog {
    var name: String = ""

    @Link
    var owner: Person? = null
}

@Model
class Trip {
    var name: String = ""
    var days: Int = 0
    var budget: Double = 0.0
    var isBooked: Boolean = false
}

@Model
class AllTypesObject {
    // String
    var string: String = ""
    var stringOpt: String? = null

    // Bool
    var bool: Boolean = false
    var boolOpt: Boolean? = null

    // Int
    var int: Int = 0
    var intOpt: Int? = null

    // Long
    var long: Long = 0L
    var longOpt: Long? = null

    // Float
    var float: Float = 0f
    var floatOpt: Float? = null

    // Double
    var double: Double = 0.0
    var doubleOpt: Double? = null

    // TODO: Add more types as supported
    // var date: Date = Date()
    // var data: ByteArray = byteArrayOf()
}

/**
 * Team model with a list of players for testing link lists.
 * No @LinkList annotation needed - detected by LatticeList<T> type.
 */
@Model
class Team {
    var name: String = ""
    var players: LatticeList<Player> = LatticeList()
}

/**
 * Player model for testing @LinkList relationships.
 */
@Model
class Player {
    var name: String = ""
    var number: Int = 0
}

/**
 * Embedded model for testing - stored as JSON in the database.
 * Must be annotated with both @Embedded and @Serializable.
 */
@Embedded
@Serializable
data class Address(
    val street: String = "",
    val city: String = "",
    val zipCode: String = ""
) : LatticeEmbedded

/**
 * Contact model with an embedded Address.
 */
@Model
class Contact {
    var name: String = ""
    var email: String = ""
    var address: Address = Address()
    var workAddress: Address? = null
}

/**
 * Document model for testing ByteArray/Blob support.
 */
@Model
class Document {
    var name: String = ""
    var content: ByteArray = ByteArray(0)
    var thumbnail: ByteArray? = null
}

/**
 * Embedding model for testing FloatVector support (vector embeddings for ML/AI).
 */
@Model
class Embedding {
    var name: String = ""
    var vector: FloatVector = FloatVector()
    var optionalVector: FloatVector? = null
}

/**
 * Event model for testing Instant/DateTime support.
 */
@Model
class Event {
    var title: String = ""
    var startTime: Instant = Instant.fromEpochMilliseconds(0)
    var endTime: Instant? = null
}

/**
 * Asset model for testing UUID support.
 */
@OptIn(ExperimentalUuidApi::class)
@Model
class Asset {
    var name: String = ""
    var assetId: Uuid = Uuid.NIL
    var parentId: Uuid? = null
}

/**
 * Enum for testing @LatticeEnum support (stored as TEXT by default).
 */
@LatticeEnum
enum class Priority {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * Enum for testing @LatticeEnum with storeAsOrdinal=true (stored as INTEGER).
 */
@LatticeEnum(storeAsOrdinal = true)
enum class Status {
    PENDING, IN_PROGRESS, COMPLETED, CANCELLED
}

/**
 * Task model for testing enum properties.
 */
@Model
class Task {
    var title: String = ""
    var priority: Priority = Priority.MEDIUM
    var priorityOpt: Priority? = null
    var status: Status = Status.PENDING
    var statusOpt: Status? = null
}

/**
 * Simple sync object for testing sync functionality.
 */
@Model
class SimpleSyncObject {
    var value: Int = 0
    var floatValue: Float = 0f
}
