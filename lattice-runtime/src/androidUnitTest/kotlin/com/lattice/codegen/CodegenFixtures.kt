package com.lattice.codegen

import com.lattice.LatticeEmbedded
import com.lattice.LatticeList
import com.lattice.Link
import com.lattice.LatticeEnum
import com.lattice.Model
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Fixtures for [GeneratedAccessorGoldenTest]. Deliberately mirrors the shapes in
 * `nativeTest/TestModels.kt` so the two suites cover the same surface — but these
 * exist to be COMPILED, not run: the golden test reads the emitted bytecode off
 * the test classpath.
 *
 * Every property kind the transform handles must appear here at least once, in
 * both its non-null and nullable form. A kind that isn't declared here has no
 * golden, and a migration is free to break it silently — which is exactly how
 * the native suite came to miss collections and `VirtualList`.
 */

@Model
class GoldenScalars {
    var string: String = ""
    var stringOpt: String? = null

    var bool: Boolean = false
    var boolOpt: Boolean? = null

    var int: Int = 0
    var intOpt: Int? = null

    var long: Long = 0L
    var longOpt: Long? = null

    var float: Float = 0f
    var floatOpt: Float? = null

    var double: Double = 0.0
    var doubleOpt: Double? = null
}

@Model
class GoldenTemporal {
    var title: String = ""

    // The datetime landmine: the transform matches `kotlinx.datetime.Instant` as a
    // literal FQN string, with a silent `getString`/`setString` fallback. If the
    // FQN ever moves, these goldens flip REAL -> TEXT and this test is the only
    // thing standing between that and shipped data corruption.
    var startTime: Instant = Instant.fromEpochMilliseconds(0)
    var endTime: Instant? = null
}

@OptIn(ExperimentalUuidApi::class)
@Model
class GoldenIdentifiers {
    var name: String = ""
    var assetId: Uuid = Uuid.NIL
    var parentId: Uuid? = null
}

@Model
class GoldenBinary {
    var name: String = ""
    var content: ByteArray = ByteArray(0)
    var thumbnail: ByteArray? = null
}

@Serializable
data class GoldenAddress(
    val street: String = "",
    val city: String = "",
    val zipCode: String = "",
) : LatticeEmbedded

@Model
class GoldenEmbeds {
    var name: String = ""
    var address: GoldenAddress = GoldenAddress()
    var workAddress: GoldenAddress? = null
}

@Model
class GoldenLinkTarget {
    var name: String = ""
    var number: Int = 0
}

@Model
class GoldenLinks {
    var name: String = ""

    @Link
    var one: GoldenLinkTarget? = null

    // Collections: zero round-trip coverage in nativeTest, and the LatticeList
    // SETTER is never invoked there at all.
    var many: LatticeList<GoldenLinkTarget> = LatticeList()
}

@Model
class GoldenCollections {
    var name: String = ""
    var tags: List<String> = emptyList()
    var counts: List<Int> = emptyList()
}

@LatticeEnum
enum class GoldenPriority { LOW, MEDIUM, HIGH }

@LatticeEnum(storeAsOrdinal = true)
enum class GoldenStatus { PENDING, ACTIVE, DONE }

@Model
class GoldenEnums {
    var priority: GoldenPriority = GoldenPriority.MEDIUM
    var priorityOpt: GoldenPriority? = null
    var status: GoldenStatus = GoldenStatus.PENDING
    var statusOpt: GoldenStatus? = null
}
