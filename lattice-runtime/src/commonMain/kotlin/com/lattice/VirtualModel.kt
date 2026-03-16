package com.lattice

import kotlin.reflect.KClass

/**
 * Marker interface for polymorphic model types.
 *
 * Multiple @Model classes can implement a VirtualModel interface to share
 * a common set of properties. Queries on the VirtualModel type return
 * results from all conforming tables via UNION ALL.
 *
 * Example:
 * ```kotlin
 * interface Searchable : VirtualModel {
 *     var title: String
 *     var content: String
 * }
 *
 * @Model
 * class Article : Searchable {
 *     override var title: String = ""
 *     override var content: String = ""
 *     var author: String = ""
 * }
 *
 * @Model
 * class BlogPost : Searchable {
 *     override var title: String = ""
 *     override var content: String = ""
 *     var published: Boolean = false
 * }
 *
 * // Query across both tables
 * val results = lattice.objects(Searchable::class) // UNION ALL
 * ```
 */
interface VirtualModel

/**
 * A polymorphic list that can contain objects of different model types
 * that share a common VirtualModel interface.
 *
 * Stored via a discriminated junction table that includes the source table name.
 */
class VirtualList<T : VirtualModel> {
    private val items = mutableListOf<T>()

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    operator fun get(index: Int): T = items[index]

    fun add(item: T) {
        items.add(item)
    }

    fun remove(index: Int): T = items.removeAt(index)

    fun clear() {
        items.clear()
    }

    fun toList(): List<T> = items.toList()

    operator fun iterator(): Iterator<T> = items.iterator()
}

/**
 * A polymorphic single link that can reference any model type
 * that conforms to a VirtualModel interface.
 *
 * Stored as a pair of (table_name, globalId) in the database.
 */
class VirtualLink<T : VirtualModel> {
    var value: T? = null

    constructor()
    constructor(value: T?) {
        this.value = value
    }
}

/**
 * Registry for VirtualModel conformances.
 * Maps VirtualModel interfaces to their concrete implementations.
 */
object VirtualModelRegistry {
    private val conformances = mutableMapOf<KClass<out VirtualModel>, MutableList<KClass<out LatticeObject>>>()

    fun <V : VirtualModel> register(virtualType: KClass<V>, concreteType: KClass<out LatticeObject>) {
        conformances.getOrPut(virtualType) { mutableListOf() }.add(concreteType)
    }

    fun <V : VirtualModel> getConformingTypes(virtualType: KClass<V>): List<KClass<out LatticeObject>> {
        return conformances[virtualType] ?: emptyList()
    }
}
