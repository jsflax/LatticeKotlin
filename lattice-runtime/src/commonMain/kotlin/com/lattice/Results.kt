package com.lattice

import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A live query result set.
 */
class Results<T : LatticeObject>(
    internal val tableName: String,
    internal val type: KClass<T>,
    internal val lattice: Lattice,
    internal val whereClause: String? = null,
    internal val orderByClause: String? = null,
    internal val limitValue: Int? = null,
    internal val offsetValue: Int? = null,
    internal val distinctBy: String? = null,
    internal val groupBy: String? = null
) : Iterable<T> {

    val count: Int
        get() = if (distinctBy != null || groupBy != null) {
            lattice.queryCountDistinct(tableName, whereClause, groupBy, distinctBy)
        } else {
            lattice.queryCount(tableName, whereClause)
        }

    operator fun get(index: Int): T {
        val currentCount = count
        require(index in 0 until currentCount) { "Index $index out of bounds (count: $currentCount)" }
        val baseOffset = (offsetValue ?: 0).toLong() + index.toLong()
        val objects = fetchSlice(limit = 1, offset = baseOffset)
        return objects.firstOrNull()
            ?: throw LatticeException("Failed to get object at index $index")
    }

    private fun fetchSlice(limit: Int, offset: Long): List<T> {
        val effectiveDistinct = distinctBy ?: groupBy
        return if (effectiveDistinct != null) {
            lattice.queryDistinctObjects(
                tableName = tableName,
                type = type,
                distinctBy = effectiveDistinct,
                whereClause = whereClause,
                orderBy = orderByClause,
                limit = limit,
                offset = offset
            )
        } else {
            lattice.queryObjects(
                tableName = tableName,
                type = type,
                whereClause = whereClause,
                orderBy = orderByClause,
                limit = limit,
                offset = offset
            )
        }
    }

    override fun iterator(): Iterator<T> = ResultsIterator()

    private inner class ResultsIterator : Iterator<T> {
        private val batchSize = 100
        private var batch: List<T> = emptyList()
        private var fetchedTotal = 0
        private var batchStart: Long = (offsetValue ?: 0).toLong()
        private var indexInBatch = 0

        override fun hasNext(): Boolean {
            val maxAllowed = limitValue ?: Int.MAX_VALUE
            if (fetchedTotal - batch.size + indexInBatch >= maxAllowed) return false
            if (indexInBatch >= batch.size) {
                val remaining = maxAllowed - fetchedTotal
                if (remaining <= 0) return false
                val reqLimit = minOf(batchSize, remaining)
                batch = fetchSlice(limit = reqLimit, offset = batchStart)
                batchStart += batch.size
                fetchedTotal += batch.size
                indexInBatch = 0
            }
            return indexInBatch < batch.size
        }

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return batch[indexInBatch++]
        }
    }

    fun where(predicate: (QueryBuilder<T>) -> QueryExpr): Results<T> {
        val builder = QueryBuilder<T>()
        val expr = predicate(builder)
        val newWhereClause = expr.toSql()
        val combinedWhere = if (whereClause != null) {
            "($whereClause) AND ($newWhereClause)"
        } else {
            newWhereClause
        }
        return Results(tableName, type, lattice, combinedWhere, orderByClause, limitValue, offsetValue, distinctBy = distinctBy, groupBy = groupBy)
    }

    fun orderBy(property: String, order: SortOrder = SortOrder.ASCENDING): Results<T> {
        val direction = when (order) {
            SortOrder.ASCENDING -> "ASC"
            SortOrder.DESCENDING -> "DESC"
        }
        val newOrderBy = "$property $direction"
        val combinedOrderBy = if (orderByClause != null) {
            "$orderByClause, $newOrderBy"
        } else {
            newOrderBy
        }
        return Results(tableName, type, lattice, whereClause, combinedOrderBy, limitValue, offsetValue, distinctBy = distinctBy, groupBy = groupBy)
    }

    fun limit(count: Int): Results<T> =
        Results(tableName, type, lattice, whereClause, orderByClause, count, offsetValue, distinctBy = distinctBy, groupBy = groupBy)

    fun offset(count: Int): Results<T> =
        Results(tableName, type, lattice, whereClause, orderByClause, limitValue, count, distinctBy = distinctBy, groupBy = groupBy)

    fun distinct(by: String): Results<T> =
        Results(tableName, type, lattice, whereClause, orderByClause, limitValue, offsetValue, distinctBy = by, groupBy = groupBy)

    fun group(by: String): Results<T> =
        Results(tableName, type, lattice, whereClause, orderByClause, limitValue, offsetValue, distinctBy = distinctBy, groupBy = by)

    fun matching(query: String, on: String): Results<T> {
        val escaped = query.replace("'", "''")
        val ftsTable = "_${tableName}_${on}_fts"
        val ftsWhere = "id IN (SELECT rowid FROM $ftsTable WHERE $ftsTable MATCH '$escaped')"
        val combinedWhere = if (whereClause != null) {
            "($whereClause) AND ($ftsWhere)"
        } else {
            ftsWhere
        }
        return Results(tableName, type, lattice, combinedWhere, orderByClause, limitValue, offsetValue, distinctBy = distinctBy, groupBy = groupBy)
    }

    fun matching(query: TextQuery, on: String): Results<T> =
        matching(query.toExpression(), on)

    /**
     * K-Nearest Neighbors vector search over `vec0` index on [on].
     */
    fun nearest(
        to: FloatVector,
        on: String,
        k: Int,
        metric: DistanceMetric = DistanceMetric.L2
    ): List<NearestMatch<T>> {
        if (k <= 0) return emptyList()
        return lattice.queryNearest(
            tableName = tableName,
            type = type,
            columnName = on,
            queryVectorBytes = to.toByteArray(),
            k = k,
            metric = metric,
            whereClause = whereClause
        )
    }

    /**
     * Geospatial bounding-box R*Tree query on [on].
     */
    fun withinBounds(
        on: String,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<T> = lattice.queryWithinBounds(
        tableName = tableName,
        type = type,
        columnName = on,
        minLat = minLat,
        maxLat = maxLat,
        minLon = minLon,
        maxLon = maxLon,
        whereClause = whereClause,
        limit = (limitValue ?: 0).toLong()
    )

    fun first(): T? {
        val objects = fetchSlice(limit = 1, offset = (offsetValue ?: 0).toLong())
        return objects.firstOrNull()
    }

    fun toList(): List<T> =
        fetchSlice(
            limit = limitValue ?: Int.MAX_VALUE,
            offset = (offsetValue ?: 0).toLong()
        )

    /**
     * Materializes all matching rows with [LatticeObject.enableRowCache] enabled so subsequent
     * scalar/@Embedded/collection property reads execute zero SQLite SELECT statements.
     */
    fun toCachedList(): List<T> =
        toList().onEach { it.enableRowCache() }

    fun deleteAll(): Int =
        lattice.deleteWhere(tableName, whereClause)

    fun observe(callback: (CollectionChange) -> Unit): Cancellable {
        return ObserverRegistry.observeTable(
            dbHandle = lattice.nativeDbHandle,
            tableName = tableName,
            whereClause = whereClause,
            callback = callback
        )
    }
}

