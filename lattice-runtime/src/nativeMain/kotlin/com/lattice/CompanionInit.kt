package com.lattice

import kotlin.reflect.KClass
import platform.posix.unlink

internal actual fun createFactoryViaReflection(kClass: KClass<*>): (() -> LatticeObject)? = null

internal actual fun createHydrationFactoryViaReflection(kClass: KClass<*>): ((Long) -> LatticeObject)? = null

internal actual fun deleteDatabaseFiles(path: String) {
    for (suffix in listOf("", "-wal", "-shm")) {
        try {
            unlink(path + suffix)
        } catch (_: Exception) {}
    }
}
