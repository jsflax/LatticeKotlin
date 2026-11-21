package com.lattice

/**
 * Column type for schema properties.
 */
enum class LatticeType {
    INTEGER,
    REAL,
    TEXT,
    BLOB
}

/**
 * Property kind for schema properties.
 */
enum class LatticePropertyKind {
    PRIMITIVE,
    LINK,
    LINK_LIST,
    VIRTUAL_LIST,
    VIRTUAL_LINK,
    UNION
}

/**
 * Describes one associated value within a @Union case.
 */
data class LatticeUnionCaseValue(
    val paramName: String,
    val type: LatticeType,
    val isLink: Boolean = false,
    val linkTarget: String? = null
)

/**
 * Describes one case of a @Union sealed hierarchy.
 */
data class LatticeUnionCase(
    val caseName: String,
    val values: List<LatticeUnionCaseValue> = emptyList()
)

/**
 * Describes a full @Union schema (`_<UnionName>` internal table + cases).
 */
data class LatticeUnionDescriptor(
    val unionTableName: String,
    val cases: List<LatticeUnionCase>
)

/**
 * Describes a property in the model schema.
 */
data class LatticePropertyDescriptor(
    val name: String,
    val type: LatticeType,
    val kind: LatticePropertyKind = LatticePropertyKind.PRIMITIVE,
    val nullable: Boolean = false,
    val targetTable: String? = null,
    val linkTable: String? = null,
    val isIndexed: Boolean = false,
    val isUnique: Boolean = false,
    val isFullText: Boolean = false,
    val isVector: Boolean = false,
    val isGeoBounds: Boolean = false,
    val columnName: String? = null,
    val isNoHistory: Boolean = false,
    val allowsUpsert: Boolean = false,
    val compoundedWith: List<String> = emptyList(),
    val unionDescriptor: LatticeUnionDescriptor? = null
)

/**
 * Marker singleton passed to compiler-synthesized internal hydration constructors
 * `constructor(handle: Long, marker: LatticeHydrationMarker)` so hydrating managed
 * database rows, links, and list elements never allocates a throwaway unmanaged C++ object.
 */
object LatticeHydrationMarker

/**
 * Interface injected by the compiler plugin into @Model classes.
 */
interface LatticeObject {
    var _latticeHandle: Long

    val _latticeTableName: String

    val _latticeSchema: List<LatticePropertyDescriptor>

    fun _copyPropertiesToNative(nativeHandle: Long)

    val isManaged: Boolean
        get() = _latticeHandle != 0L

    val globalId: String?
        get() {
            if (_latticeHandle == 0L) return null
            return NativeBridge.getObjectGlobalId(_latticeHandle)
                ?.takeIf { it.isNotEmpty() }
        }

    val id: Long
        get() {
            if (_latticeHandle == 0L) return 0L
            return NativeBridge.getObjectId(_latticeHandle)
        }

    /**
     * Materializes the full managed row in C++ so subsequent scalar, nullable,
     * @Embedded, collection, @LatticeEnum, Instant, Uuid, ByteArray, and FloatVector
     * reads execute zero SQLite SELECT statements.
     */
    fun enableRowCache() {
        if (_latticeHandle != 0L) {
            NativeBridge.enableRowCache(_latticeHandle)
        }
    }

    fun disableRowCache() {
        if (_latticeHandle != 0L) {
            NativeBridge.disableRowCache(_latticeHandle)
        }
    }

    fun refreshRowCache() {
        if (_latticeHandle != 0L) {
            NativeBridge.refreshRowCache(_latticeHandle)
        }
    }

    fun isRowCacheEnabled(): Boolean =
        _latticeHandle != 0L && NativeBridge.isRowCacheEnabled(_latticeHandle)

    /**
     * Atomically increments an integer column in SQL (`SET field = field + delta`).
     */
    fun incrementInt(property: String, delta: Long = 1L) {
        if (_latticeHandle != 0L) {
            NativeBridge.incrementInt(_latticeHandle, property, delta)
        }
    }

    /**
     * Serializes this object (and link graph up to [maxDepth] hops) to JSON,
     * matching Swift's `DynamicObject.jsonObject(maxDepth:)`.
     */
    fun toJson(maxDepth: Int = 0): String {
        if (_latticeHandle == 0L) return "{}"
        return NativeBridge.objectToJson(_latticeHandle, maxDepth) ?: "{}"
    }
}
