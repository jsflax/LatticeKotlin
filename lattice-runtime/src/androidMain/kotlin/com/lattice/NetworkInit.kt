package com.lattice

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LatticeSync"
private val networkFactoryRegistered = AtomicBoolean(false)

/**
 * Registers the Ktor-based network factory with the C++ layer via JNI.
 * Called once on first Lattice init with sync configuration.
 */
internal actual fun ensureNetworkFactoryRegistered() {
    Log.d(TAG, "ensureNetworkFactoryRegistered called, already registered: ${networkFactoryRegistered.get()}")
    if (networkFactoryRegistered.compareAndSet(false, true)) {
        Log.d(TAG, "Registering network factory...")
        LatticeNetworkBridge.initializeDefault()
        Log.d(TAG, "Network factory registered")
    }
}
