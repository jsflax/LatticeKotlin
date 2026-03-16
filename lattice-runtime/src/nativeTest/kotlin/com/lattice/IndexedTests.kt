package com.lattice

import kotlin.test.*

/**
 * Tests for @Indexed and @Unique annotations.
 * Port of Swift IndexedTests.swift.
 */
class IndexedTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_TextQueryExpressions() {
        // Unit tests for TextQuery expression generation
        assertEquals("hello AND world", TextQuery.allOf("hello", "world").toExpression())
        assertEquals("hello OR world", TextQuery.anyOf("hello", "world").toExpression())
        assertEquals("\"exact phrase\"", TextQuery.phrase("exact phrase").toExpression())
        assertEquals("mach*", TextQuery.prefix("mach").toExpression())
        assertEquals("NEAR(hello world, 5)", TextQuery.near("hello", "world", distance = 5).toExpression())
        assertEquals("custom expression", TextQuery.raw("custom expression").toExpression())
    }

    @Test
    fun test_GeoBoundsCreation() {
        val point = GeoBounds.point(37.7749, -122.4194)
        assertTrue(point.isPoint)
        assertEquals(37.7749, point.centerLat)
        assertEquals(-122.4194, point.centerLon)

        val bounds = GeoBounds(37.0, 38.0, -123.0, -121.5)
        assertFalse(bounds.isPoint)
        assertEquals(37.5, bounds.centerLat)
        assertEquals(-122.25, bounds.centerLon)
    }

    @Test
    fun test_GeoBoundsContains() {
        val bounds = GeoBounds(37.0, 38.0, -123.0, -121.5)
        assertTrue(bounds.contains(37.5, -122.0))
        assertFalse(bounds.contains(39.0, -122.0))
        assertFalse(bounds.contains(37.5, -124.0))
    }

    @Test
    fun test_GeoBoundsIntersects() {
        val a = GeoBounds(37.0, 38.0, -123.0, -121.5)
        val b = GeoBounds(37.5, 38.5, -122.0, -121.0)
        val c = GeoBounds(39.0, 40.0, -123.0, -121.5)

        assertTrue(a.intersects(b))
        assertTrue(b.intersects(a))
        assertFalse(a.intersects(c))
    }

    @Test
    fun test_DistanceUnitConversion() {
        val meters = 1609.344
        assertEquals(1.0, DistanceUnit.MILES.fromMeters(meters), 0.001)
        assertEquals(1.609344, DistanceUnit.KILOMETERS.fromMeters(meters), 0.001)
        assertEquals(meters, DistanceUnit.METERS.fromMeters(meters), 0.001)

        assertEquals(meters, DistanceUnit.MILES.toMeters(1.0), 0.001)
    }
}
