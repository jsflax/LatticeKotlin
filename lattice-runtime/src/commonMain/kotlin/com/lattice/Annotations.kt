package com.lattice

/**
 * Marks a class as a Lattice model.
 *
 * By default the SQLite table name matches the class simple name.
 * Pass [tableName] to override the table name explicitly.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Model(
    val tableName: String = ""
)

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
 * Marks a class as an embedded model (stored inline as JSON TEXT).
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
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Indexed

/**
 * Marks a property as having a unique constraint.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Unique(
    val compoundedWith: Array<String> = [],
    val allowsUpsert: Boolean = false
)

/**
 * Marks a property for full-text search indexing (FTS5).
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class FullText

/**
 * Marks a streamed/rapidly-mutated primitive property so UPDATE audit triggers
 * record that the column changed without copying intermediate snapshots into
 * AuditLog (sync late-binds the live value at upload time).
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class NoHistory

/**
 * Marks a sealed interface or sealed class as a native Lattice @Union type
 * stored in an owned `_<UnionName>` internal table.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Union(
    val tableName: String = ""
)

/**
 * Optional override for a @Union case's wire discriminator name
 * (defaults to decapitalized subclass simpleName, matching Swift enum case names).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class UnionCase(
    val name: String
)

/**
 * Marks a single-value @Union case parameter as unlabeled on the wire
 * (`param_name = ""`, SQLite column `<caseName>`, union_value key `"_0"`),
 * matching Swift `case verdict(VerdictPayload)`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Unlabeled

/**
 * Maps a Kotlin property name to a custom column name in the database.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Property(
    val name: String
)

/**
 * Marks an enum class as a Lattice-compatible enum.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class LatticeEnum(
    val storeAsOrdinal: Boolean = false,
    val defaultValue: String = ""
)
