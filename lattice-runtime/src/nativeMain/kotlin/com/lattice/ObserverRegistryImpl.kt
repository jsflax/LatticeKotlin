package com.lattice

import com.lattice.capi.*
import kotlinx.cinterop.*

/**
 * Native implementation of ObserverRegistry using cinterop.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object ObserverRegistry {

    // Helper to convert Long to native pointer
    @Suppress("NOTHING_TO_INLINE")
    private inline fun Long.toNativePtr(): NativePtr = NativePtr.NULL + this

    private fun Long.toDbPtr(): CPointer<lattice_db_t>? {
        if (this == 0L) return null
        return interpretCPointer(this.toNativePtr())
    }

    actual fun observeTable(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        callback: (CollectionChange) -> Unit
    ): Cancellable {
        val db = dbHandle.toDbPtr()
            ?: throw LatticeException("Invalid database handle")

        // Wrapper class to hold the callback
        class ObserverContext(val callback: (CollectionChange) -> Unit)

        val context = ObserverContext(callback)
        val stableRef = StableRef.create(context)

        // Register C callback
        val token = lattice_db_observe_table(
            db,
            tableName,
            stableRef.asCPointer(),
            staticCFunction { contextPtr, operation, rowId, _ ->
                val ctx = contextPtr!!.asStableRef<ObserverContext>().get()

                // Convert C operation string to CollectionChange
                val opStr = operation?.toKString() ?: return@staticCFunction

                val change = when (opStr) {
                    "INSERT" -> CollectionChange.Insert(rowId)
                    "DELETE" -> CollectionChange.Delete(rowId)
                    "UPDATE" -> CollectionChange.Update(rowId)
                    else -> return@staticCFunction
                }

                ctx.callback(change)
            }
        )

        if (token == 0UL) {
            stableRef.dispose()
            throw LatticeException("Failed to register observer")
        }

        return ObservationToken(db, tableName, token, stableRef)
    }

    /**
     * Internal observation token that wraps the C++ observer.
     */
    private class ObservationToken(
        private val dbHandle: CPointer<lattice_db_t>,
        private val tableName: String,
        private val observerToken: ULong,
        private val stableRef: StableRef<*>
    ) : Cancellable {
        private var _isActive = true

        override val isActive: Boolean
            get() = _isActive

        override fun cancel() {
            if (_isActive) {
                _isActive = false
                lattice_db_remove_table_observer(dbHandle, tableName, observerToken)
                stableRef.dispose()
            }
        }
    }
}
