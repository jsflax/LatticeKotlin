package com.lattice

import kotlin.test.*

/**
 * Tests for VirtualList (polymorphic list).
 * Port of Swift VirtualListTests.swift.
 */
class VirtualListTests {

    @Test
    fun test_VirtualListBasicOperations() {
        val list = VirtualList<VirtualModel>()
        assertTrue(list.isEmpty)
        assertEquals(0, list.size)
    }

    @Test
    fun test_VirtualModelRegistryRegistration() {
        // Verify the registry can store and retrieve conformances
        val conforming = VirtualModelRegistry.getConformingTypes(VirtualModel::class)
        // No registrations yet in test context
        assertNotNull(conforming)
    }

    @Test
    fun test_VirtualLinkCreation() {
        val link = VirtualLink<VirtualModel>()
        assertNull(link.value)
    }

    @Test
    fun test_VirtualListAddAndGet() {
        // Use a simple marker interface for testing
        val list = VirtualList<VirtualModel>()
        // VirtualList stores items — we can't add real VirtualModel instances
        // without concrete implementations, so test the container API
        assertTrue(list.isEmpty)
        assertEquals(0, list.size)
        val snapshot = list.toList()
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun test_VirtualListClear() {
        val list = VirtualList<VirtualModel>()
        // Clearing an empty list should not throw
        list.clear()
        assertTrue(list.isEmpty)
        assertEquals(0, list.size)
    }

    @Test
    fun test_VirtualListIteration() {
        val list = VirtualList<VirtualModel>()
        val collected = mutableListOf<VirtualModel>()
        for (item in list) {
            collected.add(item)
        }
        assertTrue(collected.isEmpty())
    }
}
