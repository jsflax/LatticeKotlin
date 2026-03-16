package com.lattice

/**
 * Geographic bounding box for R*Tree spatial indexing.
 *
 * Stored as 4 REAL columns (minLat, maxLat, minLon, maxLon) with
 * automatic R*Tree index for efficient spatial queries.
 *
 * Use as a point (min == max) for single locations:
 * ```kotlin
 * val location = GeoBounds.point(37.7749, -122.4194)
 * ```
 *
 * Or as a bounding box:
 * ```kotlin
 * val bounds = GeoBounds(37.0, 38.0, -123.0, -121.5)
 * ```
 */
data class GeoBounds(
    val minLat: Double = 0.0,
    val maxLat: Double = 0.0,
    val minLon: Double = 0.0,
    val maxLon: Double = 0.0,
) {
    /** Center latitude of the bounds. */
    val centerLat: Double get() = (minLat + maxLat) / 2.0

    /** Center longitude of the bounds. */
    val centerLon: Double get() = (minLon + maxLon) / 2.0

    /** Whether this represents a single point. */
    val isPoint: Boolean get() = minLat == maxLat && minLon == maxLon

    /** Check if a point is within this bounding box. */
    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon

    /** Check if another bounding box intersects this one. */
    fun intersects(other: GeoBounds): Boolean =
        !(other.maxLat < minLat || other.minLat > maxLat ||
            other.maxLon < minLon || other.minLon > maxLon)

    companion object {
        /** Create a point (bounding box with zero area). */
        fun point(lat: Double, lon: Double) = GeoBounds(lat, lat, lon, lon)
    }
}

/**
 * Distance units for geospatial queries.
 */
enum class DistanceUnit {
    METERS,
    KILOMETERS,
    MILES,
    FEET;

    /** Convert meters to this unit. */
    fun fromMeters(meters: Double): Double = when (this) {
        METERS -> meters
        KILOMETERS -> meters / 1000.0
        MILES -> meters / 1609.344
        FEET -> meters * 3.28084
    }

    /** Convert this unit to meters. */
    fun toMeters(value: Double): Double = when (this) {
        METERS -> value
        KILOMETERS -> value * 1000.0
        MILES -> value * 1609.344
        FEET -> value / 3.28084
    }
}
