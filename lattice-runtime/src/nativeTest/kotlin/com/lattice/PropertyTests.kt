package com.lattice

import kotlinx.datetime.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for all supported property types: AllTypes, ByteArray/Blob,
 * FloatVector, Instant/DateTime, and UUID.
 */
class PropertyTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    // ============================================================
    // AllTypes
    // ============================================================

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

    // ============================================================
    // ByteArray / Blob
    // ============================================================

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

    // ============================================================
    // FloatVector
    // ============================================================

    /**
     * Tests FloatVector property support for vector embeddings.
     */
    @Test
    fun test_FloatVector() {
        val lattice = testLattice(Embedding::class)

        // Create embedding with vector
        val embedding = Embedding()
        embedding.name = "doc1"
        embedding.vector = FloatVector(floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f))

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
        emb1.vector = FloatVector(floatArrayOf(1.0f, 2.0f))
        // optionalVector is null

        // Create embedding with optional vector
        val emb2 = Embedding()
        emb2.name = "with-opt"
        emb2.vector = FloatVector(floatArrayOf(3.0f, 4.0f))
        emb2.optionalVector = FloatVector(floatArrayOf(5.0f, 6.0f, 7.0f))

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
        val v1 = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))
        val v2 = FloatVector(floatArrayOf(0.0f, 1.0f, 0.0f))

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
        val v3 = FloatVector(floatArrayOf(2.0f, 0.0f, 0.0f))
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
        val original = FloatVector(floatArrayOf(1.5f, -2.5f, 3.14159f, 0.0f, Float.MAX_VALUE, Float.MIN_VALUE))

        val bytes = original.toByteArray()
        assertEquals(6 * 4, bytes.size) // 6 floats * 4 bytes each

        val restored = FloatVector.fromByteArray(bytes)
        assertEquals(original.dimensions, restored.dimensions)

        for (i in 0 until original.dimensions) {
            assertEquals(original[i], restored[i])
        }
    }

    // ============================================================
    // Instant / DateTime
    // ============================================================

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
        // Compare with millisecond tolerance (double storage loses sub-ms precision)
        val diffMs = kotlin.math.abs(precise.toEpochMilliseconds() - preciseEvent.startTime.toEpochMilliseconds())
        assertTrue(diffMs <= 1, "Precise timestamp should match within 1ms, diff=$diffMs")

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

    // ============================================================
    // UUID
    // ============================================================

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
}
