package com.lattice

/**
 * Marks a class as a Lattice model.
 *
 * The compiler plugin transforms this class to:
 * 1. Implement [LatticeObject] interface
 * 2. Add a native handle backing field
 * 3. Transform property getters/setters to delegate to native when managed
 *
 * ```kotlin
 * @Model
 * class Trip {
 *     var name: String = ""
 *     var days: Int = 0
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Model

/**
 * Marks a property as a link to another model (to-one relationship).
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Link

/**
 * Marks a property as a link list to other models (to-many relationship).
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class LinkList

/**
 * Marks a class as an embedded model (stored inline, not as separate table).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Embedded

/**
 * Marks a property to be ignored by Lattice.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Ignore

/**
 * Marks a property to have a non-unique index created on its column.
 *
 * ```kotlin
 * @Model
 * class Product {
 *     @Indexed var name: String = ""
 *     @Indexed var category: String = ""
 * }
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Indexed

/**
 * Marks a property as having a unique constraint.
 *
 * For compound unique constraints, use [compoundedWith] to specify
 * additional property names that form the compound key.
 *
 * Set [allowsUpsert] to true to allow INSERT OR REPLACE behavior.
 *
 * ```kotlin
 * @Model
 * class User {
 *     @Unique var email: String = ""
 *     @Unique(compoundedWith = ["firstName"], allowsUpsert = true)
 *     var lastName: String = ""
 *     var firstName: String = ""
 * }
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Unique(
    val compoundedWith: Array<String> = [],
    val allowsUpsert: Boolean = false
)

/**
 * Marks a property for full-text search indexing (FTS5).
 *
 * ```kotlin
 * @Model
 * class Article {
 *     var title: String = ""
 *     @FullText var content: String = ""
 * }
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class FullText

/**
 * Maps a Kotlin property name to a custom column name in the database.
 *
 * ```kotlin
 * @Model
 * class Person {
 *     @Property(name = "full_name")
 *     var fullName: String = ""
 * }
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Property(
    val name: String
)

/**
 * Marks an enum class as a Lattice-compatible enum.
 *
 * By default, enums are stored as TEXT using the enum entry name.
 * Set [storeAsOrdinal] to true to store as INTEGER using the ordinal.
 *
 * ```kotlin
 * @LatticeEnum
 * enum class Status {
 *     PENDING, ACTIVE, COMPLETED
 * }
 *
 * @Model
 * class Task {
 *     var status: Status = Status.PENDING
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class LatticeEnum(
    val storeAsOrdinal: Boolean = false
)
