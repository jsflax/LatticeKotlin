package com.lattice.conformance

import com.lattice.FloatVector
import com.lattice.FullText
import com.lattice.Lattice
import com.lattice.LatticeList
import com.lattice.LatticeObject
import com.lattice.Link
import com.lattice.Model
import com.lattice.Unique
import kotlin.reflect.KClass

/*
 * Pre-compiled catalog models for the LatticeCore conformance corpus
 * (conformance/README.md, "The table catalog").
 *
 * The corpus's inline scenario schemas are the source of truth; a
 * statically-typed runner pre-compiles one native model per catalog table
 * (+ version, for migration scenarios) and MUST validate each scenario's
 * inline declaration against the compiled shape, hard-erroring on any
 * mismatch (see Catalog.validate in CorpusRunner.kt).
 *
 * Only tables reachable by scenarios the runner actually EXECUTES are
 * compiled here. Tables used exclusively by capability-gated suites that
 * latticekotlin does not declare (CfPlace/geo, CfCounter/row-cache+increment,
 * CfNoteA+CfNoteB+CfBinder/virtual, CfMigPerson/migration-row-transform) are
 * deliberately absent — those scenarios SKIP before schema validation runs.
 *
 * Corpus "int" columns are modeled as Kotlin Long (64-bit signed, per spec).
 *
 * The @Unique / @FullText annotations below document the corpus-declared
 * flags. They are currently INERT in latticekotlin: the compiler plugin does
 * not read them (no is_unique / is_full_text in the generated descriptors)
 * and NativeBridgeImpl.createDbWithSchemaArrays hard-codes every flag to
 * false. The resulting divergences are ledgered in CorpusRunner.kt.
 */

@Model
class CfPerson {
    var name: String = ""
    var age: Long = 0
    var score: Double = 0.0
    var active: Boolean = false
    var nickname: String? = null
    var city: String = ""
}

@Model
class CfCard {
    @Unique
    var code: String = ""
    var note: String = ""
}

@Model
class CfPet {
    var name: String = ""
    var kind: String = ""
}

@Model
class CfOwner {
    var name: String = ""

    @Link
    var pet: CfPet? = null

    var pets: LatticeList<CfPet> = LatticeList()
}

@Model
class CfArticle {
    var title: String = ""

    @FullText
    var content: String = ""
}

@Model
class CfDoc {
    var title: String = ""
    var kind: String = ""
    var embedding: FloatVector = FloatVector() // corpus dims: 4 (FloatVector is dynamically sized)
}

@Model
class CfWidget {
    var label: String = ""
}

@Model
class CfBlobDoc {
    var label: String = ""
    var payload: ByteArray = ByteArray(0)
}

// =============================================================================
// Property accessors: generic (by-name) access routed through the PUBLIC
// compiled property surface — never through NativeBridge.
// =============================================================================

/** List-property operations expressed through the public LatticeList surface. */
class ListAccessor(
    val size: (LatticeObject) -> Int,
    val append: (LatticeObject, LatticeObject) -> Unit,
    val removeAt: (LatticeObject, Int) -> Unit,
    val items: (LatticeObject) -> List<LatticeObject>,
)

/** Compiled shape + accessors for one catalog table at one schema version. */
class TableSpec(
    val name: String,
    /** Corpus-shape declaration this compiled model implements (validated against the inline schema). */
    val props: Map<String, PropSpec>,
    val kclass: KClass<out LatticeObject>,
    val factory: () -> LatticeObject,
    val getters: Map<String, (LatticeObject) -> Any?>,
    val setters: Map<String, (LatticeObject, Any?) -> Unit>,
    val lists: Map<String, ListAccessor> = emptyMap(),
)

/** One property of a catalog table, in corpus vocabulary. */
data class PropSpec(
    val type: String,
    val optional: Boolean = false,
    val indexed: Boolean = false,
    val unique: Boolean = false,
    val fullText: Boolean = false,
    val dims: Int? = null,
    val target: String? = null,
    val protocol: String? = null,
)

internal object Catalog {