/**
 * Cold Flow emitting the initial snapshot followed by conflated re-queries on
 * [queryDispatcher] whenever the underlying table mutates locally, via cross-process
 * notifications, or via WebSocket sync.
 *
 * When [cacheRows] is true (default), every hydrated row has [LatticeObject.enableRowCache]
 * called on [queryDispatcher] before emission so Jetpack Compose UI recomposition reads
 * scalar, nullable, @Embedded, collection, @LatticeEnum, Instant, and Uuid properties
 * with **zero** SQLite I/O on Android's Main thread.
 */
fun <T : LatticeObject> Results<T>.asFlow(
    queryDispatcher: CoroutineDispatcher = defaultQueryDispatcher,
    cacheRows: Boolean = true
): Flow<List<T>> = callbackFlow {
    val dbRef = this@asFlow.lattice
    val token = observe { trySend(Unit) }
    trySend(Unit)
    awaitClose {
        token.cancel()
        @Suppress("UNUSED_EXPRESSION")
        dbRef
    }
}.conflate().map {
    withContext(queryDispatcher) {
        if (lattice.isClosed) {
            emptyList()
        } else {
            val rows = toList()
            if (cacheRows) {
                rows.forEach { it.enableRowCache() }
            }
            rows
        }
    }
}

fun <T : LatticeObject> Results<T>.changesFlow(): Flow<CollectionChange> = callbackFlow {
    val dbRef = this@changesFlow.lattice
    val token = observe { change -> trySend(change) }
    awaitClose {
        token.cancel()
        @Suppress("UNUSED_EXPRESSION")
        dbRef
    }
}

fun Results<*>.countFlow(
    queryDispatcher: CoroutineDispatcher = defaultQueryDispatcher
): Flow<Int> = callbackFlow {
    val dbRef = this@countFlow.lattice
    val token = observe { trySend(Unit) }
    trySend(Unit)
    awaitClose {
        token.cancel()
        @Suppress("UNUSED_EXPRESSION")
        dbRef
    }
}.conflate().map {
    withContext(queryDispatcher) {
        if (lattice.isClosed) 0 else count
    }
}
