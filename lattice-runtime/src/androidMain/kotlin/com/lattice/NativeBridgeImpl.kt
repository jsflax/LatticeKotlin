package com.lattice

/**
 * Android JNI implementation of NativeBridge.
 *
 * This calls the Lattice C library through JNI bindings.
 * The native library must be loaded before any calls are made.
 */
internal actual object NativeBridge {

    init {
        // Load the JNI bridge library, which links against LatticeCAPI
        System.loadLibrary("lattice_jni")
    }

    // ========== Database Lifecycle ==========

    actual fun createDbInMemory(): Long = nativeCreateDbInMemory()

    actual fun createDbAtPath(path: String): Long = nativeCreateDbAtPath(path)

    actual fun createDbWithSchemas(path: String, schemasJson: String): Long =
        nativeCreateDbWithSchemas(path, schemasJson)

    actual fun createDbWithSync(
        path: String,
        schemasJson: String,
        syncEndpoint: String,
        authToken: String
    ): Long = nativeCreateDbWithSync(path, schemasJson, syncEndpoint, authToken)

    actual fun createDbWithSchemaArrays(
        path: String,
        tableNames: Array<String>,
        propertyCounts: IntArray,
        propNames: Array<String>,
        propTypes: IntArray,
        propKinds: IntArray,
        propNullable: BooleanArray,
        propTargetTables: Array<String?>,
        propLinkTables: Array<String?>,
        syncEndpoint: String?,
        authToken: String?
    ): Long = nativeCreateDbWithSchemaArrays(
        path, tableNames, propertyCounts,
        propNames, propTypes, propKinds, propNullable,
        propTargetTables, propLinkTables,
        syncEndpoint, authToken
    )

    actual fun releaseDb(dbHandle: Long) = nativeReleaseDb(dbHandle)

    actual fun getLastError(): String? = nativeGetLastError()

    // ========== Object Operations ==========

    actual fun addObject(dbHandle: Long, objectHandle: Long): Long =
        nativeAddObject(dbHandle, objectHandle)

    actual fun findObject(dbHandle: Long, tableName: String, id: Long): Long =
        nativeFindObject(dbHandle, tableName, id)

    actual fun findObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long =
        nativeFindObjectByGlobalId(dbHandle, tableName, globalId)

    actual fun removeObject(dbHandle: Long, objectHandle: Long): Boolean =
        nativeRemoveObject(dbHandle, objectHandle)

    // ========== Query Operations ==========

    actual fun queryCount(dbHandle: Long, tableName: String, whereClause: String?): Int =
        nativeQueryCount(dbHandle, tableName, whereClause)

    actual fun queryObjects(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): LongArray = nativeQueryObjects(dbHandle, tableName, whereClause, orderBy, limit, offset)

    actual fun deleteWhere(dbHandle: Long, tableName: String, whereClause: String?): Int =
        nativeDeleteWhere(dbHandle, tableName, whereClause)

    // ========== Transaction Operations ==========

    actual fun beginTransaction(dbHandle: Long): Boolean = nativeBeginTransaction(dbHandle)

    actual fun commitTransaction(dbHandle: Long): Boolean = nativeCommitTransaction(dbHandle)

    actual fun rollbackTransaction(dbHandle: Long) = nativeRollbackTransaction(dbHandle)

    // ========== Object Property Access ==========

    actual fun createObject(tableName: String, schemaJson: String): Long =
        nativeCreateObject(tableName, schemaJson)

    actual fun getIntProperty(objectHandle: Long, propertyName: String): Long =
        nativeGetIntProperty(objectHandle, propertyName)

    actual fun setIntProperty(objectHandle: Long, propertyName: String, value: Long) =
        nativeSetIntProperty(objectHandle, propertyName, value)

    actual fun getDoubleProperty(objectHandle: Long, propertyName: String): Double =
        nativeGetDoubleProperty(objectHandle, propertyName)

    actual fun setDoubleProperty(objectHandle: Long, propertyName: String, value: Double) =
        nativeSetDoubleProperty(objectHandle, propertyName, value)

    actual fun getStringProperty(objectHandle: Long, propertyName: String): String? =
        nativeGetStringProperty(objectHandle, propertyName)

    actual fun setStringProperty(objectHandle: Long, propertyName: String, value: String?) =
        nativeSetStringProperty(objectHandle, propertyName, value)

    actual fun getBoolProperty(objectHandle: Long, propertyName: String): Boolean =
        nativeGetBoolProperty(objectHandle, propertyName)

    actual fun setBoolProperty(objectHandle: Long, propertyName: String, value: Boolean) =
        nativeSetBoolProperty(objectHandle, propertyName, value)

    actual fun getObjectId(objectHandle: Long): Long = nativeGetObjectId(objectHandle)

    actual fun getObjectGlobalId(objectHandle: Long): String? = nativeGetObjectGlobalId(objectHandle)

    actual fun hasValue(objectHandle: Long, propertyName: String): Boolean =
        nativeHasValue(objectHandle, propertyName)

    actual fun setNull(objectHandle: Long, propertyName: String) =
        nativeSetNull(objectHandle, propertyName)

    // ========== Blob Property Access ==========

    actual fun getBlobSize(objectHandle: Long, propertyName: String): Int =
        nativeGetBlobSize(objectHandle, propertyName)

    actual fun getBlobData(objectHandle: Long, propertyName: String, buffer: ByteArray): Int =
        nativeGetBlobData(objectHandle, propertyName, buffer)

    actual fun setBlobProperty(objectHandle: Long, propertyName: String, data: ByteArray?) =
        nativeSetBlobProperty(objectHandle, propertyName, data)

    // ========== Link Property Access ==========

    actual fun getLinkedObject(objectHandle: Long, propertyName: String): Long =
        nativeGetLinkedObject(objectHandle, propertyName)

    actual fun setLinkedObject(objectHandle: Long, propertyName: String, linkedHandle: Long?) =
        nativeSetLinkedObject(objectHandle, propertyName, linkedHandle)

    actual fun getLinkListHandle(objectHandle: Long, propertyName: String): Long =
        nativeGetLinkListHandle(objectHandle, propertyName)

    // ========== Object Creation ==========

    actual fun createObjectWithSchema(tableName: String, schemaJson: String): Long =
        nativeCreateObjectWithSchema(tableName, schemaJson)

    // ========== Sync Operations ==========

    actual fun receiveSyncData(dbHandle: Long, data: ByteArray): String? =
        nativeReceiveSyncData(dbHandle, data)

    actual fun getEventsAfter(dbHandle: Long, globalId: String?): String? =
        nativeGetEventsAfter(dbHandle, globalId)

    actual fun getPendingEvents(dbHandle: Long): String? = nativeGetPendingEvents(dbHandle)

    actual fun markSynced(dbHandle: Long, globalIdsJson: String) =
        nativeMarkSynced(dbHandle, globalIdsJson)

    // ========== Results Operations ==========

    actual fun resultsCount(resultsHandle: Long): Int = nativeResultsCount(resultsHandle)

    actual fun resultsGet(resultsHandle: Long, index: Int): Long = nativeResultsGet(resultsHandle, index)

    actual fun resultsFree(resultsHandle: Long) = nativeResultsFree(resultsHandle)

    // ========== Link List Operations ==========

    actual fun linkListCount(listHandle: Long): Int = nativeLinkListCount(listHandle)

    actual fun linkListGet(listHandle: Long, index: Int): Long = nativeLinkListGet(listHandle, index)

    actual fun linkListAppend(listHandle: Long, objectHandle: Long) =
        nativeLinkListAppend(listHandle, objectHandle)

    actual fun linkListInsert(listHandle: Long, index: Int, objectHandle: Long) =
        nativeLinkListInsert(listHandle, index, objectHandle)

    actual fun linkListRemove(listHandle: Long, index: Int) = nativeLinkListRemove(listHandle, index)

    actual fun linkListClear(listHandle: Long) = nativeLinkListClear(listHandle)

    // ========== String Memory ==========

    actual fun freeString(ptr: Long) = nativeFreeString(ptr)

    // ========== JNI External Declarations ==========

    // Database Lifecycle
    private external fun nativeCreateDbInMemory(): Long
    private external fun nativeCreateDbAtPath(path: String): Long
    private external fun nativeCreateDbWithSchemas(path: String, schemasJson: String): Long
    private external fun nativeCreateDbWithSync(
        path: String,
        schemasJson: String,
        syncEndpoint: String,
        authToken: String
    ): Long
    private external fun nativeCreateDbWithSchemaArrays(
        path: String,
        tableNames: Array<String>,
        propertyCounts: IntArray,
        propNames: Array<String>,
        propTypes: IntArray,
        propKinds: IntArray,
        propNullable: BooleanArray,
        propTargetTables: Array<String?>,
        propLinkTables: Array<String?>,
        syncEndpoint: String?,
        authToken: String?
    ): Long
    private external fun nativeReleaseDb(dbHandle: Long)
    private external fun nativeGetLastError(): String?

    // Object Operations
    private external fun nativeAddObject(dbHandle: Long, objectHandle: Long): Long
    private external fun nativeFindObject(dbHandle: Long, tableName: String, id: Long): Long
    private external fun nativeFindObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long
    private external fun nativeRemoveObject(dbHandle: Long, objectHandle: Long): Boolean

    // Query Operations
    private external fun nativeQueryCount(dbHandle: Long, tableName: String, whereClause: String?): Int
    private external fun nativeQueryObjects(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): LongArray
    private external fun nativeDeleteWhere(dbHandle: Long, tableName: String, whereClause: String?): Int

    // Transaction Operations
    private external fun nativeBeginTransaction(dbHandle: Long): Boolean
    private external fun nativeCommitTransaction(dbHandle: Long): Boolean
    private external fun nativeRollbackTransaction(dbHandle: Long)

    // Object Property Access
    private external fun nativeCreateObject(tableName: String, schemaJson: String): Long
    private external fun nativeGetIntProperty(objectHandle: Long, propertyName: String): Long
    private external fun nativeSetIntProperty(objectHandle: Long, propertyName: String, value: Long)
    private external fun nativeGetDoubleProperty(objectHandle: Long, propertyName: String): Double
    private external fun nativeSetDoubleProperty(objectHandle: Long, propertyName: String, value: Double)
    private external fun nativeGetStringProperty(objectHandle: Long, propertyName: String): String?
    private external fun nativeSetStringProperty(objectHandle: Long, propertyName: String, value: String?)
    private external fun nativeGetBoolProperty(objectHandle: Long, propertyName: String): Boolean
    private external fun nativeSetBoolProperty(objectHandle: Long, propertyName: String, value: Boolean)
    private external fun nativeGetObjectId(objectHandle: Long): Long
    private external fun nativeGetObjectGlobalId(objectHandle: Long): String?
    private external fun nativeHasValue(objectHandle: Long, propertyName: String): Boolean
    private external fun nativeSetNull(objectHandle: Long, propertyName: String)

    // Blob Property Access
    private external fun nativeGetBlobSize(objectHandle: Long, propertyName: String): Int
    private external fun nativeGetBlobData(objectHandle: Long, propertyName: String, buffer: ByteArray): Int
    private external fun nativeSetBlobProperty(objectHandle: Long, propertyName: String, data: ByteArray?)

    // Link Property Access
    private external fun nativeGetLinkedObject(objectHandle: Long, propertyName: String): Long
    private external fun nativeSetLinkedObject(objectHandle: Long, propertyName: String, linkedHandle: Long?)
    private external fun nativeGetLinkListHandle(objectHandle: Long, propertyName: String): Long

    // Object Creation
    private external fun nativeCreateObjectWithSchema(tableName: String, schemaJson: String): Long

    // Sync Operations
    private external fun nativeReceiveSyncData(dbHandle: Long, data: ByteArray): String?
    private external fun nativeGetEventsAfter(dbHandle: Long, globalId: String?): String?
    private external fun nativeGetPendingEvents(dbHandle: Long): String?
    private external fun nativeMarkSynced(dbHandle: Long, globalIdsJson: String)

    // Results Operations
    private external fun nativeResultsCount(resultsHandle: Long): Int
    private external fun nativeResultsGet(resultsHandle: Long, index: Int): Long
    private external fun nativeResultsFree(resultsHandle: Long)

    // Link List Operations
    private external fun nativeLinkListCount(listHandle: Long): Int
    private external fun nativeLinkListGet(listHandle: Long, index: Int): Long
    private external fun nativeLinkListAppend(listHandle: Long, objectHandle: Long)
    private external fun nativeLinkListInsert(listHandle: Long, index: Int, objectHandle: Long)
    private external fun nativeLinkListRemove(listHandle: Long, index: Int)
    private external fun nativeLinkListClear(listHandle: Long)

    // String Memory
    private external fun nativeFreeString(ptr: Long)

    // ========== Observer Operations ==========

    /**
     * Register a table observer.
     * @param callback A Function3<String, Long, String?, Unit> that receives (operation, rowId, globalId)
     * @return Context handle (used for removal), or 0 on failure
     */
    fun observeTable(
        dbHandle: Long,
        tableName: String,
        callback: (operation: String, rowId: Long, globalId: String?) -> Unit
    ): Long = nativeObserveTable(dbHandle, tableName, callback)

    fun removeTableObserver(dbHandle: Long, tableName: String, contextHandle: Long) =
        nativeRemoveTableObserver(dbHandle, tableName, contextHandle)

    // Observer Operations
    private external fun nativeObserveTable(
        dbHandle: Long,
        tableName: String,
        callback: Any  // Function3<String, Long, String?, Unit>
    ): Long

    private external fun nativeRemoveTableObserver(
        dbHandle: Long,
        tableName: String,
        contextHandle: Long
    )

    // ========== Network Factory Operations ==========

    fun createNetworkFactory(factory: LatticeWebSocketFactory): Long =
        nativeCreateNetworkFactory(factory)

    fun setGlobalNetworkFactory(factoryHandle: Long) =
        nativeSetGlobalNetworkFactory(factoryHandle)

    fun triggerOnOpen(clientHandle: Long) = nativeTriggerOnOpen(clientHandle)

    fun triggerOnMessage(clientHandle: Long, type: Int, data: ByteArray) =
        nativeTriggerOnMessage(clientHandle, type, data)

    fun triggerOnError(clientHandle: Long, error: String) =
        nativeTriggerOnError(clientHandle, error)

    fun triggerOnClose(clientHandle: Long, code: Int, reason: String) =
        nativeTriggerOnClose(clientHandle, code, reason)

    // Network Factory Operations
    private external fun nativeCreateNetworkFactory(factory: Any): Long
    private external fun nativeSetGlobalNetworkFactory(factoryHandle: Long)
    private external fun nativeTriggerOnOpen(clientHandle: Long)
    private external fun nativeTriggerOnMessage(clientHandle: Long, type: Int, data: ByteArray)
    private external fun nativeTriggerOnError(clientHandle: Long, error: String)
    private external fun nativeTriggerOnClose(clientHandle: Long, code: Int, reason: String)
}
