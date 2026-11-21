package com.lattice

import java.io.File

internal actual object NativeBridge {

    init {
        loadNativeLibraries()
    }

    private fun loadNativeLibraries() {
        val vmName = System.getProperty("java.vm.name") ?: ""
        val isAndroidArt = vmName.contains("Dalvik", ignoreCase = true) ||
            vmName.contains("ART", ignoreCase = true)

        if (!isAndroidArt) {
            val hostDir = System.getProperty("lattice.host.native.dir")
                ?: System.getenv("LATTICE_HOST_NATIVE_DIR")
            if (!hostDir.isNullOrBlank()) {
                val dir = File(hostDir)
                val osName = System.getProperty("os.name")?.lowercase() ?: ""
                val ext = when {
                    osName.contains("mac") || osName.contains("darwin") -> "dylib"
                    osName.contains("win") -> "dll"
                    else -> "so"
                }
                val prefix = if (ext == "dll") "" else "lib"
                val capiFile = File(dir, "${prefix}LatticeCAPI.$ext")
                val jniFile = File(dir, "${prefix}lattice_jni.$ext")
                if (capiFile.exists() && jniFile.exists()) {
                    System.load(capiFile.absolutePath)
                    System.load(jniFile.absolutePath)
                    return
                }
            }
        }

        try {
            System.loadLibrary("LatticeCAPI")
        } catch (_: UnsatisfiedLinkError) {
            // DT_NEEDED on lattice_jni may still resolve it on standard loaders
        }
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
        propFlags: IntArray,
        syncEndpoint: String?,
        authToken: String?
    ): Long = nativeCreateDbWithSchemaArrays(
        path, tableNames, propertyCounts, propNames, propTypes, propKinds,
        propNullable, propTargetTables, propLinkTables, propFlags, syncEndpoint, authToken
    )

    actual fun openDbV2(
        path: String,
        tableNames: Array<String>,
        propertyCounts: IntArray,
        propNames: Array<String>,
        propColumnNames: Array<String?>,
        propTypes: IntArray,
        propKinds: IntArray,
        propNullable: BooleanArray,
        propTargetTables: Array<String?>,
        propLinkTables: Array<String?>,
        propFlags: IntArray,
        propUnionSpecs: Array<String?>,
        constraintTableNames: Array<String>,
        constraintColumnsJoined: Array<String>,
        constraintAllowsUpsert: BooleanArray,
        schedulerHandle: Long,
        syncEndpoint: String?,
        authToken: String?,
        targetSchemaVersion: Int,
        busyTimeoutMs: Int,
        auditRetentionSeconds: Long,
        syncIsObserver: Int,
        uploadsEnabled: Int,
        syncAuthMode: Int,
        websocketSyncIdentity: String?,
        pinnedServerCertSha256: String?,
        createIfMissing: Int,
        readOnly: Int,
        syncFilterJson: String?,
        chunkSize: Long,
        maxReconnectAttempts: Int,
        uploadCoalesceMs: Int,
        migrationTables: Array<String>,
        migrationTransform: ((tableName: String, oldRowJson: String) -> String?)?
    ): Long = nativeOpenDbV2(
        path, tableNames, propertyCounts, propNames, propColumnNames,
        propTypes, propKinds, propNullable, propTargetTables, propLinkTables,
        propFlags, propUnionSpecs, constraintTableNames, constraintColumnsJoined,
        constraintAllowsUpsert, schedulerHandle, syncEndpoint, authToken,
        targetSchemaVersion, busyTimeoutMs, auditRetentionSeconds, syncIsObserver,
        uploadsEnabled, syncAuthMode, websocketSyncIdentity, pinnedServerCertSha256,
        createIfMissing, readOnly, syncFilterJson, chunkSize, maxReconnectAttempts,
        uploadCoalesceMs, migrationTables, migrationTransform
    )

    actual fun closeDb(dbHandle: Long) = nativeCloseDb(dbHandle)
    actual fun releaseDb(dbHandle: Long) = nativeReleaseDb(dbHandle)
    actual fun getLastError(): String? = nativeGetLastError()

    // ========== Object Operations ==========
    actual fun addObject(dbHandle: Long, objectHandle: Long): Long = nativeAddObject(dbHandle, objectHandle)
    actual fun addObjectWithGlobalId(dbHandle: Long, objectHandle: Long, globalId: String): Long =
        nativeAddObjectWithGlobalId(dbHandle, objectHandle, globalId)
    actual fun releaseObject(objectHandle: Long) = nativeReleaseObject(objectHandle)
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
    actual fun createObjectWithSchema(tableName: String, schemaJson: String): Long =
        nativeCreateObjectWithSchema(tableName, schemaJson)
    actual fun createDbObject(dbHandle: Long, tableName: String): Long =
        nativeCreateDbObject(dbHandle, tableName)
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
    actual fun getObjectTableName(objectHandle: Long): String? = nativeGetObjectTableName(objectHandle)
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

    // ========== Union Property Access ==========
    actual fun createUnionValue(caseName: String): Long = nativeCreateUnionValue(caseName)
    actual fun releaseUnionValue(uvHandle: Long) = nativeReleaseUnionValue(uvHandle)
    actual fun getUnionCase(uvHandle: Long): String? = nativeGetUnionCase(uvHandle)
    actual fun hasUnionField(uvHandle: Long, key: String): Boolean = nativeHasUnionField(uvHandle, key)
    actual fun getUnionInt(uvHandle: Long, key: String): Long = nativeGetUnionInt(uvHandle, key)
    actual fun getUnionDouble(uvHandle: Long, key: String): Double = nativeGetUnionDouble(uvHandle, key)
    actual fun getUnionString(uvHandle: Long, key: String): String? = nativeGetUnionString(uvHandle, key)
    actual fun getUnionBlob(uvHandle: Long, key: String): ByteArray = nativeGetUnionBlob(uvHandle, key)
    actual fun getUnionLink(uvHandle: Long, key: String): Long = nativeGetUnionLink(uvHandle, key)
    actual fun setUnionInt(uvHandle: Long, key: String, value: Long) = nativeSetUnionInt(uvHandle, key, value)
    actual fun setUnionDouble(uvHandle: Long, key: String, value: Double) = nativeSetUnionDouble(uvHandle, key, value)
    actual fun setUnionString(uvHandle: Long, key: String, value: String) = nativeSetUnionString(uvHandle, key, value)
    actual fun setUnionBlob(uvHandle: Long, key: String, value: ByteArray) = nativeSetUnionBlob(uvHandle, key, value)
    actual fun setUnionLink(uvHandle: Long, key: String, objHandle: Long) = nativeSetUnionLink(uvHandle, key, objHandle)
    actual fun getObjectUnion(objectHandle: Long, propertyName: String): Long =
        nativeGetObjectUnion(objectHandle, propertyName)
    actual fun setObjectUnion(objectHandle: Long, propertyName: String, uvHandle: Long): Boolean =
        nativeSetObjectUnion(objectHandle, propertyName, uvHandle)
    actual fun clearObjectUnion(objectHandle: Long, propertyName: String): Boolean =
        nativeClearObjectUnion(objectHandle, propertyName)

    // ========== Row Cache, Atomic Increment, Detached JSON & Statement Counters ==========
    actual fun enableRowCache(objectHandle: Long): Boolean = nativeEnableRowCache(objectHandle)
    actual fun disableRowCache(objectHandle: Long): Boolean = nativeDisableRowCache(objectHandle)
    actual fun refreshRowCache(objectHandle: Long): Boolean = nativeRefreshRowCache(objectHandle)
    actual fun isRowCacheEnabled(objectHandle: Long): Boolean = nativeIsRowCacheEnabled(objectHandle)
    actual fun incrementInt(objectHandle: Long, propertyName: String, delta: Long): Boolean =
        nativeIncrementInt(objectHandle, propertyName, delta)
    actual fun objectToJson(objectHandle: Long, maxDepth: Int): String? =
        nativeObjectToJson(objectHandle, maxDepth)
    actual fun totalStatementCount(): Long = nativeTotalStatementCount()
    actual fun threadStatementCount(): Long = nativeThreadStatementCount()

    // ========== Sync, Maintenance & Observers ==========
    actual fun receiveSyncData(dbHandle: Long, data: ByteArray): String? = nativeReceiveSyncData(dbHandle, data)
    actual fun getEventsAfter(dbHandle: Long, globalId: String?): String? = nativeGetEventsAfter(dbHandle, globalId)
    actual fun getPendingEvents(dbHandle: Long): String? = nativeGetPendingEvents(dbHandle)
    actual fun markSynced(dbHandle: Long, globalIdsJson: String) = nativeMarkSynced(dbHandle, globalIdsJson)
    actual fun compactAuditLog(dbHandle: Long): Long = nativeCompactAuditLog(dbHandle)
    actual fun pruneAuditLog(dbHandle: Long, retentionSeconds: Long): Long =
        nativePruneAuditLog(dbHandle, retentionSeconds)
    actual fun recordAuditWatermark(dbHandle: Long): Boolean = nativeRecordAuditWatermark(dbHandle)
    actual fun reclaimSpace(dbHandle: Long): Long = nativeReclaimSpace(dbHandle)
    actual fun checkpointDb(dbHandle: Long, truncate: Boolean): Boolean = nativeCheckpointDb(dbHandle, truncate)
    actual fun setSyncFilter(dbHandle: Long, filterJson: String): Boolean = nativeSetSyncFilter(dbHandle, filterJson)
    actual fun clearSyncFilter(dbHandle: Long): Boolean = nativeClearSyncFilter(dbHandle)
    actual fun isSyncConnected(dbHandle: Long): Boolean = nativeIsSyncConnected(dbHandle)
    actual fun getSyncProgress(dbHandle: Long): LongArray = nativeGetSyncProgress(dbHandle)
    actual fun observeSyncState(dbHandle: Long, callback: (Boolean) -> Unit): Long =
        nativeObserveSyncState(dbHandle, callback)
    actual fun observeSyncProgress(dbHandle: Long, callback: (Long, Long, Long, Long) -> Unit): Long =
        nativeObserveSyncProgress(dbHandle, callback)
    actual fun observeSyncError(dbHandle: Long, callback: (String) -> Unit): Long =
        nativeObserveSyncError(dbHandle, callback)
    actual fun removeSyncObserver(dbHandle: Long, token: Long) = nativeRemoveSyncObserver(dbHandle, token)

    // ========== Results Operations ==========
    actual fun resultsCount(resultsHandle: Long): Int = nativeResultsCount(resultsHandle)
    actual fun resultsGet(resultsHandle: Long, index: Int): Long = nativeResultsGet(resultsHandle, index)
    actual fun resultsFree(resultsHandle: Long) = nativeResultsFree(resultsHandle)

    // ========== Link List Operations ==========
    actual fun linkListCount(listHandle: Long): Int = nativeLinkListCount(listHandle)
    actual fun linkListGet(listHandle: Long, index: Int): Long = nativeLinkListGet(listHandle, index)
    actual fun linkListAppend(listHandle: Long, objectHandle: Long) = nativeLinkListAppend(listHandle, objectHandle)
    actual fun linkListInsert(listHandle: Long, index: Int, objectHandle: Long) =
        nativeLinkListInsert(listHandle, index, objectHandle)
    actual fun linkListRemove(listHandle: Long, index: Int) = nativeLinkListRemove(listHandle, index)
    actual fun linkListClear(listHandle: Long) = nativeLinkListClear(listHandle)
    actual fun linkListRelease(listHandle: Long) = nativeLinkListRelease(listHandle)

    // ========== String Memory ==========
    actual fun freeString(ptr: Long) = nativeFreeString(ptr)

    // ========== Query Extensions ==========
    actual fun queryDistinct(
        dbHandle: Long,
        tableName: String,
        distinctBy: String?,
        whereClause: String?,
        orderBy: String?,
        limit: Long,
        offset: Long
    ): Long = nativeQueryDistinct(dbHandle, tableName, distinctBy, whereClause, orderBy, limit, offset)

    actual fun countDistinct(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        groupBy: String?,
        distinctBy: String?
    ): Int = nativeCountDistinct(dbHandle, tableName, whereClause, groupBy, distinctBy)

    actual fun queryFts(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        matchExpression: String,
        orderBy: String?,
        limit: Long
    ): Long = nativeQueryFts(dbHandle, tableName, columnName, matchExpression, orderBy, limit)

    actual fun queryNearest(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        queryVectorBytes: ByteArray,
        k: Int,
        metricOrdinal: Int,
        whereClause: String?,
        outDistances: DoubleArray
    ): Array<String> = nativeQueryNearest(
        dbHandle, tableName, columnName, queryVectorBytes, k, metricOrdinal, whereClause, outDistances
    )

    actual fun queryWithinBounds(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        whereClause: String?,
        limit: Long
    ): LongArray = nativeQueryWithinBounds(
        dbHandle, tableName, columnName, minLat, maxLat, minLon, maxLon, whereClause, limit
    )

    // ========== Database Attachment ==========
    actual fun attachDb(dbHandle: Long, otherHandle: Long): Boolean = nativeAttachDb(dbHandle, otherHandle)
    actual fun detachDb(dbHandle: Long, otherHandle: Long): Boolean = nativeDetachDb(dbHandle, otherHandle)
    actual fun lastAttachError(dbHandle: Long): String? = nativeLastAttachError(dbHandle)

    // ========== Android-Only Observer & Network Bridge Entrypoints ==========
    fun observeTable(
        dbHandle: Long,
        tableName: String,
        callback: (operation: String, rowId: Long, globalId: String?) -> Unit
    ): Long = nativeObserveTable(dbHandle, tableName, callback)

    fun removeTableObserver(dbHandle: Long, tableName: String, contextHandle: Long) =
        nativeRemoveTableObserver(dbHandle, tableName, contextHandle)

    fun createNetworkFactory(factory: LatticeWebSocketFactory): Long =
        nativeCreateNetworkFactory(factory)

    fun setGlobalNetworkFactory(factoryHandle: Long) =
        nativeSetGlobalNetworkFactory(factoryHandle)

    fun triggerOnOpen(clientHandle: Long) = nativeTriggerOnOpen(clientHandle)
    fun triggerOnMessage(clientHandle: Long, type: Int, data: ByteArray) =
        nativeTriggerOnMessage(clientHandle, type, data)
    fun triggerOnError(clientHandle: Long, error: String) = nativeTriggerOnError(clientHandle, error)
    fun triggerOnClose(clientHandle: Long, code: Int, reason: String) =
        nativeTriggerOnClose(clientHandle, code, reason)

    // ========== JNI External Declarations ==========
    private external fun nativeCreateDbInMemory(): Long
    private external fun nativeCreateDbAtPath(path: String): Long
    private external fun nativeCreateDbWithSchemas(path: String, schemasJson: String): Long
    private external fun nativeCreateDbWithSync(path: String, schemasJson: String, syncEndpoint: String, authToken: String): Long
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
        propFlags: IntArray,
        syncEndpoint: String?,
        authToken: String?
    ): Long
    private external fun nativeOpenDbV2(
        path: String,
        tableNames: Array<String>,
        propertyCounts: IntArray,
        propNames: Array<String>,
        propColumnNames: Array<String?>,
        propTypes: IntArray,
        propKinds: IntArray,
        propNullable: BooleanArray,
        propTargetTables: Array<String?>,
        propLinkTables: Array<String?>,
        propFlags: IntArray,
        propUnionSpecs: Array<String?>,
        constraintTableNames: Array<String>,
        constraintColumnsJoined: Array<String>,
        constraintAllowsUpsert: BooleanArray,
        schedulerHandle: Long,
        syncEndpoint: String?,
        authToken: String?,
        targetSchemaVersion: Int,
        busyTimeoutMs: Int,
        auditRetentionSeconds: Long,
        syncIsObserver: Int,
        uploadsEnabled: Int,
        syncAuthMode: Int,
        websocketSyncIdentity: String?,
        pinnedServerCertSha256: String?,
        createIfMissing: Int,
        readOnly: Int,
        syncFilterJson: String?,
        chunkSize: Long,
        maxReconnectAttempts: Int,
        uploadCoalesceMs: Int,
        migrationTables: Array<String>,
        migrationTransform: Any?
    ): Long
    private external fun nativeCloseDb(dbHandle: Long)
    private external fun nativeReleaseDb(dbHandle: Long)
    private external fun nativeGetLastError(): String?

    private external fun nativeAddObject(dbHandle: Long, objectHandle: Long): Long
    private external fun nativeAddObjectWithGlobalId(dbHandle: Long, objectHandle: Long, globalId: String): Long
    private external fun nativeReleaseObject(objectHandle: Long)
    private external fun nativeFindObject(dbHandle: Long, tableName: String, id: Long): Long
    private external fun nativeFindObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long
    private external fun nativeRemoveObject(dbHandle: Long, objectHandle: Long): Boolean

    private external fun nativeQueryCount(dbHandle: Long, tableName: String, whereClause: String?): Int
    private external fun nativeQueryObjects(
        dbHandle: Long, tableName: String, whereClause: String?, orderBy: String?, limit: Int, offset: Long
    ): LongArray
    private external fun nativeDeleteWhere(dbHandle: Long, tableName: String, whereClause: String?): Int

    private external fun nativeBeginTransaction(dbHandle: Long): Boolean
    private external fun nativeCommitTransaction(dbHandle: Long): Boolean
    private external fun nativeRollbackTransaction(dbHandle: Long)

    private external fun nativeCreateObject(tableName: String, schemaJson: String): Long
    private external fun nativeCreateObjectWithSchema(tableName: String, schemaJson: String): Long
    private external fun nativeCreateDbObject(dbHandle: Long, tableName: String): Long
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
    private external fun nativeGetObjectTableName(objectHandle: Long): String?
    private external fun nativeHasValue(objectHandle: Long, propertyName: String): Boolean
    private external fun nativeSetNull(objectHandle: Long, propertyName: String)

    private external fun nativeGetBlobSize(objectHandle: Long, propertyName: String): Int
    private external fun nativeGetBlobData(objectHandle: Long, propertyName: String, buffer: ByteArray): Int
    private external fun nativeSetBlobProperty(objectHandle: Long, propertyName: String, data: ByteArray?)

    private external fun nativeGetLinkedObject(objectHandle: Long, propertyName: String): Long
    private external fun nativeSetLinkedObject(objectHandle: Long, propertyName: String, linkedHandle: Long?)
    private external fun nativeGetLinkListHandle(objectHandle: Long, propertyName: String): Long

    private external fun nativeCreateUnionValue(caseName: String): Long
    private external fun nativeReleaseUnionValue(uvHandle: Long)
    private external fun nativeGetUnionCase(uvHandle: Long): String?
    private external fun nativeHasUnionField(uvHandle: Long, key: String): Boolean
    private external fun nativeGetUnionInt(uvHandle: Long, key: String): Long
    private external fun nativeGetUnionDouble(uvHandle: Long, key: String): Double
    private external fun nativeGetUnionString(uvHandle: Long, key: String): String?
    private external fun nativeGetUnionBlob(uvHandle: Long, key: String): ByteArray
    private external fun nativeGetUnionLink(uvHandle: Long, key: String): Long
    private external fun nativeSetUnionInt(uvHandle: Long, key: String, value: Long)
    private external fun nativeSetUnionDouble(uvHandle: Long, key: String, value: Double)
    private external fun nativeSetUnionString(uvHandle: Long, key: String, value: String)
    private external fun nativeSetUnionBlob(uvHandle: Long, key: String, value: ByteArray)
    private external fun nativeSetUnionLink(uvHandle: Long, key: String, objHandle: Long)
    private external fun nativeGetObjectUnion(objectHandle: Long, propertyName: String): Long
    private external fun nativeSetObjectUnion(objectHandle: Long, propertyName: String, uvHandle: Long): Boolean
    private external fun nativeClearObjectUnion(objectHandle: Long, propertyName: String): Boolean

    private external fun nativeEnableRowCache(objectHandle: Long): Boolean
    private external fun nativeDisableRowCache(objectHandle: Long): Boolean
    private external fun nativeRefreshRowCache(objectHandle: Long): Boolean
    private external fun nativeIsRowCacheEnabled(objectHandle: Long): Boolean
    private external fun nativeIncrementInt(objectHandle: Long, propertyName: String, delta: Long): Boolean
    private external fun nativeObjectToJson(objectHandle: Long, maxDepth: Int): String?
    private external fun nativeTotalStatementCount(): Long
    private external fun nativeThreadStatementCount(): Long

    private external fun nativeReceiveSyncData(dbHandle: Long, data: ByteArray): String?
    private external fun nativeGetEventsAfter(dbHandle: Long, globalId: String?): String?
    private external fun nativeGetPendingEvents(dbHandle: Long): String?
    private external fun nativeMarkSynced(dbHandle: Long, globalIdsJson: String)
    private external fun nativeCompactAuditLog(dbHandle: Long): Long
    private external fun nativePruneAuditLog(dbHandle: Long, retentionSeconds: Long): Long
    private external fun nativeRecordAuditWatermark(dbHandle: Long): Boolean
    private external fun nativeReclaimSpace(dbHandle: Long): Long
    private external fun nativeCheckpointDb(dbHandle: Long, truncate: Boolean): Boolean
    private external fun nativeSetSyncFilter(dbHandle: Long, filterJson: String): Boolean
    private external fun nativeClearSyncFilter(dbHandle: Long): Boolean
    private external fun nativeIsSyncConnected(dbHandle: Long): Boolean
    private external fun nativeGetSyncProgress(dbHandle: Long): LongArray
    private external fun nativeObserveSyncState(dbHandle: Long, callback: Any): Long
    private external fun nativeObserveSyncProgress(dbHandle: Long, callback: Any): Long
    private external fun nativeObserveSyncError(dbHandle: Long, callback: Any): Long
    private external fun nativeRemoveSyncObserver(dbHandle: Long, token: Long)

    private external fun nativeResultsCount(resultsHandle: Long): Int
    private external fun nativeResultsGet(resultsHandle: Long, index: Int): Long
    private external fun nativeResultsFree(resultsHandle: Long)

    private external fun nativeLinkListCount(listHandle: Long): Int
    private external fun nativeLinkListGet(listHandle: Long, index: Int): Long
    private external fun nativeLinkListAppend(listHandle: Long, objectHandle: Long)
    private external fun nativeLinkListInsert(listHandle: Long, index: Int, objectHandle: Long)
    private external fun nativeLinkListRemove(listHandle: Long, index: Int)
    private external fun nativeLinkListClear(listHandle: Long)
    private external fun nativeLinkListRelease(listHandle: Long)

    private external fun nativeFreeString(ptr: Long)

    private external fun nativeQueryDistinct(
        dbHandle: Long, tableName: String, distinctBy: String?, whereClause: String?,
        orderBy: String?, limit: Long, offset: Long
    ): Long
    private external fun nativeCountDistinct(
        dbHandle: Long, tableName: String, whereClause: String?, groupBy: String?, distinctBy: String?
    ): Int
    private external fun nativeQueryFts(
        dbHandle: Long, tableName: String, columnName: String, matchExpression: String,
        orderBy: String?, limit: Long
    ): Long
    private external fun nativeQueryNearest(
        dbHandle: Long, tableName: String, columnName: String, queryVectorBytes: ByteArray,
        k: Int, metricOrdinal: Int, whereClause: String?, outDistances: DoubleArray
    ): Array<String>
    private external fun nativeQueryWithinBounds(
        dbHandle: Long, tableName: String, columnName: String,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        whereClause: String?, limit: Long
    ): LongArray

    private external fun nativeAttachDb(dbHandle: Long, otherHandle: Long): Boolean
    private external fun nativeDetachDb(dbHandle: Long, otherHandle: Long): Boolean
    private external fun nativeLastAttachError(dbHandle: Long): String?

    private external fun nativeObserveTable(dbHandle: Long, tableName: String, callback: Any): Long
    private external fun nativeRemoveTableObserver(dbHandle: Long, tableName: String, contextHandle: Long)
    private external fun nativeCreateNetworkFactory(factory: Any): Long
    private external fun nativeSetGlobalNetworkFactory(factoryHandle: Long)
    private external fun nativeTriggerOnOpen(clientHandle: Long)
    private external fun nativeTriggerOnMessage(clientHandle: Long, type: Int, data: ByteArray)
    private external fun nativeTriggerOnError(clientHandle: Long, error: String)
    private external fun nativeTriggerOnClose(clientHandle: Long, code: Int, reason: String)
}
