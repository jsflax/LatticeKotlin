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
 * Stored via a discriminated junction table that includes a target_type column.
 * When managed (attached to a database), all operations delegate to the native
 * link list through JNI. Type resolution uses `lattice_object_get_table_name()`
 * to determine the concrete type of each element, then creates the correct
 * Kotlin wrapper via the factory registry.
 *
 * This mirrors the Swift VirtualList which uses `link_list_ref` with
 * `ModelTypeRegistry.shared.resolve(tableName)` for runtime polymorphism.
 */
class VirtualList<T : VirtualModel>() {

    /**
     * Internal: The native handle for this link list.
     * Set by the compiler-generated code when retrieving from a managed object.
     * Uses the same link list handle as LatticeList — the C++ layer handles
     * the target_type discriminator transparently.
     */
    var _nativeHandle: Long = 0L

    /**
     * Whether this list is managed (attached to a native link list).
     */
    val isManaged: Boolean get() = _nativeHandle != 0L

    /**
     * Unmanaged storage for elements added before the list is attached to a managed object.
     */
    private val unmanagedItems = mutableListOf<T>()

    val size: Int
        get() = if (isManaged) {
            NativeBridge.linkListCount(_nativeHandle)
        } else {
            unmanagedItems.size
        }

    val isEmpty: Boolean get() = size == 0

    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        return if (isManaged) {
            val objHandle = NativeBridge.linkListGet(_nativeHandle, index)
            if (objHandle == 0L) {
                throw IllegalStateException("Failed to get element at index $index")
            }

            // Resolve the concrete type via table name — this is the key
            // difference from LatticeList which knows the type statically.
            val tableName = NativeBridge.getObjectTableName(objHandle)
                ?: throw IllegalStateException("Cannot resolve table name for object at index $index")

            // Look up factory by table name
            val factory = VirtualModelRegistry.getFactoryForTable(tableName)
                ?: throw IllegalStateException("No factory registered for table '$tableName'. " +
                    "Ensure the @Model class is registered with Lattice.")

            val element = factory()
            element._latticeHandle = objHandle
            element as T
        } else {
            unmanagedItems[index]
        }
    }

    fun add(item: T) {
        if (isManaged) {
            val latticeObj = item as? LatticeObject
                ?: throw IllegalArgumentException("VirtualList items must be LatticeObject instances when managed")
            NativeBridge.linkListAppend(_nativeHandle, latticeObj._latticeHandle)
        } else {
            unmanagedItems.add(item)
        }
    }

    fun remove(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        return if (isManaged) {
            val element = get(index)
            NativeBridge.linkListRemove(_nativeHandle, index)
            element
        } else {
            unmanagedItems.removeAt(index)
        }
    }

    fun clear() {
        if (isManaged) {
            NativeBridge.linkListClear(_nativeHandle)
        } else {
            unmanagedItems.clear()
        }
    }

    fun toList(): List<T> {
        val count = size
        return (0 until count).map { get(it) }
    }

    operator fun iterator(): Iterator<T> = VirtualListIterator()

    /**
     * Copy unmanaged elements to the managed list.
     * Called when the parent object becomes managed.
     */
    internal fun copyToManaged() {
        if (!isManaged) return

        unmanagedItems.forEach { item ->
            val latticeObj = item as? LatticeObject
            if (latticeObj != null && latticeObj._latticeHandle != 0L) {
                NativeBridge.linkListAppend(_nativeHandle, latticeObj._latticeHandle)
            }
        }
        unmanagedItems.clear()
    }

    private inner class VirtualListIterator : Iterator<T> {
        private var index = 0
        private val count = size

        override fun hasNext(): Boolean = index < count
        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return get(index++)
        }
    }
}

/**
 * A polymorphic single link that can reference any model type
 * that conforms to a VirtualModel interface.
 *
 * Stored as a pair of (table_name, row_id) in the database.
 */
class VirtualLink<T : VirtualModel> {
    var value: T? = null

    constructor()
    constructor(value: T?) {
        this.value = value
    }
}

/**
 * Registry for VirtualModel conformances and table-to-factory mappings.
 * Maps VirtualModel interfaces to their concrete implementations,
 * and table names to factory functions for runtime type resolution.
 */
object VirtualModelRegistry {
    private val conformances = mutableMapOf<KClass<out VirtualModel>, MutableList<KClass<out LatticeObject>>>()
    private val tableFactories = mutableMapOf<String, () -> LatticeObject>()

    fun <V : VirtualModel> register(virtualType: KClass<V>, concreteType: KClass<out LatticeObject>) {
        conformances.getOrPut(virtualType) { mutableListOf() }.add(concreteType)
    }

    /**
     * Register a factory for a table name. Called by generated companion object init blocks.
     * This enables VirtualList to create the correct concrete type when reading from the database.
     */
    fun registerTableFactory(tableName: String, factory: () -> LatticeObject) {
        tableFactories[tableName] = factory
    }

    fun <V : VirtualModel> getConformingTypes(virtualType: KClass<V>): List<KClass<out LatticeObject>> {
        return conformances[virtualType] ?: emptyList()
    }

    /**
     * Get factory for a table name. Used by VirtualList to resolve polymorphic types.
     * Falls back to Lattice.getFactory if no table-specific factory is registered.
     */
    fun getFactoryForTable(tableName: String): (() -> LatticeObject)? {
        return tableFactories[tableName]
    }
}
