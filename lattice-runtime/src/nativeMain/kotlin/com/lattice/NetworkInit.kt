package com.lattice

import kotlin.concurrent.AtomicInt

private val networkFactoryRegistered = AtomicInt(0)

/**
 * Registers the Ktor-based network factory with the C++ layer.
 * Called once on first Lattice init with sync configuration.
 */
internal actual fun ensureNetworkFactoryRegistered() {
    // Use atomic compare-and-swap to ensure single registration
    if (networkFactoryRegistered.compareAndSet(0, 1)) {
        val factory = KtorWebSocketFactory()
        LatticeNetworkBridge.setGlobalFactory(factory)
    }
}
