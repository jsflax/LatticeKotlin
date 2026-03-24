package com.lattice

import kotlin.reflect.KClass
import platform.posix.unlink

/**
 * On Kotlin/Native, reflection-based instantiation is not supported.
 * Users must register factories explicitly or rely on compiler-generated registration.
 */
internal actual fun createFactoryViaReflection(kClass: KClass<*>): (() -> LatticeObject)? {
    // Kotlin/Native doesn't support reflection-based instantiation
    // Return null - factories must be registered explicitly
    return null
}

/**
 * Native implementation: delete database files using platform.posix.unlink.
 */
internal actual fun deleteDatabaseFiles(path: String) {
    for (suffix in listOf("", "-wal", "-shm")) {
        try {
            unlink(path + suffix)
        } catch (_: Exception) {}
    }
}
