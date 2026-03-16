package com.lattice

import kotlinx.datetime.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Native accessors called by compiler-generated property getters/setters.
 *
 * The handle is stored as a Long (pointer address on native, JNI handle on JVM).
 * Uses NativeBridge for platform-specific native calls.
 */
object LatticeNative {

    // ========== String Getters/Setters ==========

    fun getString(handle: Long, property: String): String {
        if (handle == 0L) return ""
        return NativeBridge.getStringProperty(handle, property) ?: ""
    }

    fun getStringOrNull(handle: Long, property: String): String? {
        if (handle == 0L) return null
        return if (NativeBridge.hasValue(handle, property)) {
            NativeBridge.getStringProperty(handle, property)
        } else null
    }

    fun setString(handle: Long, property: String, value: String?) {
        if (handle == 0L) return
        NativeBridge.setStringProperty(handle, property, value)
    }

    // ========== Int Getters/Setters ==========

    fun getInt(handle: Long, property: String): Int {
        if (handle == 0L) return 0
        return NativeBridge.getIntProperty(handle, property).toInt()
    }

    fun getIntOrNull(handle: Long, property: String): Int? {
        if (handle == 0L) return null
        return if (NativeBridge.hasValue(handle, property)) {
            NativeBridge.getIntProperty(handle, property).toInt()
        } else null
    }

    fun setInt(handle: Long, property: String, value: Int?) {
        if (handle == 0L) return
        if (value != null) {
            NativeBridge.setIntProperty(handle, property, value.toLong())
        } else {
            NativeBridge.setNull(handle, property)
        }
    }

    // ========== Long Getters/Setters ==========

    fun getLong(handle: Long, property: String): Long {
        if (handle == 0L) return 0L
        return NativeBridge.getIntProperty(handle, property)
    }

    fun getLongOrNull(handle: Long, property: String): Long? {
        if (handle == 0L) return null
        return if (NativeBridge.hasValue(handle, property)) {
            NativeBridge.getIntProperty(handle, property)
        } else null
    }

    fun setLong(handle: Long, property: String, value: Long?) {
        if (handle == 0L) return
        if (value != null) {
            NativeBridge.setIntProperty(handle, property, value)
        } else {
            NativeBridge.setNull(handle, property)
        }
    }

    // ========== Double Getters/Setters ==========

    fun getDouble(handle: Long, property: String): Double {
        if (handle == 0L) return 0.0
        return NativeBridge.getDoubleProperty(handle, property)
    }

    fun getDoubleOrNull(handle: Long, property: String): Double? {
        if (handle == 0L) return null
        return if (NativeBridge.hasValue(handle, property)) {
            NativeBridge.getDoubleProperty(handle, property)
        } else null
    }

    fun setDouble(handle: Long, property: String, value: Double?) {
        if (handle == 0L) return
        if (value != null) {
            NativeBridge.setDoubleProperty(handle, property, value)
        } else {
            NativeBridge.setNull(handle, property)
        }
    }

    // ========== Float Getters/Setters ==========

    fun getFloat(handle: Long, property: String): Float = getDouble(handle, property).toFloat()

    fun getFloatOrNull(handle: Long, property: String): Float? = getDoubleOrNull(handle, property)?.toFloat()

    fun setFloat(handle: Long, property: String, value: Float?) = setDouble(handle, property, value?.toDouble())

    // ========== Boolean Getters/Setters ==========

    fun getBoolean(handle: Long, property: String): Boolean {
        if (handle == 0L) return false
        return NativeBridge.getBoolProperty(handle, property)
    }

    fun getBooleanOrNull(handle: Long, property: String): Boolean? {
        if (handle == 0L) return null
        return if (NativeBridge.hasValue(handle, property)) {
            NativeBridge.getBoolProperty(handle, property)
        } else null
    }

    fun setBoolean(handle: Long, property: String, value: Boolean?) {
        if (handle == 0L) return
        if (value != null) {
            NativeBridge.setBoolProperty(handle, property, value)
        } else {
            NativeBridge.setNull(handle, property)
        }
    }

    // ========== Null Handling ==========

    /**
     * Set a property to NULL explicitly.
     * Used by nullable enum properties.
     */
    fun setNull(handle: Long, property: String) {
        if (handle == 0L) return
        NativeBridge.setNull(handle, property)
    }

    /**
     * Check if a property has a value (not NULL).
     */
    fun hasValue(handle: Long, property: String): Boolean {
        if (handle == 0L) return false
        return NativeBridge.hasValue(handle, property)
    }

    // ========== Blob/ByteArray ==========

    fun getBlob(handle: Long, property: String): ByteArray {
        if (handle == 0L) return ByteArray(0)

        val size = NativeBridge.getBlobSize(handle, property)
        if (size == 0) return ByteArray(0)

        val buffer = ByteArray(size)
        NativeBridge.getBlobData(handle, property, buffer)
        return buffer
    }

