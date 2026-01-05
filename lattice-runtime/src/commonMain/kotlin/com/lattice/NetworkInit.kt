package com.lattice

/**
 * Platform-specific network initialization for sync support.
 * Called automatically when a Lattice database is created with sync configuration.
 * Uses the same pattern as Swift: registers the factory once globally on first use.
 */
internal expect fun ensureNetworkFactoryRegistered()
