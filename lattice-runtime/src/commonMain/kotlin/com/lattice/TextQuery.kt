package com.lattice

/**
 * Builder for FTS5 full-text search expressions.
 * Use with Results.matching() to search @FullText indexed columns.
 */
sealed class TextQuery {
    abstract fun toExpression(): String

    /** All terms must match (FTS5 AND) */
    data class AllOf(val terms: List<String>) : TextQuery() {
        override fun toExpression(): String = terms.joinToString(" AND ")
    }

    /** Any term can match (FTS5 OR) */
    data class AnyOf(val terms: List<String>) : TextQuery() {
        override fun toExpression(): String = terms.joinToString(" OR ")
    }

    /** Exact phrase match */
    data class Phrase(val text: String) : TextQuery() {
        override fun toExpression(): String = "\"$text\""
    }

    /** Prefix match */
    data class Prefix(val term: String) : TextQuery() {
        override fun toExpression(): String = "$term*"
    }

    /** NEAR query - terms within distance of each other */
    data class Near(val terms: List<String>, val distance: Int = 10) : TextQuery() {
        override fun toExpression(): String = "NEAR(${terms.joinToString(" ")}, $distance)"
    }

    /** Raw FTS5 expression */
    data class Raw(val expression: String) : TextQuery() {
        override fun toExpression(): String = expression
    }

    companion object {
        fun allOf(vararg terms: String) = AllOf(terms.toList())
        fun anyOf(vararg terms: String) = AnyOf(terms.toList())
        fun phrase(text: String) = Phrase(text)
        fun prefix(term: String) = Prefix(term)
        fun near(vararg terms: String, distance: Int = 10) = Near(terms.toList(), distance)
        fun raw(expression: String) = Raw(expression)
    }
}
