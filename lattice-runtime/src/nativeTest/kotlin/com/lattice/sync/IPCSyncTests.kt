package com.lattice.sync

import com.lattice.*
import kotlin.test.*

/**
 * Tests for IPC sync between processes.
 * Port of Swift IPCSyncTests.swift.
 */
class IPCSyncTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_IpcTargetWithExplicitSocketPath() {
        val target = IpcTarget(
            channel = "com.myapp.shared",
            socketPath = "/tmp/myapp.sock"
        )
        assertEquals("/tmp/myapp.sock", target.socketPath)
    }

    @Test
    fun test_MultipleIpcTargets() {
        val targets = listOf(
            IpcTarget(channel = "com.myapp.main"),
            IpcTarget(channel = "com.myapp.extension"),
            IpcTarget(channel = "com.myapp.widget")
        )
        assertEquals(3, targets.size)
    }
}
