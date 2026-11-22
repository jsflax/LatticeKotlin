package com.lattice

import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
 * Full database, schema-migration, and WebSocket sync configuration for [Lattice].
 */
data class LatticeConfiguration(
    val path: String? = null,
    val syncEndpoint: String? = null,
    val authorizationToken: String? = null,
    val targetSchemaVersion: Int = 1,
    val migrations: Map<Int, Migration> = emptyMap(),
    val busyTimeoutMs: Int = 30_000,
    val auditRetentionSeconds: Long? = null,
    val syncIsObserver: Boolean = false,
    val uploadsEnabled: Boolean = true,
    val syncAuthMode: SyncAuthMode = SyncAuthMode.BEARER_TOKEN,
    val syncFilter: SyncFilter? = null,
    val websocketSyncIdentity: String? = null,
    val pinnedServerCertificateSha256: String? = null,
    val createIfMissing: Boolean = true,
    val isReadOnly: Boolean = false,
    val chunkSize: Long? = null,
    val maxReconnectAttempts: Int? = null,
    val uploadCoalesceMs: Int? = null,
    val backgroundSyncManaged: Boolean = false,
    val backgroundSyncMode: BackgroundSyncMode =
        if (backgroundSyncManaged) BackgroundSyncMode.MANAGED else BackgroundSyncMode.AUTOMATIC
) {
    val effectiveBackgroundSyncManaged: Boolean
        get() = backgroundSyncManaged || backgroundSyncMode == BackgroundSyncMode.MANAGED

    val effectiveSyncAutostart: Boolean
        get() = !effectiveBackgroundSyncManaged && backgroundSyncMode != BackgroundSyncMode.DEFERRED
    init {
        require(
            syncEndpoint == null ||
                syncAuthMode == SyncAuthMode.TRANSPORT_MANAGED ||
                !authorizationToken.isNullOrBlank()
        ) {
            "LatticeConfiguration: non-blank authorizationToken is required when syncEndpoint is set in BEARER_TOKEN mode"
        }
    }
}

typealias Configuration = LatticeConfiguration

/**
 * Main entry point for Lattice database operations.
 *
 * Implements [AutoCloseable] for deterministic resource management; never closes
 * implicitly on GC so active WebSocket sync channels stay alive across ART GC sweeps.
 */
