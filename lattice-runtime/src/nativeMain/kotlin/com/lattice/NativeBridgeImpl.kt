package com.lattice

import com.lattice.capi.*
import kotlinx.cinterop.*
import platform.posix.memcpy
import platform.posix.strdup

@OptIn(ExperimentalForeignApi::class)
internal actual object NativeBridge {

    @Suppress("NOTHING_TO_INLINE")
    private inline fun Long.toNativePtr(): NativePtr = NativePtr.NULL + this

    private fun Long.toDbPtr(): CPointer<lattice_db_t>? =
        if (this == 0L) null else interpretCPointer(this.toNativePtr())

    private fun Long.toObjectPtr(): CPointer<lattice_object_t>? =
        if (this == 0L) null else interpretCPointer(this.toNativePtr())

    private fun Long.toResultsPtr(): CPointer<lattice_results_t>? =
        if (this == 0L) null else interpretCPointer(this.toNativePtr())

    private fun Long.toLinkListPtr(): CPointer<lattice_link_list_t>? =
        if (this == 0L) null else interpretCPointer(this.toNativePtr())

    private fun Long.toUnionPtr(): CPointer<lattice_union_value_t>? =
        if (this == 0L) null else interpretCPointer(this.toNativePtr())

    private fun Long.toSchedulerPtr(): CPointer<lattice_scheduler_t>? =
        if (this == 0L) null else interpretCPointer(this.toNativePtr())

    // ========== Database Lifecycle ==========

    actual fun createDbInMemory(): Long =
        lattice_db_create_in_memory()?.rawValue?.toLong() ?: 0L

    actual fun createDbAtPath(path: String): Long =
        lattice_db_create_at_path(path)?.rawValue?.toLong() ?: 0L

    actual fun createDbWithSchemas(path: String, schemasJson: String): Long {
        val ptr = if (path == ":memory:") lattice_db_create_in_memory() else lattice_db_create_at_path(path)
        return ptr?.rawValue?.toLong() ?: 0L
    }

    actual fun createDbWithSync(
        path: String,
        schemasJson: String,
        syncEndpoint: String,
        authToken: String
    ): Long = lattice_db_create_with_sync(path, null, 0u, null, syncEndpoint, authToken)?.rawValue?.toLong() ?: 0L

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
    ): Long = openDbV2(
        path = path,
        tableNames = tableNames,
        propertyCounts = propertyCounts,
        propNames = propNames,
        propColumnNames = arrayOfNulls(propNames.size),
        propTypes = propTypes,
        propKinds = propKinds,
        propNullable = propNullable,
        propTargetTables = propTargetTables,
        propLinkTables = propLinkTables,
        propFlags = propFlags,
        propUnionSpecs = arrayOfNulls(propNames.size),
        constraintTableNames = emptyArray(),
        constraintColumnsJoined = emptyArray(),
        constraintAllowsUpsert = BooleanArray(0),
        schedulerHandle = 0L,
        syncEndpoint = syncEndpoint,
        authToken = authToken,
        targetSchemaVersion = 1,
        busyTimeoutMs = -1,
        auditRetentionSeconds = -1L,
        syncIsObserver = -1,
        uploadsEnabled = -1,
        syncAuthMode = -1,
        websocketSyncIdentity = null,
        pinnedServerCertSha256 = null,
        createIfMissing = -1,
        readOnly = -1,
        syncFilterJson = null,
        chunkSize = 0L,
        maxReconnectAttempts = -1,
        uploadCoalesceMs = -1,
        migrationTables = emptyArray(),
        migrationTransform = null
    )

    private fun MemScope.allocUnionDescriptor(spec: String): CPointer<lattice_union_descriptor_t>? {
        val parsed = LatticeNative.parseUnionSpec(spec) ?: return null
        val udesc = alloc<lattice_union_descriptor_t>()
        lattice_union_descriptor_init(udesc.ptr)
        udesc.union_table_name = parsed.unionTableName.cstr.ptr
        if (parsed.cases.isNotEmpty()) {
            val cArr = allocArray<lattice_union_case_t>(parsed.cases.size)
            for (ci in parsed.cases.indices) {
                val kc = parsed.cases[ci]
                lattice_union_case_init(cArr[ci].ptr)
                cArr[ci].case_name = kc.caseName.cstr.ptr
                if (kc.values.isNotEmpty()) {
                    val vArr = allocArray<lattice_union_case_value_t>(kc.values.size)
                    for (vi in kc.values.indices) {
                        val kv = kc.values[vi]
                        lattice_union_case_value_init(vArr[vi].ptr)
                        vArr[vi].param_name = kv.paramName.cstr.ptr
                        vArr[vi].type = kv.type.ordinal.toUInt()
                        vArr[vi].is_link = kv.isLink
                        vArr[vi].link_target = kv.linkTarget?.cstr?.ptr
                    }
                    cArr[ci].values = vArr
                    cArr[ci].value_count = kc.values.size.toULong()
                }
            }
            udesc.cases = cArr
            udesc.case_count = parsed.cases.size.toULong()
        }
        return udesc.ptr
    }

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
    ): Long = memScoped {
        val numTables = tableNames.size
        val schemasPtr: CPointer<lattice_schema_ext_t>? = if (numTables > 0) {
            val arr = allocArray<lattice_schema_ext_t>(numTables)
            var propIdx = 0
            for (t in 0 until numTables) {
                lattice_schema_ext_init(arr[t].ptr)
                val tname = tableNames[t]
                arr[t].table_name = tname.cstr.ptr
                val numProps = propertyCounts[t]
                arr[t].property_count = numProps.toULong()

                if (numProps > 0) {
                    val props = allocArray<lattice_property_ext_t>(numProps)
                    arr[t].properties = props
                    for (p in 0 until numProps) {
                        lattice_property_ext_init(props[p].ptr)
                        val flags = propFlags.getOrElse(propIdx) { 0 }
                        props[p].base.name = propNames[propIdx].cstr.ptr
                        props[p].base.column_name = propColumnNames.getOrNull(propIdx)?.cstr?.ptr
                        props[p].base.type = propTypes[propIdx].toUInt()
                        props[p].base.kind = propKinds[propIdx].toUInt()
                        props[p].base.nullable = propNullable[propIdx]
                        props[p].base.target_table = propTargetTables.getOrNull(propIdx)?.cstr?.ptr
                        props[p].base.link_table = propLinkTables.getOrNull(propIdx)?.cstr?.ptr
                        props[p].base.is_unique = (flags and LatticeNative.FLAG_UNIQUE) != 0
                        props[p].base.is_indexed = (flags and LatticeNative.FLAG_INDEXED) != 0
                        props[p].base.is_full_text = (flags and LatticeNative.FLAG_FULL_TEXT) != 0
                        props[p].base.is_vector = (flags and LatticeNative.FLAG_VECTOR) != 0
                        props[p].base.is_geo_bounds = (flags and LatticeNative.FLAG_GEO_BOUNDS) != 0
                        props[p].no_history = (flags and LatticeNative.FLAG_NO_HISTORY) != 0
                        propUnionSpecs.getOrNull(propIdx)?.let { uSpec ->
                            props[p].union_desc = allocUnionDescriptor(uSpec)
                        }
                        propIdx++
                    }
                }

                // Attach table-level compound/upsert unique constraints
                val matchingIdx = constraintTableNames.indices.filter { constraintTableNames[it] == tname }
                if (matchingIdx.isNotEmpty()) {
                    val cArr = allocArray<lattice_unique_constraint_t>(matchingIdx.size)
                    matchingIdx.forEachIndexed { ci, rawIdx ->
                        val cols = constraintColumnsJoined[rawIdx].split(",").filter { it.isNotEmpty() }
                        val colPtrs = allocArray<CPointerVar<ByteVar>>(cols.size)
                        cols.forEachIndexed { m, colName -> colPtrs[m] = colName.cstr.ptr }
                        cArr[ci].table_name = tname.cstr.ptr
                        cArr[ci].property_names = colPtrs
                        cArr[ci].property_count = cols.size.toULong()
                        cArr[ci].allows_upsert = constraintAllowsUpsert[rawIdx]
                    }
                    arr[t].constraints = cArr
                    arr[t].constraint_count = matchingIdx.size.toULong()
                }
            }
            arr
        } else null

        val opts = alloc<lattice_sync_options_t>()
        lattice_sync_options_init(opts.ptr)
        if (targetSchemaVersion >= 1) opts.target_schema_version = targetSchemaVersion
        if (busyTimeoutMs > 0) opts.busy_timeout_ms = busyTimeoutMs
        if (auditRetentionSeconds >= 0L) opts.audit_retention_seconds = auditRetentionSeconds
        if (syncIsObserver >= 0) opts.sync_is_observer = syncIsObserver
        if (uploadsEnabled >= 0) opts.uploads_enabled = uploadsEnabled
        if (syncAuthMode >= 0) opts.sync_authentication_mode = syncAuthMode
        if (websocketSyncIdentity != null) opts.websocket_sync_identity = websocketSyncIdentity.cstr.ptr
        if (pinnedServerCertSha256 != null) opts.pinned_server_certificate_sha256 = pinnedServerCertSha256.cstr.ptr
        if (createIfMissing >= 0) opts.create_if_missing = createIfMissing
        if (readOnly >= 0) opts.read_only = readOnly
        if (syncFilterJson != null) opts.sync_filter_json = syncFilterJson.cstr.ptr
        if (chunkSize > 0L) opts.chunk_size = chunkSize
        if (maxReconnectAttempts >= 0) opts.max_reconnect_attempts = maxReconnectAttempts
        if (uploadCoalesceMs >= 0) opts.upload_coalesce_ms = uploadCoalesceMs

        val migRef: StableRef<(String, String) -> String?>? =
            migrationTransform?.let { StableRef.create(it) }
        try {
            val migTablesPtr: CPointer<CPointerVar<ByteVar>>? = if (migRef != null && migrationTables.isNotEmpty()) {
                val arr = allocArray<CPointerVar<ByteVar>>(migrationTables.size)
                migrationTables.forEachIndexed { idx, s -> arr[idx] = s.cstr.ptr }
                arr
            } else null

            val migFn: lattice_migration_table_row_fn? = if (migRef != null) {
                staticCFunction { ctx, tnamePtr, oldJsonPtr, outNewJsonPtr ->
                    if (ctx == null || tnamePtr == null || oldJsonPtr == null || outNewJsonPtr == null) return@staticCFunction
                    val fn = ctx.asStableRef<(String, String) -> String?>().get()
                    val res = fn(tnamePtr.toKString(), oldJsonPtr.toKString())
                    if (res != null) {
                        outNewJsonPtr.pointed.value = strdup(res)
                    }
                }
            } else null

            val dbPtr = lattice_db_open_v2(
                path,
                schemasPtr,
                numTables.toULong(),
                sizeOf<lattice_schema_ext_t>().toULong(),
                schedulerHandle.toSchedulerPtr(),
                syncEndpoint,
                authToken,
                opts.ptr,
                migTablesPtr,
                if (migTablesPtr != null) migrationTables.size.toULong() else 0u,
                migRef?.asCPointer(),
                migFn
            )
            return dbPtr?.rawValue?.toLong() ?: 0L
        } finally {
            migRef?.dispose()
        }
    }

    actual fun closeDb(dbHandle: Long) {
        dbHandle.toDbPtr()?.let { lattice_db_close(it) }
    }

    actual fun releaseDb(dbHandle: Long) {
        dbHandle.toDbPtr()?.let { lattice_db_release(it) }
    }

    actual fun getLastError(): String? = lattice_last_error()?.toKString()

    // ========== Object Operations ==========

    actual fun releaseObject(objectHandle: Long) {
        objectHandle.toObjectPtr()?.let { lattice_object_release(it) }
    }

    actual fun addObject(dbHandle: Long, objectHandle: Long): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val obj = objectHandle.toObjectPtr() ?: return 0L
        return lattice_db_add(db, obj)?.rawValue?.toLong() ?: 0L
    }

    actual fun addObjectWithGlobalId(dbHandle: Long, objectHandle: Long, globalId: String): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val obj = objectHandle.toObjectPtr() ?: return 0L
        return lattice_db_add_with_global_id(db, obj, globalId)?.rawValue?.toLong() ?: 0L
    }

    actual fun findObject(dbHandle: Long, tableName: String, id: Long): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        return lattice_db_find(db, tableName, id)?.rawValue?.toLong() ?: 0L
    }

    actual fun findObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        return lattice_db_find_by_global_id(db, tableName, globalId)?.rawValue?.toLong() ?: 0L
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
        val handles = LongArray(count) { i ->
            lattice_results_get(resultsPtr, i.convert())?.rawValue?.toLong() ?: 0L
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
        dbHandle.toDbPtr()?.let { lattice_db_rollback(it) }
    }

    // ========== Object Property Access ==========

    actual fun createObject(tableName: String, schemaJson: String): Long =
        lattice_object_create(tableName)?.rawValue?.toLong() ?: 0L

    actual fun getIntProperty(objectHandle: Long, propertyName: String): Long =
        objectHandle.toObjectPtr()?.let { lattice_object_get_int(it, propertyName) } ?: 0L

    actual fun setIntProperty(objectHandle: Long, propertyName: String, value: Long) {
        objectHandle.toObjectPtr()?.let { lattice_object_set_int(it, propertyName, value) }
    }

    actual fun getDoubleProperty(objectHandle: Long, propertyName: String): Double =
        objectHandle.toObjectPtr()?.let { lattice_object_get_double(it, propertyName) } ?: 0.0

    actual fun setDoubleProperty(objectHandle: Long, propertyName: String, value: Double) {
        objectHandle.toObjectPtr()?.let { lattice_object_set_double(it, propertyName, value) }
    }

    actual fun getStringProperty(objectHandle: Long, propertyName: String): String? =
        objectHandle.toObjectPtr()?.let { lattice_object_get_string(it, propertyName)?.toKString() }

    actual fun setStringProperty(objectHandle: Long, propertyName: String, value: String?) {
        val obj = objectHandle.toObjectPtr() ?: return
        if (value != null) lattice_object_set_string(obj, propertyName, value)
        else lattice_object_set_null(obj, propertyName)
    }

    actual fun getBoolProperty(objectHandle: Long, propertyName: String): Boolean =
        objectHandle.toObjectPtr()?.let { lattice_object_get_int(it, propertyName) != 0L } ?: false

    actual fun setBoolProperty(objectHandle: Long, propertyName: String, value: Boolean) {
        objectHandle.toObjectPtr()?.let { lattice_object_set_int(it, propertyName, if (value) 1L else 0L) }
    }

    actual fun getObjectId(objectHandle: Long): Long =
        objectHandle.toObjectPtr()?.let { lattice_object_get_id(it) } ?: 0L

    actual fun getObjectGlobalId(objectHandle: Long): String? =
        objectHandle.toObjectPtr()?.let { lattice_object_get_global_id(it)?.toKString() }

    actual fun getObjectTableName(objectHandle: Long): String? =
        objectHandle.toObjectPtr()?.let { lattice_object_get_table_name(it)?.toKString() }

    actual fun hasValue(objectHandle: Long, propertyName: String): Boolean =
        objectHandle.toObjectPtr()?.let { lattice_object_has_value(it, propertyName) } ?: false

    actual fun setNull(objectHandle: Long, propertyName: String) {
        objectHandle.toObjectPtr()?.let { lattice_object_set_null(it, propertyName) }
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
        } else if (data != null) {
            lattice_object_set_blob(obj, propertyName, null, 0u)
        } else {
            lattice_object_set_null(obj, propertyName)
        }
    }

    // ========== Link Property Access ==========

    actual fun getLinkedObject(objectHandle: Long, propertyName: String): Long {
        val obj = objectHandle.toObjectPtr() ?: return 0L
        if (!lattice_object_has_value(obj, propertyName)) return 0L
        return lattice_object_get_object(obj, propertyName)?.rawValue?.toLong() ?: 0L
    }

    actual fun setLinkedObject(objectHandle: Long, propertyName: String, linkedHandle: Long?) {
        val obj = objectHandle.toObjectPtr() ?: return
        if (linkedHandle != null && linkedHandle != 0L) {
            linkedHandle.toObjectPtr()?.let { lattice_object_set_object(obj, propertyName, it) }
        } else {
            lattice_object_set_null(obj, propertyName)
        }
    }

    actual fun getLinkListHandle(objectHandle: Long, propertyName: String): Long {
        val obj = objectHandle.toObjectPtr() ?: return 0L
        return lattice_object_get_link_list(obj, propertyName)?.rawValue?.toLong() ?: 0L
    }

    // ========== Union Property Access ==========

    actual fun createUnionValue(caseName: String): Long =
        lattice_union_value_create(caseName)?.rawValue?.toLong() ?: 0L

    actual fun releaseUnionValue(uvHandle: Long) {
        uvHandle.toUnionPtr()?.let { lattice_union_value_release(it) }
    }

    actual fun getUnionCase(uvHandle: Long): String? =
        uvHandle.toUnionPtr()?.let { lattice_union_value_get_case(it)?.toKString() }

    actual fun hasUnionField(uvHandle: Long, key: String): Boolean =
        uvHandle.toUnionPtr()?.let { lattice_union_value_has_field(it, key) } ?: false

    actual fun getUnionInt(uvHandle: Long, key: String): Long =
        uvHandle.toUnionPtr()?.let { lattice_union_value_get_int(it, key) } ?: 0L

    actual fun getUnionDouble(uvHandle: Long, key: String): Double =
        uvHandle.toUnionPtr()?.let { lattice_union_value_get_double(it, key) } ?: 0.0

    actual fun getUnionString(uvHandle: Long, key: String): String? =
        uvHandle.toUnionPtr()?.let { lattice_union_value_get_string(it, key)?.toKString() }

    actual fun getUnionBlob(uvHandle: Long, key: String): ByteArray {
        val uv = uvHandle.toUnionPtr() ?: return ByteArray(0)
        val sz = lattice_union_value_get_blob(uv, key, null, 0u).toInt()
        if (sz <= 0) return ByteArray(0)
        val out = ByteArray(sz)
        out.usePinned { pinned ->
            lattice_union_value_get_blob(uv, key, pinned.addressOf(0).reinterpret(), sz.toULong())
        }
        return out
    }

    actual fun getUnionLink(uvHandle: Long, key: String): Long =
        uvHandle.toUnionPtr()?.let { lattice_union_value_get_link(it, key)?.rawValue?.toLong() } ?: 0L

    actual fun setUnionInt(uvHandle: Long, key: String, value: Long) {
        uvHandle.toUnionPtr()?.let { lattice_union_value_set_int(it, key, value) }
    }

    actual fun setUnionDouble(uvHandle: Long, key: String, value: Double) {
        uvHandle.toUnionPtr()?.let { lattice_union_value_set_double(it, key, value) }
    }

    actual fun setUnionString(uvHandle: Long, key: String, value: String) {
        uvHandle.toUnionPtr()?.let { lattice_union_value_set_string(it, key, value) }
    }

    actual fun setUnionBlob(uvHandle: Long, key: String, value: ByteArray) {
        val uv = uvHandle.toUnionPtr() ?: return
        if (value.isEmpty()) {
            lattice_union_value_set_blob(uv, key, null, 0u)
        } else {
            value.usePinned { pinned ->
                lattice_union_value_set_blob(uv, key, pinned.addressOf(0).reinterpret(), value.size.toULong())
            }
        }
    }

    actual fun setUnionLink(uvHandle: Long, key: String, objHandle: Long) {
        val uv = uvHandle.toUnionPtr() ?: return
        val obj = objHandle.toObjectPtr() ?: return
        lattice_union_value_set_link(uv, key, obj)
    }

    actual fun getObjectUnion(objectHandle: Long, propertyName: String): Long =
        objectHandle.toObjectPtr()?.let { lattice_object_get_union(it, propertyName)?.rawValue?.toLong() } ?: 0L

    actual fun setObjectUnion(objectHandle: Long, propertyName: String, uvHandle: Long): Boolean {
        val obj = objectHandle.toObjectPtr() ?: return false
        return lattice_object_set_union(obj, propertyName, uvHandle.toUnionPtr()) == LATTICE_OK
    }

    actual fun clearObjectUnion(objectHandle: Long, propertyName: String): Boolean {
        val obj = objectHandle.toObjectPtr() ?: return false
        return lattice_object_clear_union(obj, propertyName) == LATTICE_OK
    }

    // ========== Row Cache, Atomic Increment, Detached JSON & Statement Counters ==========

    actual fun enableRowCache(objectHandle: Long): Boolean =
        objectHandle.toObjectPtr()?.let { lattice_object_enable_row_cache(it) == LATTICE_OK } ?: false

    actual fun disableRowCache(objectHandle: Long): Boolean =
        objectHandle.toObjectPtr()?.let { lattice_object_disable_row_cache(it) == LATTICE_OK } ?: false

    actual fun refreshRowCache(objectHandle: Long): Boolean =
        objectHandle.toObjectPtr()?.let { lattice_object_refresh_row_cache(it) == LATTICE_OK } ?: false

    actual fun isRowCacheEnabled(objectHandle: Long): Boolean =
        objectHandle.toObjectPtr()?.let { lattice_object_is_row_cache_enabled(it) } ?: false

    actual fun incrementInt(objectHandle: Long, propertyName: String, delta: Long): Boolean =
        objectHandle.toObjectPtr()?.let { lattice_object_increment_int(it, propertyName, delta) == LATTICE_OK } ?: false

    actual fun objectToJson(objectHandle: Long, maxDepth: Int): String? {
        val obj = objectHandle.toObjectPtr() ?: return null
        val raw = lattice_object_to_json(obj, maxDepth) ?: return null
        val str = raw.toKString()
        lattice_string_free(raw)
        return str
    }

    actual fun totalStatementCount(): Long = lattice_db_total_statement_count().toLong()

    actual fun threadStatementCount(): Long = lattice_db_thread_statement_count().toLong()

    // ========== Object Creation (Schema v2) ==========

    actual fun createObjectWithSchema(tableName: String, schemaJson: String): Long = memScoped {
        if (schemaJson.isEmpty()) {
            return lattice_object_create(tableName)?.rawValue?.toLong() ?: 0L
        }
        try {
            val trimmed = schemaJson.trim().removePrefix("[").removeSuffix("]")
            val entries = mutableListOf<Map<String, String>>()
            var depth = 0
            var inStr = false
            var current = StringBuilder()
            for (c in trimmed) {
                if (c == '"') inStr = !inStr
                if (!inStr) {
                    when (c) {
                        '{' -> { depth++; current.append(c) }
                        '}' -> {
                            depth--
                            current.append(c)
                            if (depth == 0) {
                                val entry = mutableMapOf<String, String>()
                                val body = current.toString().removePrefix("{").removeSuffix("}")
                                // Split top-level comma pairs respecting quotes
                                val pairs = mutableListOf<String>()
                                var pInStr = false
                                val pBuf = StringBuilder()
                                for (ch in body) {
                                    if (ch == '"') pInStr = !pInStr
                                    if (ch == ',' && !pInStr) {
                                        pairs.add(pBuf.toString())
                                        pBuf.clear()
                                    } else pBuf.append(ch)
                                }
                                if (pBuf.isNotEmpty()) pairs.add(pBuf.toString())
                                for (pair in pairs) {
                                    val kv = pair.split(":", limit = 2)
                                    if (kv.size == 2) {
                                        entry[kv[0].trim().removeSurrounding("\"")] =
                                            kv[1].trim().removeSurrounding("\"")
                                    }
                                }
                                entries.add(entry)
                                current = StringBuilder()
                            }
                        }
                        ',' -> if (depth > 0) current.append(c)
                        else -> current.append(c)
                    }
                } else current.append(c)
            }

            if (entries.isEmpty()) {
                return lattice_object_create(tableName)?.rawValue?.toLong() ?: 0L
            }

            val props = allocArray<lattice_property_ext_t>(entries.size)
            for (i in entries.indices) {
                lattice_property_ext_init(props[i].ptr)
                val e = entries[i]
                props[i].base.name = e["name"]?.cstr?.ptr
                props[i].base.type = (e["type"]?.toIntOrNull() ?: 2).toUInt()
                props[i].base.kind = (e["kind"]?.toIntOrNull() ?: 0).toUInt()
                props[i].base.nullable = e["nullable"]?.toBooleanStrictOrNull() ?: false
                props[i].base.target_table = e["target_table"]?.takeIf { it.isNotEmpty() && it != "null" }?.cstr?.ptr
                props[i].base.link_table = e["link_table"]?.takeIf { it.isNotEmpty() && it != "null" }?.cstr?.ptr
                props[i].base.column_name = e["column_name"]?.takeIf { it.isNotEmpty() && it != "null" }?.cstr?.ptr
                props[i].base.is_indexed = e["is_indexed"]?.toBooleanStrictOrNull() ?: false
                props[i].base.is_unique = e["is_unique"]?.toBooleanStrictOrNull() ?: false
                props[i].base.is_full_text = e["is_full_text"]?.toBooleanStrictOrNull() ?: false
                props[i].base.is_vector = e["is_vector"]?.toBooleanStrictOrNull() ?: false
                props[i].base.is_geo_bounds = e["is_geo_bounds"]?.toBooleanStrictOrNull() ?: false
                props[i].no_history = e["is_no_history"]?.toBooleanStrictOrNull() ?: false
                e["union_spec"]?.takeIf { it.isNotEmpty() }?.let { uSpec ->
                    props[i].union_desc = allocUnionDescriptor(uSpec)
                }
            }

            val ptr = lattice_object_create_with_schema_v2(
                tableName,
                props,
                entries.size.toULong(),
                sizeOf<lattice_property_ext_t>().toULong()
            ) ?: return 0L
            return ptr.rawValue.toLong()
        } catch (_: Exception) {
            return lattice_object_create(tableName)?.rawValue?.toLong() ?: 0L
        }
    }

    actual fun createDbObject(dbHandle: Long, tableName: String): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        return lattice_db_create_object(db, tableName)?.rawValue?.toLong() ?: 0L
    }

    // ========== Sync, Maintenance & Observers ==========

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
        dbHandle.toDbPtr()?.let { lattice_db_mark_synced(it, globalIdsJson) }
    }

    actual fun compactAuditLog(dbHandle: Long): Long =
        dbHandle.toDbPtr()?.let { lattice_db_compact_audit_log(it) } ?: 0L

    actual fun pruneAuditLog(dbHandle: Long, retentionSeconds: Long): Long =
        dbHandle.toDbPtr()?.let { lattice_db_prune_audit_log(it, retentionSeconds) } ?: 0L

    actual fun recordAuditWatermark(dbHandle: Long): Boolean =
        dbHandle.toDbPtr()?.let { lattice_db_record_audit_watermark(it) == LATTICE_OK } ?: false

    actual fun reclaimSpace(dbHandle: Long): Long =
        dbHandle.toDbPtr()?.let { lattice_db_reclaim_space(it) } ?: 0L

    actual fun checkpointDb(dbHandle: Long, truncate: Boolean): Boolean =
        dbHandle.toDbPtr()?.let { lattice_db_checkpoint(it, if (truncate) 1 else 0) == LATTICE_OK } ?: false

    actual fun setSyncFilter(dbHandle: Long, filterJson: String): Boolean =
        dbHandle.toDbPtr()?.let { lattice_db_set_sync_filter(it, filterJson) == LATTICE_OK } ?: false

    actual fun clearSyncFilter(dbHandle: Long): Boolean =
        dbHandle.toDbPtr()?.let { lattice_db_clear_sync_filter(it) == LATTICE_OK } ?: false

    actual fun isSyncConnected(dbHandle: Long): Boolean =
        dbHandle.toDbPtr()?.let { lattice_db_is_sync_connected(it) } ?: false

    actual fun getSyncProgress(dbHandle: Long): LongArray = memScoped {
        val db = dbHandle.toDbPtr() ?: return LongArray(4)
        val prog = alloc<lattice_sync_progress_t>()
        prog.struct_size = sizeOf<lattice_sync_progress_t>().toULong()
        if (lattice_db_get_sync_progress(db, prog.ptr) != LATTICE_OK) return LongArray(4)
        longArrayOf(prog.pending_upload, prog.total_upload, prog.acked, prog.received)
    }

    actual fun observeSyncState(dbHandle: Long, callback: (Boolean) -> Unit): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val ref = StableRef.create(callback)
        val tok = lattice_db_observe_sync_state(
            db,
            ref.asCPointer(),
            staticCFunction { ctx, connected ->
                ctx?.asStableRef<(Boolean) -> Unit>()?.get()?.invoke(connected)
            },
            staticCFunction { ctx ->
                ctx?.asStableRef<(Boolean) -> Unit>()?.dispose()
            }
        )
        if (tok == 0uL) ref.dispose()
        return tok.toLong()
    }

    actual fun observeSyncProgress(dbHandle: Long, callback: (Long, Long, Long, Long) -> Unit): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val ref = StableRef.create(callback)
        val tok = lattice_db_observe_sync_progress(
            db,
            ref.asCPointer(),
            staticCFunction { ctx, progPtr ->
                if (ctx != null && progPtr != null) {
                    val p = progPtr.pointed
                    ctx.asStableRef<(Long, Long, Long, Long) -> Unit>().get()
                        .invoke(p.pending_upload, p.total_upload, p.acked, p.received)
                }
            },
            staticCFunction { ctx ->
                ctx?.asStableRef<(Long, Long, Long, Long) -> Unit>()?.dispose()
            }
        )
        if (tok == 0uL) ref.dispose()
        return tok.toLong()
    }

    actual fun observeSyncError(dbHandle: Long, callback: (String) -> Unit): Long {
        val db = dbHandle.toDbPtr() ?: return 0L
        val ref = StableRef.create(callback)
        val tok = lattice_db_observe_sync_error(
            db,
            ref.asCPointer(),
            staticCFunction { ctx, msgPtr ->
                if (ctx != null && msgPtr != null) {
                    ctx.asStableRef<(String) -> Unit>().get().invoke(msgPtr.toKString())
                }
            },
            staticCFunction { ctx ->
                ctx?.asStableRef<(String) -> Unit>()?.dispose()
            }
        )
        if (tok == 0uL) ref.dispose()
        return tok.toLong()
    }

    actual fun removeSyncObserver(dbHandle: Long, token: Long) {
        val db = dbHandle.toDbPtr() ?: return
        if (token != 0L) lattice_db_remove_sync_observer(db, token.toULong())
    }

    // ========== Results Operations ==========

    actual fun resultsCount(resultsHandle: Long): Int =
        resultsHandle.toResultsPtr()?.let { lattice_results_count(it).toInt() } ?: 0

    actual fun resultsGet(resultsHandle: Long, index: Int): Long =
        resultsHandle.toResultsPtr()?.let { lattice_results_get(it, index.convert())?.rawValue?.toLong() } ?: 0L

    actual fun resultsFree(resultsHandle: Long) {
        resultsHandle.toResultsPtr()?.let { lattice_results_free(it) }
    }

    // ========== Link List Operations ==========

    actual fun linkListCount(listHandle: Long): Int =
        listHandle.toLinkListPtr()?.let { lattice_link_list_size(it).toInt() } ?: 0

    actual fun linkListGet(listHandle: Long, index: Int): Long =
        listHandle.toLinkListPtr()?.let { lattice_link_list_get(it, index.convert())?.rawValue?.toLong() } ?: 0L

    actual fun linkListAppend(listHandle: Long, objectHandle: Long) {
        val list = listHandle.toLinkListPtr() ?: return
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_link_list_push_back(list, obj)
    }

    actual fun linkListInsert(listHandle: Long, index: Int, objectHandle: Long) {
        val list = listHandle.toLinkListPtr() ?: return
        val obj = objectHandle.toObjectPtr() ?: return
        lattice_link_list_push_back(list, obj)
    }

    actual fun linkListRemove(listHandle: Long, index: Int) {
        listHandle.toLinkListPtr()?.let { lattice_link_list_erase(it, index.convert()) }
    }

    actual fun linkListClear(listHandle: Long) {
        listHandle.toLinkListPtr()?.let { lattice_link_list_clear(it) }
    }

    actual fun linkListRelease(listHandle: Long) {
        listHandle.toLinkListPtr()?.let { lattice_link_list_release(it) }
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
        return lattice_db_query_distinct(db, tableName, distinctBy, whereClause, orderBy, limit, offset)
            ?.rawValue?.toLong() ?: 0L
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
        return lattice_db_query_fts(db, tableName, columnName, matchExpression, orderBy, limit)
            ?.rawValue?.toLong() ?: 0L
    }

    actual fun queryNearest(
        dbHandle: Long,
        tableName: String,
        columnName: String,
        queryVectorBytes: ByteArray,
        k: Int,
        metricOrdinal: Int,
        whereClause: String?,
        outDistances: DoubleArray
    ): Array<String> {
        val db = dbHandle.toDbPtr() ?: return emptyArray()
        if (queryVectorBytes.isEmpty() || k <= 0) return emptyArray()
        val resultsPtr = queryVectorBytes.usePinned { pinned ->
            lattice_db_query_nearest(
                db,
                tableName,
                columnName,
                pinned.addressOf(0).reinterpret(),
                queryVectorBytes.size.toULong(),
                k,
                metricOrdinal.toUInt(),
                whereClause
            )
        } ?: return emptyArray()

        val count = lattice_knn_results_count(resultsPtr).toInt()
        val out = Array(count) { i ->
            val item = resultsPtr[i]
            if (i < outDistances.size) outDistances[i] = item.distance
            item.global_id?.toKString() ?: ""
        }
        lattice_knn_results_free(resultsPtr, count.toULong())
        return out
    }

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
    ): LongArray {
        val db = dbHandle.toDbPtr() ?: return LongArray(0)
        val resPtr = lattice_db_query_within_bounds(
            db, tableName, columnName, minLat, maxLat, minLon, maxLon, whereClause, limit
        ) ?: return LongArray(0)
        val count = lattice_results_count(resPtr).toInt()
        val handles = LongArray(count) { i ->
            lattice_results_get(resPtr, i.convert())?.rawValue?.toLong() ?: 0L
        }
        lattice_results_free(resPtr)
        return handles
    }

    // ========== Database Attachment ==========

    actual fun attachDb(dbHandle: Long, otherHandle: Long): Boolean {
        val db = dbHandle.toDbPtr() ?: return false
        val other = otherHandle.toDbPtr() ?: return false
        return lattice_db_attach(db, other) == LATTICE_OK
    }

    actual fun detachDb(dbHandle: Long, otherHandle: Long): Boolean {
        val db = dbHandle.toDbPtr() ?: return false
        val other = otherHandle.toDbPtr() ?: return false
        return lattice_db_detach(db, other) == LATTICE_OK
    }

    actual fun lastAttachError(dbHandle: Long): String? =
        dbHandle.toDbPtr()?.let { lattice_db_last_attach_error(it)?.toKString() }
}
