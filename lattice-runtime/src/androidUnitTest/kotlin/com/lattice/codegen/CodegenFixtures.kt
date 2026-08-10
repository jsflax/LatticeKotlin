package com.lattice.codegen

import com.lattice.Embedded
import com.lattice.FloatVector
import com.lattice.FullText
import com.lattice.Indexed
import com.lattice.LatticeEmbedded
import com.lattice.LatticeEnum
import com.lattice.LatticeList
import com.lattice.Link
import com.lattice.Model
import com.lattice.Unique
import com.lattice.VirtualList
import com.lattice.VirtualModel
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

// @Embedded on the CLASS is REQUIRED: the transformer keys on the annotation on
// the property's type, not the LatticeEmbedded supertype. Without it embedded
// properties fall into the scalar fallback (getString + CHECKCAST — a guaranteed
// CCE on JVM), which is exactly what this fixture silently pinned before the
// annotation was added, leaving transformEmbeddedGetter/Setter with zero golden
// coverage across the 2.4 migration.
@Embedded
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

/**
 * Constraint annotations — pins NONZERO flag bits in the _latticeSchema golden
 * (unique=1, indexed=2, full-text=4). Every other fixture emits ':0', so an
 * always-zero regression in the flags emission would pass every golden but
 * this one.
 */
@Model
class GoldenConstraints {
    @Unique
    var email: String = ""

    @Indexed
    var city: String = ""

    @FullText
    var bio: String = ""

    var plain: String = ""
}

/** Polymorphic supertype for the VirtualList fixture. */
interface GoldenSearchable : VirtualModel

@Model
class GoldenVirtuals {
    var name: String = ""

    // VirtualList is detected by TYPE NAME in the transformer; this is the only
    // fixture in either suite that runs a VirtualList property through codegen
    // (the native VirtualListTests exercise the container API, never a
    // transformed property).
    var results: VirtualList<GoldenSearchable> = VirtualList()

    var vector: FloatVector = FloatVector()
    var optionalVector: FloatVector? = null
}

/** Explicit fallback entry, rather than the first-declared default. */
@LatticeEnum(defaultValue = "TRIAGE")
enum class GoldenSeverity { CRITICAL, TRIAGE, MINOR }

@Model
class GoldenEnums {
    var priority: GoldenPriority = GoldenPriority.MEDIUM
    var priorityOpt: GoldenPriority? = null
    var status: GoldenStatus = GoldenStatus.PENDING
    var statusOpt: GoldenStatus? = null
    var severity: GoldenSeverity = GoldenSeverity.TRIAGE
    var severityOpt: GoldenSeverity? = null
}