    /** (tableName, schemaVersion) -> compiled spec. */
    val tables: Map<Pair<String, Int>, TableSpec> = buildMap {
        fun put(version: Int, spec: TableSpec) = put(spec.name to version, spec)

        put(1, TableSpec(
            name = "CfPerson",
            props = mapOf(
                "name" to PropSpec("string"),
                "age" to PropSpec("int"),
                "score" to PropSpec("double"),
                "active" to PropSpec("bool"),
                "nickname" to PropSpec("string", optional = true),
                "city" to PropSpec("string"),
            ),
            kclass = CfPerson::class,
            factory = { CfPerson() },
            getters = mapOf(
                "name" to { o -> (o as CfPerson).name },
                "age" to { o -> (o as CfPerson).age },
                "score" to { o -> (o as CfPerson).score },
                "active" to { o -> (o as CfPerson).active },
                "nickname" to { o -> (o as CfPerson).nickname },
                "city" to { o -> (o as CfPerson).city },
            ),
            setters = mapOf(
                "name" to { o, v -> (o as CfPerson).name = v as String },
                "age" to { o, v -> (o as CfPerson).age = v as Long },
                "score" to { o, v -> (o as CfPerson).score = v as Double },
                "active" to { o, v -> (o as CfPerson).active = v as Boolean },
                "nickname" to { o, v -> (o as CfPerson).nickname = v as String? },
                "city" to { o, v -> (o as CfPerson).city = v as String },
            ),
        ))

        put(1, TableSpec(
            name = "CfCard",
            props = mapOf(
                "code" to PropSpec("string", unique = true),
                "note" to PropSpec("string"),
            ),
            kclass = CfCard::class,
            factory = { CfCard() },
            getters = mapOf(
                "code" to { o -> (o as CfCard).code },
                "note" to { o -> (o as CfCard).note },
            ),
            setters = mapOf(
                "code" to { o, v -> (o as CfCard).code = v as String },
                "note" to { o, v -> (o as CfCard).note = v as String },
            ),
        ))

        put(1, TableSpec(
            name = "CfPet",
            props = mapOf(
                "name" to PropSpec("string"),
                "kind" to PropSpec("string"),
            ),
            kclass = CfPet::class,
            factory = { CfPet() },
            getters = mapOf(
                "name" to { o -> (o as CfPet).name },
                "kind" to { o -> (o as CfPet).kind },
            ),
            setters = mapOf(
                "name" to { o, v -> (o as CfPet).name = v as String },
                "kind" to { o, v -> (o as CfPet).kind = v as String },
            ),
        ))

        put(1, TableSpec(
            name = "CfOwner",
            props = mapOf(
                "name" to PropSpec("string"),
                "pet" to PropSpec("link", target = "CfPet"),
                "pets" to PropSpec("list", target = "CfPet"),
            ),
            kclass = CfOwner::class,
            factory = { CfOwner() },
            getters = mapOf(
                "name" to { o -> (o as CfOwner).name },
                "pet" to { o -> (o as CfOwner).pet },
            ),
            setters = mapOf(
                "name" to { o, v -> (o as CfOwner).name = v as String },
                "pet" to { o, v -> (o as CfOwner).pet = v as CfPet? },
            ),
            lists = mapOf(
                "pets" to ListAccessor(
                    size = { o -> (o as CfOwner).pets.size },
                    append = { o, item -> (o as CfOwner).pets.add(item as CfPet) },
                    removeAt = { o, i -> (o as CfOwner).pets.removeAt(i) },
                    items = { o -> (o as CfOwner).pets.toList() },
                ),
            ),
        ))

        put(1, TableSpec(
            name = "CfArticle",
            props = mapOf(
                "title" to PropSpec("string"),
                "content" to PropSpec("string", fullText = true),
            ),
            kclass = CfArticle::class,
            factory = { CfArticle() },
            getters = mapOf(
                "title" to { o -> (o as CfArticle).title },
                "content" to { o -> (o as CfArticle).content },
            ),
            setters = mapOf(
                "title" to { o, v -> (o as CfArticle).title = v as String },
                "content" to { o, v -> (o as CfArticle).content = v as String },
            ),
        ))

        put(1, TableSpec(
            name = "CfDoc",
            props = mapOf(
                "title" to PropSpec("string"),
                "kind" to PropSpec("string"),
                "embedding" to PropSpec("vector", dims = 4),
            ),
            kclass = CfDoc::class,
            factory = { CfDoc() },
            getters = mapOf(
                "title" to { o -> (o as CfDoc).title },
                "kind" to { o -> (o as CfDoc).kind },
                "embedding" to { o -> (o as CfDoc).embedding },
            ),
            setters = mapOf(
                "title" to { o, v -> (o as CfDoc).title = v as String },
                "kind" to { o, v -> (o as CfDoc).kind = v as String },
                "embedding" to { o, v -> (o as CfDoc).embedding = v as FloatVector },
            ),
        ))

        put(1, TableSpec(
            name = "CfWidget",
            props = mapOf(
                "label" to PropSpec("string"),
            ),
            kclass = CfWidget::class,
            factory = { CfWidget() },
            getters = mapOf(
                "label" to { o -> (o as CfWidget).label },
            ),
            setters = mapOf(
                "label" to { o, v -> (o as CfWidget).label = v as String },
            ),
        ))

        put(2, TableSpec(
            name = "CfWidget",
            props = mapOf(
                "label" to PropSpec("string"),
                "count" to PropSpec("int"),
                "note" to PropSpec("string", optional = true),
            ),
            kclass = com.lattice.conformance.v2.CfWidget::class,
            factory = { com.lattice.conformance.v2.CfWidget() },
            getters = mapOf(
                "label" to { o -> (o as com.lattice.conformance.v2.CfWidget).label },
                "count" to { o -> (o as com.lattice.conformance.v2.CfWidget).count },
                "note" to { o -> (o as com.lattice.conformance.v2.CfWidget).note },
            ),
            setters = mapOf(
                "label" to { o, v -> (o as com.lattice.conformance.v2.CfWidget).label = v as String },
                "count" to { o, v -> (o as com.lattice.conformance.v2.CfWidget).count = v as Long },
                "note" to { o, v -> (o as com.lattice.conformance.v2.CfWidget).note = v as String? },
            ),
        ))

        put(1, TableSpec(
            name = "CfBlobDoc",
            props = mapOf(
                "label" to PropSpec("string"),
                "payload" to PropSpec("bytes"),
            ),
            kclass = CfBlobDoc::class,
            factory = { CfBlobDoc() },
            getters = mapOf(
                "label" to { o -> (o as CfBlobDoc).label },
                "payload" to { o -> (o as CfBlobDoc).payload },
            ),
            setters = mapOf(
                "label" to { o, v -> (o as CfBlobDoc).label = v as String },
                "payload" to { o, v -> (o as CfBlobDoc).payload = v as ByteArray },
            ),
        ))

        put(2, TableSpec(
            name = "CfBlobDoc",
            props = mapOf(
                "label" to PropSpec("string"),
                "payload" to PropSpec("bytes"),
                "stars" to PropSpec("int"),
            ),
            kclass = com.lattice.conformance.v2.CfBlobDoc::class,
            factory = { com.lattice.conformance.v2.CfBlobDoc() },
            getters = mapOf(
                "label" to { o -> (o as com.lattice.conformance.v2.CfBlobDoc).label },
                "payload" to { o -> (o as com.lattice.conformance.v2.CfBlobDoc).payload },
                "stars" to { o -> (o as com.lattice.conformance.v2.CfBlobDoc).stars },
            ),
            setters = mapOf(
                "label" to { o, v -> (o as com.lattice.conformance.v2.CfBlobDoc).label = v as String },
                "payload" to { o, v -> (o as com.lattice.conformance.v2.CfBlobDoc).payload = v as ByteArray },
                "stars" to { o, v -> (o as com.lattice.conformance.v2.CfBlobDoc).stars = v as Long },
            ),
        ))
    }

    /** Register every catalog factory (native has no reflection fallback). Idempotent. */
    fun registerFactories() {
        for (spec in tables.values) {
            @Suppress("UNCHECKED_CAST")
            Lattice.registerFactory(spec.kclass as KClass<LatticeObject>, spec.factory)
        }
    }
}
