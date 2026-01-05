package com.lattice

import kotlin.reflect.KClass

/**
 * A list of linked models for one-to-many relationships.
 *
 * Usage:
 * ```kotlin
 * @Model
 * class Trip {
 *     @LinkList
 *     var destinations: LatticeList<Destination> = LatticeList()
 * }
 * ```
 *
 * This class wraps a native link list handle and provides list operations.
 * The list is backed by a C++ link_list_ref which manages the relationship
 * in the database.
 */
class LatticeList<T : LatticeObject>() : AbstractMutableList<T>() {

    /**
     * Internal: The native handle for this link list.
     * Set by the compiler-generated code when retrieving from a managed object.
     */
    var _nativeHandle: Long = 0L

    /**
     * Internal: Factory function to create elements of type T.
     * Set by the compiler-generated code.
     */
    var _elementFactory: (() -> T)? = null

    /**
     * The element class, used to lookup factory from registry.
     */
    private var _elementClass: KClass<T>? = null

    /**
     * Set the element class for factory lookup.
     */
    @Suppress("UNCHECKED_CAST")
    fun setElementClass(clazz: KClass<*>) {
        _elementClass = clazz as? KClass<T>
    }

    /**
     * Unmanaged storage for elements added before the list is attached to a managed object.
     */
    private val unmanagedElements = mutableListOf<T>()

    /**
     * Whether this list is managed (attached to a C++ link_list_ref).
     */
    val isManaged: Boolean get() = _nativeHandle != 0L

    @Suppress("UNCHECKED_CAST")
    private fun createElement(): T {
        // Try the direct factory first
        _elementFactory?.let { return it() }

        // Try the element class lookup
        _elementClass?.let { clazz ->
            val factory = Lattice.getFactory(clazz)
            if (factory != null) {
                return factory() as T
            }
        }

        throw IllegalStateException("No element factory available for LatticeList")
    }

    override val size: Int
        get() = if (isManaged) {
            NativeBridge.linkListCount(_nativeHandle)
        } else {
            unmanagedElements.size
        }

    override fun get(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        return if (isManaged) {
            val objHandle = NativeBridge.linkListGet(_nativeHandle, index)
            if (objHandle == 0L) {
                throw IllegalStateException("Failed to get element at index $index")
            }

            val element = createElement()
            element._latticeHandle = objHandle
            element
        } else {
            unmanagedElements[index]
        }
    }

    override fun add(index: Int, element: T) {
        if (index < 0 || index > size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        if (isManaged) {
            if (index == size) {
                // Append
                NativeBridge.linkListAppend(_nativeHandle, element._latticeHandle)
            } else {
                // Insert at index
                NativeBridge.linkListInsert(_nativeHandle, index, element._latticeHandle)
            }
        } else {
            unmanagedElements.add(index, element)
        }
    }

    override fun removeAt(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        return if (isManaged) {
            val element = get(index)
            NativeBridge.linkListRemove(_nativeHandle, index)
            element
        } else {
            unmanagedElements.removeAt(index)
        }
    }

    override fun set(index: Int, element: T): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        return if (isManaged) {
            val old = get(index)
            NativeBridge.linkListRemove(_nativeHandle, index)
            NativeBridge.linkListInsert(_nativeHandle, index, element._latticeHandle)
            old
        } else {
            unmanagedElements.set(index, element)
        }
    }

    override fun clear() {
        if (isManaged) {
            NativeBridge.linkListClear(_nativeHandle)
        } else {
            unmanagedElements.clear()
        }
    }

    /**
     * Copy unmanaged elements to the managed list.
     * Called when the parent object becomes managed.
     */
    internal fun copyToManaged() {
        if (!isManaged) return

        unmanagedElements.forEach { element ->
            if (element._latticeHandle != 0L) {
                NativeBridge.linkListAppend(_nativeHandle, element._latticeHandle)
            }
        }
        unmanagedElements.clear()
    }
}
