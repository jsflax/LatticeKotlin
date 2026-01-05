package com.lattice

/**
 * Android JNI implementation of ObserverRegistry.
 *
 * Uses JNI to register observers with the native Lattice library.
 * Callbacks from C are dispatched back to Kotlin through the JNI bridge.
 */
internal actual object ObserverRegistry {

    actual fun observeTable(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        callback: (CollectionChange) -> Unit
    ): Cancellable {
        if (dbHandle == 0L) {
            throw LatticeException("Invalid database handle")
        }

        // Create the native callback that converts operation strings to CollectionChange
        val nativeCallback: (String, Long, String?) -> Unit = { operation, rowId, _ ->
            val change = when (operation) {
                "INSERT" -> CollectionChange.Insert(rowId)
                "DELETE" -> CollectionChange.Delete(rowId)
                "UPDATE" -> CollectionChange.Update(rowId)
                else -> null
            }
            change?.let { callback(it) }
        }

        // Register with native layer
        val contextHandle = NativeBridge.observeTable(dbHandle, tableName, nativeCallback)

        if (contextHandle == 0L) {
            throw LatticeException("Failed to register table observer")
        }

        return ObservationToken(dbHandle, tableName, contextHandle)
    }

    /**
     * Internal observation token that wraps the JNI observer.
     */
    private class ObservationToken(
        private val dbHandle: Long,
        private val tableName: String,
        private val contextHandle: Long
    ) : Cancellable {
        private var _isActive = true

        override val isActive: Boolean
            get() = _isActive

        override fun cancel() {
            if (_isActive) {
                _isActive = false
                NativeBridge.removeTableObserver(dbHandle, tableName, contextHandle)
            }
        }
    }
}
