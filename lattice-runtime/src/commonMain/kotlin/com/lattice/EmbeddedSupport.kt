package com.lattice

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass

/**
 * JSON instance for embedded model serialization.
 * Uses lenient parsing and encodes defaults for wire parity with Swift Codable.
 */
val latticeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * Optional marker interface for embedded models.
 * Any `@Embedded @Serializable` class works automatically with or without implementing [LatticeEmbedded].
 */
interface LatticeEmbedded

/**
 * Registry of serializers for embedded types.
 * Automatically falls back to `kClass.serializerOrNull()` for `@Serializable` types.
 */
object EmbeddedSerializers {
    private val serializers = mutableMapOf<KClass<*>, KSerializer<*>>()
    private val factories = mutableMapOf<KClass<*>, () -> Any>()

    fun <T : Any> register(kClass: KClass<T>, serializer: KSerializer<T>, factory: () -> T) {
        serializers[kClass] = serializer
        factories[kClass] = factory
    }

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getSerializer(kClass: KClass<T>): KSerializer<T>? {
        (serializers[kClass] as? KSerializer<T>)?.let { return it }
        val resolved = try {
            kClass.serializerOrNull()
        } catch (_: Exception) {
            null
        }
        if (resolved != null) {
            serializers[kClass] = resolved
        }
        return resolved
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getFactory(kClass: KClass<T>): (() -> T)? {
        (factories[kClass] as? (() -> T))?.let { return it }
        val serializer = getSerializer(kClass)
        if (serializer != null) {
            return {
                latticeJson.decodeFromString(serializer, "{}")
            }
        }
        return null
    }

    fun <T : Any> decode(kClass: KClass<T>, json: String): T? {
        val serializer = getSerializer(kClass) ?: return null
        return try {
            latticeJson.decodeFromString(serializer, json)
        } catch (_: Exception) {
            null
        }
    }

    fun <T : Any> encode(kClass: KClass<T>, value: T): String? {
        val serializer = getSerializer(kClass) ?: return null
        return try {
            latticeJson.encodeToString(serializer, value)
        } catch (_: Exception) {
            null
        }
    }
}

inline fun <reified T : Any> registerEmbeddedSerializer(
    serializer: KSerializer<T>,
    noinline factory: () -> T
) {
    EmbeddedSerializers.register(T::class, serializer, factory)
}
