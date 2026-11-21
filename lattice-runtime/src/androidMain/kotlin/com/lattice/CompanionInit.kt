package com.lattice

import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
internal actual fun createFactoryViaReflection(kClass: KClass<*>): (() -> LatticeObject)? {
    return try {
        val constructor = kClass.java.getDeclaredConstructor()
        constructor.isAccessible = true
        val factory: () -> LatticeObject = { constructor.newInstance() as LatticeObject }
        factory
    } catch (_: Exception) {
        null
    }
}

@Suppress("UNCHECKED_CAST")
internal actual fun createHydrationFactoryViaReflection(kClass: KClass<*>): ((Long) -> LatticeObject)? {
    return try {
        val constructor = kClass.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            LatticeHydrationMarker::class.java
        )
        constructor.isAccessible = true
        val factory: (Long) -> LatticeObject = { handle ->
            constructor.newInstance(handle, LatticeHydrationMarker) as LatticeObject
        }
        factory
    } catch (_: Exception) {
        null
    }
}

internal actual fun deleteDatabaseFiles(path: String) {
    for (suffix in listOf("", "-wal", "-shm")) {
        try {
            java.io.File(path + suffix).delete()
        } catch (_: Exception) {}
    }
}
