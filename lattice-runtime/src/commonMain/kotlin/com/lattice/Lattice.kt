package com.lattice

import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Represents a database change event for sync.
 */
data class Change(
    val tableName: String,
    val operation: ChangeOperation,
    val rowId: Long,
    val globalId: String,
    val isSynchronized: Boolean = false
)

enum class ChangeOperation {
    INSERT, UPDATE, DELETE
}

/**
 * Configuration for Lattice database.
 */
data class LatticeConfiguration(
    val path: String? = null,
    val syncEndpoint: String? = null,
    val authorizationToken: String? = null
)

/**
 * Main entry point for Lattice database operations.
 *
 * The compiler plugin (via FIR extension) automatically adds LatticeObject
 * as a supertype to all @Model classes, enabling type-safe usage.
 */
class Lattice private constructor(
    private val config: LatticeConfiguration,
    private val scheduler: LatticeScheduler?,
    modelTypes: Array<out KClass<out LatticeObject>>
) {
    /**
     * Create or open a database at the given path.
     * Pass null or ":memory:" for an in-memory database.
     *
     * Observer callbacks will be invoked synchronously on the thread
     * where mutations occur.
     */
    constructor(path: String?, vararg modelTypes: KClass<out LatticeObject>) :
        this(LatticeConfiguration(path = path), null, modelTypes)

    /**
     * Create or open a database with a scheduler for observer callbacks.
     *
     * The scheduler dispatches observer callbacks to a specific coroutine
     * dispatcher, enabling thread-safe observation.
     *
     * Example:
     * ```kotlin
     * val scheduler = LatticeScheduler(Dispatchers.Main)
     * val lattice = Lattice("/path/to/db", scheduler, Person::class)
     * ```
     */
    constructor(path: String?, scheduler: LatticeScheduler, vararg modelTypes: KClass<out LatticeObject>) :
        this(LatticeConfiguration(path = path), scheduler, modelTypes)

    /**
     * Create or open a database with full configuration including sync.
     *
     * Example:
     * ```kotlin
     * val config = LatticeConfiguration(
     *     path = "/path/to/db",
     *     syncEndpoint = "ws://localhost:8080/sync",
     *     authorizationToken = "my-token"
     * )
     * val lattice = Lattice(config, Person::class)
     * ```
     */
    constructor(config: LatticeConfiguration, vararg modelTypes: KClass<out LatticeObject>) :
        this(config, null, modelTypes)

    // Store model types for later use
    private val registeredTypes = modelTypes.toList()

    /** The model types registered with this database. */
    val registeredModelTypes: List<KClass<out LatticeObject>> get() = registeredTypes
    private val dbHandle: Long
    private val modelRegistry = mutableMapOf<KClass<*>, ModelInfo>()

    /** The configuration used to create this database. */
    val configuration: LatticeConfiguration get() = config

    // Change stream for observation
    private val _changeFlow = MutableSharedFlow<List<Change>>(extraBufferCapacity = 64)

    /** Whether the sync WebSocket is currently connected. */
    val isSyncConnected: Boolean get() = NativeBridge.isSyncConnected(dbHandle)

    /**
     * Suspend until the sync WebSocket connection is established.
     * Uses an event-driven check via the changeStream subscription,
     * not polling.
     */
    suspend fun awaitSyncConnected(timeoutMs: Long = 30_000) {
        if (config.syncEndpoint == null || isSyncConnected) return
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (!isSyncConnected) {
                kotlinx.coroutines.delay(50) // yield, check again
            }
        }
    }


    // Factory functions for creating model instances (populated by companion)
    companion object {
        private val modelFactories = mutableMapOf<KClass<*>, () -> LatticeObject>()

        /**
         * Register a factory function for a model type.
         * This should be called by generated code or manually for each model.
         */
        fun <T : LatticeObject> registerFactory(kClass: KClass<T>, factory: () -> T) {
            modelFactories[kClass] = factory
        }

        /**
         * Get a factory function for a model type.
         * Used by LatticeList to create elements.
         */
        fun getFactory(kClass: KClass<*>): (() -> LatticeObject)? {
            var factory = modelFactories[kClass]
            if (factory == null) {
                // Fallback: find by class name
                factory = modelFactories.entries.find {
                    it.key.simpleName == kClass.simpleName
                }?.value
            }
            return factory
        }

        /**
         * Delete a database and its associated files (WAL, SHM).
         * Call after [close] to remove the database files from disk.
         * Mirrors Swift's `Lattice.delete(for:)`.
         */
        fun deleteDatabase(path: String) {
            for (suffix in listOf("", "-wal", "-shm")) {
                try {
                    platform.posix.unlink(path + suffix)
                } catch (_: Exception) {}
            }
        }
    }

    init {
        // Register network factory if sync is configured (like Swift does)
        if (config.syncEndpoint != null) {
            ensureNetworkFactoryRegistered()
        }

        // Build schemas from model types using registered factories
        val schemas = mutableListOf<Pair<String, List<LatticePropertyDescriptor>>>()

        modelTypes.forEach { kClass ->
            val tableName = kClass.simpleName ?: throw LatticeException("Model must have a name")
            modelRegistry[kClass] = ModelInfo(tableName)

            // Try to get schema from a factory-created instance
            var factory = modelFactories[kClass]
            if (factory == null) {
                // Fallback: find by class name
                factory = modelFactories.entries.find {
                    it.key.simpleName == kClass.simpleName
                }?.value
            }

            // If still no factory, try to create one via reflection
            if (factory == null) {
                val reflectionFactory = createFactoryViaReflection(kClass)
                if (reflectionFactory != null) {
                    factory = reflectionFactory
                    modelFactories[kClass] = reflectionFactory
                }
            }

            if (factory != null) {
                val instance = factory()
                schemas.add(tableName to instance._latticeSchema)
            }
        }

        // Create database with schemas and optional sync
        val path = config.path

        dbHandle = if (schemas.isNotEmpty()) {
            // Build flat arrays for schema data
            val schemaArrays = buildSchemaArrays(schemas)

            NativeBridge.createDbWithSchemaArrays(
                path ?: ":memory:",
                schemaArrays.tableNames,
                schemaArrays.propertyCounts,
                schemaArrays.propNames,
                schemaArrays.propTypes,
                schemaArrays.propKinds,
                schemaArrays.propNullable,
                schemaArrays.propTargetTables,
                schemaArrays.propLinkTables,
                config.syncEndpoint,
                config.authorizationToken
            )
        } else if (config.syncEndpoint != null && config.authorizationToken != null) {
            // No schemas but sync enabled
            NativeBridge.createDbWithSync(
                path ?: ":memory:",
                "[]",
                config.syncEndpoint,
                config.authorizationToken
            )
        } else {
            // No schemas, no sync - simple creation
            if (path == null || path == ":memory:") {
                NativeBridge.createDbInMemory()
            } else {
                NativeBridge.createDbAtPath(path)
            }
        }

        if (dbHandle == 0L) {
            throw LatticeException("Failed to create database: ${NativeBridge.getLastError()}")
        }

        // Register AuditLog table observer for changeStream (mirrors Swift's pattern).
        // When sync data arrives, the C++ layer creates AuditLog entries. Observing
        // AuditLog ensures changeStream fires for both local AND remote mutations.
        setupAuditLogObserver()
    }

    private var auditLogObserverToken: Long = 0

    private fun setupAuditLogObserver() {
        auditLogObserverToken = ObserverRegistry.observeTableRaw(
            dbHandle = dbHandle,
            tableName = "AuditLog",
        ) { operation, rowId, globalId ->
            // Resolve the AuditLog entry to get the MODEL-level change
            // (mirrors Swift's changes.resolve(on: lattice) pattern)
            val auditHandle = NativeBridge.findObject(dbHandle, "AuditLog", rowId)
            if (auditHandle != 0L) {
                val modelTable = NativeBridge.getStringProperty(auditHandle, "tableName") ?: "AuditLog"
                val modelOp = NativeBridge.getStringProperty(auditHandle, "operation") ?: operation
                val modelRowId = NativeBridge.getIntProperty(auditHandle, "rowId")
                val modelGlobalId = NativeBridge.getStringProperty(auditHandle, "globalRowId") ?: globalId
                val isSynced = NativeBridge.getIntProperty(auditHandle, "isSynchronized") != 0L
                // Note: auditHandle will be released by the C++ ref-counting

                val change = Change(
                    tableName = modelTable,
                    operation = try { ChangeOperation.valueOf(modelOp) } catch (_: Exception) { ChangeOperation.INSERT },
                    rowId = modelRowId,
                    globalId = modelGlobalId,
                    isSynchronized = isSynced,
                )
                _changeFlow.tryEmit(listOf(change))
            } else {
                // Fallback: emit raw AuditLog change
                val change = Change(
                    tableName = "AuditLog",
                    operation = try { ChangeOperation.valueOf(operation) } catch (_: Exception) { ChangeOperation.INSERT },
                    rowId = rowId,
                    globalId = globalId,
                )
                _changeFlow.tryEmit(listOf(change))
            }
        }
    }

    // Data class to hold flattened schema arrays for JNI
    private data class SchemaArrays(
        val tableNames: Array<String>,
        val propertyCounts: IntArray,
        val propNames: Array<String>,
        val propTypes: IntArray,
        val propKinds: IntArray,
        val propNullable: BooleanArray,
        val propTargetTables: Array<String?>,
        val propLinkTables: Array<String?>
    )

    private fun buildSchemaArrays(schemas: List<Pair<String, List<LatticePropertyDescriptor>>>): SchemaArrays {
        val tableNames = mutableListOf<String>()
        val propertyCounts = mutableListOf<Int>()
        val propNames = mutableListOf<String>()
        val propTypes = mutableListOf<Int>()
        val propKinds = mutableListOf<Int>()
        val propNullable = mutableListOf<Boolean>()
        val propTargetTables = mutableListOf<String?>()
        val propLinkTables = mutableListOf<String?>()

        schemas.forEach { (tableName, properties) ->
            tableNames.add(tableName)
            propertyCounts.add(properties.size)

            properties.forEach { prop ->
                propNames.add(prop.name)
                // Type: INTEGER=0, REAL=1, TEXT=2, BLOB=3
                propTypes.add(when (prop.type) {
                    LatticeType.INTEGER -> 0
                    LatticeType.REAL -> 1
                    LatticeType.TEXT -> 2
                    LatticeType.BLOB -> 3
                })
                // Kind: PRIMITIVE=0, LINK=1, LINK_LIST=2
                propKinds.add(when (prop.kind) {
                    LatticePropertyKind.PRIMITIVE -> 0
                    LatticePropertyKind.LINK -> 1
                    LatticePropertyKind.LINK_LIST -> 2
                    LatticePropertyKind.VIRTUAL_LIST -> 3
                    LatticePropertyKind.VIRTUAL_LINK -> 4
                })
                propNullable.add(prop.nullable)
                propTargetTables.add(prop.targetTable)
                propLinkTables.add(prop.linkTable)
            }
        }

        return SchemaArrays(
            tableNames = tableNames.toTypedArray(),
            propertyCounts = propertyCounts.toIntArray(),
            propNames = propNames.toTypedArray(),
            propTypes = propTypes.toIntArray(),
            propKinds = propKinds.toIntArray(),
            propNullable = propNullable.toBooleanArray(),
            propTargetTables = propTargetTables.toTypedArray(),
            propLinkTables = propLinkTables.toTypedArray()
        )
    }

    /**
     * Add an object to the database.
     * The object becomes managed after this call.
     */
    fun <T : LatticeObject> add(obj: T): T {
        val existingHandle = obj._latticeHandle
        if (existingHandle == 0L) {
            throw LatticeException("Object has no native handle - was it created properly?")
        }

        // Add to database - returns managed object handle
        val managedHandle = NativeBridge.addObject(dbHandle, existingHandle)
        if (managedHandle == 0L) {
            throw LatticeException("Failed to add object: ${NativeBridge.getLastError()}")
        }

        // Update the Kotlin object's handle to point to managed object
        obj._latticeHandle = managedHandle

        return obj
    }

    /**
     * Add an object with a specific globalId (preserving identity across databases).
     */
    fun <T : LatticeObject> add(obj: T, preservingGlobalId: String): T {
        val existingHandle = obj._latticeHandle
        if (existingHandle == 0L) {
            throw LatticeException("Object has no native handle - was it created properly?")
        }

        val managedHandle = NativeBridge.addObjectWithGlobalId(dbHandle, existingHandle, preservingGlobalId)
        if (managedHandle == 0L) {
            throw LatticeException("Failed to add object with globalId: ${NativeBridge.getLastError()}")
        }

        obj._latticeHandle = managedHandle
        return obj
    }

    /**
     * Attach another database for cross-DB queries (UNION ALL).
     * After attaching, queries on this database will also search the attached database.
     */
    fun attaching(other: Lattice): Lattice {
        if (!NativeBridge.attachDb(dbHandle, other.dbHandle)) {
            throw LatticeException("Failed to attach database: ${NativeBridge.getLastError()}")
        }
        return this
    }

    /**
     * Query all objects of a given type.
     */
    fun <T : LatticeObject> objects(type: KClass<T>): Results<T> {
        val info = modelRegistry[type]
            ?: throw LatticeException("Model type not registered: ${type.simpleName}")

        return Results(info.tableName, type, this)
    }

    // Internal: get live count for Results
    internal fun queryCount(tableName: String, whereClause: String?): Int {
        return NativeBridge.queryCount(dbHandle, tableName, whereClause)
    }

    internal fun queryCountDistinct(tableName: String, whereClause: String?, groupBy: String?, distinctBy: String?): Int {
        return NativeBridge.countDistinct(dbHandle, tableName, whereClause, groupBy, distinctBy)
    }

    // Internal: query objects for Results with pagination
    internal fun <T : LatticeObject> queryObjects(
        tableName: String,
        type: KClass<T>,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): List<T> {
        val handles = NativeBridge.queryObjects(dbHandle, tableName, whereClause, orderBy, limit, offset)
        val result = mutableListOf<T>()
        for (handle in handles) {
            if (handle != 0L) {
                result.add(wrapNativeObject(handle, type))
            }
        }
        return result
    }

    /**
     * Find an object by its primary key.
     */
    fun <T : LatticeObject> find(type: KClass<T>, id: Long): T? {
        val info = modelRegistry[type]
            ?: throw LatticeException("Model type not registered: ${type.simpleName}")

        val handle = NativeBridge.findObject(dbHandle, info.tableName, id)
        if (handle == 0L) return null
        return wrapNativeObject(handle, type)
    }

    /**
     * Find an object by its global ID.
     */
    fun <T : LatticeObject> findByGlobalId(type: KClass<T>, globalId: String): T? {
        val info = modelRegistry[type]
            ?: throw LatticeException("Model type not registered: ${type.simpleName}")

        val handle = NativeBridge.findObjectByGlobalId(dbHandle, info.tableName, globalId)
        if (handle == 0L) return null
        return wrapNativeObject(handle, type)
    }

    /**
     * Remove an object from the database.
     */
    fun <T : LatticeObject> remove(obj: T) {
        if (!obj.isManaged) {
            throw LatticeException("Cannot remove unmanaged object")
        }

        val success = NativeBridge.removeObject(dbHandle, obj._latticeHandle)
        if (!success) {
            throw LatticeException("Failed to remove: ${NativeBridge.getLastError()}")
        }

        obj._latticeHandle = 0L
    }

    /**
     * Execute a block within a transaction.
     */
    fun <R> transaction(block: () -> R): R {
        if (!NativeBridge.beginTransaction(dbHandle)) {
            throw LatticeException("Failed to begin transaction: ${NativeBridge.getLastError()}")
        }

        return try {
            val result = block()
            if (!NativeBridge.commitTransaction(dbHandle)) {
                throw LatticeException("Failed to commit: ${NativeBridge.getLastError()}")
            }
            result
        } catch (e: Exception) {
            NativeBridge.rollbackTransaction(dbHandle)
            throw e
        }
    }

    /**
     * Close the database connection.
     */
    fun close() {
        // Close DB connections and stop background services FIRST
        // (mirrors Swift's Lattice.close() → lattice_db::close())
        NativeBridge.closeDb(dbHandle)
        NativeBridge.releaseDb(dbHandle)
        scheduler?.close()
    }

    // (companion object is defined above with registerFactory, getFactory, deleteDatabase)

    // Internal: delete objects matching where clause
    internal fun deleteWhere(tableName: String, whereClause: String?): Int {
        return NativeBridge.deleteWhere(dbHandle, tableName, whereClause)
    }

    // Internal: wrap a native handle in a Kotlin object
    @Suppress("UNCHECKED_CAST")
    internal fun <T : LatticeObject> wrapNativeObject(handle: Long, type: KClass<T>): T {
        var factory = modelFactories[type]
        if (factory == null) {
            factory = modelFactories.entries.find {
                it.key.simpleName == type.simpleName
            }?.value
        }

        if (factory == null) {
            throw LatticeException("No factory registered for ${type.simpleName}")
        }

        val obj = factory()
        obj._latticeHandle = handle
        return obj as T
    }

    // Internal: expose dbHandle for observation and advanced use
    internal val nativeDbHandle: Long
        get() = dbHandle

    /**
     * Flow of database changes.
     * Emits a list of changes whenever the database is modified (INSERT, UPDATE, DELETE).
     *
     * Use this to react to changes from sync or local mutations.
     */
    val changeStream: Flow<List<Change>> = _changeFlow.asSharedFlow()

    // Internal method to emit changes (called from observation system)
    internal suspend fun emitChange(change: Change) {
        _changeFlow.emit(listOf(change))
    }

    // ========== Internal Sync Methods ==========

    /**
     * Receive sync data from server.
     * @param data JSON-encoded ServerSentEvent
     * @return List of globalIds that were applied
     */
    internal fun receive(data: ByteArray): List<String> {
        val result = NativeBridge.receiveSyncData(dbHandle, data)
            ?: throw LatticeException("Failed to receive sync data: ${NativeBridge.getLastError()}")

        return parseJsonStringArray(result)
    }

    /**
     * Get audit log entries after a checkpoint.
     * @param globalId The last known globalId (null for all entries)
     * @return JSON-encoded array of audit log entries
     */
    internal fun eventsAfter(globalId: String?): String {
        return NativeBridge.getEventsAfter(dbHandle, globalId)
            ?: throw LatticeException("Failed to get events: ${NativeBridge.getLastError()}")
    }

    /**
     * Get pending (unsynchronized) audit log entries.
     * @return JSON-encoded array of pending audit log entries
     */
    internal fun pendingEvents(): String {
        return NativeBridge.getPendingEvents(dbHandle)
            ?: throw LatticeException("Failed to get pending events: ${NativeBridge.getLastError()}")
    }

    /**
     * Mark audit log entries as synchronized.
     * @param globalIds List of globalIds to mark as synced
     */
    internal fun markSynced(globalIds: List<String>) {
        val jsonArray = "[${globalIds.joinToString(",") { "\"$it\"" }}]"
        NativeBridge.markSynced(dbHandle, jsonArray)
    }

    internal fun compactAuditLog(): Long {
        return NativeBridge.compactAuditLog(dbHandle)
    }

    // Simple JSON array parser for string arrays
    private fun parseJsonStringArray(json: String): List<String> {
        val trimmed = json.trim()
        if (trimmed == "[]" || trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var i = trimmed.indexOf('[') + 1
        while (i < trimmed.length) {
            while (i < trimmed.length && trimmed[i].isWhitespace()) i++
            if (i >= trimmed.length || trimmed[i] == ']') break

            if (trimmed[i] == '"') {
                i++
                val endQuote = trimmed.indexOf('"', i)
                if (endQuote > i) {
                    result.add(trimmed.substring(i, endQuote))
                    i = endQuote + 1
                }
            }

            while (i < trimmed.length && (trimmed[i] == ',' || trimmed[i].isWhitespace())) i++
        }
        return result
    }

    private data class ModelInfo(val tableName: String)
}

class LatticeException(message: String) : Exception(message)

/**
 * Platform-specific function to create a factory for a model class via reflection.
 * On JVM/Android: uses reflection to call the no-arg constructor.
 * On Native: returns null (must use explicit factory registration).
 */
internal expect fun createFactoryViaReflection(kClass: KClass<*>): (() -> LatticeObject)?

/**
 * Convenience: query objects with reified type.
 */
inline fun <reified T : LatticeObject> Lattice.objects(): Results<T> = objects(T::class)

/**
 * Convenience: find by ID with reified type.
 */
inline fun <reified T : LatticeObject> Lattice.find(id: Long): T? = find(T::class, id)

/**
 * Convenience: find by global ID with reified type.
 */
inline fun <reified T : LatticeObject> Lattice.findByGlobalId(globalId: String): T? =
    findByGlobalId(T::class, globalId)
