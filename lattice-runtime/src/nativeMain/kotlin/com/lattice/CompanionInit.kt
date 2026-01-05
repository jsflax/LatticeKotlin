package com.lattice

import kotlin.reflect.KClass

/**
 * On Kotlin/Native, reflection-based instantiation is not supported.
 * Users must register factories explicitly or rely on compiler-generated registration.
 */
internal actual fun createFactoryViaReflection(kClass: KClass<*>): (() -> LatticeObject)? {
    // Kotlin/Native doesn't support reflection-based instantiation
    // Return null - factories must be registered explicitly
    return null
}
