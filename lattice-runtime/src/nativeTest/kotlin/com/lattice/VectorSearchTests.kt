package com.lattice

import kotlin.test.*

/**
 * Tests for vector search types and distance metrics.
 * Port of Swift VectorSearchTests.swift.
 */
class VectorSearchTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_DistanceMetricValues() {
        assertEquals(DistanceMetric.L2, DistanceMetric.valueOf("L2"))
        assertEquals(DistanceMetric.COSINE, DistanceMetric.valueOf("COSINE"))
        assertEquals(DistanceMetric.L1, DistanceMetric.valueOf("L1"))
    }

    @Test
    fun test_NearestMatchCreation() {
        // Test with a simple object stand-in
        val trip = Trip()
        trip.name = "Test Trip"
        trip.days = 5
        trip.budget = 1000.0
        trip.isBooked = false

        val lattice = testLattice(Trip::class)
        lattice.add(trip)

        val match = NearestMatch(trip, 0.5)
        assertEquals(trip, match.`object`)
        assertEquals(0.5, match.distance)

        lattice.close()
    }

    @Test
    fun test_FloatVectorL2Distance() {
        val a = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))
        val b = FloatVector(floatArrayOf(0.0f, 1.0f, 0.0f))

        val dist = a.l2Distance(b)
        // L2 distance between [1,0,0] and [0,1,0] = sqrt(2) ≈ 1.414
        assertEquals(1.414f, dist, 0.01f)
    }

    @Test
    fun test_FloatVectorCosineDistance() {
        val a = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))
        val b = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))

        // Same vector → cosine distance = 0
        assertEquals(0.0f, a.cosineDistance(b), 0.001f)

        // Orthogonal vectors → cosine distance = 1
        val c = FloatVector(floatArrayOf(0.0f, 1.0f, 0.0f))
        assertEquals(1.0f, a.cosineDistance(c), 0.001f)
    }

    @Test
    fun test_FloatVectorNormalized() {
        val v = FloatVector(floatArrayOf(3.0f, 4.0f, 0.0f))
        val n = v.normalized()

        // Magnitude should be ~1.0
        val mag = kotlin.math.sqrt(
            n.elements[0] * n.elements[0] +
            n.elements[1] * n.elements[1] +
            n.elements[2] * n.elements[2]
        )
        assertEquals(1.0f, mag, 0.001f)
    }

    // Port of Swift test_VectorStorage
    @Test
    fun test_VectorStorage() {
        val lattice = testLattice(Embedding::class)

        // Create an embedding with a small vector
        val embedding = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val item = Embedding().apply {
            name = "Test Embedding"
            vector = FloatVector(embedding)
        }

        assertEquals(5, item.vector.dimensions)

        lattice.add(item)

        // Retrieve and verify
        val results = lattice.objects(Embedding::class)
        assertEquals(1, results.count)

        val retrieved = results.first()!!
        assertEquals("Test Embedding", retrieved.name)
        assertEquals(5, retrieved.vector.dimensions)

        // Check values are preserved
        for (i in embedding.indices) {
            assertEquals(embedding[i], retrieved.vector[i], 0.0001f)
        }

        lattice.close()
    }

    // Port of Swift test_VectorBinarySerialization
    @Test
    fun test_VectorBinarySerialization() {
        val original = FloatVector(floatArrayOf(1.5f, -2.5f, 3.14159f, 0.0f, -0.0001f))
        val data = original.toByteArray()
        val restored = FloatVector.fromByteArray(data)

        assertEquals(original.dimensions, restored.dimensions)
        for (i in 0 until original.dimensions) {
            assertEquals(original[i], restored[i], 0.00001f)
        }
    }

    // Port of Swift test_VectorDistanceFunctions (expanded)
    @Test
    fun test_VectorDistanceFunctions_Full() {
        val v1 = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))
        val v2 = FloatVector(floatArrayOf(0.0f, 1.0f, 0.0f))
        val v3 = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))

        // L2 distance: sqrt((1-0)^2 + (0-1)^2 + (0-0)^2) = sqrt(2)
        val l2 = v1.l2Distance(v2)
        assertEquals(kotlin.math.sqrt(2.0f), l2, 0.0001f)

        // Same vectors should have 0 distance
        assertTrue(v1.l2Distance(v3) < 0.0001f)

        // Cosine distance of orthogonal vectors = 1 (similarity = 0)
        val cosine = v1.cosineDistance(v2)
        assertEquals(1.0f, cosine, 0.0001f)

        // Cosine distance of same vectors = 0 (similarity = 1)
        assertTrue(v1.cosineDistance(v3) < 0.0001f)

        // Dot product
        assertTrue(v1.dot(v2) < 0.0001f) // orthogonal
        assertEquals(1.0f, v1.dot(v3), 0.0001f) // parallel
    }

    // Port of Swift test_VectorNormalization (expanded with 3-4-5 triangle)
    @Test
    fun test_VectorNormalization_345() {
        val v = FloatVector(floatArrayOf(3.0f, 4.0f)) // 3-4-5 triangle
        val normalized = v.normalized()

        // Should have unit length
        val length = kotlin.math.sqrt(normalized[0] * normalized[0] + normalized[1] * normalized[1])
        assertEquals(1.0f, length, 0.0001f)

        // Direction preserved
        assertEquals(0.6f, normalized[0], 0.0001f)
        assertEquals(0.8f, normalized[1], 0.0001f)
    }

    // Port of Swift test_MultipleDocumentsWithVectors
    @Test
    fun test_MultipleEmbeddingsWithVectors() {
        val lattice = testLattice(Embedding::class)

        // Create embeddings with different vectors
        val items = listOf(
            Embedding().apply { name = "A"; vector = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f)) },
            Embedding().apply { name = "B"; vector = FloatVector(floatArrayOf(0.0f, 1.0f, 0.0f)) },
            Embedding().apply { name = "C"; vector = FloatVector(floatArrayOf(0.0f, 0.0f, 1.0f)) },
            Embedding().apply { name = "D"; vector = FloatVector(floatArrayOf(0.5f, 0.5f, 0.0f)) }
        )

        items.forEach { lattice.add(it) }

        val results = lattice.objects(Embedding::class)
        assertEquals(4, results.count)

        // Find embedding most similar to [1, 0, 0] using client-side distance
        val query = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))
        var bestName: String? = null
        var bestDistance = Float.MAX_VALUE

        for (emb in results) {
            if (emb.vector.dimensions == query.dimensions) {
                val distance = emb.vector.cosineDistance(query)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestName = emb.name
                }
            }
        }

        assertEquals("A", bestName)

        lattice.close()
    }

    // Port of Swift test_EmptyVectorDoesNotCrashQuery (adapted)
    @Test
    fun test_EmptyVectorDoesNotCrash() {
        val lattice = testLattice(Embedding::class)

        // Insert an embedding with a real vector
        val emb1 = Embedding().apply {
            name = "Has Vector"
            vector = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))
        }
        lattice.add(emb1)

        // Insert an embedding with an empty/default vector — should not crash
        val emb2 = Embedding().apply {
            name = "No Vector"
            // vector stays default empty
        }
        lattice.add(emb2)

        // Both should be queryable
        assertEquals(2, lattice.objects(Embedding::class).count)

        lattice.close()
    }

    // Test FloatVector equality and hashCode
    @Test
    fun test_FloatVectorEquality() {
        val v1 = FloatVector(floatArrayOf(1.0f, 2.0f, 3.0f))
        val v2 = FloatVector(floatArrayOf(1.0f, 2.0f, 3.0f))
        val v3 = FloatVector(floatArrayOf(1.0f, 2.0f, 4.0f))

        assertEquals(v1, v2)
        assertEquals(v1.hashCode(), v2.hashCode())
        assertNotEquals(v1, v3)
    }

    // Test FloatVector iteration
    @Test
    fun test_FloatVectorIteration() {
        val values = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f)
        val v = FloatVector(values)

        assertEquals(5, v.dimensions)

        val collected = mutableListOf<Float>()
        for (value in v) {
            collected.add(value)
        }

        assertEquals(5, collected.size)
        for (i in values.indices) {
            assertEquals(values[i], collected[i])
        }
    }
}
