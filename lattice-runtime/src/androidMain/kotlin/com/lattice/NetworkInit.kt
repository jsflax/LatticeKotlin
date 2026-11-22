package com.lattice

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LatticeSync"
private val networkFactoryRegistered = AtomicBoolean(false)

internal fun markNetworkFactoryRegistered() {
    networkFactoryRegistered.set(true)
}

internal fun safeLogDebug(tag: String, msg: String) {
    try { Log.d(tag, msg) } catch (_: Throwable) {}
}

internal fun safeLogError(tag: String, msg: String) {
    try { Log.e(tag, msg) } catch (_: Throwable) {}
}

internal actual fun ensureNetworkFactoryRegistered() {
    if (networkFactoryRegistered.compareAndSet(false, true)) {
        safeLogDebug(TAG, "Registering default Ktor network factory")
        LatticeNetworkBridge.initializeDefault()
    }
}
