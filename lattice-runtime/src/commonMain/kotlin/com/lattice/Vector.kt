package com.lattice

/**
 * A fixed-size vector of floating-point values optimized for vector search.
 *
 * Vectors are stored as packed binary BLOBs compatible with sqlite-vec's format.
 * This enables efficient ANN (Approximate Nearest Neighbor) queries.
 *
 * Example usage:
 * ```kotlin
 * @Model
 * class Document {
 *     var title: String = ""
 *     var embedding: FloatVector = FloatVector()  // 1536-dim OpenAI embedding
 * }
 * ```
 */
class FloatVector(
    val elements: FloatArray = FloatArray(0)
) : Iterable<Float> {

    constructor(elements: List<Float>) : this(elements.toFloatArray())

    constructor(dimensions: Int, init: (Int) -> Float = { 0f }) : this(FloatArray(dimensions, init))

    /** Number of dimensions in this vector */
    val dimensions: Int get() = elements.size

    /** Access individual elements */
    operator fun get(index: Int): Float = elements[index]

    operator fun set(index: Int, value: Float) {
        elements[index] = value
    }

    override fun iterator(): Iterator<Float> = elements.iterator()

    /**
     * Convert to ByteArray for storage.
     * Each float is stored as 4 bytes in little-endian format.
     */
    fun toByteArray(): ByteArray {
        val bytes = ByteArray(elements.size * 4)
        for (i in elements.indices) {
            val bits = elements[i].toRawBits()
            bytes[i * 4] = (bits and 0xFF).toByte()
            bytes[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return bytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatVector) return false
        return elements.contentEquals(other.elements)
    }

    override fun hashCode(): Int = elements.contentHashCode()

    override fun toString(): String = "FloatVector(${elements.take(5).joinToString()}${if (elements.size > 5) ", ..." else ""})"

    companion object {
        /**
         * Create from ByteArray (4 bytes per float, little-endian).
         */
        fun fromByteArray(bytes: ByteArray): FloatVector {
            val count = bytes.size / 4
            val elements = FloatArray(count)
            for (i in 0 until count) {
                val bits = (bytes[i * 4].toInt() and 0xFF) or
                        ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
                elements[i] = Float.fromBits(bits)
            }
            return FloatVector(elements)
        }
    }

    // Distance functions for vector similarity

    /**
     * Euclidean (L2) distance squared - faster than L2 when you only need relative ordering.
     */
    fun l2DistanceSquared(other: FloatVector): Float {
        require(dimensions == other.dimensions) { "Vector dimensions must match" }
        var sum = 0f
        for (i in 0 until dimensions) {
            val diff = elements[i] - other.elements[i]
            sum += diff * diff
        }
        return sum
    }

    /**
     * Euclidean (L2) distance.
     */
    fun l2Distance(other: FloatVector): Float = kotlin.math.sqrt(l2DistanceSquared(other))

    /**
     * Cosine distance (1 - cosine similarity).
     */
    fun cosineDistance(other: FloatVector): Float {
        require(dimensions == other.dimensions) { "Vector dimensions must match" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until dimensions) {
            dot += elements[i] * other.elements[i]
            normA += elements[i] * elements[i]
            normB += other.elements[i] * other.elements[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        if (denom == 0f) return 1f
        return 1f - (dot / denom)
    }

    /**
     * Dot product (inner product).
     */
    fun dot(other: FloatVector): Float {
        require(dimensions == other.dimensions) { "Vector dimensions must match" }
        var result = 0f
        for (i in 0 until dimensions) {
            result += elements[i] * other.elements[i]
        }
        return result
    }

    /**
     * Return a normalized (unit length) vector.
     */
    fun normalized(): FloatVector {
        var sumSquares = 0f
        for (e in elements) {
            sumSquares += e * e
        }
        val norm = kotlin.math.sqrt(sumSquares)
        if (norm == 0f) return FloatVector(elements.copyOf())
        return FloatVector(FloatArray(elements.size) { elements[it] / norm })
    }
}

/**
 * A vector of 64-bit doubles.
 */
class DoubleVector(
    val elements: DoubleArray = DoubleArray(0)
) : Iterable<Double> {

    constructor(elements: List<Double>) : this(elements.toDoubleArray())

    constructor(dimensions: Int, init: (Int) -> Double = { 0.0 }) : this(DoubleArray(dimensions, init))

    val dimensions: Int get() = elements.size

    operator fun get(index: Int): Double = elements[index]

    operator fun set(index: Int, value: Double) {
        elements[index] = value
    }

    override fun iterator(): Iterator<Double> = elements.iterator()

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(elements.size * 8)
        for (i in elements.indices) {
            val bits = elements[i].toRawBits()
            for (j in 0 until 8) {
                bytes[i * 8 + j] = ((bits shr (j * 8)) and 0xFF).toByte()
            }
        }
        return bytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DoubleVector) return false
        return elements.contentEquals(other.elements)
    }

    override fun hashCode(): Int = elements.contentHashCode()

    companion object {
        fun fromByteArray(bytes: ByteArray): DoubleVector {
            val count = bytes.size / 8
            val elements = DoubleArray(count)
            for (i in 0 until count) {
                var bits = 0L
                for (j in 0 until 8) {
                    bits = bits or ((bytes[i * 8 + j].toLong() and 0xFF) shl (j * 8))
                }
                elements[i] = Double.fromBits(bits)
            }
            return DoubleVector(elements)
        }
    }
}
