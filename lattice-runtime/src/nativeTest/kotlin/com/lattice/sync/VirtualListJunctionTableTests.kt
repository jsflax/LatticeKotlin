package com.lattice.sync

import com.lattice.*
import kotlin.test.*

/**
 * Tests for virtual list junction table sync.
 * Port of Swift VirtualListJunctionTableTests.swift.
 */
class VirtualListJunctionTableTests {

    @Test
    fun test_VirtualListEmpty() {
        val list = VirtualList<VirtualModel>()
        assertTrue(list.isEmpty)
        assertEquals(0, list.size)
    }
}
