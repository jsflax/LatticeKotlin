package com.lattice

import kotlin.reflect.KClass

/**
 * Creates a factory for a model class using JVM reflection.
 * Finds the no-arg constructor and returns a factory that calls it.
 */
@Suppress("UNCHECKED_CAST")
internal actual fun createFactoryViaReflection(kClass: KClass<*>): (() -> LatticeObject)? {
    return try {
        val javaClass = kClass.java
        val constructor = javaClass.getDeclaredConstructor()
        constructor.isAccessible = true
        val factory: () -> LatticeObject = {
            constructor.newInstance() as LatticeObject
        }
        factory
    } catch (e: Exception) {
        null
    }
}
