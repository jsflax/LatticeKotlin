package com.lattice.sync

import com.lattice.*
import kotlin.test.*

/**
 * Tests for sync progress monitoring.
 * Port of Swift SyncProgressTests.swift.
 */
class SyncProgressTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_SyncFilterEmpty() {
        val filter = SyncFilter()
        assertTrue(filter.entries.isEmpty())
    }
}
