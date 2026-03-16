package com.lattice

/**
 * Distance metric for vector similarity search.
 */
enum class DistanceMetric {
    /** Euclidean distance (L2 norm). Default. */
    L2,
    /** Cosine distance (1 - cosine similarity). */
    COSINE,
    /** Manhattan distance (L1 norm). */
    L1
}

/**
 * A single result from a nearest-neighbor vector search.
 *
 * @property object The matched model instance.
 * @property distance The distance between the query vector and this object's vector.
 */
data class NearestMatch<T : LatticeObject>(
    val `object`: T,
    val distance: Double
)
