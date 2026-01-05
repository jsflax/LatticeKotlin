package com.lattice

import kotlin.reflect.KClass

/**
 * A live query result set.
 *
 * Results are "live" - they always reflect the current database state.
 * Iteration and count queries fetch fresh data each time.
 *
 * Example:
 *     val adults = lattice.objects(Person::class)
 *         .where { it.int("age") gte 18 }
 *         .orderBy("name")
 *         .limit(10)
 *
 *     for (person in adults) {
 *         println(person.name)
 *     }
 */
class Results<T : LatticeObject>(
    private val tableName: String,
    private val type: KClass<T>,
    private val lattice: Lattice,
    private val whereClause: String? = null,
    private val orderByClause: String? = null,
    private val limitValue: Int? = null,
    private val offsetValue: Int? = null
) : Iterable<T> {

    /**
     * Returns the current count of objects matching this query.
     * This is a live count - it reflects the current database state.
     */
    val count: Int
        get() = lattice.queryCount(tableName, whereClause)

    /**
     * Get object at index. Always fetches fresh from database.
     */
    operator fun get(index: Int): T {
        val currentCount = count
        require(index in 0 until currentCount) { "Index $index out of bounds (count: $currentCount)" }

        // Query for single object at offset
        val objects = lattice.queryObjects(tableName, type, whereClause, orderByClause, limit = 1, offset = index.toLong())
        return objects.firstOrNull()
            ?: throw LatticeException("Failed to get object at index $index")
    }

    override fun iterator(): Iterator<T> = ResultsIterator()

    private inner class ResultsIterator : Iterator<T> {
        private val batchSize = 100
        private var batch: List<T> = emptyList()
        private var batchStart: Long = 0
        private var indexInBatch = 0

        override fun hasNext(): Boolean {
            // Fetch in batches to avoid O(n²) OFFSET penalty
            if (indexInBatch >= batch.count()) {
                batch = lattice.queryObjects(tableName, type, whereClause, orderByClause, limit = batchSize, offset = batchStart)
                batchStart += batch.count()
                indexInBatch = 0
            }
            return indexInBatch < batch.count()
        }

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return batch[indexInBatch++]
        }
    }

    /**
     * Filter results with a query predicate.
     * Returns a new Results with the filter applied.
     *
     * Example:
     *     results.where { it.int("age") gte 18 }
     *     results.where { (it.string("name") contains "John") and (it.boolean("active") eq true) }
     */
    fun where(predicate: (QueryBuilder<T>) -> QueryExpr): Results<T> {
        val builder = QueryBuilder<T>()
        val expr = predicate(builder)
        val newWhereClause = expr.toSql()

        // Combine with existing where clause using AND
        val combinedWhere = if (whereClause != null) {
            "($whereClause) AND ($newWhereClause)"
        } else {
            newWhereClause
        }

        return Results(tableName, type, lattice, combinedWhere, orderByClause, limitValue, offsetValue)
    }

    /**
     * Sort results by a property.
     * Returns a new Results with the sort applied.
     *
     * Example:
     *     results.orderBy("name")
     *     results.orderBy("age", SortOrder.DESCENDING)
     */
    fun orderBy(property: String, order: SortOrder = SortOrder.ASCENDING): Results<T> {
        val direction = when (order) {
            SortOrder.ASCENDING -> "ASC"
            SortOrder.DESCENDING -> "DESC"
        }
        val newOrderBy = "$property $direction"

        // Append to existing order by
        val combinedOrderBy = if (orderByClause != null) {
            "$orderByClause, $newOrderBy"
        } else {
            newOrderBy
        }

        return Results(tableName, type, lattice, whereClause, combinedOrderBy, limitValue, offsetValue)
    }

    /**
     * Limit the number of results.
     * Returns a new Results with the limit applied.
     */
    fun limit(count: Int): Results<T> {
        return Results(tableName, type, lattice, whereClause, orderByClause, count, offsetValue)
    }

    /**
     * Skip the first N results.
     * Returns a new Results with the offset applied.
     */
    fun offset(count: Int): Results<T> {
        return Results(tableName, type, lattice, whereClause, orderByClause, limitValue, count)
    }

    /**
     * Returns the first matching object, or null if none.
     */
    fun first(): T? {
        val objects = lattice.queryObjects(tableName, type, whereClause, orderByClause, limit = 1, offset = 0)
        return objects.firstOrNull()
    }

    /**
     * Returns all results as a list (snapshot).
     * Unlike iteration, this returns a frozen list that won't update.
     */
    fun toList(): List<T> {
        return lattice.queryObjects(
            tableName,
            type,
            whereClause,
            orderByClause,
            limit = limitValue ?: Int.MAX_VALUE,
            offset = (offsetValue ?: 0).toLong()
        )
    }

    /**
     * Delete all objects matching this query.
     * Returns the number of deleted objects.
     */
    fun deleteAll(): Int {
        return lattice.deleteWhere(tableName, whereClause)
    }

    /**
     * Observe changes to this collection.
     *
     * The callback is invoked whenever objects matching this query are
     * inserted, updated, or deleted.
     *
     * Example:
     * ```kotlin
     * val token = results.observe { change ->
     *     when (change) {
     *         is CollectionChange.Insert -> println("Inserted: ${change.rowId}")
     *         is CollectionChange.Delete -> println("Deleted: ${change.rowId}")
     *         is CollectionChange.Update -> println("Updated: ${change.rowId}")
     *     }
     * }
     *
     * // Later, to stop observing:
     * token.cancel()
     * ```
     *
     * @param callback Called with the change whenever the collection changes
     * @return A Cancellable token. The observation continues until this token
     *         is cancelled or garbage collected.
     */
    fun observe(callback: (CollectionChange) -> Unit): Cancellable {
        // Platform-specific observation handled via observer registry
        return ObserverRegistry.observeTable(
            dbHandle = lattice.nativeDbHandle,
            tableName = tableName,
            whereClause = whereClause,
            callback = callback
        )
    }
}
