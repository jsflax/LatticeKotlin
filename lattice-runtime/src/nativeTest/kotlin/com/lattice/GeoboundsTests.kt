package com.lattice

import kotlin.test.*

/**
 * Tests for GeoBounds type and geospatial query support.
 * Port of Swift GeoboundsTests.swift.
 */
class GeoboundsTests {

    @Test
    fun test_PointCreation() {
        val point = GeoBounds.point(37.7749, -122.4194)
        assertTrue(point.isPoint)
        assertEquals(37.7749, point.minLat)
        assertEquals(37.7749, point.maxLat)
        assertEquals(-122.4194, point.minLon)
        assertEquals(-122.4194, point.maxLon)
    }

    @Test
    fun test_BoundsCreation() {
        val bounds = GeoBounds(37.0, 38.0, -123.0, -121.5)
        assertFalse(bounds.isPoint)
        assertEquals(37.0, bounds.minLat)
        assertEquals(38.0, bounds.maxLat)
        assertEquals(-123.0, bounds.minLon)
        assertEquals(-121.5, bounds.maxLon)
    }

    @Test
    fun test_Center() {
        val bounds = GeoBounds(37.0, 38.0, -123.0, -121.0)
        assertEquals(37.5, bounds.centerLat)
        assertEquals(-122.0, bounds.centerLon)
    }

    @Test
    fun test_Contains() {
        val bounds = GeoBounds(37.0, 38.0, -123.0, -121.5)
        assertTrue(bounds.contains(37.5, -122.0))
        assertTrue(bounds.contains(37.0, -123.0)) // Edge
        assertTrue(bounds.contains(38.0, -121.5)) // Edge
        assertFalse(bounds.contains(36.9, -122.0))
        assertFalse(bounds.contains(37.5, -123.1))
    }

    @Test
    fun test_Intersects() {
        val a = GeoBounds(37.0, 38.0, -123.0, -121.5)
        val b = GeoBounds(37.5, 38.5, -122.0, -121.0) // Overlaps
        val c = GeoBounds(39.0, 40.0, -123.0, -121.5) // No overlap
        val d = GeoBounds(38.0, 39.0, -121.5, -120.0) // Touches edge

        assertTrue(a.intersects(b))
        assertTrue(b.intersects(a))
        assertFalse(a.intersects(c))
        assertTrue(a.intersects(d)) // Edge touch = intersect
    }

    @Test
    fun test_Equality() {
        val a = GeoBounds(37.0, 38.0, -123.0, -121.5)
        val b = GeoBounds(37.0, 38.0, -123.0, -121.5)
        val c = GeoBounds(37.0, 38.0, -123.0, -121.0)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun test_DefaultValues() {
        val g = GeoBounds()
        assertEquals(0.0, g.minLat)
        assertEquals(0.0, g.maxLat)
        assertEquals(0.0, g.minLon)
        assertEquals(0.0, g.maxLon)
    }

    @Test
    fun test_CenterOfPoint() {
        val p = GeoBounds.point(37.7, -122.4)
        assertEquals(37.7, p.centerLat)
        assertEquals(-122.4, p.centerLon)
    }

    @Test
    fun test_ContainsBoundary() {
        val b = GeoBounds(37.0, 38.0, -123.0, -121.5)
        assertTrue(b.contains(37.0, -123.0)) // min corner
        assertTrue(b.contains(38.0, -121.5)) // max corner
    }

    @Test
    fun test_IntersectsContained() {
        val outer = GeoBounds(36.0, 39.0, -124.0, -120.0)
        val inner = GeoBounds(37.0, 38.0, -123.0, -121.5)
        assertTrue(outer.intersects(inner))
        assertTrue(inner.intersects(outer))
    }

    @Test
    fun test_IntersectsSelf() {
        val b = GeoBounds(37.0, 38.0, -123.0, -121.5)
        assertTrue(b.intersects(b))
    }

    @Test
    fun test_DistanceUnit_Kilometers() {
        assertEquals(1.0, DistanceUnit.KILOMETERS.fromMeters(1000.0), 0.001)
        assertEquals(1000.0, DistanceUnit.KILOMETERS.toMeters(1.0), 0.001)
    }

    @Test
    fun test_DistanceUnit_Miles() {
        assertEquals(1.0, DistanceUnit.MILES.fromMeters(1609.344), 0.001)
        assertEquals(1609.344, DistanceUnit.MILES.toMeters(1.0), 0.001)
    }

    @Test
    fun test_DistanceUnit_RoundTrip() {
        val original = 5280.0 // meters
        val miles = DistanceUnit.MILES.fromMeters(original)
        val backToMeters = DistanceUnit.MILES.toMeters(miles)
        assertEquals(original, backToMeters, 0.001)
    }
}
