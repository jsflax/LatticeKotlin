package com.lattice

import kotlin.reflect.KClass

interface VirtualModel

class VirtualList<T : VirtualModel>() {

    var _nativeHandle: Long = 0L
        set(value) {
            field = value
            if (value != 0L) {
                NativePhantomReaper.registerLinkList(this, value, null)
            }
        }

    val isManaged: Boolean get() = _nativeHandle != 0L

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
            val tableName = NativeBridge.getObjectTableName(objHandle)
                ?: throw IllegalStateException("Cannot resolve table name for object at index $index")

            val hydration = VirtualModelRegistry.getHydrationFactoryForTable(tableName)
            val element: LatticeObject = if (hydration != null) {
                hydration(objHandle)
            } else {
                val factory = VirtualModelRegistry.getFactoryForTable(tableName)
                    ?: throw IllegalStateException(
                        "No factory registered for table '$tableName'. Ensure the @Model class is registered with Lattice."
                    )
                factory()
            }
            LatticeNative.adoptManagedHandle(element, objHandle, null) as T
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

    fun toList(): List<T> = (0 until size).map { get(it) }

    operator fun iterator(): Iterator<T> = VirtualListIterator()

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

class VirtualLink<T : VirtualModel> {
    var value: T? = null

    constructor()
    constructor(value: T?) {
        this.value = value
    }
}

object VirtualModelRegistry {
    private val conformances = mutableMapOf<KClass<out VirtualModel>, MutableList<KClass<out LatticeObject>>>()
    private val tableFactories = mutableMapOf<String, () -> LatticeObject>()
    private val tableHydrationFactories = mutableMapOf<String, (Long) -> LatticeObject>()

    fun <V : VirtualModel> register(virtualType: KClass<V>, concreteType: KClass<out LatticeObject>) {
        conformances.getOrPut(virtualType) { mutableListOf() }.add(concreteType)
    }

    fun registerTableFactory(tableName: String, factory: () -> LatticeObject) {
        tableFactories[tableName] = factory
    }

    fun registerTableHydrationFactory(tableName: String, factory: (Long) -> LatticeObject) {
        tableHydrationFactories[tableName] = factory
    }

    fun <V : VirtualModel> getConformingTypes(virtualType: KClass<V>): List<KClass<out LatticeObject>> =
        conformances[virtualType] ?: emptyList()

    fun getFactoryForTable(tableName: String): (() -> LatticeObject)? =
        tableFactories[tableName]

    fun getHydrationFactoryForTable(tableName: String): ((Long) -> LatticeObject)? =
        tableHydrationFactories[tableName]
}
