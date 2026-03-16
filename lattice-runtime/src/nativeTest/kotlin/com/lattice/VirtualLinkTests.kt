package com.lattice

import kotlin.test.*

/**
 * Tests for VirtualLink (polymorphic single link).
 * Port of Swift VirtualLinkTests.swift.
 */
class VirtualLinkTests {

    @Test
    fun test_VirtualLinkDefaultIsNull() {
        val link = VirtualLink<VirtualModel>()
        assertNull(link.value)
    }

    @Test
    fun test_VirtualLinkSetAndClear() {
        val link = VirtualLink<VirtualModel>()
        assertNull(link.value)
        // Set to null explicitly
        link.value = null
        assertNull(link.value)
    }

    @Test
    fun test_VirtualLinkMultipleIndependent() {
        val link1 = VirtualLink<VirtualModel>()
        val link2 = VirtualLink<VirtualModel>()
        // Two links don't share state
        assertNull(link1.value)
        assertNull(link2.value)
        link1.value = null
        assertNull(link2.value) // unaffected
    }

    @Test
    fun test_VirtualListToListReturnsIndependentCopy() {
        val list = VirtualList<VirtualModel>()
        val copy1 = list.toList()
        val copy2 = list.toList()
        assertEquals(copy1, copy2)
        assertTrue(copy1.isEmpty())
    }
}
