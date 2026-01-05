package com.lattice

/**
 * Query expression AST for building type-safe database queries.
 *
 * Usage:
 *     results.where { it.age gte 18 }
 *     results.where { (it.age gte 18) and (it.name contains "John") }
 */
sealed class QueryExpr {
    abstract fun toSql(): String

    infix fun and(other: QueryExpr): QueryExpr = AndExpr(this, other)
    infix fun or(other: QueryExpr): QueryExpr = OrExpr(this, other)
    operator fun not(): QueryExpr = NotExpr(this)
}

/**
 * Comparison expression (e.g., age >= 18)
 */
class CompareExpr(
    private val field: String,
    private val op: String,
    private val value: Any?
) : QueryExpr() {
    override fun toSql(): String {
        if (value == null) {
            return when (op) {
                "=" -> "$field IS NULL"
                "!=" -> "$field IS NOT NULL"
                else -> "$field $op NULL"
            }
        }
        val sqlValue = when (value) {
            is String -> "'${value.replace("'", "''")}'"
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }
        return "$field $op $sqlValue"
    }
}

/**
 * LIKE expression for string matching
 */
class LikeExpr(
    private val field: String,
    private val pattern: String,
    private val caseInsensitive: Boolean = false
) : QueryExpr() {
    override fun toSql(): String {
        val escapedPattern = pattern.replace("'", "''")
        return if (caseInsensitive) {
            "LOWER($field) LIKE LOWER('$escapedPattern')"
        } else {
            "$field LIKE '$escapedPattern'"
        }
    }
}

/**
 * IN expression for set membership
 */
class InExpr(
    private val field: String,
    private val values: List<Any>
) : QueryExpr() {
    override fun toSql(): String {
        if (values.isEmpty()) return "0" // Empty IN is always false
        val sqlValues = values.joinToString(", ") { value ->
            when (value) {
                is String -> "'${value.replace("'", "''")}'"
                is Boolean -> if (value) "1" else "0"
                else -> value.toString()
            }
        }
        return "$field IN ($sqlValues)"
    }
}

/**
 * BETWEEN expression for range queries
 */
class BetweenExpr(
    private val field: String,
    private val low: Any,
    private val high: Any
) : QueryExpr() {
    override fun toSql(): String {
        val lowSql = when (low) {
            is String -> "'${low.replace("'", "''")}'"
            else -> low.toString()
        }
        val highSql = when (high) {
            is String -> "'${high.replace("'", "''")}'"
            else -> high.toString()
        }
        return "$field BETWEEN $lowSql AND $highSql"
    }
}

/**
 * AND expression combining two expressions
 */
class AndExpr(
    private val left: QueryExpr,
    private val right: QueryExpr
) : QueryExpr() {
    override fun toSql(): String = "(${left.toSql()} AND ${right.toSql()})"
}

/**
 * OR expression combining two expressions
 */
class OrExpr(
    private val left: QueryExpr,
    private val right: QueryExpr
) : QueryExpr() {
    override fun toSql(): String = "(${left.toSql()} OR ${right.toSql()})"
}

/**
 * NOT expression negating an expression
 */
class NotExpr(
    private val expr: QueryExpr
) : QueryExpr() {
    override fun toSql(): String = "NOT (${expr.toSql()})"
}

/**
 * Proxy for a property in query expressions.
 * Supports operators for building WHERE clauses.
 */
class QueryProperty<T>(val name: String) {
    // Equality
    infix fun eq(value: T?): QueryExpr = CompareExpr(name, "=", value)
    infix fun neq(value: T?): QueryExpr = CompareExpr(name, "!=", value)

    // Comparison (for numeric types)
    infix fun lt(value: T): QueryExpr = CompareExpr(name, "<", value)
    infix fun lte(value: T): QueryExpr = CompareExpr(name, "<=", value)
    infix fun gt(value: T): QueryExpr = CompareExpr(name, ">", value)
    infix fun gte(value: T): QueryExpr = CompareExpr(name, ">=", value)

    // Null checks
    fun isNull(): QueryExpr = CompareExpr(name, "=", null)
    fun isNotNull(): QueryExpr = CompareExpr(name, "!=", null)

    // Range
    fun between(low: T, high: T): QueryExpr = BetweenExpr(name, low as Any, high as Any)

    // Collection membership
    fun `in`(values: List<T>): QueryExpr = InExpr(name, values.map { it as Any })
    fun inList(vararg values: T): QueryExpr = InExpr(name, values.map { it as Any })
}

/**
 * String-specific query operations
 */
class QueryStringProperty(val name: String) {
    // Equality
    infix fun eq(value: String?): QueryExpr = CompareExpr(name, "=", value)
    infix fun neq(value: String?): QueryExpr = CompareExpr(name, "!=", value)

    // Comparison
    infix fun lt(value: String): QueryExpr = CompareExpr(name, "<", value)
    infix fun lte(value: String): QueryExpr = CompareExpr(name, "<=", value)
    infix fun gt(value: String): QueryExpr = CompareExpr(name, ">", value)
    infix fun gte(value: String): QueryExpr = CompareExpr(name, ">=", value)

    // Null checks
    fun isNull(): QueryExpr = CompareExpr(name, "=", null)
    fun isNotNull(): QueryExpr = CompareExpr(name, "!=", null)

    // Collection membership
    fun `in`(values: List<String>): QueryExpr = InExpr(name, values)
    fun inList(vararg values: String): QueryExpr = InExpr(name, values.toList())

    // String-specific
    fun contains(substring: String, caseInsensitive: Boolean = false): QueryExpr =
        LikeExpr(name, "%$substring%", caseInsensitive)

    fun startsWith(prefix: String, caseInsensitive: Boolean = false): QueryExpr =
        LikeExpr(name, "$prefix%", caseInsensitive)

    fun endsWith(suffix: String, caseInsensitive: Boolean = false): QueryExpr =
        LikeExpr(name, "%$suffix", caseInsensitive)

    fun like(pattern: String, caseInsensitive: Boolean = false): QueryExpr =
        LikeExpr(name, pattern, caseInsensitive)

    val isEmpty: QueryExpr get() = CompareExpr(name, "=", "")
    val isNotEmpty: QueryExpr get() = CompareExpr(name, "!=", "")
}

/**
 * Query builder that provides property access for type-safe queries.
 *
 * Note: In the current implementation, property names must be specified as strings.
 * The compiler plugin could generate type-safe accessors in the future.
 */
open class QueryBuilder<T : LatticeObject> {
    /**
     * Access a string property by name.
     */
    fun string(name: String): QueryStringProperty = QueryStringProperty(name)

    /**
     * Access an int property by name.
     */
    fun int(name: String): QueryProperty<Int> = QueryProperty(name)

    /**
     * Access a long property by name.
     */
    fun long(name: String): QueryProperty<Long> = QueryProperty(name)

    /**
     * Access a float property by name.
     */
    fun float(name: String): QueryProperty<Float> = QueryProperty(name)

    /**
     * Access a double property by name.
     */
    fun double(name: String): QueryProperty<Double> = QueryProperty(name)

    /**
     * Access a boolean property by name.
     */
    fun boolean(name: String): QueryProperty<Boolean> = QueryProperty(name)

    /**
     * Access any property by name (generic).
     */
    fun <V> prop(name: String): QueryProperty<V> = QueryProperty(name)
}

/**
 * Sort order for query results.
 */
enum class SortOrder {
    ASCENDING,
    DESCENDING
}
