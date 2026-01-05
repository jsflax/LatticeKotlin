package com.lattice

import kotlinx.serialization.json.Json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * JSON instance for embedded model serialization.
 * Uses lenient parsing to handle edge cases.
 */
val latticeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * Interface for embedded models.
 * Embedded models are stored as JSON strings in the database.
 *
 * Usage:
 * ```kotlin
 * @Embedded
 * @Serializable
 * data class Address(
 *     val street: String = "",
 *     val city: String = ""
 * ) : LatticeEmbedded
 * ```
 */
interface LatticeEmbedded

/**
 * Registry of serializers for embedded types.
 * Call registerEmbeddedSerializer<YourType>() to register.
 */
object EmbeddedSerializers {
    private val serializers = mutableMapOf<KClass<*>, KSerializer<*>>()
    private val factories = mutableMapOf<KClass<*>, () -> Any>()

    fun <T : Any> register(kClass: KClass<T>, serializer: KSerializer<T>, factory: () -> T) {
        serializers[kClass] = serializer
        factories[kClass] = factory
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getSerializer(kClass: KClass<T>): KSerializer<T>? {
        return serializers[kClass] as? KSerializer<T>
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getFactory(kClass: KClass<T>): (() -> T)? {
        return factories[kClass] as? (() -> T)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> decode(kClass: KClass<T>, json: String): T? {
        val serializer = getSerializer(kClass) ?: return null
        return try {
            latticeJson.decodeFromString(serializer, json)
        } catch (e: Exception) {
            null
        }
    }

    fun <T : Any> encode(kClass: KClass<T>, value: T): String? {
        val serializer = getSerializer(kClass) ?: return null
        return try {
            latticeJson.encodeToString(serializer, value)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Register an embedded type serializer.
 * Call this for each embedded type in your app initialization.
 */
inline fun <reified T : LatticeEmbedded> registerEmbeddedSerializer(
    serializer: KSerializer<T>,
    noinline factory: () -> T
) {
    EmbeddedSerializers.register(T::class, serializer, factory)
}
