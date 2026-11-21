package com.lattice

import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.reflect.KClass
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

/**
 * Native accessors called by compiler-generated property getters/setters.
 */
object LatticeNative {

    // ========== Schema Flag Bitmask (mirrored in LatticeIrTransformer) ==========
    const val FLAG_UNIQUE = 1
    const val FLAG_INDEXED = 2
    const val FLAG_FULL_TEXT = 4
    const val FLAG_VECTOR = 8
    const val FLAG_NO_HISTORY = 16
    const val FLAG_GEO_BOUNDS = 32
    const val FLAG_ALLOWS_UPSERT = 64

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

    fun setNull(handle: Long, property: String) {
        if (handle == 0L) return
        NativeBridge.setNull(handle, property)
    }

    fun hasValue(handle: Long, property: String): Boolean {
        if (handle == 0L) return false
        return NativeBridge.hasValue(handle, property)
    }

    // ========== Blob/ByteArray ==========

    fun getBlob(handle: Long, property: String): ByteArray {
        if (handle == 0L) return ByteArray(0)
        val size = NativeBridge.getBlobSize(handle, property)
        if (size <= 0) return ByteArray(0)
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

    // ========== FloatVector & DoubleVector ==========

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
        if (value == null) setBlob(handle, property, null)
        else setBlob(handle, property, value.toByteArray())
    }

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
        if (value == null) setBlob(handle, property, null)
        else setBlob(handle, property, value.toByteArray())
    }

    // ========== GeoBounds ==========

    fun getGeoBounds(handle: Long, property: String): GeoBounds {
        if (handle == 0L) return GeoBounds(0.0, 0.0, 0.0, 0.0)
        return GeoBounds(
            minLat = getDouble(handle, "${property}_minLat"),
            maxLat = getDouble(handle, "${property}_maxLat"),
            minLon = getDouble(handle, "${property}_minLon"),
            maxLon = getDouble(handle, "${property}_maxLon")
        )
    }

    fun getGeoBoundsOrNull(handle: Long, property: String): GeoBounds? {
        if (handle == 0L) return null
        if (!hasValue(handle, "${property}_minLat")) return null
        return getGeoBounds(handle, property)
    }

    fun setGeoBounds(handle: Long, property: String, value: GeoBounds?) {
        if (handle == 0L) return
        if (value == null) {
            setNull(handle, "${property}_minLat")
            setNull(handle, "${property}_maxLat")
            setNull(handle, "${property}_minLon")
            setNull(handle, "${property}_maxLon")
        } else {
            setDouble(handle, "${property}_minLat", value.minLat)
            setDouble(handle, "${property}_maxLat", value.maxLat)
            setDouble(handle, "${property}_minLon", value.minLon)
            setDouble(handle, "${property}_maxLon", value.maxLon)
        }
    }

    // ========== Instant (Date/Time — Unix epoch seconds REAL, 1:1 with Swift Date) ==========

    private fun doubleToInstant(timestamp: Double): Instant {
        val secs = floor(timestamp).toLong()
        val nanos = ((timestamp - secs.toDouble()) * 1_000_000_000.0).roundToLong()
        return if (nanos >= 1_000_000_000L) {
            Instant.fromEpochSeconds(secs + 1, (nanos - 1_000_000_000L).toInt())
        } else {
            Instant.fromEpochSeconds(secs, nanos.toInt())
        }
    }

    fun getInstant(handle: Long, property: String): Instant =
        doubleToInstant(getDouble(handle, property))

    fun getInstantOrNull(handle: Long, property: String): Instant? {
        if (!hasValue(handle, property)) return null
        return doubleToInstant(getDouble(handle, property))
    }

    fun setInstant(handle: Long, property: String, value: Instant?) {
        if (value == null) {
            NativeBridge.setNull(handle, property)
        } else {
            val seconds = value.epochSeconds.toDouble() + (value.nanosecondsOfSecond / 1_000_000_000.0)
            setDouble(handle, property, seconds)
        }
    }

    // ========== UUID (lowercase hyphenated RFC 9562 TEXT, 1:1 with Swift UUID) ==========

    @OptIn(ExperimentalUuidApi::class)
    fun getUuid(handle: Long, property: String): Uuid {
        val str = getString(handle, property)
        return if (str.isEmpty()) Uuid.NIL else try { Uuid.parse(str) } catch (_: Exception) { Uuid.NIL }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getUuidOrNull(handle: Long, property: String): Uuid? {
        val str = getStringOrNull(handle, property) ?: return null
        return if (str.isEmpty()) null else try { Uuid.parse(str) } catch (_: Exception) { null }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun setUuid(handle: Long, property: String, value: Uuid?) {
        if (value == null) setString(handle, property, null)
        else setString(handle, property, value.toString())
    }

    // ========== Object Identity ==========

    fun getId(handle: Long): Long = if (handle == 0L) 0L else NativeBridge.getObjectId(handle)

    fun getGlobalId(handle: Long): String? = if (handle == 0L) null else NativeBridge.getObjectGlobalId(handle)

    // ========== Link & Handle Adoption (Zero-Leak Defense-in-Depth) ==========

    fun getObjectHandle(handle: Long, property: String): Long {
        if (handle == 0L) return 0L
        return NativeBridge.getLinkedObject(handle, property)
    }

    fun setObjectHandle(handle: Long, property: String, linkedHandle: Long?) {
        if (handle == 0L) return
        NativeBridge.setLinkedObject(handle, property, linkedHandle)
    }

    /**
     * Adopts [handle] onto [obj], releasing any superseded unmanaged handle first
     * and registering [handle] with [NativePhantomReaper] (fixes P0 unmanaged leak on @Link getters).
     */
    fun setHandle(obj: LatticeObject, handle: Long) {
        val prior = obj._latticeHandle
        if (prior != 0L && prior != handle) {
            NativeBridge.releaseObject(prior)
        }
        obj._latticeHandle = handle
        if (handle != 0L) {
            NativePhantomReaper.registerObject(obj, handle, null)
        }
    }

    internal fun <T : LatticeObject> adoptManagedHandle(
        obj: T,
        handle: Long,
        arena: NativeHandleArena?
    ): T {
        if (arena != null) {
            return arena.adoptObject(obj, handle)
        }
        setHandle(obj, handle)
        return obj
    }

    fun getHandle(obj: LatticeObject): Long = obj._latticeHandle

    // ========== Link List Operations ==========

    fun getLinkListHandle(handle: Long, property: String): Long {
        if (handle == 0L) return 0L
        return NativeBridge.getLinkListHandle(handle, property)
    }

    fun <T : LatticeObject> configureLinkList(
        list: LatticeList<T>,
        parentHandle: Long,
        property: String,
        elementFactory: () -> T
    ) {
        val listHandle = getLinkListHandle(parentHandle, property)
        list._nativeHandle = listHandle
        list._elementFactory = elementFactory
        if (listHandle != 0L) {
            NativePhantomReaper.registerLinkList(list, listHandle, null)
        }
    }

    // ========== Union Operations (`@Union`) ==========

    fun getUnionHandle(objectHandle: Long, property: String): Long {
        if (objectHandle == 0L) return 0L
        return NativeBridge.getObjectUnion(objectHandle, property)
    }

    fun createUnionHandle(caseName: String): Long =
        NativeBridge.createUnionValue(caseName)

    fun releaseUnionHandle(uvHandle: Long) {
        if (uvHandle != 0L) NativeBridge.releaseUnionValue(uvHandle)
    }

    fun getUnionCase(uvHandle: Long): String =
        if (uvHandle == 0L) "" else (NativeBridge.getUnionCase(uvHandle) ?: "")

    fun getUnionString(uvHandle: Long, key: String): String =
        if (uvHandle == 0L) "" else (NativeBridge.getUnionString(uvHandle, key) ?: "")

    fun getUnionInt(uvHandle: Long, key: String): Int =
        if (uvHandle == 0L) 0 else NativeBridge.getUnionInt(uvHandle, key).toInt()

    fun getUnionLong(uvHandle: Long, key: String): Long =
        if (uvHandle == 0L) 0L else NativeBridge.getUnionInt(uvHandle, key)

    fun getUnionDouble(uvHandle: Long, key: String): Double =
        if (uvHandle == 0L) 0.0 else NativeBridge.getUnionDouble(uvHandle, key)

    fun getUnionFloat(uvHandle: Long, key: String): Float =
        getUnionDouble(uvHandle, key).toFloat()

    fun getUnionBoolean(uvHandle: Long, key: String): Boolean =
        getUnionLong(uvHandle, key) != 0L

    fun getUnionBlob(uvHandle: Long, key: String): ByteArray =
        if (uvHandle == 0L) ByteArray(0) else NativeBridge.getUnionBlob(uvHandle, key)

    fun <T : Any> getUnionEmbedded(uvHandle: Long, key: String, kClass: KClass<T>): T? {
        val json = if (uvHandle == 0L) null else NativeBridge.getUnionString(uvHandle, key)
        if (json.isNullOrEmpty()) {
            return EmbeddedSerializers.getFactory(kClass)?.invoke()
                ?: EmbeddedSerializers.decode(kClass, "{}")
        }
        return EmbeddedSerializers.decode(kClass, json)
            ?: EmbeddedSerializers.getFactory(kClass)?.invoke()
            ?: EmbeddedSerializers.decode(kClass, "{}")
    }

    fun getUnionInstant(uvHandle: Long, key: String): Instant =
        doubleToInstant(getUnionDouble(uvHandle, key))

    @OptIn(ExperimentalUuidApi::class)
    fun getUnionUuid(uvHandle: Long, key: String): Uuid {
        val s = getUnionString(uvHandle, key)
        return if (s.isEmpty()) Uuid.NIL else try { Uuid.parse(s) } catch (_: Exception) { Uuid.NIL }
    }

    fun getUnionLinkHandle(uvHandle: Long, key: String): Long =
        if (uvHandle == 0L) 0L else NativeBridge.getUnionLink(uvHandle, key)

    fun setUnionString(uvHandle: Long, key: String, value: String) {
        if (uvHandle != 0L) NativeBridge.setUnionString(uvHandle, key, value)
    }

    fun setUnionInt(uvHandle: Long, key: String, value: Int) {
        if (uvHandle != 0L) NativeBridge.setUnionInt(uvHandle, key, value.toLong())
    }

    fun setUnionLong(uvHandle: Long, key: String, value: Long) {
        if (uvHandle != 0L) NativeBridge.setUnionInt(uvHandle, key, value)
    }

    fun setUnionDouble(uvHandle: Long, key: String, value: Double) {
        if (uvHandle != 0L) NativeBridge.setUnionDouble(uvHandle, key, value)
    }

    fun setUnionFloat(uvHandle: Long, key: String, value: Float) {
        if (uvHandle != 0L) NativeBridge.setUnionDouble(uvHandle, key, value.toDouble())
    }

    fun setUnionBoolean(uvHandle: Long, key: String, value: Boolean) {
        if (uvHandle != 0L) NativeBridge.setUnionInt(uvHandle, key, if (value) 1L else 0L)
    }

    fun setUnionBlob(uvHandle: Long, key: String, value: ByteArray) {
        if (uvHandle != 0L) NativeBridge.setUnionBlob(uvHandle, key, value)
    }

    fun <T : Any> setUnionEmbedded(uvHandle: Long, key: String, value: T, kClass: KClass<T>) {
        if (uvHandle == 0L) return
        val json = EmbeddedSerializers.encode(kClass, value) ?: "{}"
        NativeBridge.setUnionString(uvHandle, key, json)
    }

    fun setUnionLinkHandle(uvHandle: Long, key: String, obj: LatticeObject?) {
        if (uvHandle != 0L && obj != null && obj._latticeHandle != 0L) {
            NativeBridge.setUnionLink(uvHandle, key, obj._latticeHandle)
        }
    }

    fun setUnionInstant(uvHandle: Long, key: String, value: Instant?) {
        if (uvHandle != 0L && value != null) {
            val seconds = value.epochSeconds.toDouble() + (value.nanosecondsOfSecond / 1_000_000_000.0)
            NativeBridge.setUnionDouble(uvHandle, key, seconds)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun setUnionUuid(uvHandle: Long, key: String, value: Uuid?) {
        if (uvHandle != 0L && value != null) {
            NativeBridge.setUnionString(uvHandle, key, value.toString())
        }
    }

    fun hasUnionField(uvHandle: Long, key: String): Boolean =
        uvHandle != 0L && NativeBridge.hasUnionField(uvHandle, key)

    fun setAndReleaseUnion(objectHandle: Long, property: String, uvHandle: Long) {
        try {
            if (objectHandle != 0L && uvHandle != 0L) {
                NativeBridge.setObjectUnion(objectHandle, property, uvHandle)
            }
        } finally {
            if (uvHandle != 0L) {
                NativeBridge.releaseUnionValue(uvHandle)
            }
        }
    }

    fun clearUnion(objectHandle: Long, property: String) {
        if (objectHandle != 0L) {
            NativeBridge.clearObjectUnion(objectHandle, property)
        }
    }

    // ========== Embedded & Collection Support ==========

    fun getEmbeddedJson(handle: Long, property: String): String? {
        if (handle == 0L) return null
        if (!NativeBridge.hasValue(handle, property)) return null
        return NativeBridge.getStringProperty(handle, property)
    }

    fun setEmbeddedJson(handle: Long, property: String, json: String?) {
        if (handle == 0L) return
        NativeBridge.setStringProperty(handle, property, json)
    }

    fun <T> getCollectionProperty(
        handle: Long,
        property: String,
        serializer: KSerializer<T>,
        default: T
    ): T {
        if (handle == 0L) return default
        val json = NativeBridge.getStringProperty(handle, property) ?: return default
        if (json.isEmpty()) return default
        return try {
            collectionJson.decodeFromString(serializer, json)
        } catch (_: Exception) {
            default
        }
    }

    fun <T> setCollectionProperty(
        handle: Long,
        property: String,
        value: T,
        serializer: KSerializer<T>
    ) {
        if (handle == 0L) return
        val json = try {
            collectionJson.encodeToString(serializer, value)
        } catch (_: Exception) {
            "[]"
        }
        NativeBridge.setStringProperty(handle, property, json)
    }

    private val collectionJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val stringListSerializer: KSerializer<List<String>> by lazy {
        ListSerializer(serializer<String>())
    }
    private val intListSerializer: KSerializer<List<Int>> by lazy {
        ListSerializer(serializer<Int>())
    }
    private val longListSerializer: KSerializer<List<Long>> by lazy {
        ListSerializer(serializer<Long>())
    }
    private val doubleListSerializer: KSerializer<List<Double>> by lazy {
        ListSerializer(serializer<Double>())
    }
    private val stringSetSerializer: KSerializer<Set<String>> by lazy {
        SetSerializer(serializer<String>())
    }

    fun getStringList(handle: Long, property: String): MutableList<String> {
        if (handle == 0L) return mutableListOf()
        val json = NativeBridge.getStringProperty(handle, property) ?: return mutableListOf()
        if (json.isEmpty()) return mutableListOf()
        return try {
            collectionJson.decodeFromString(stringListSerializer, json).toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun getStringListOrNull(handle: Long, property: String): MutableList<String>? {
        if (handle == 0L || !hasValue(handle, property)) return null
        return getStringList(handle, property)
    }

    fun setStringList(handle: Long, property: String, value: List<String>?) {
        if (handle == 0L) return
        if (value == null) { setNull(handle, property); return }
        val json = try { collectionJson.encodeToString(stringListSerializer, value) } catch (_: Exception) { "[]" }
        NativeBridge.setStringProperty(handle, property, json)
    }

    fun getIntList(handle: Long, property: String): MutableList<Int> {
        if (handle == 0L) return mutableListOf()
        val json = NativeBridge.getStringProperty(handle, property) ?: return mutableListOf()
        if (json.isEmpty()) return mutableListOf()
        return try {
            collectionJson.decodeFromString(intListSerializer, json).toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun setIntList(handle: Long, property: String, value: List<Int>?) {
        if (handle == 0L) return
        if (value == null) { setNull(handle, property); return }
        val json = try { collectionJson.encodeToString(intListSerializer, value) } catch (_: Exception) { "[]" }
        NativeBridge.setStringProperty(handle, property, json)
    }

    fun getLongList(handle: Long, property: String): MutableList<Long> {
        if (handle == 0L) return mutableListOf()
        val json = NativeBridge.getStringProperty(handle, property) ?: return mutableListOf()
        if (json.isEmpty()) return mutableListOf()
        return try {
            collectionJson.decodeFromString(longListSerializer, json).toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun setLongList(handle: Long, property: String, value: List<Long>?) {
        if (handle == 0L) return
        if (value == null) { setNull(handle, property); return }
        val json = try { collectionJson.encodeToString(longListSerializer, value) } catch (_: Exception) { "[]" }
        NativeBridge.setStringProperty(handle, property, json)
    }

    fun getDoubleList(handle: Long, property: String): MutableList<Double> {
        if (handle == 0L) return mutableListOf()
        val json = NativeBridge.getStringProperty(handle, property) ?: return mutableListOf()
        if (json.isEmpty()) return mutableListOf()
        return try {
            collectionJson.decodeFromString(doubleListSerializer, json).toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun setDoubleList(handle: Long, property: String, value: List<Double>?) {
        if (handle == 0L) return
        if (value == null) { setNull(handle, property); return }
        val json = try { collectionJson.encodeToString(doubleListSerializer, value) } catch (_: Exception) { "[]" }
        NativeBridge.setStringProperty(handle, property, json)
    }

    fun getStringSet(handle: Long, property: String): MutableSet<String> {
        if (handle == 0L) return mutableSetOf()
        val json = NativeBridge.getStringProperty(handle, property) ?: return mutableSetOf()
        if (json.isEmpty()) return mutableSetOf()
        return try {
            collectionJson.decodeFromString(stringSetSerializer, json).toMutableSet()
        } catch (_: Exception) { mutableSetOf() }
    }

    fun setStringSet(handle: Long, property: String, value: Set<String>?) {
        if (handle == 0L) return
        if (value == null) { setNull(handle, property); return }
        val sorted = value.sorted()
        val json = try { collectionJson.encodeToString(stringListSerializer, sorted) } catch (_: Exception) { "[]" }
        NativeBridge.setStringProperty(handle, property, json)
    }

    fun getStringStringMap(handle: Long, property: String): MutableMap<String, String> {
        if (handle == 0L) return mutableMapOf()
        val json = NativeBridge.getStringProperty(handle, property) ?: return mutableMapOf()
        if (json.isEmpty()) return mutableMapOf()
        return try {
            (collectionJson.parseToJsonElement(json) as? JsonObject)
                ?.mapValues { (_, v) -> if (v is JsonPrimitive) v.content else v.toString() }
                ?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }
    }

    fun setStringStringMap(handle: Long, property: String, value: Map<String, String>?) {
        if (handle == 0L) return
        if (value == null) { setNull(handle, property); return }
        val json = try {
            buildJsonObject {
                value.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }.toString()
        } catch (_: Exception) { "{}" }
        NativeBridge.setStringProperty(handle, property, json)
    }

    /**
     * `List<@Embedded>` support (e.g. `ChatMessage.reasoningSegments: List<ReasoningSegment>`).
     */
    fun <T : Any> getEmbeddedList(handle: Long, property: String, kClass: KClass<T>): MutableList<T> {
        if (handle == 0L) return mutableListOf()
        val json = NativeBridge.getStringProperty(handle, property) ?: return mutableListOf()
        if (json.isEmpty()) return mutableListOf()
        val elemSerializer = EmbeddedSerializers.getSerializer(kClass) ?: return mutableListOf()
        return try {
            latticeJson.decodeFromString(ListSerializer(elemSerializer), json).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun <T : Any> setEmbeddedList(handle: Long, property: String, value: List<T>?, kClass: KClass<T>) {
        if (handle == 0L) return
        if (value == null) { setNull(handle, property); return }
        val elemSerializer = EmbeddedSerializers.getSerializer(kClass)
        val json = if (elemSerializer != null) {
            try { latticeJson.encodeToString(ListSerializer(elemSerializer), value) } catch (_: Exception) { "[]" }
        } else "[]"
        NativeBridge.setStringProperty(handle, property, json)
    }

    /**
     * `List<@LatticeEnum>` / `Set<@LatticeEnum>` support (e.g. `Agent.scopes: Set<AgentCapability>`),
     * skipping unknown future enum strings cleanly for forward version-skew safety.
     */
    @Suppress("UNCHECKED_CAST")
    fun <E : Enum<E>> getEnumList(handle: Long, property: String, values: Array<E>): MutableList<E> {
        val names = getStringList(handle, property)
        val byName = values.associateBy { it.name }
        return names.mapNotNull { byName[it] }.toMutableList()
    }

    fun setEnumList(handle: Long, property: String, value: List<Enum<*>>?) {
        if (value == null) setNull(handle, property)
        else setStringList(handle, property, value.map { it.name })
    }

    @Suppress("UNCHECKED_CAST")
    fun <E : Enum<E>> getEnumSet(handle: Long, property: String, values: Array<E>): MutableSet<E> {
        val names = getStringList(handle, property)
        val byName = values.associateBy { it.name }
        return names.mapNotNull { byName[it] }.toMutableSet()
    }

    fun setEnumSet(handle: Long, property: String, value: Set<Enum<*>>?) {
        if (value == null) setNull(handle, property)
        else setStringSet(handle, property, value.map { it.name }.toSet())
    }

    fun <T : Any> getEmbedded(
        handle: Long,
        property: String,
        kClass: KClass<T>,
        useDefaultIfMissing: Boolean = true
    ): T? {
        val json = getEmbeddedJson(handle, property)
        if (json == null || json.isEmpty()) {
            return if (useDefaultIfMissing) {
                EmbeddedSerializers.getFactory(kClass)?.invoke()
                    ?: EmbeddedSerializers.decode(kClass, "{}")
            } else null
        }
        return EmbeddedSerializers.decode(kClass, json)
            ?: if (useDefaultIfMissing) {
                EmbeddedSerializers.getFactory(kClass)?.invoke()
                    ?: EmbeddedSerializers.decode(kClass, "{}")
            } else null
    }

    fun <T : Any> setEmbedded(handle: Long, property: String, value: T?, kClass: KClass<T>) {
        if (value == null) {
            setEmbeddedJson(handle, property, null)
        } else {
            val json = EmbeddedSerializers.encode(kClass, value)
            setEmbeddedJson(handle, property, json)
        }
    }

    // ========== Schema Builder (called by compiler-generated code) ==========

    /**
     * Build a schema list from a compact descriptor string.
     * Format:
     *   "name:type:kind:nullable[:targetTable[:flags[:columnName[:compoundedWith[:unionSpec]]]]],..."
     *
     * Flags bitfield:
     *   1 = unique, 2 = indexed, 4 = full-text, 8 = vector, 16 = no-history,
     *   32 = geo-bounds, 64 = allows-upsert
     *
     * `compoundedWith`: `+`-joined sibling column names for compound `@Unique`.
     * `unionSpec`: `_UnionTable|case1;case2~param^typeOrd^isLink^linkTarget~...`
     */
    fun buildSchemaFromString(descriptor: String): List<LatticePropertyDescriptor> {
        if (descriptor.isEmpty()) return emptyList()
        return descriptor.split(",").map { entry ->
            val parts = entry.split(":")
            val flags = parts.getOrNull(5)?.toIntOrNull() ?: 0
            val columnName = parts.getOrNull(6)?.takeIf { it.isNotEmpty() }
            val compounded = parts.getOrNull(7)?.takeIf { it.isNotEmpty() }?.split("+") ?: emptyList()
            val unionSpec = parts.getOrNull(8)?.takeIf { it.isNotEmpty() }
            val unionDesc = unionSpec?.let { parseUnionSpec(it) }

            LatticePropertyDescriptor(
                name = parts[0],
                type = LatticeType.entries[parts[1].toInt()],
                kind = LatticePropertyKind.entries[parts[2].toInt()],
                nullable = parts[3] == "1",
                targetTable = parts.getOrNull(4)?.takeIf { it.isNotEmpty() },
                isIndexed = flags and FLAG_INDEXED != 0,
                isUnique = flags and FLAG_UNIQUE != 0,
                isFullText = flags and FLAG_FULL_TEXT != 0,
                isVector = flags and FLAG_VECTOR != 0,
                isGeoBounds = flags and FLAG_GEO_BOUNDS != 0,
                columnName = columnName,
                isNoHistory = flags and FLAG_NO_HISTORY != 0,
                allowsUpsert = flags and FLAG_ALLOWS_UPSERT != 0,
                compoundedWith = compounded,
                unionDescriptor = unionDesc
            )
        }
    }

    internal fun parseUnionSpec(spec: String): LatticeUnionDescriptor? {
        val barIdx = spec.indexOf('|')
        if (barIdx <= 0) return null
        val unionTableName = spec.substring(0, barIdx)
        val casesPart = spec.substring(barIdx + 1)
        val cases = if (casesPart.isEmpty()) {
            emptyList()
        } else {
            casesPart.split(";").filter { it.isNotEmpty() }.map { caseToken ->
                val segs = caseToken.split("~")
                val caseName = segs[0]
                val vals = segs.drop(1).filter { it.isNotEmpty() }.map { valToken ->
                    val vp = valToken.split("^")
                    LatticeUnionCaseValue(
                        paramName = vp.getOrElse(0) { "" },
                        type = LatticeType.entries[vp.getOrNull(1)?.toIntOrNull() ?: 2],
                        isLink = vp.getOrNull(2) == "1",
                        linkTarget = vp.getOrNull(3)?.takeIf { it.isNotEmpty() }
                    )
                }
                LatticeUnionCase(caseName = caseName, values = vals)
            }
        }
        return LatticeUnionDescriptor(unionTableName = unionTableName, cases = cases)
    }

    internal fun encodeUnionSpec(desc: LatticeUnionDescriptor): String {
        val casesStr = desc.cases.joinToString(";") { c ->
            if (c.values.isEmpty()) c.caseName
            else c.caseName + "~" + c.values.joinToString("~") { v ->
                "${v.paramName}^${v.type.ordinal}^${if (v.isLink) "1" else "0"}^${v.linkTarget ?: ""}"
            }
        }
        return "${desc.unionTableName}|$casesStr"
    }

    // ========== @LatticeEnum name resolution ==========

    fun enumNameOrDefault(values: Array<out Enum<*>>, name: String?, defaultIndex: Int): String {
        if (name != null) {
            for (entry in values) if (entry.name == name) return name
        }
        return values[if (defaultIndex in values.indices) defaultIndex else 0].name
    }

    fun enumNameOrNull(values: Array<out Enum<*>>, name: String?): String? {
        if (name != null) {
            for (entry in values) if (entry.name == name) return name
        }
        return null
    }

    // ========== Object Creation ==========

    fun createObject(tableName: String): Long {
        val handle = NativeBridge.createObject(tableName, "")
        if (handle == 0L) {
            throw IllegalStateException("Failed to create object for table: $tableName")
        }
        return handle
    }

    fun createObjectWithSchema(tableName: String, schema: List<LatticePropertyDescriptor>?): Long {
        if (schema == null || schema.isEmpty()) {
            return createObject(tableName)
        }
        val schemaJson = buildSchemaJson(tableName, schema)
        val handle = NativeBridge.createObjectWithSchema(tableName, schemaJson)
        if (handle == 0L) {
            throw IllegalStateException("Failed to create object with schema for table: $tableName")
        }
        return handle
    }

    private fun buildSchemaJson(tableName: String, schema: List<LatticePropertyDescriptor>): String {
        val sb = StringBuilder("[")
        schema.forEachIndexed { index, prop ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"name\":\"").append(prop.name).append("\",")
            sb.append("\"type\":").append(prop.type.ordinal).append(",")
            sb.append("\"kind\":").append(prop.kind.ordinal).append(",")
            sb.append("\"nullable\":").append(prop.nullable)
            if (prop.targetTable != null) {
                sb.append(",\"target_table\":\"").append(prop.targetTable).append("\"")
            }
            if (prop.linkTable != null) {
                sb.append(",\"link_table\":\"").append(prop.linkTable).append("\"")
            }
            if (prop.columnName != null) {
                sb.append(",\"column_name\":\"").append(prop.columnName).append("\"")
            }
            if (prop.isIndexed) sb.append(",\"is_indexed\":true")
            if (prop.isUnique) sb.append(",\"is_unique\":true")
            if (prop.isFullText) sb.append(",\"is_full_text\":true")
            if (prop.isVector) sb.append(",\"is_vector\":true")
            if (prop.isGeoBounds) sb.append(",\"is_geo_bounds\":true")
            if (prop.isNoHistory) sb.append(",\"is_no_history\":true")
            if (prop.unionDescriptor != null) {
                sb.append(",\"union_spec\":\"").append(encodeUnionSpec(prop.unionDescriptor)).append("\"")
            }
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }
}
