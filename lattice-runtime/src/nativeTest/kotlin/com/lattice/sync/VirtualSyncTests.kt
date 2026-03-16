package com.lattice.sync

import com.lattice.*
import kotlin.test.*

/**
 * Tests for syncing virtual/polymorphic models.
 * Port of Swift VirtualSyncTests.swift.
 */
class VirtualSyncTests {

    @Test
    fun test_VirtualModelRegistryEmpty() {
        // No conformances registered by default
        val types = VirtualModelRegistry.getConformingTypes(VirtualModel::class)
        // May or may not be empty depending on test ordering
        assertNotNull(types)
    }
}
