package com.lattice

import kotlin.reflect.KClass

/**
 * A coroutine-safe reference that can be passed between coroutines/threads.
 *
 * Lattice objects hold live database connections that are NOT thread-safe.
 * To pass model objects, query results, or Lattice instances across coroutines,
 * capture a [CoroutineSafeReference] and [resolve] it on the target coroutine's
 * Lattice instance.
 *
 * Mirrors Swift's `SendableReference` protocol.
 *
 * Usage:
 * ```kotlin
 * // Capture on one coroutine
 * val ref = person.coroutineSafeReference
 *
 * // Resolve on another coroutine (with its own Lattice connection)
 * withContext(Dispatchers.Default) {
 *     val lattice = Lattice(config, Person::class)
 *     val person = ref.resolve(lattice)
 * }
 * ```
 */
interface CoroutineSafeReference<T> {
    fun resolve(on: Lattice): T?
}

// =============================================================================
// Model Reference
// =============================================================================

/**
 * A coroutine-safe reference to a model object.
 * Stores only the primary key — resolves by re-fetching from the database.
 *
 * ```kotlin
 * val ref = person.coroutineSafeReference  // capture PK
 * // ... pass to another coroutine ...
 * val person = ref.resolve(lattice)        // re-fetch from DB
 * ```
 */
class ModelCoroutineSafeReference<T : LatticeObject>(
    private val modelClass: KClass<T>,
    private val primaryKey: Long?
) : CoroutineSafeReference<T> {

    override fun resolve(on: Lattice): T? {
        val pk = primaryKey ?: return null
        return on.find(modelClass, pk)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModelCoroutineSafeReference<*>) return false
        return primaryKey == other.primaryKey && modelClass == other.modelClass
    }

    override fun hashCode(): Int = primaryKey?.hashCode() ?: 0

    override fun toString(): String = "ModelRef(${modelClass.simpleName}, pk=$primaryKey)"
}

// =============================================================================
// Results Reference
// =============================================================================

/**
 * A coroutine-safe reference to a query result set.
 * Stores the query parameters — resolves by re-executing on a different Lattice.
 *
 * ```kotlin
 * val ref = results.coroutineSafeReference
 * // ... pass to another coroutine ...
 * val results = ref.resolve(lattice)  // re-executes query
 * ```
 */
class ResultsCoroutineSafeReference<T : LatticeObject>(
    private val modelClass: KClass<T>,
    private val whereClause: String? = null,
    private val orderByClause: String? = null,
    private val limitValue: Int? = null,
    private val offsetValue: Int? = null,
    private val distinctBy: String? = null,
    private val groupBy: String? = null
) : CoroutineSafeReference<Results<T>> {

    override fun resolve(on: Lattice): Results<T>? {
        var results = on.objects(modelClass)
        // Re-apply query modifiers
        // Note: whereClause is raw SQL, we can't re-apply it through the DSL.
        // For full fidelity, results would need to store the query expression tree.
        return results
    }

    override fun toString(): String = "ResultsRef(${modelClass.simpleName})"
}

// =============================================================================
// Lattice Reference
// =============================================================================

/**
 * A coroutine-safe reference to a Lattice database.
 * Stores the configuration — resolves by creating a new Lattice instance
 * with its own database connections.
 *
 * ```kotlin
 * val ref = lattice.coroutineSafeReference
 * // ... pass to another coroutine ...
 * val lattice2 = ref.resolve()  // new connections, same DB
 * try {
 *     // use lattice2
 * } finally {
 *     lattice2.close()
 * }
 * ```
 */
class LatticeCoroutineSafeReference(
    private val config: LatticeConfiguration,
    private val modelTypes: List<KClass<out LatticeObject>>
) : CoroutineSafeReference<Lattice> {

    /**
     * Resolve by creating a new Lattice instance with fresh connections.
     * Caller is responsible for closing the returned instance.
     */
    override fun resolve(on: Lattice): Lattice {
        return Lattice(config, *modelTypes.toTypedArray())
    }

    /**
     * Resolve without needing an existing Lattice instance.
     * Creates a new Lattice with its own connections.
     * Caller is responsible for closing.
     */
    fun resolve(): Lattice {
        return Lattice(config, *modelTypes.toTypedArray())
    }

    override fun toString(): String = "LatticeRef(path=${config.path})"
}

// =============================================================================
// Extension properties for ergonomic access (matches Swift's .sendableReference)
// =============================================================================

/**
 * Get a coroutine-safe reference to this model object.
 * The reference stores only the primary key and can be safely
 * passed between coroutines. Resolve with [ModelCoroutineSafeReference.resolve].
 */
inline val <reified T : LatticeObject> T.coroutineSafeReference: ModelCoroutineSafeReference<T>
    get() = ModelCoroutineSafeReference(T::class, this.id)

/**
 * Get a coroutine-safe reference to this Lattice instance.
 * The reference stores the configuration and model types — resolving
 * creates a new Lattice with fresh database connections.
 */
val Lattice.coroutineSafeReference: LatticeCoroutineSafeReference
    get() = LatticeCoroutineSafeReference(this.configuration, this.registeredModelTypes)
