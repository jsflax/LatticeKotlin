package com.lattice

/**
 * Internal bridge to native Lattice C library.
 * Platform-specific implementations use cinterop (Native) or JNI (Android/JVM).
 *
 * All handles are represented as Long values (pointers on native, JNI handles on JVM).
 */
internal expect object NativeBridge {
    // ========== Database Lifecycle ==========

    /** Create an in-memory database. Returns db handle or 0 on failure. */
    fun createDbInMemory(): Long

    /** Create/open database at path. Returns db handle or 0 on failure. */
    fun createDbAtPath(path: String): Long

    /** Create database with schemas. Returns db handle or 0 on failure. */
    fun createDbWithSchemas(path: String, schemasJson: String): Long

    /** Create database with schemas and sync configuration. Returns db handle or 0 on failure. */
    fun createDbWithSync(
        path: String,
        schemasJson: String,
        syncEndpoint: String,
        authToken: String
    ): Long

    /**
     * Create database with schema using flat arrays (avoids JSON parsing in JNI).
     * Schema data is passed as parallel arrays:
     * - tableNames: One entry per table
     * - propertyCounts: Number of properties for each table
     * - propNames, propTypes, etc: All properties flattened across all tables
     *
     * Types: 0=INTEGER, 1=REAL, 2=TEXT, 3=BLOB
     * Kinds: 0=PRIMITIVE, 1=LINK, 2=LINK_LIST
     *
     * For sync, pass non-null syncEndpoint and authToken.
     */
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
        syncEndpoint: String?,
        authToken: String?
    ): Long

    /** Close and release database. */
    /** Explicitly close DB connections and stop background services. Call before release. */
    fun closeDb(dbHandle: Long)

    fun releaseDb(dbHandle: Long)

    /** Get last error message. */
    fun getLastError(): String?

    // ========== Object Operations ==========

    /** Add object to database. Returns managed object handle or 0 on failure. */
    fun addObject(dbHandle: Long, objectHandle: Long): Long

    /** Find object by primary key. Returns object handle or 0 if not found. */
    fun findObject(dbHandle: Long, tableName: String, id: Long): Long

    /** Find object by global ID. Returns object handle or 0 if not found. */
    fun findObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long

    /** Remove object from database. Returns true on success. */
    fun removeObject(dbHandle: Long, objectHandle: Long): Boolean

    // ========== Query Operations ==========

    /** Count objects matching query. */
    fun queryCount(dbHandle: Long, tableName: String, whereClause: String?): Int

    /**
     * Query objects. Returns array of object handles.
     * Caller must free the results with freeQueryResults.
     */
    fun queryObjects(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): LongArray

    /** Delete objects matching where clause. Returns count of deleted objects. */
    fun deleteWhere(dbHandle: Long, tableName: String, whereClause: String?): Int

    // ========== Transaction Operations ==========

    /** Begin a transaction. Returns true on success. */
    fun beginTransaction(dbHandle: Long): Boolean

    /** Commit current transaction. Returns true on success. */
    fun commitTransaction(dbHandle: Long): Boolean

    /** Rollback current transaction. */
    fun rollbackTransaction(dbHandle: Long)

    // ========== Object Property Access ==========

    /** Create a new unmanaged object with the given schema. Returns object handle. */
    fun createObject(tableName: String, schemaJson: String): Long

    /** Get integer property from object. */
    fun getIntProperty(objectHandle: Long, propertyName: String): Long

    /** Set integer property on object. */
    fun setIntProperty(objectHandle: Long, propertyName: String, value: Long)

    /** Get double property from object. */
    fun getDoubleProperty(objectHandle: Long, propertyName: String): Double

    /** Set double property on object. */
    fun setDoubleProperty(objectHandle: Long, propertyName: String, value: Double)

    /** Get string property from object. */
    fun getStringProperty(objectHandle: Long, propertyName: String): String?

    /** Set string property on object. */
    fun setStringProperty(objectHandle: Long, propertyName: String, value: String?)

    /** Get boolean property from object. */
    fun getBoolProperty(objectHandle: Long, propertyName: String): Boolean

    /** Set boolean property on object. */
    fun setBoolProperty(objectHandle: Long, propertyName: String, value: Boolean)

    /** Get the row ID of a managed object. */
    fun getObjectId(objectHandle: Long): Long

    /** Get the global ID of a managed object. */
    fun getObjectGlobalId(objectHandle: Long): String?

    /** Check if property has a value (not NULL). */
    fun hasValue(objectHandle: Long, propertyName: String): Boolean

    /** Set property to NULL. */
    fun setNull(objectHandle: Long, propertyName: String)

    // ========== Blob Property Access ==========

    /** Get blob property size. Returns 0 if null or empty. */
    fun getBlobSize(objectHandle: Long, propertyName: String): Int

    /** Get blob property data. */
    fun getBlobData(objectHandle: Long, propertyName: String, buffer: ByteArray): Int

    /** Set blob property. */
    fun setBlobProperty(objectHandle: Long, propertyName: String, data: ByteArray?)

    // ========== Link Property Access ==========

    /** Get linked object handle. Returns 0 if no link. */
    fun getLinkedObject(objectHandle: Long, propertyName: String): Long

    /** Set linked object. */
    fun setLinkedObject(objectHandle: Long, propertyName: String, linkedHandle: Long?)

    /** Get link list handle. Returns 0 if no list. */
    fun getLinkListHandle(objectHandle: Long, propertyName: String): Long

    // ========== Object Creation ==========

    /** Create object with schema (JSON-encoded). Returns object handle. */
    fun createObjectWithSchema(tableName: String, schemaJson: String): Long

    // ========== Sync Operations ==========

    /** Receive sync data from server. Returns JSON array of applied globalIds. */
    fun receiveSyncData(dbHandle: Long, data: ByteArray): String?

    /** Get audit log entries after checkpoint. Returns JSON. */
    fun getEventsAfter(dbHandle: Long, globalId: String?): String?

    /** Get pending (unsynced) audit log entries. Returns JSON. */
    fun getPendingEvents(dbHandle: Long): String?

    /** Mark entries as synchronized. */
    fun markSynced(dbHandle: Long, globalIdsJson: String)

    /** Compact audit log — replace history with current state INSERTs. Returns entry count. */
    fun compactAuditLog(dbHandle: Long): Long

    /** Check if sync WebSocket is connected. */
    fun isSyncConnected(dbHandle: Long): Boolean

    // ========== Results Operations ==========

    /** Get count of query results. */
    fun resultsCount(resultsHandle: Long): Int

    /** Get object at index from results. Returns object handle. */
    fun resultsGet(resultsHandle: Long, index: Int): Long

    /** Free query results. */
    fun resultsFree(resultsHandle: Long)

    // ========== Link List Operations ==========

    /** Get count of items in link list. */
    fun linkListCount(listHandle: Long): Int

    /** Get object at index in link list. Returns object handle. */
    fun linkListGet(listHandle: Long, index: Int): Long

    /** Append object to link list. */
    fun linkListAppend(listHandle: Long, objectHandle: Long)

    /** Insert object at index in link list. */
    fun linkListInsert(listHandle: Long, index: Int, objectHandle: Long)

    /** Remove object at index from link list. */
    fun linkListRemove(listHandle: Long, index: Int)

    /** Clear all objects from link list. */
    fun linkListClear(listHandle: Long)

    // ========== String Memory ==========

    /** Free a string returned by native code. */
    fun freeString(ptr: Long)

    // ========== Query Extensions ==========

    /** Query with DISTINCT (GROUP BY) on a column. Returns results handle. */
    fun queryDistinct(
        dbHandle: Long,
        tableName: String,
        distinctBy: String?,
        whereClause: String?,
        orderBy: String?,
        limit: Long,
        offset: Long
    ): Long

    /** Count with DISTINCT/GROUP BY support. */
    fun countDistinct(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        groupBy: String?,
        distinctBy: String?
    ): Int

    /** Full-text search query. Returns results handle. */
    fun queryFts(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        matchExpression: String,
        orderBy: String?,
        limit: Long
    ): Long

    // ========== Database Attachment ==========

    /** Attach another database for cross-DB queries. */
    fun attachDb(dbHandle: Long, otherHandle: Long): Boolean

    // ========== Add with preserved global ID ==========

    /** Add an object with a specific globalId. Returns object handle. */
    fun addObjectWithGlobalId(dbHandle: Long, objectHandle: Long, globalId: String): Long
}
