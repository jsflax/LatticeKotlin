package com.lattice

import kotlin.reflect.KClass

class LatticeList<T : LatticeObject>() : AbstractMutableList<T>() {

    var _nativeHandle: Long = 0L
        set(value) {
            field = value
            if (value != 0L) {
                NativePhantomReaper.registerLinkList(this, value, null)
            }
        }

    var _elementFactory: (() -> T)? = null

    private var _elementClass: KClass<T>? = null

    @Suppress("UNCHECKED_CAST")
    fun setElementClass(clazz: KClass<*>) {
        _elementClass = clazz as? KClass<T>
    }

    private val unmanagedElements = mutableListOf<T>()

    val isManaged: Boolean get() = _nativeHandle != 0L

    @Suppress("UNCHECKED_CAST")
    private fun hydrateElement(objHandle: Long): T {
        _elementClass?.let { clazz ->
            val hydration = Lattice.getHydrationFactory(clazz)
            if (hydration != null) {
                val obj = hydration(objHandle) as T
                return LatticeNative.adoptManagedHandle(obj, objHandle, null)
            }
        }
        val element: T = _elementFactory?.invoke()
            ?: (_elementClass?.let { Lattice.getFactory(it)?.invoke() as? T })
            ?: throw IllegalStateException("No element factory available for LatticeList")
        return LatticeNative.adoptManagedHandle(element, objHandle, null)
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
            hydrateElement(objHandle)
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
                NativeBridge.linkListAppend(_nativeHandle, element._latticeHandle)
            } else {
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