    fun getBlobOrNull(handle: Long, property: String): ByteArray? {
        if (handle == 0L) return null
        return if (NativeBridge.hasValue(handle, property)) {
            getBlob(handle, property)
        } else null
    }

    fun setBlob(handle: Long, property: String, value: ByteArray?) {
        if (handle == 0L) return
        NativeBridge.setBlobProperty(handle, property, value)
    }

    // ========== FloatVector ==========

    fun getFloatVector(handle: Long, property: String): FloatVector {
        val bytes = getBlob(handle, property)
        if (bytes.isEmpty()) return FloatVector()
        return FloatVector.fromByteArray(bytes)
    }

    fun getFloatVectorOrNull(handle: Long, property: String): FloatVector? {
        val bytes = getBlobOrNull(handle, property) ?: return null
        if (bytes.isEmpty()) return FloatVector()
        return FloatVector.fromByteArray(bytes)
    }

    fun setFloatVector(handle: Long, property: String, value: FloatVector?) {
        if (value == null) {
            setBlob(handle, property, null)
        } else {
            setBlob(handle, property, value.toByteArray())
        }
    }

    // ========== DoubleVector ==========

    fun getDoubleVector(handle: Long, property: String): DoubleVector {
        val bytes = getBlob(handle, property)
        if (bytes.isEmpty()) return DoubleVector()
        return DoubleVector.fromByteArray(bytes)
    }

    fun getDoubleVectorOrNull(handle: Long, property: String): DoubleVector? {
        val bytes = getBlobOrNull(handle, property) ?: return null
        if (bytes.isEmpty()) return DoubleVector()
        return DoubleVector.fromByteArray(bytes)
    }

    fun setDoubleVector(handle: Long, property: String, value: DoubleVector?) {
        if (value == null) {
            setBlob(handle, property, null)
        } else {
            setBlob(handle, property, value.toByteArray())
        }
    }

    // ========== Instant (Date/Time) ==========

    /**
     * Get an Instant property. Stored as REAL (Unix timestamp - seconds since epoch).
     */
    fun getInstant(handle: Long, property: String): Instant {
        val timestamp = getDouble(handle, property)
        return Instant.fromEpochSeconds(
            timestamp.toLong(),
            ((timestamp % 1.0) * 1_000_000_000).toInt()
        )
    }

    fun getInstantOrNull(handle: Long, property: String): Instant? {
        if (!hasValue(handle, property)) return null
        val timestamp = getDouble(handle, property)
        return Instant.fromEpochSeconds(
            timestamp.toLong(),
            ((timestamp % 1.0) * 1_000_000_000).toInt()
        )
    }

    fun setInstant(handle: Long, property: String, value: Instant?) {
        if (value == null) {
            NativeBridge.setNull(handle, property)
        } else {
            // Convert to Unix timestamp (seconds since epoch as double)
            val seconds = value.epochSeconds.toDouble() + (value.nanosecondsOfSecond / 1_000_000_000.0)
            setDouble(handle, property, seconds)
        }
    }

    // ========== UUID ==========