class Lattice(
    private val config: LatticeConfiguration,
    private val scheduler: LatticeScheduler?,
    vararg modelTypes: KClass<out LatticeObject>
) : AutoCloseable {

    constructor(path: String?, vararg modelTypes: KClass<out LatticeObject>) :
        this(LatticeConfiguration(path = path), null, *modelTypes)

    constructor(path: String?, scheduler: LatticeScheduler, vararg modelTypes: KClass<out LatticeObject>) :
        this(LatticeConfiguration(path = path), scheduler, *modelTypes)

    constructor(config: LatticeConfiguration, vararg modelTypes: KClass<out LatticeObject>) :
        this(config, null, *modelTypes)

    constructor(config: LatticeConfigurationWithMigration, vararg modelTypes: KClass<out LatticeObject>) :
        this(
            LatticeConfiguration(
                path = config.path,
                syncEndpoint = config.syncEndpoint,
                authorizationToken = config.authorizationToken,
                targetSchemaVersion = config.targetSchemaVersion,
                migrations = config.migrations
            ),
            null,
            *modelTypes
        )

    private val registeredTypes = modelTypes.toList()
    val registeredModelTypes: List<KClass<out LatticeObject>> get() = registeredTypes

    private val dbHandle: Long
    private val modelRegistry = mutableMapOf<KClass<*>, ModelInfo>()
    internal val arena = NativeHandleArena()

    val configuration: LatticeConfiguration get() = config

    val isClosed: Boolean get() = arena.isClosed

    private val _changeFlow = MutableSharedFlow<List<Change>>(extraBufferCapacity = 64)

    val isSyncConnected: Boolean
        get() = !isClosed && NativeBridge.isSyncConnected(dbHandle)

    val syncProgress: SyncProgress
        get() {
            if (isClosed) return SyncProgress()
            val arr = NativeBridge.getSyncProgress(dbHandle)
            return if (arr.size >= 4) {
                SyncProgress(arr[0], arr[1], arr[2], arr[3])
            } else SyncProgress()
        }

    /**
     * Cold Flow observing WebSocket sync connection state (`true` when connected, `false` when disconnected).
     */
    val syncStateFlow: Flow<Boolean>
        get() = callbackFlow {
            val owner = this@Lattice
            if (owner.isClosed) {
                trySend(false)
                close()
                return@callbackFlow
            }
            trySend(owner.isSyncConnected)
            val token = NativeBridge.observeSyncState(owner.dbHandle) { connected ->
                trySend(connected)
            }
            awaitClose {
                if (!owner.isClosed && token != 0L) {
                    NativeBridge.removeSyncObserver(owner.dbHandle, token)
                }
            }
        }

    /**
     * Cold Flow observing WebSocket/IPC sync progress snapshots.
     */
    val syncProgressFlow: Flow<SyncProgress>
        get() = callbackFlow {
            val owner = this@Lattice
            if (owner.isClosed) {
                close()
                return@callbackFlow
            }
            val token = NativeBridge.observeSyncProgress(owner.dbHandle) { pending, total, acked, received ->
                trySend(SyncProgress(pending, total, acked, received))
            }
            awaitClose {
                if (!owner.isClosed && token != 0L) {
                    NativeBridge.removeSyncObserver(owner.dbHandle, token)
                }
            }
        }

    /**
     * Cold Flow observing sync transport / protocol errors.
     */
    val syncErrorFlow: Flow<String>
        get() = callbackFlow {
            val owner = this@Lattice
            if (owner.isClosed) {
                close()
                return@callbackFlow
            }
            val token = NativeBridge.observeSyncError(owner.dbHandle) { msg ->
                trySend(msg)
            }
            awaitClose {
                if (!owner.isClosed && token != 0L) {
                    NativeBridge.removeSyncObserver(owner.dbHandle, token)
                }
            }
        }

    suspend fun awaitSyncConnected(timeoutMs: Long = 30_000) {
        if (config.syncEndpoint == null || isSyncConnected) return
        withTimeout(timeoutMs) {
            while (!isSyncConnected) {
                delay(50)
            }
        }
    }

    /**
     * Whether this [Lattice] store was opened under SDK/application-managed background sync lifecycle.
     */
    val isBackgroundSyncManaged: Boolean
        get() = !isClosed && NativeBridge.isBackgroundSyncManaged(dbHandle)

    /**
     * Active native background sync generation (`0` when suspended or unmanaged).
     */
    val currentBackgroundSyncGeneration: Long
        get() = if (isClosed) 0L else NativeBridge.currentBackgroundSyncGeneration(dbHandle)

    /**
     * Performs one bounded finite WebSocket sync pass off the caller thread via [Dispatchers.IO].
     *
     * Uploads pending local outbox entries and applies initial server catch-up replay within
     * [maximumDurationSeconds], settling any owned private transport and SQLite resources before returning.
     * Cancelling the calling coroutine immediately signals native `cancel_background_sync` and awaits
     * full native settlement before throwing [kotlinx.coroutines.CancellationException].
     */
    suspend fun syncOnce(maximumDurationSeconds: Double = 20.0): SyncOnceResult {
        if (!maximumDurationSeconds.isFinite() || maximumDurationSeconds <= 0.0) {
            throw LatticeException("maximumDurationSeconds must be finite and greater than zero")
        }
        if (isClosed) return SyncOnceResult.UNAVAILABLE
        coroutineContext.ensureActive()
        if (config.syncEndpoint != null) {
            ensureNetworkFactoryRegistered()
        }

        val cancelHandle = NativeBridge.createSyncCancellation()
        if (cancelHandle == 0L) {
            throw LatticeException("Failed to allocate sync cancellation token")
        }
        return try {
            val (code, nativeError) = withContext(Dispatchers.IO) {
                if (isClosed) {
                    SyncOnceResult.UNAVAILABLE.ordinal to null
                } else {
                    val completionFlag = object {
                        @Volatile
                        var finishedNormally: Boolean = false
                    }
                    val cancelWatcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            if (!completionFlag.finishedNormally) {
                                NativeBridge.cancelSyncCancellation(cancelHandle)
                                if (!isClosed) {
                                    NativeBridge.cancelBackgroundSync(dbHandle)
                                }
                            }
                        }
                    }
                    try {
                        val c = NativeBridge.syncOnce(dbHandle, maximumDurationSeconds, cancelHandle)
                        val err = if (c < 0) NativeBridge.getLastError() else null
                        c to err
                    } finally {
                        completionFlag.finishedNormally = true
                        cancelWatcher.cancel()
                    }
                }
            }
            coroutineContext.ensureActive()
            when (code) {
                0 -> SyncOnceResult.COMPLETED
                1 -> SyncOnceResult.BUDGET_EXHAUSTED
                2 -> SyncOnceResult.ALREADY_RUNNING
                3 -> SyncOnceResult.UNAVAILABLE
                else -> throw LatticeException(
                    nativeError ?: "Finite syncOnce failed (status=$code)"
                )
            }
        } finally {
            NativeBridge.releaseSyncCancellation(cancelHandle)
        }
    }

    /**
     * Suspends active background/foreground WebSocket sync on this store and settles any managed
     * generation while keeping the primary [Lattice] store open in memory.
     */
    fun suspendBackgroundSync() {
        if (isClosed) return
        if (!NativeBridge.suspendBackgroundSync(dbHandle)) {
            throw LatticeException(
                NativeBridge.getLastError() ?: "Failed to suspend background sync"
            )
        }
    }

    /**
     * Resumes continuous foreground WebSocket sync (and managed maintenance) on this store.
     */
    fun resumeBackgroundSync() {
        if (isClosed) return
        if (config.syncEndpoint != null) {
            ensureNetworkFactoryRegistered()
        }
        if (!NativeBridge.resumeBackgroundSync(dbHandle)) {
            throw LatticeException(
                NativeBridge.getLastError() ?: "Failed to resume background sync"
            )
        }
    }

    /**
     * Requests cancellation of any currently active managed/finite background sync generation on this store.
     */
    fun cancelBackgroundSync(): Boolean {
        if (isClosed) return false
        return NativeBridge.cancelBackgroundSync(dbHandle)
    }

    companion object {
        private val registryLock = ArenaLock()
        private val modelFactories = mutableMapOf<KClass<*>, () -> LatticeObject>()
        private val hydrationFactories = mutableMapOf<KClass<*>, (Long) -> LatticeObject>()

        fun <T : LatticeObject> registerFactory(kClass: KClass<T>, factory: () -> T) {
            registryLock.withLock {
                modelFactories[kClass] = factory
            }
        }

        fun <T : LatticeObject> registerHydrationFactory(kClass: KClass<T>, factory: (Long) -> T) {
            registryLock.withLock {
                hydrationFactories[kClass] = factory
            }
        }

        fun getFactory(kClass: KClass<*>): (() -> LatticeObject)? = registryLock.withLock {
            modelFactories[kClass]
                ?: modelFactories.entries.find { it.key.simpleName == kClass.simpleName }?.value
        }

        fun getHydrationFactory(kClass: KClass<*>): ((Long) -> LatticeObject)? = registryLock.withLock {
            hydrationFactories[kClass]
                ?: hydrationFactories.entries.find { it.key.simpleName == kClass.simpleName }?.value
        }

        fun deleteDatabase(path: String) {
            deleteDatabaseFiles(path)
        }

        fun totalStatementCount(): Long = NativeBridge.totalStatementCount()

        fun threadStatementCount(): Long = NativeBridge.threadStatementCount()
    }

    init {
        if (config.syncEndpoint != null) {
            ensureNetworkFactoryRegistered()
        }

        val schemas = mutableListOf<Pair<String, List<LatticePropertyDescriptor>>>()

        modelTypes.forEach { kClass ->
            ensureFactoriesRegistered(kClass)
            val probe = probeModelMetadata(kClass)
            val tableName = probe?.first
                ?: kClass.simpleName
                ?: throw LatticeException("Model must have a name")
            modelRegistry[kClass] = ModelInfo(tableName)
            if (probe != null) {
                schemas.add(tableName to probe.second)
            }
        }

        val dbPath = config.path ?: ":memory:"
        val schemaArrays = buildSchemaArrays(schemas)

        val rowMigrations = config.migrations.values
            .filter { it.tableName.isNotEmpty() && it.transform != null }
        val migrationTables = rowMigrations.map { it.tableName }.distinct().toTypedArray()
        val transformsByTable = rowMigrations
            .groupBy { it.tableName }
            .mapValues { (_, list) -> list.mapNotNull { it.transform } }

        val migrationCallback: ((String, String) -> String?)? = if (transformsByTable.isEmpty()) {
            null
        } else {
            { tableName, oldJsonStr ->
                val fns = transformsByTable[tableName]
                if (fns.isNullOrEmpty()) {
                    null
                } else {
                    var rowMap = decodeMigrationRow(oldJsonStr)
                    for (fn in fns) {
                        val updates = fn(rowMap)
                        rowMap = rowMap + updates
                    }
                    encodeMigrationRow(rowMap)
                }
            }
        }

        dbHandle = NativeBridge.openDbV2(
            path = dbPath,
            tableNames = schemaArrays.tableNames,
            propertyCounts = schemaArrays.propertyCounts,
            propNames = schemaArrays.propNames,
            propColumnNames = schemaArrays.propColumnNames,
            propTypes = schemaArrays.propTypes,
            propKinds = schemaArrays.propKinds,
            propNullable = schemaArrays.propNullable,
            propTargetTables = schemaArrays.propTargetTables,
            propLinkTables = schemaArrays.propLinkTables,
            propFlags = schemaArrays.propFlags,
            propUnionSpecs = schemaArrays.propUnionSpecs,
            constraintTableNames = schemaArrays.constraintTableNames,
            constraintColumnsJoined = schemaArrays.constraintColumnsJoined,
            constraintAllowsUpsert = schemaArrays.constraintAllowsUpsert,
            schedulerHandle = scheduler?.nativeHandlePtr ?: 0L,
            syncEndpoint = config.syncEndpoint,
            authToken = config.authorizationToken,
            targetSchemaVersion = config.targetSchemaVersion,
            busyTimeoutMs = config.busyTimeoutMs,
            auditRetentionSeconds = config.auditRetentionSeconds ?: -1L,
            syncIsObserver = if (config.syncIsObserver) 1 else -1,
            uploadsEnabled = if (config.uploadsEnabled) -1 else 0,
            syncAuthMode = config.syncAuthMode.code,
            websocketSyncIdentity = config.websocketSyncIdentity,
            pinnedServerCertSha256 = config.pinnedServerCertificateSha256,
            createIfMissing = if (config.createIfMissing) -1 else 0,
            readOnly = if (config.isReadOnly) 1 else -1,
            syncFilterJson = config.syncFilter?.toJson(),
            chunkSize = config.chunkSize ?: 0L,
            maxReconnectAttempts = config.maxReconnectAttempts ?: -1,
            uploadCoalesceMs = config.uploadCoalesceMs ?: -1,
            backgroundSyncManaged = if (config.effectiveBackgroundSyncManaged) 1 else -1,
            syncAutostart = if (config.effectiveSyncAutostart) -1 else 0,
            migrationTables = migrationTables,
            migrationTransform = migrationCallback
        )

        if (dbHandle == 0L) {
            throw LatticeException("Failed to create database: ${NativeBridge.getLastError()}")
        }

        ObserverRegistry.registerHandle(dbHandle)

        if (!config.isReadOnly) {
            setupAuditLogObserver()
        }
    }

    private fun ensureFactoriesRegistered(kClass: KClass<out LatticeObject>) {
        if (getHydrationFactory(kClass) == null) {
            createHydrationFactoryViaReflection(kClass)?.let { hf ->
                registryLock.withLock { hydrationFactories[kClass] = hf }
            }
        }
        if (getFactory(kClass) == null) {
            createFactoryViaReflection(kClass)?.let { f ->
                registryLock.withLock { modelFactories[kClass] = f }
            }
        }
    }

    /**
     * Reads `(_latticeTableName, _latticeSchema)` without leaking any unmanaged native handle!
     */
    private fun probeModelMetadata(kClass: KClass<out LatticeObject>): Pair<String, List<LatticePropertyDescriptor>>? {
        val hf = getHydrationFactory(kClass)
        if (hf != null) {
            val zeroInstance = hf(0L)
            val tn = zeroInstance._latticeTableName
            VirtualModelRegistry.registerTableHydrationFactory(tn, hf)
            getFactory(kClass)?.let { VirtualModelRegistry.registerTableFactory(tn, it) }
            return tn to zeroInstance._latticeSchema
        }
        val f = getFactory(kClass) ?: return null
        val instance = f()
        val tn = instance._latticeTableName
        val schema = instance._latticeSchema
        val transientHandle = instance._latticeHandle
        instance._latticeHandle = 0L
        if (transientHandle != 0L) {
            NativeBridge.releaseObject(transientHandle)
        }
        VirtualModelRegistry.registerTableFactory(tn, f)
        return tn to schema
    }

    private fun setupAuditLogObserver() {
        ObserverRegistry.observeTableRaw(
            dbHandle = dbHandle,
            tableName = "AuditLog"
        ) { operation, rowId, globalId ->
            if (isClosed) return@observeTableRaw
            val auditHandle = NativeBridge.findObject(dbHandle, "AuditLog", rowId)
            if (auditHandle != 0L) {
                val modelTable = NativeBridge.getStringProperty(auditHandle, "tableName") ?: "AuditLog"
                val modelOp = NativeBridge.getStringProperty(auditHandle, "operation") ?: operation
                val modelRowId = NativeBridge.getIntProperty(auditHandle, "rowId")
                val modelGlobalId = NativeBridge.getStringProperty(auditHandle, "globalRowId") ?: globalId
                val isSynced = NativeBridge.getIntProperty(auditHandle, "isSynchronized") != 0L
                NativeBridge.releaseObject(auditHandle)

                val change = Change(
                    tableName = modelTable,
                    operation = try { ChangeOperation.valueOf(modelOp) } catch (_: Exception) { ChangeOperation.INSERT },
                    rowId = modelRowId,
                    globalId = modelGlobalId,
                    isSynchronized = isSynced
                )
                _changeFlow.tryEmit(listOf(change))
            } else {
                val change = Change(
                    tableName = "AuditLog",
                    operation = try { ChangeOperation.valueOf(operation) } catch (_: Exception) { ChangeOperation.INSERT },
                    rowId = rowId,
                    globalId = globalId
                )
                _changeFlow.tryEmit(listOf(change))
            }
        }
    }

    private class SchemaArrays(
        val tableNames: Array<String>,
        val propertyCounts: IntArray,
        val propNames: Array<String>,
        val propColumnNames: Array<String?>,
        val propTypes: IntArray,
        val propKinds: IntArray,
        val propNullable: BooleanArray,
        val propTargetTables: Array<String?>,
        val propLinkTables: Array<String?>,
        val propFlags: IntArray,
        val propUnionSpecs: Array<String?>,
        val constraintTableNames: Array<String>,
        val constraintColumnsJoined: Array<String>,
        val constraintAllowsUpsert: BooleanArray
    )

    private fun buildSchemaArrays(schemas: List<Pair<String, List<LatticePropertyDescriptor>>>): SchemaArrays {
        val tableNames = mutableListOf<String>()
        val propertyCounts = mutableListOf<Int>()
        val propNames = mutableListOf<String>()
        val propColumnNames = mutableListOf<String?>()
        val propTypes = mutableListOf<Int>()
        val propKinds = mutableListOf<Int>()
        val propNullable = mutableListOf<Boolean>()
        val propTargetTables = mutableListOf<String?>()
        val propLinkTables = mutableListOf<String?>()
        val propFlags = mutableListOf<Int>()
        val propUnionSpecs = mutableListOf<String?>()
        val constraintTableNames = mutableListOf<String>()
        val constraintColumnsJoined = mutableListOf<String>()
        val constraintAllowsUpsert = mutableListOf<Boolean>()

        schemas.forEach { (tableName, properties) ->
            tableNames.add(tableName)
            propertyCounts.add(properties.size)

            properties.forEach { prop ->
                propNames.add(prop.name)
                propColumnNames.add(prop.columnName)
                propTypes.add(prop.type.ordinal)
                propKinds.add(prop.kind.ordinal)
                propNullable.add(prop.nullable)
                propTargetTables.add(prop.targetTable)
                propLinkTables.add(prop.linkTable)
                propFlags.add(
                    (if (prop.isUnique && prop.compoundedWith.isEmpty()) LatticeNative.FLAG_UNIQUE else 0) or
                        (if (prop.isIndexed) LatticeNative.FLAG_INDEXED else 0) or
                        (if (prop.isFullText) LatticeNative.FLAG_FULL_TEXT else 0) or
                        (if (prop.isVector) LatticeNative.FLAG_VECTOR else 0) or
                        (if (prop.isNoHistory) LatticeNative.FLAG_NO_HISTORY else 0) or
                        (if (prop.isGeoBounds) LatticeNative.FLAG_GEO_BOUNDS else 0) or
                        (if (prop.allowsUpsert) LatticeNative.FLAG_ALLOWS_UPSERT else 0)
                )
                propUnionSpecs.add(prop.unionDescriptor?.let { LatticeNative.encodeUnionSpec(it) })

                if (prop.isUnique && (prop.compoundedWith.isNotEmpty() || prop.allowsUpsert)) {
                    val cols = listOf(prop.name) + prop.compoundedWith
                    constraintTableNames.add(tableName)
                    constraintColumnsJoined.add(cols.joinToString(","))
                    constraintAllowsUpsert.add(prop.allowsUpsert)
                }
            }
        }

        return SchemaArrays(
            tableNames = tableNames.toTypedArray(),
            propertyCounts = propertyCounts.toIntArray(),
            propNames = propNames.toTypedArray(),
            propColumnNames = propColumnNames.toTypedArray(),
            propTypes = propTypes.toIntArray(),
            propKinds = propKinds.toIntArray(),
            propNullable = propNullable.toBooleanArray(),
            propTargetTables = propTargetTables.toTypedArray(),
            propLinkTables = propLinkTables.toTypedArray(),
            propFlags = propFlags.toIntArray(),
            propUnionSpecs = propUnionSpecs.toTypedArray(),
            constraintTableNames = constraintTableNames.toTypedArray(),
            constraintColumnsJoined = constraintColumnsJoined.toTypedArray(),
            constraintAllowsUpsert = constraintAllowsUpsert.toBooleanArray()
        )
    }

    fun <T : LatticeObject> add(obj: T): T {
        if (isClosed) throw LatticeClosedException()
        val existingHandle = obj._latticeHandle
        if (existingHandle == 0L) {
            throw LatticeException("Object has no native handle - was it created properly?")
        }
        val managedHandle = NativeBridge.addObject(dbHandle, existingHandle)
        if (managedHandle == 0L) {
            throw LatticeException("Failed to add object: ${NativeBridge.getLastError()}")
        }
        // `addObject` returns a +1 retained managed handle (which aliases `existingHandle`);
        // release the pre-add unmanaged +1 reference so the arena holds the sole +1 reference.
        obj._latticeHandle = 0L
        NativeBridge.releaseObject(existingHandle)
        return arena.adoptObject(obj, managedHandle)
    }

    fun <T : LatticeObject> add(obj: T, preservingGlobalId: String): T {
        if (isClosed) throw LatticeClosedException()
        val existingHandle = obj._latticeHandle
        if (existingHandle == 0L) {
            throw LatticeException("Object has no native handle - was it created properly?")
        }
        val managedHandle = NativeBridge.addObjectWithGlobalId(dbHandle, existingHandle, preservingGlobalId)
        if (managedHandle == 0L) {
            throw LatticeException("Failed to add object with globalId: ${NativeBridge.getLastError()}")
        }
        obj._latticeHandle = 0L
        NativeBridge.releaseObject(existingHandle)
        return arena.adoptObject(obj, managedHandle)
    }

    fun attaching(other: Lattice): Lattice {
        if (!NativeBridge.attachDb(dbHandle, other.dbHandle)) {
            throw LatticeException("Failed to attach database: ${NativeBridge.lastAttachError(dbHandle) ?: NativeBridge.getLastError()}")
        }
        return this
    }

    fun detach(other: Lattice) {
        if (!NativeBridge.detachDb(dbHandle, other.dbHandle)) {
            throw LatticeException("Failed to detach database: ${NativeBridge.lastAttachError(dbHandle) ?: NativeBridge.getLastError()}")
        }
    }

    fun <T : LatticeObject> objects(type: KClass<T>): Results<T> {
        val info = modelRegistry[type]
            ?: modelRegistry.entries.find { it.key.simpleName == type.simpleName }?.value
            ?: throw LatticeException("Model type not registered: ${type.simpleName}")
        return Results(info.tableName, type, this)
    }

    internal fun queryCount(tableName: String, whereClause: String?): Int {
        if (isClosed) return 0
        return NativeBridge.queryCount(dbHandle, tableName, whereClause)
    }

    internal fun queryCountDistinct(tableName: String, whereClause: String?, groupBy: String?, distinctBy: String?): Int {
        if (isClosed) return 0
        return NativeBridge.countDistinct(dbHandle, tableName, whereClause, groupBy, distinctBy)
    }

    internal fun <T : LatticeObject> queryObjects(
        tableName: String,
        type: KClass<T>,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): List<T> {
        if (isClosed) return emptyList()
        val handles = NativeBridge.queryObjects(dbHandle, tableName, whereClause, orderBy, limit, offset)
        return wrapNativeHandles(handles, type)
    }

    internal fun <T : LatticeObject> queryDistinctObjects(
        tableName: String,
        type: KClass<T>,
        distinctBy: String?,
        whereClause: String?,
        orderBy: String?,
        limit: Int,
        offset: Long
    ): List<T> {
        if (isClosed) return emptyList()
        val resHandle = NativeBridge.queryDistinct(dbHandle, tableName, distinctBy, whereClause, orderBy, limit.toLong(), offset)
        if (resHandle == 0L) return emptyList()
        return try {
            val count = NativeBridge.resultsCount(resHandle)
            val handles = LongArray(count) { idx -> NativeBridge.resultsGet(resHandle, idx) }
            wrapNativeHandles(handles, type)
        } finally {
            NativeBridge.resultsFree(resHandle)
        }
    }

    internal fun <T : LatticeObject> queryNearest(
        tableName: String,
        type: KClass<T>,
        columnName: String,
        queryVectorBytes: ByteArray,
        k: Int,
        metric: DistanceMetric,
        whereClause: String?
    ): List<NearestMatch<T>> {
        if (isClosed || k <= 0) return emptyList()
        val distances = DoubleArray(k)
        val globalIds = NativeBridge.queryNearest(
            dbHandle = dbHandle,
            tableName = tableName,
            columnName = columnName,
            queryVectorBytes = queryVectorBytes,
            k = k,
            metricOrdinal = metric.ordinal,
            whereClause = whereClause,
            outDistances = distances
        )
        val out = ArrayList<NearestMatch<T>>(globalIds.size)
        for (i in globalIds.indices) {
            val gid = globalIds[i]
            val obj = findByGlobalId(type, gid)
            if (obj != null) {
                out.add(NearestMatch(obj, distances[i]))
            }
        }
        return out
    }

    internal fun <T : LatticeObject> queryWithinBounds(
        tableName: String,
        type: KClass<T>,
        columnName: String,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        whereClause: String?,
        limit: Long
    ): List<T> {
        if (isClosed) return emptyList()
        val handles = NativeBridge.queryWithinBounds(
            dbHandle, tableName, columnName, minLat, maxLat, minLon, maxLon, whereClause, limit
        )
        return wrapNativeHandles(handles, type)
    }

    private fun <T : LatticeObject> wrapNativeHandles(handles: LongArray, type: KClass<T>): List<T> {
        val result = ArrayList<T>(handles.size)
        for (i in handles.indices) {
            val handle = handles[i]
            if (handle == 0L) continue
            try {
                result.add(wrapNativeObject(handle, type))
            } catch (e: LatticeClosedException) {
                // Release remaining handles in this batch if close() raced mid-hydration
                for (j in (i + 1) until handles.size) {
                    if (handles[j] != 0L) NativeBridge.releaseObject(handles[j])
                }
                return emptyList()
            }
        }
        return result
    }

    fun <T : LatticeObject> find(type: KClass<T>, id: Long): T? {
        if (isClosed) return null
        val info = modelRegistry[type]
            ?: modelRegistry.entries.find { it.key.simpleName == type.simpleName }?.value
            ?: throw LatticeException("Model type not registered: ${type.simpleName}")
        val handle = NativeBridge.findObject(dbHandle, info.tableName, id)
        if (handle == 0L) return null
        return try { wrapNativeObject(handle, type) } catch (_: LatticeClosedException) { null }
    }

    fun <T : LatticeObject> findByGlobalId(type: KClass<T>, globalId: String): T? {
        if (isClosed) return null
        val info = modelRegistry[type]
            ?: modelRegistry.entries.find { it.key.simpleName == type.simpleName }?.value
            ?: throw LatticeException("Model type not registered: ${type.simpleName}")
        val handle = NativeBridge.findObjectByGlobalId(dbHandle, info.tableName, globalId)
        if (handle == 0L) return null
        return try { wrapNativeObject(handle, type) } catch (_: LatticeClosedException) { null }
    }

    fun <T : LatticeObject> remove(obj: T) {
        if (isClosed) throw LatticeClosedException()
        if (!obj.isManaged) {
            throw LatticeException("Cannot remove unmanaged object")
        }
        val handle = obj._latticeHandle
        val success = NativeBridge.removeObject(dbHandle, handle)
        if (!success) {
            throw LatticeException("Failed to remove: ${NativeBridge.getLastError()}")
        }
        arena.reapObject(handle)
        obj._latticeHandle = 0L
    }

    fun <R> transaction(block: () -> R): R {
        if (isClosed) throw LatticeClosedException()
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

    // ========== Maintenance & Sync Control ==========

    fun pruneAuditLog(retentionSeconds: Long): Long {
        if (isClosed) return 0L
        return NativeBridge.pruneAuditLog(dbHandle, retentionSeconds)
    }

    fun recordAuditWatermark(): Boolean {
        if (isClosed) return false
        return NativeBridge.recordAuditWatermark(dbHandle)
    }

    fun reclaimSpace(): Long {
        if (isClosed) return 0L
        return NativeBridge.reclaimSpace(dbHandle)
    }

    fun checkpoint(truncate: Boolean = false): Boolean {
        if (isClosed) return false
        return NativeBridge.checkpointDb(dbHandle, truncate)
    }

    fun updateSyncFilter(filter: SyncFilter?) {
        if (isClosed) return
        val ok = if (filter == null) {
            NativeBridge.clearSyncFilter(dbHandle)
        } else {
            NativeBridge.setSyncFilter(dbHandle, filter.toJson())
        }
        if (!ok) {
            throw LatticeException("Failed to update sync filter: ${NativeBridge.getLastError()}")
        }
    }

    override fun close() {
        if (!ObserverRegistry.invalidateObservers(dbHandle)) return
        arena.closeAndDrain()
        NativeBridge.closeDb(dbHandle)
        NativeBridge.releaseDb(dbHandle)
        scheduler?.close()
    }

    internal fun deleteWhere(tableName: String, whereClause: String?): Int {
        if (isClosed) return 0
        return NativeBridge.deleteWhere(dbHandle, tableName, whereClause)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : LatticeObject> wrapNativeObject(handle: Long, type: KClass<T>): T {
        val hydration = getHydrationFactory(type)
        if (hydration != null) {
            val obj = hydration(handle) as T
            return arena.adoptObject(obj, handle)
        }
        val factory = getFactory(type)
            ?: throw LatticeException("No factory registered for ${type.simpleName}")
        val obj = factory() as T
        return arena.adoptObject(obj, handle)
    }

    internal val nativeDbHandle: Long
        get() = dbHandle

    val changeStream: Flow<List<Change>> = _changeFlow.asSharedFlow()

    internal fun receive(data: ByteArray): List<String> {
        val result = NativeBridge.receiveSyncData(dbHandle, data)
            ?: throw LatticeException("Failed to receive sync data: ${NativeBridge.getLastError()}")
        return parseJsonStringArray(result)
    }

    internal fun eventsAfter(globalId: String?): String =
        NativeBridge.getEventsAfter(dbHandle, globalId)
            ?: throw LatticeException("Failed to get events: ${NativeBridge.getLastError()}")

    internal fun pendingEvents(): String =
        NativeBridge.getPendingEvents(dbHandle)
            ?: throw LatticeException("Failed to get pending events: ${NativeBridge.getLastError()}")

    internal fun markSynced(globalIds: List<String>) {
        val jsonArray = "[${globalIds.joinToString(",") { "\"$it\"" }}]"
        NativeBridge.markSynced(dbHandle, jsonArray)
    }

    internal fun compactAuditLog(): Long = NativeBridge.compactAuditLog(dbHandle)

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
                if (endQuote >= i) {
                    result.add(trimmed.substring(i, endQuote))
                    i = endQuote + 1
                } else break
            } else i++
            while (i < trimmed.length && (trimmed[i] == ',' || trimmed[i].isWhitespace())) i++
        }
        return result
    }

    private fun decodeMigrationRow(jsonStr: String): Map<String, Any?> {
        val obj = Json.parseToJsonElement(jsonStr) as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) ->
            when (v) {
                is JsonNull -> null
                is JsonPrimitive -> when {
                    v.isString -> v.content
                    v.booleanOrNull != null -> v.booleanOrNull
                    v.longOrNull != null -> v.longOrNull
                    v.doubleOrNull != null -> v.doubleOrNull
                    else -> v.content
                }
                else -> v.toString()
            }
        }
    }

    private fun encodeMigrationRow(row: Map<String, Any?>): String =
        buildJsonObject {
            row.forEach { (k, v) ->
                when (v) {
                    null -> put(k, JsonNull)
                    is Boolean -> put(k, JsonPrimitive(v))
                    is Int -> put(k, JsonPrimitive(v))
                    is Long -> put(k, JsonPrimitive(v))
                    is Float -> put(k, JsonPrimitive(v))
                    is Double -> put(k, JsonPrimitive(v))
                    is Number -> put(k, JsonPrimitive(v.toDouble()))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }.toString()

    private data class ModelInfo(val tableName: String)
}

open class LatticeException(message: String) : Exception(message)

internal expect fun createFactoryViaReflection(kClass: KClass<*>): (() -> LatticeObject)?
internal expect fun createHydrationFactoryViaReflection(kClass: KClass<*>): ((Long) -> LatticeObject)?
internal expect fun deleteDatabaseFiles(path: String)

inline fun <reified T : LatticeObject> Lattice.objects(): Results<T> = objects(T::class)
inline fun <reified T : LatticeObject> Lattice.find(id: Long): T? = find(T::class, id)
inline fun <reified T : LatticeObject> Lattice.findByGlobalId(globalId: String): T? =
    findByGlobalId(T::class, globalId)
