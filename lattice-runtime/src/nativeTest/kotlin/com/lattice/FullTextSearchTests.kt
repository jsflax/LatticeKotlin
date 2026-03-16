package com.lattice

import kotlin.test.*

/**
 * Tests for full-text search (FTS5) support.
 *
 * These tests verify TextQuery expression generation as unit tests
 * that don't require a database connection. Integration tests with
 * actual FTS5 virtual tables will be added once the compiler plugin
 * generates @FullText schema metadata.
 */
class FullTextSearchTests {

    // -- TextQuery expression generation tests --

    @Test
    fun test_AllOf_SingleTerm() {
        val query = TextQuery.allOf("kotlin")
        assertEquals("kotlin", query.toExpression())
    }

    @Test
    fun test_AllOf_MultipleTerms() {
        val query = TextQuery.allOf("machine", "learning", "model")
        assertEquals("machine AND learning AND model", query.toExpression())
    }

    @Test
    fun test_AnyOf_SingleTerm() {
        val query = TextQuery.anyOf("kotlin")
        assertEquals("kotlin", query.toExpression())
    }

    @Test
    fun test_AnyOf_MultipleTerms() {
        val query = TextQuery.anyOf("kotlin", "swift", "rust")
        assertEquals("kotlin OR swift OR rust", query.toExpression())
    }

    @Test
    fun test_Phrase() {
        val query = TextQuery.phrase("neural network")
        assertEquals("\"neural network\"", query.toExpression())
    }

    @Test
    fun test_Prefix() {
        val query = TextQuery.prefix("mach")
        assertEquals("mach*", query.toExpression())
    }

    @Test
    fun test_Near_DefaultDistance() {
        val query = TextQuery.near("hello", "world")
        assertEquals("NEAR(hello world, 10)", query.toExpression())
    }

    @Test
    fun test_Near_CustomDistance() {
        val query = TextQuery.near("hello", "world", distance = 5)
        assertEquals("NEAR(hello world, 5)", query.toExpression())
    }

    @Test
    fun test_Raw() {
        val query = TextQuery.raw("title:kotlin OR body:multiplatform")
        assertEquals("title:kotlin OR body:multiplatform", query.toExpression())
    }

    // -- Data class equality tests --

    @Test
    fun test_AllOf_Equality() {
        val a = TextQuery.allOf("a", "b")
        val b = TextQuery.allOf("a", "b")
        val c = TextQuery.allOf("a", "c")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun test_AnyOf_Equality() {
        val a = TextQuery.anyOf("x", "y")
        val b = TextQuery.anyOf("x", "y")
        assertEquals(a, b)
    }

    @Test
    fun test_Phrase_Equality() {
        val a = TextQuery.phrase("hello world")
        val b = TextQuery.phrase("hello world")
        val c = TextQuery.phrase("goodbye world")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun test_Near_Equality() {
        val a = TextQuery.near("a", "b", distance = 5)
        val b = TextQuery.near("a", "b", distance = 5)
        val c = TextQuery.near("a", "b", distance = 10)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // -- Sealed class type checks --

    @Test
    fun test_TextQuery_IsSealed() {
        val queries: List<TextQuery> = listOf(
            TextQuery.allOf("a"),
            TextQuery.anyOf("b"),
            TextQuery.phrase("c"),
            TextQuery.prefix("d"),
            TextQuery.near("e", "f"),
            TextQuery.raw("g")
        )

        // Verify all produce non-empty expressions
        for (query in queries) {
            assertTrue(query.toExpression().isNotEmpty(), "Expression should not be empty for $query")
        }
    }

    // -- Edge cases --

    @Test
    fun test_AllOf_EmptyList() {
        val query = TextQuery.AllOf(emptyList())
        assertEquals("", query.toExpression())
    }

    @Test
    fun test_AnyOf_EmptyList() {
        val query = TextQuery.AnyOf(emptyList())
        assertEquals("", query.toExpression())
    }

    @Test
    fun test_Phrase_EmptyString() {
        val query = TextQuery.phrase("")
        assertEquals("\"\"", query.toExpression())
    }

    @Test
    fun test_Prefix_EmptyString() {
        val query = TextQuery.prefix("")
        assertEquals("*", query.toExpression())
    }

    @Test
    fun test_Near_SingleTerm() {
        val query = TextQuery.near("solo")
        assertEquals("NEAR(solo, 10)", query.toExpression())
    }

    @Test
    fun test_Near_ManyTerms() {
        val query = TextQuery.near("a", "b", "c", "d", distance = 3)
        assertEquals("NEAR(a b c d, 3)", query.toExpression())
    }
}
