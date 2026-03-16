package com.lattice

import com.lattice.capi.*
import kotlinx.cinterop.*

/**
 * Native implementation of NativeBridge using cinterop to call the Lattice C library.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object NativeBridge {

    // Helper to convert Long to native pointer
    @Suppress("NOTHING_TO_INLINE")
    private inline fun Long.toNativePtr(): NativePtr = NativePtr.NULL + this

    private fun Long.toDbPtr(): CPointer<lattice_db_t>? {
        if (this == 0L) return null
        return interpretCPointer(this.toNativePtr())
    }

    private fun Long.toObjectPtr(): CPointer<lattice_object_t>? {
        if (this == 0L) return null
        return interpretCPointer(this.toNativePtr())
    }

    private fun Long.toResultsPtr(): CPointer<lattice_results_t>? {
        if (this == 0L) return null
        return interpretCPointer(this.toNativePtr())
    }

    private fun Long.toLinkListPtr(): CPointer<lattice_link_list_t>? {
        if (this == 0L) return null
        return interpretCPointer(this.toNativePtr())
    }

    // ========== Database Lifecycle ==========

    actual fun createDbInMemory(): Long {
        val ptr = lattice_db_create_in_memory() ?: return 0L
        return ptr.rawValue.toLong()
    }

    actual fun createDbAtPath(path: String): Long {
        val ptr = lattice_db_create_at_path(path) ?: return 0L
        return ptr.rawValue.toLong()
    }

    actual fun createDbWithSchemas(path: String, schemasJson: String): Long {
        // Parse JSON and build schema structures
        // For now, use simple creation - schema handling will be done at Lattice level
        val ptr = if (path == ":memory:") {
            lattice_db_create_in_memory()
        } else {
            lattice_db_create_at_path(path)
        }
        return ptr?.rawValue?.toLong() ?: 0L
    }

    actual fun createDbWithSync(
        path: String,
        schemasJson: String,
        syncEndpoint: String,
        authToken: String
    ): Long {
        val ptr = lattice_db_create_with_sync(
            path,
            null,  // schemas - use createDbWithSchemaArrays instead
            0u,    // schema_count
            null,  // scheduler
            syncEndpoint,
            authToken
        )
        return ptr?.rawValue?.toLong() ?: 0L
    }

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
    ): Long = memScoped {
        if (tableNames.isEmpty()) {
            // No schemas
            return if (syncEndpoint != null && authToken != null) {
                val ptr = lattice_db_create_with_sync(path, null, 0u, null, syncEndpoint, authToken)
                ptr?.rawValue?.toLong() ?: 0L
            } else {
                val ptr = if (path == ":memory:") {
                    lattice_db_create_in_memory()
                } else {
                    lattice_db_create_at_path(path)
                }
                ptr?.rawValue?.toLong() ?: 0L
            }
        }

        // Build C schema structures
        val numTables = tableNames.size.toULong()
        val schemas = allocArray<lattice_schema_t>(tableNames.size)

        var propIdx = 0
        for (t in tableNames.indices) {
            schemas[t].table_name = tableNames[t].cstr.ptr
            val numProps = propertyCounts[t]
            schemas[t].property_count = numProps.toULong()

            if (numProps > 0) {
                val props = allocArray<lattice_property_t>(numProps)
                schemas[t].properties = props

                for (p in 0 until numProps) {
                    props[p].name = propNames[propIdx].cstr.ptr
                    props[p].type = propTypes[propIdx].toUInt()
                    props[p].kind = propKinds[propIdx].toUInt()
                    props[p].nullable = propNullable[propIdx]
                    props[p].target_table = propTargetTables[propIdx]?.cstr?.ptr
                    props[p].link_table = propLinkTables[propIdx]?.cstr?.ptr
                    // New fields default to false/null - will be set by extended API
                    props[p].is_indexed = false
                    props[p].is_unique = false
                    props[p].is_full_text = false
                    props[p].is_vector = false
                    props[p].is_geo_bounds = false
                    props[p].column_name = null
                    propIdx++
                }
            }
        }

        val ptr = if (syncEndpoint != null && authToken != null) {
            lattice_db_create_with_sync(path, schemas, numTables, null, syncEndpoint, authToken)
        } else {
            lattice_db_create_with_schemas(path, schemas, numTables)
        }
        ptr?.rawValue?.toLong() ?: 0L
    }

    actual fun closeDb(dbHandle: Long) {
        val ptr = dbHandle.toDbPtr() ?: return
        lattice_db_close(ptr)
    }

    actual fun releaseDb(dbHandle: Long) {
        val ptr = dbHandle.toDbPtr() ?: return
        lattice_db_release(ptr)
    }

    actual fun getLastError(): String? {
        return lattice_last_error()?.toKString()
    }

    // ========== Object Operations ==========

    actual fun addObject(dbHandle: Long, objectHandle: Long): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val obj = objectHandle.toObjectPtr() ?: return 0L
        val result = lattice_db_add(db, obj) ?: return 0L
        return result.rawValue.toLong()
    }

    actual fun findObject(dbHandle: Long, tableName: String, id: Long): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val result = lattice_db_find(db, tableName, id) ?: return 0L
        return result.rawValue.toLong()
    }

    actual fun findObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val result = lattice_db_find_by_global_id(db, tableName, globalId) ?: return 0L
        return result.rawValue.toLong()
    }

    actual fun removeObject(dbHandle: Long, objectHandle: Long): Boolean {
        val db = dbHandle.toDbPtr() ?: return false
        val obj = objectHandle.toObjectPtr() ?: return false
        return lattice_db_remove(db, obj) == LATTICE_OK
    }

    // ========== Query Operations ==========

    actual fun queryCount(dbHandle: Long, tableName: String, whereClause: String?): Int {
        val db = dbHandle.toDbPtr() ?: return 0
        return lattice_db_count(db, tableName, whereClause).toInt()
    }

    actual fun queryObjects(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): LongArray {
        val db = dbHandle.toDbPtr() ?: return LongArray(0)
        val resultsPtr = lattice_db_query(db, tableName, whereClause, orderBy, limit.toLong(), offset)
            ?: return LongArray(0)

        val count = lattice_results_count(resultsPtr).toInt()
        val handles = LongArray(count)

        for (i in 0 until count) {
            val objPtr = lattice_results_get(resultsPtr, i.convert())
            handles[i] = objPtr?.rawValue?.toLong() ?: 0L
        }

        lattice_results_free(resultsPtr)
        return handles
    }

    actual fun deleteWhere(dbHandle: Long, tableName: String, whereClause: String?): Int {
        val db = dbHandle.toDbPtr() ?: return 0
        return lattice_db_delete_where(db, tableName, whereClause).toInt()
    }

    // ========== Transaction Operations ==========

    actual fun beginTransaction(dbHandle: Long): Boolean {
        val db = dbHandle.toDbPtr() ?: return false
        return lattice_db_begin_transaction(db) == LATTICE_OK
    }

    actual fun commitTransaction(dbHandle: Long): Boolean {
        val db = dbHandle.toDbPtr() ?: return false
        return lattice_db_commit(db) == LATTICE_OK
    }

    actual fun rollbackTransaction(dbHandle: Long) {
        val db = dbHandle.toDbPtr() ?: return
        lattice_db_rollback(db)
    }

    // ========== Object Property Access ==========

    actual fun createObject(tableName: String, schemaJson: String): Long {
        // For simple creation without schema
        val ptr = lattice_object_create(tableName) ?: return 0L
        return ptr.rawValue.toLong()
    }

    actual fun getIntProperty(objectHandle: Long, propertyName: String): Long {
        val obj = objectHandle.toObjectPtr() ?: return 0L
        return lattice_object_get_int(obj, propertyName)
    }

    actual fun setIntProperty(objectHandle: Long, propertyName: String, value: Long) {
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_object_set_int(obj, propertyName, value)
    }

    actual fun getDoubleProperty(objectHandle: Long, propertyName: String): Double {
        val obj = objectHandle.toObjectPtr() ?: return 0.0
        return lattice_object_get_double(obj, propertyName)
    }

    actual fun setDoubleProperty(objectHandle: Long, propertyName: String, value: Double) {
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_object_set_double(obj, propertyName, value)
    }

    actual fun getStringProperty(objectHandle: Long, propertyName: String): String? {
        val obj = objectHandle.toObjectPtr() ?: return null
        return lattice_object_get_string(obj, propertyName)?.toKString()
    }

    actual fun setStringProperty(objectHandle: Long, propertyName: String, value: String?) {
        val obj = objectHandle.toObjectPtr() ?: return
        if (value != null) {
            lattice_object_set_string(obj, propertyName, value)
        } else {
            lattice_object_set_null(obj, propertyName)
        }
    }

    actual fun getBoolProperty(objectHandle: Long, propertyName: String): Boolean {
        val obj = objectHandle.toObjectPtr() ?: return false
        return lattice_object_get_int(obj, propertyName) != 0L
    }

    actual fun setBoolProperty(objectHandle: Long, propertyName: String, value: Boolean) {
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_object_set_int(obj, propertyName, if (value) 1L else 0L)
    }

    actual fun getObjectId(objectHandle: Long): Long {
        val obj = objectHandle.toObjectPtr() ?: return 0L
        return lattice_object_get_id(obj)
    }

    actual fun getObjectGlobalId(objectHandle: Long): String? {
        val obj = objectHandle.toObjectPtr() ?: return null
        return lattice_object_get_global_id(obj)?.toKString()
    }

    actual fun hasValue(objectHandle: Long, propertyName: String): Boolean {
        val obj = objectHandle.toObjectPtr() ?: return false
        return lattice_object_has_value(obj, propertyName)
    }

    actual fun setNull(objectHandle: Long, propertyName: String) {
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_object_set_null(obj, propertyName)
    }

    // ========== Blob Property Access ==========

    actual fun getBlobSize(objectHandle: Long, propertyName: String): Int {
        val obj = objectHandle.toObjectPtr() ?: return 0
        return lattice_object_get_blob(obj, propertyName, null, 0u).toInt()
    }

    actual fun getBlobData(objectHandle: Long, propertyName: String, buffer: ByteArray): Int {
        val obj = objectHandle.toObjectPtr() ?: return 0
        return buffer.usePinned { pinned ->
            lattice_object_get_blob(obj, propertyName, pinned.addressOf(0).reinterpret(), buffer.size.toULong()).toInt()
        }
    }

    actual fun setBlobProperty(objectHandle: Long, propertyName: String, data: ByteArray?) {
        val obj = objectHandle.toObjectPtr() ?: return
        if (data != null && data.isNotEmpty()) {
            data.usePinned { pinned ->
                lattice_object_set_blob(obj, propertyName, pinned.addressOf(0).reinterpret(), data.size.toULong())
            }
        } else if (data != null && data.isEmpty()) {
            lattice_object_set_blob(obj, propertyName, null, 0u)
        } else {
            lattice_object_set_null(obj, propertyName)
        }
    }

    // ========== Link Property Access ==========

    actual fun getLinkedObject(objectHandle: Long, propertyName: String): Long {
        val obj = objectHandle.toObjectPtr() ?: return 0L
        if (!lattice_object_has_value(obj, propertyName)) return 0L
        val linked = lattice_object_get_object(obj, propertyName) ?: return 0L
        return linked.rawValue.toLong()
    }

    actual fun setLinkedObject(objectHandle: Long, propertyName: String, linkedHandle: Long?) {
        val obj = objectHandle.toObjectPtr() ?: return
        if (linkedHandle != null && linkedHandle != 0L) {
            val linked = linkedHandle.toObjectPtr()
            if (linked != null) {
                lattice_object_set_object(obj, propertyName, linked)
            }
        } else {
            lattice_object_set_null(obj, propertyName)
        }
    }

    actual fun getLinkListHandle(objectHandle: Long, propertyName: String): Long {
        val obj = objectHandle.toObjectPtr() ?: return 0L
        val list = lattice_object_get_link_list(obj, propertyName) ?: return 0L
        return list.rawValue.toLong()
    }

    // ========== Object Creation ==========

    actual fun createObjectWithSchema(tableName: String, schemaJson: String): Long = memScoped {
        if (schemaJson.isEmpty()) {
            val ptr = lattice_object_create(tableName) ?: return 0L
            return ptr.rawValue.toLong()
        }

        // Parse schema JSON: [{"name":"...", "type":N, "kind":N, "nullable":B, ...}, ...]
        // Build C property array from JSON
        try {
            // Simple JSON parsing (schemaJson is a JSON array of property objects)
            val trimmed = schemaJson.trim().removePrefix("[").removeSuffix("]")
            val entries = mutableListOf<Map<String, String>>()

            // Split by },{ pattern
            var depth = 0
            var current = StringBuilder()
            for (c in trimmed) {
                when (c) {
                    '{' -> { depth++; current.append(c) }
                    '}' -> {
                        depth--
                        current.append(c)
                        if (depth == 0) {
                            val entry = mutableMapOf<String, String>()
                            val pairs = current.toString()
                                .removePrefix("{").removeSuffix("}")
                                .split(",")
                            for (pair in pairs) {
                                val kv = pair.split(":", limit = 2)
                                if (kv.size == 2) {
                                    val key = kv[0].trim().removeSurrounding("\"")
                                    val value = kv[1].trim().removeSurrounding("\"")
                                    entry[key] = value
                                }
                            }
                            entries.add(entry)
                            current = StringBuilder()
                        }
                    }
                    ',' -> if (depth == 0) { /* skip comma between objects */ } else current.append(c)
                    else -> current.append(c)
                }
            }

            if (entries.isEmpty()) {
                val ptr = lattice_object_create(tableName) ?: return 0L
                return ptr.rawValue.toLong()
            }

            val props = allocArray<lattice_property_t>(entries.size)
            for (i in entries.indices) {
                val e = entries[i]
                props[i].name = e["name"]?.cstr?.ptr
                props[i].type = (e["type"]?.toIntOrNull() ?: 2).toUInt()
                props[i].kind = (e["kind"]?.toIntOrNull() ?: 0).toUInt()
                props[i].nullable = e["nullable"]?.toBooleanStrictOrNull() ?: false
                props[i].target_table = e["target_table"]?.takeIf { it.isNotEmpty() && it != "null" }?.cstr?.ptr
                props[i].link_table = e["link_table"]?.takeIf { it.isNotEmpty() && it != "null" }?.cstr?.ptr
                props[i].is_indexed = false
                props[i].is_unique = false
                props[i].is_full_text = false
                props[i].is_vector = false
                props[i].is_geo_bounds = false
                props[i].column_name = null
            }

            val ptr = lattice_object_create_with_schema(tableName, props, entries.size.toULong()) ?: return 0L
            return ptr.rawValue.toLong()
        } catch (e: Exception) {
            // Fallback to simple creation
            val ptr = lattice_object_create(tableName) ?: return 0L
            return ptr.rawValue.toLong()
        }
    }

    // ========== Sync Operations ==========

    actual fun receiveSyncData(dbHandle: Long, data: ByteArray): String? {
        val db = dbHandle.toDbPtr() ?: return null
        val result = data.usePinned { pinned ->
            lattice_db_receive_sync_data(db, pinned.addressOf(0).reinterpret(), data.size.toULong())
        } ?: return null

        val jsonStr = result.toKString()
        lattice_string_free(result)
        return jsonStr
    }

    actual fun getEventsAfter(dbHandle: Long, globalId: String?): String? {
        val db = dbHandle.toDbPtr() ?: return null
        val result = lattice_db_events_after(db, globalId) ?: return null
        val jsonStr = result.toKString()
        lattice_string_free(result)
        return jsonStr
    }

    actual fun getPendingEvents(dbHandle: Long): String? {
        val db = dbHandle.toDbPtr() ?: return null
        val result = lattice_db_get_pending_audit_log(db) ?: return null
        val jsonStr = result.toKString()
        lattice_string_free(result)
        return jsonStr
    }

    actual fun markSynced(dbHandle: Long, globalIdsJson: String) {
        val db = dbHandle.toDbPtr() ?: return
        lattice_db_mark_synced(db, globalIdsJson)
    }

    actual fun compactAuditLog(dbHandle: Long): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        return lattice_db_compact_audit_log(db)
    }

    actual fun isSyncConnected(dbHandle: Long): Boolean {
        val db = dbHandle.toDbPtr() ?: return false
        return lattice_db_is_sync_connected(db)
    }

    // ========== Results Operations ==========

    actual fun resultsCount(resultsHandle: Long): Int {
        val results = resultsHandle.toResultsPtr() ?: return 0
        return lattice_results_count(results).toInt()
    }

    actual fun resultsGet(resultsHandle: Long, index: Int): Long {
        val results = resultsHandle.toResultsPtr() ?: return 0L
        val obj = lattice_results_get(results, index.convert()) ?: return 0L
        return obj.rawValue.toLong()
    }

    actual fun resultsFree(resultsHandle: Long) {
        val results = resultsHandle.toResultsPtr() ?: return
        lattice_results_free(results)
    }

    // ========== Link List Operations ==========

    actual fun linkListCount(listHandle: Long): Int {
        val list = listHandle.toLinkListPtr() ?: return 0
        return lattice_link_list_size(list).toInt()
    }

    actual fun linkListGet(listHandle: Long, index: Int): Long {
        val list = listHandle.toLinkListPtr() ?: return 0L
        val obj = lattice_link_list_get(list, index.convert()) ?: return 0L
        return obj.rawValue.toLong()
    }

    actual fun linkListAppend(listHandle: Long, objectHandle: Long) {
        val list = listHandle.toLinkListPtr() ?: return
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_link_list_push_back(list, obj)
    }

    actual fun linkListInsert(listHandle: Long, index: Int, objectHandle: Long) {
        // C API doesn't have insert, so we need to do append only
        // For now, just append - insert functionality not supported
        val list = listHandle.toLinkListPtr() ?: return
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_link_list_push_back(list, obj)
    }

    actual fun linkListRemove(listHandle: Long, index: Int) {
        val list = listHandle.toLinkListPtr() ?: return
        lattice_link_list_erase(list, index.convert())
    }

    actual fun linkListClear(listHandle: Long) {
        val list = listHandle.toLinkListPtr() ?: return
        lattice_link_list_clear(list)
    }

    // ========== String Memory ==========

    actual fun freeString(ptr: Long) {
        if (ptr == 0L) return
        val cstr: CPointer<ByteVar> = interpretCPointer(ptr.toNativePtr()) ?: return
        lattice_string_free(cstr)
    }

    // ========== Query Extensions ==========

    actual fun queryDistinct(
        dbHandle: Long,
        tableName: String,
        distinctBy: String?,
        whereClause: String?,
        orderBy: String?,
        limit: Long,
        offset: Long
    ): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val results = lattice_db_query_distinct(
            db, tableName, distinctBy, whereClause, orderBy, limit, offset
        ) ?: return 0L
        return results.rawValue.toLong()
    }

    actual fun countDistinct(
        dbHandle: Long,
        tableName: String,
        whereClause: String?,
        groupBy: String?,
        distinctBy: String?
    ): Int {
        val db = dbHandle.toDbPtr() ?: return 0
        return lattice_db_count_distinct(db, tableName, whereClause, groupBy, distinctBy).toInt()
    }

    actual fun queryFts(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        matchExpression: String,
        orderBy: String?,
        limit: Long
    ): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val results = lattice_db_query_fts(
            db, tableName, columnName, matchExpression, orderBy, limit
        ) ?: return 0L
        return results.rawValue.toLong()
    }

    // ========== Database Attachment ==========

    actual fun attachDb(dbHandle: Long, otherHandle: Long): Boolean {
        val db = dbHandle.toDbPtr() ?: return false
        val other = otherHandle.toDbPtr() ?: return false
        return lattice_db_attach(db, other) == LATTICE_OK
    }

    // ========== Add with preserved global ID ==========

    actual fun addObjectWithGlobalId(dbHandle: Long, objectHandle: Long, globalId: String): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val obj = objectHandle.toObjectPtr() ?: return 0L
        val result = lattice_db_add_with_global_id(db, obj, globalId) ?: return 0L
        return result.rawValue.toLong()
    }
}