    @OptIn(ExperimentalUuidApi::class)
    fun getUuid(handle: Long, property: String): Uuid {
        val str = getString(handle, property)
        return if (str.isEmpty()) {
            Uuid.NIL
        } else {
            try {
                Uuid.parse(str)
            } catch (e: Exception) {
                Uuid.NIL
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getUuidOrNull(handle: Long, property: String): Uuid? {
        val str = getStringOrNull(handle, property) ?: return null
        return if (str.isEmpty()) {
            null
        } else {
            try {
                Uuid.parse(str)
            } catch (e: Exception) {
                null
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun setUuid(handle: Long, property: String, value: Uuid?) {
        if (value == null) {
            setString(handle, property, null)
        } else {
            setString(handle, property, value.toString())
        }
    }

    // ========== Object Identity ==========

    fun getId(handle: Long): Long {
        if (handle == 0L) return 0L
        return NativeBridge.getObjectId(handle)
    }

    fun getGlobalId(handle: Long): String? {
        if (handle == 0L) return null
        return NativeBridge.getObjectGlobalId(handle)
    }

    // ========== Link Getters/Setters ==========

    /**
     * Get a linked object handle. Returns 0L if no link.
     */
    fun getObjectHandle(handle: Long, property: String): Long {
        if (handle == 0L) return 0L
        return NativeBridge.getLinkedObject(handle, property)
    }

    /**
     * Set a linked object.
     */
    fun setObjectHandle(handle: Long, property: String, linkedHandle: Long?) {
        if (handle == 0L) return
        NativeBridge.setLinkedObject(handle, property, linkedHandle)
    }

    /**
     * Helper to set _latticeHandle on any LatticeObject.
     * Used by generated code for link property getters.
     */
    fun setHandle(obj: LatticeObject, handle: Long) {
        obj._latticeHandle = handle
    }

    /**
     * Helper to get _latticeHandle from any LatticeObject.
     * Used by generated code for link property setters.
     */
    fun getHandle(obj: LatticeObject): Long {
        return obj._latticeHandle
    }

    // ========== Link List Operations ==========

    /**
     * Get a link list handle from an object.
     * Returns 0L if the property doesn't exist or has no list.
     */
    fun getLinkListHandle(handle: Long, property: String): Long {
        if (handle == 0L) return 0L
        return NativeBridge.getLinkListHandle(handle, property)
    }

    /**
     * Configure a LatticeList with a native handle and element factory.
     * Called by generated code for @LinkList property getters.
     */
    fun <T : LatticeObject> configureLinkList(
        list: LatticeList<T>,
        parentHandle: Long,
        property: String,
        elementFactory: () -> T
    ) {
        list._nativeHandle = getLinkListHandle(parentHandle, property)
        list._elementFactory = elementFactory
    }

    // ========== Embedded Model Support ==========

    /**
     * Get an embedded model as JSON string from the native object.
     */
    fun getEmbeddedJson(handle: Long, property: String): String? {
        if (handle == 0L) return null
        if (!NativeBridge.hasValue(handle, property)) {
            return null
        }
        return NativeBridge.getStringProperty(handle, property)
    }

    /**
     * Set an embedded model as JSON string on the native object.
     */
    fun setEmbeddedJson(handle: Long, property: String, json: String?) {
        if (handle == 0L) return
        NativeBridge.setStringProperty(handle, property, json)
    }

    /**
     * Get an embedded model, decoding from JSON.
     * Uses the registered serializer for the type.
     *
     * @param useDefaultIfMissing If true and no value stored, use factory to create default.
     *                            If false, return null when no value is stored.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getEmbedded(
        handle: Long,
        property: String,
        kClass: kotlin.reflect.KClass<T>,
        useDefaultIfMissing: Boolean = true
    ): T? {
        val json = getEmbeddedJson(handle, property)
        if (json == null || json.isEmpty()) {
            // No value stored
            return if (useDefaultIfMissing) {
                EmbeddedSerializers.getFactory(kClass)?.invoke()
            } else {
                null
            }
        }
        return EmbeddedSerializers.decode(kClass, json)
            ?: if (useDefaultIfMissing) {
                EmbeddedSerializers.getFactory(kClass)?.invoke()
            } else {
                null
            }
    }

    /**
     * Set an embedded model, encoding to JSON.
     * Uses the registered serializer for the type.
     */
    fun <T : Any> setEmbedded(handle: Long, property: String, value: T?, kClass: kotlin.reflect.KClass<T>) {
        if (value == null) {
            setEmbeddedJson(handle, property, null)
        } else {
            val json = EmbeddedSerializers.encode(kClass, value)
            setEmbeddedJson(handle, property, json)
        }
    }

    // ========== Object Creation ==========

    /**
     * Create a new unmanaged C++ object for the given table (without schema).
     * Not recommended - use createObjectWithSchema instead.
     */
    fun createObject(tableName: String): Long {
        val handle = NativeBridge.createObject(tableName, "")
        if (handle == 0L) {
            throw IllegalStateException("Failed to create object for table: $tableName")
        }
        return handle
    }

    /**
     * Create a new unmanaged C++ object with schema (like Swift's _defaultCxxLatticeObject).
     * This is the preferred way to create objects - schema is embedded so INSERT works correctly.
     */
    fun createObjectWithSchema(tableName: String, schema: List<LatticePropertyDescriptor>): Long {
        if (schema.isEmpty()) {
            return createObject(tableName)
        }

        // Build schema JSON
        val schemaJson = buildSchemaJson(tableName, schema)
        val handle = NativeBridge.createObjectWithSchema(tableName, schemaJson)
        if (handle == 0L) {
            throw IllegalStateException("Failed to create object with schema for table: $tableName")
        }
        return handle
    }

    private fun buildSchemaJson(tableName: String, schema: List<LatticePropertyDescriptor>): String {
        // Build a flat JSON array of property objects (matches NativeBridgeImpl parser)
        val sb = StringBuilder()
        sb.append("[")
        schema.forEachIndexed { index, prop ->
            if (index > 0) sb.append(",")
            val typeInt = when (prop.type) {
                LatticeType.INTEGER -> 0
                LatticeType.REAL -> 1
                LatticeType.TEXT -> 2
                LatticeType.BLOB -> 3
            }
            val kindInt = when (prop.kind) {
                LatticePropertyKind.PRIMITIVE -> 0
                LatticePropertyKind.LINK -> 1
                LatticePropertyKind.LINK_LIST -> 2
                LatticePropertyKind.VIRTUAL_LIST -> 3
                LatticePropertyKind.VIRTUAL_LINK -> 4
            }
            sb.append("{")
            sb.append("\"name\":\"${prop.name}\",")
            sb.append("\"type\":$typeInt,")
            sb.append("\"kind\":$kindInt,")
            sb.append("\"nullable\":${prop.nullable}")
            prop.targetTable?.let { sb.append(",\"target_table\":\"$it\"") }
            prop.linkTable?.let { sb.append(",\"link_table\":\"$it\"") }
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }
}
