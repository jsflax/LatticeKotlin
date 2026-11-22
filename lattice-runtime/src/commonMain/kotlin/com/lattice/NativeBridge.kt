package com.lattice

/**
 * Internal bridge to native Lattice C library (`libLatticeCAPI`).
 * Platform-specific implementations use cinterop (Native) or JNI (Android/JVM).
 */
internal expect object NativeBridge {
    // ========== Database Lifecycle ==========
    fun createDbInMemory(): Long
    fun createDbAtPath(path: String): Long
    fun createDbWithSchemas(path: String, schemasJson: String): Long
    fun createDbWithSync(
        path: String,
        schemasJson: String,
        syncEndpoint: String,
        authToken: String
    ): Long

    fun createDbWithSchemaArrays(
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

    /**
     * Full Schema v2 + Sync Options v2 + Migration open (`lattice_db_open_v2`),
     * preserving `sizeof(lattice_property_t)` while passing `@NoHistory`, `@Union`,
     * `@Property(name)`, compound `@Unique`, `targetSchemaVersion`, `busyTimeoutMs`,
     * `uploadsEnabled`, `auditRetentionSeconds`, `pinnedServerCertificateSha256`, etc.
     */
    fun openDbV2(
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
        backgroundSyncManaged: Int,
        syncAutostart: Int,
        migrationTables: Array<String>,
        migrationTransform: ((tableName: String, oldRowJson: String) -> String?)?
    ): Long

    fun closeDb(dbHandle: Long)
    fun releaseDb(dbHandle: Long)
    fun getLastError(): String?

    // ========== Object Operations ==========
    fun addObject(dbHandle: Long, objectHandle: Long): Long
    fun addObjectWithGlobalId(dbHandle: Long, objectHandle: Long, globalId: String): Long
    fun releaseObject(objectHandle: Long)
    fun findObject(dbHandle: Long, tableName: String, id: Long): Long
    fun findObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long
    fun removeObject(dbHandle: Long, objectHandle: Long): Boolean

    // ========== Query Operations ==========
    fun queryCount(dbHandle: Long, tableName: String, whereClause: String?): Int
    fun queryObjects(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): LongArray
    fun deleteWhere(dbHandle: Long, tableName: String, whereClause: String?): Int

    // ========== Transaction Operations ==========
    fun beginTransaction(dbHandle: Long): Boolean
    fun commitTransaction(dbHandle: Long): Boolean
    fun rollbackTransaction(dbHandle: Long)

    // ========== Object Property Access ==========
    fun createObject(tableName: String, schemaJson: String): Long
    fun createObjectWithSchema(tableName: String, schemaJson: String): Long
    fun createDbObject(dbHandle: Long, tableName: String): Long
    fun getIntProperty(objectHandle: Long, propertyName: String): Long
    fun setIntProperty(objectHandle: Long, propertyName: String, value: Long)
    fun getDoubleProperty(objectHandle: Long, propertyName: String): Double
    fun setDoubleProperty(objectHandle: Long, propertyName: String, value: Double)
    fun getStringProperty(objectHandle: Long, propertyName: String): String?
    fun setStringProperty(objectHandle: Long, propertyName: String, value: String?)
    fun getBoolProperty(objectHandle: Long, propertyName: String): Boolean
    fun setBoolProperty(objectHandle: Long, propertyName: String, value: Boolean)
    fun getObjectId(objectHandle: Long): Long
    fun getObjectGlobalId(objectHandle: Long): String?
    fun getObjectTableName(objectHandle: Long): String?
    fun hasValue(objectHandle: Long, propertyName: String): Boolean
    fun setNull(objectHandle: Long, propertyName: String)

    // ========== Blob Property Access ==========
    fun getBlobSize(objectHandle: Long, propertyName: String): Int
    fun getBlobData(objectHandle: Long, propertyName: String, buffer: ByteArray): Int
    fun setBlobProperty(objectHandle: Long, propertyName: String, data: ByteArray?)

    // ========== Link Property Access ==========
    fun getLinkedObject(objectHandle: Long, propertyName: String): Long
    fun setLinkedObject(objectHandle: Long, propertyName: String, linkedHandle: Long?)
    fun getLinkListHandle(objectHandle: Long, propertyName: String): Long

    // ========== Union Property Access (`lattice_union_value_t`) ==========
    fun createUnionValue(caseName: String): Long
    fun releaseUnionValue(uvHandle: Long)
    fun getUnionCase(uvHandle: Long): String?
    fun hasUnionField(uvHandle: Long, key: String): Boolean
    fun getUnionInt(uvHandle: Long, key: String): Long
    fun getUnionDouble(uvHandle: Long, key: String): Double
    fun getUnionString(uvHandle: Long, key: String): String?
    fun getUnionBlob(uvHandle: Long, key: String): ByteArray
    fun getUnionLink(uvHandle: Long, key: String): Long
    fun setUnionInt(uvHandle: Long, key: String, value: Long)
    fun setUnionDouble(uvHandle: Long, key: String, value: Double)
    fun setUnionString(uvHandle: Long, key: String, value: String)
    fun setUnionBlob(uvHandle: Long, key: String, value: ByteArray)
    fun setUnionLink(uvHandle: Long, key: String, objHandle: Long)
    fun getObjectUnion(objectHandle: Long, propertyName: String): Long
    fun setObjectUnion(objectHandle: Long, propertyName: String, uvHandle: Long): Boolean
    fun clearObjectUnion(objectHandle: Long, propertyName: String): Boolean

    // ========== Row Cache, Atomic Increment, Detached JSON & Statement Counters ==========
    fun enableRowCache(objectHandle: Long): Boolean
    fun disableRowCache(objectHandle: Long): Boolean
    fun refreshRowCache(objectHandle: Long): Boolean
    fun isRowCacheEnabled(objectHandle: Long): Boolean
    fun incrementInt(objectHandle: Long, propertyName: String, delta: Long): Boolean
    fun objectToJson(objectHandle: Long, maxDepth: Int): String?
    fun totalStatementCount(): Long
    fun threadStatementCount(): Long

    // ========== Sync, Maintenance & Observers ==========
    fun receiveSyncData(dbHandle: Long, data: ByteArray): String?
    fun getEventsAfter(dbHandle: Long, globalId: String?): String?
    fun getPendingEvents(dbHandle: Long): String?
    fun markSynced(dbHandle: Long, globalIdsJson: String)
    fun compactAuditLog(dbHandle: Long): Long
    fun pruneAuditLog(dbHandle: Long, retentionSeconds: Long): Long
    fun recordAuditWatermark(dbHandle: Long): Boolean
    fun reclaimSpace(dbHandle: Long): Long
    fun checkpointDb(dbHandle: Long, truncate: Boolean): Boolean
    fun setSyncFilter(dbHandle: Long, filterJson: String): Boolean
    fun clearSyncFilter(dbHandle: Long): Boolean
    fun isSyncConnected(dbHandle: Long): Boolean
    fun getSyncProgress(dbHandle: Long): LongArray // [pendingUpload, totalUpload, acked, received]
    fun observeSyncState(dbHandle: Long, callback: (Boolean) -> Unit): Long
    fun observeSyncProgress(dbHandle: Long, callback: (Long, Long, Long, Long) -> Unit): Long
    fun observeSyncError(dbHandle: Long, callback: (String) -> Unit): Long
    fun removeSyncObserver(dbHandle: Long, token: Long)

    // ========== Managed Background Sync & Finite syncOnce (3.1.3) ==========
    fun createSyncCancellation(): Long
    fun cancelSyncCancellation(cancelHandle: Long)
    fun isSyncCancellationCancelled(cancelHandle: Long): Boolean
    fun releaseSyncCancellation(cancelHandle: Long)
    fun isBackgroundSyncManaged(dbHandle: Long): Boolean
    fun currentBackgroundSyncGeneration(dbHandle: Long): Long
    /** Returns 0..3 for [SyncOnceResult] ordinals, or negative [lattice_status_t] on error. */
    fun syncOnce(dbHandle: Long, maximumDurationSeconds: Double, cancelHandle: Long): Int
    fun suspendBackgroundSync(dbHandle: Long): Boolean
    fun resumeBackgroundSync(dbHandle: Long): Boolean
    fun cancelBackgroundSync(dbHandle: Long): Boolean

    // ========== Results Operations ==========
    fun resultsCount(resultsHandle: Long): Int
    fun resultsGet(resultsHandle: Long, index: Int): Long
    fun resultsFree(resultsHandle: Long)

    // ========== Link List Operations ==========
    fun linkListCount(listHandle: Long): Int
    fun linkListGet(listHandle: Long, index: Int): Long
    fun linkListAppend(listHandle: Long, objectHandle: Long)
    fun linkListInsert(listHandle: Long, index: Int, objectHandle: Long)
    fun linkListRemove(listHandle: Long, index: Int)
    fun linkListClear(listHandle: Long)
    fun linkListRelease(listHandle: Long)

    // ========== String Memory ==========
    fun freeString(ptr: Long)

    // ========== Query Extensions ==========
    fun queryDistinct(
        dbHandle: Long,
        tableName: String,
        distinctBy: String?,
        whereClause: String?,
        orderBy: String?,
        limit: Long,
        offset: Long
    ): Long

    fun countDistinct(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        groupBy: String?,
        distinctBy: String?
    ): Int

    fun queryFts(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        matchExpression: String,
        orderBy: String?,
        limit: Long
    ): Long

    /** Returns parallel results: pairs of (globalId, distance) encoded via [outDistances]. */
    fun queryNearest(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        queryVectorBytes: ByteArray,
        k: Int,
        metricOrdinal: Int,
        whereClause: String?,
        outDistances: DoubleArray
    ): Array<String>

    fun queryWithinBounds(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        whereClause: String?,
        limit: Long
    ): LongArray

    // ========== Database Attachment ==========
    fun attachDb(dbHandle: Long, otherHandle: Long): Boolean
    fun detachDb(dbHandle: Long, otherHandle: Long): Boolean
    fun lastAttachError(dbHandle: Long): String?
}
