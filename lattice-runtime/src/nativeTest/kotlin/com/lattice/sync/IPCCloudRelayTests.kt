package com.lattice.sync

import com.lattice.*
import kotlin.test.*

/**
 * Tests for IPC cloud relay (IPC → WSS sync bridge).
 * Port of Swift IPCCloudRelayTests.swift.
 */
class IPCCloudRelayTests {

    @BeforeTest
    fun setup() {
        registerTestModelFactories()
    }

    @Test
    fun test_IpcTargetWithSyncFilter() {
        val target = IpcTarget(
            channel = "com.myapp.cloud-relay",
            syncFilter = SyncFilter(
                entries = listOf(
                    SyncFilterEntry("Person", "isSynced = 1")
                )
            )
        )
        assertNotNull(target.syncFilter)
        assertEquals(1, target.syncFilter!!.entries.size)
    }
}
