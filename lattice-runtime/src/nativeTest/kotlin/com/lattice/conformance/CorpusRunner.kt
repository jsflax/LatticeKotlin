package com.lattice.conformance

import com.lattice.BetweenExpr
import com.lattice.CompareExpr
import com.lattice.FloatVector
import com.lattice.InExpr
import com.lattice.Lattice
import com.lattice.LatticeException
import com.lattice.LatticeObject
import com.lattice.LikeExpr
import com.lattice.QueryExpr
import com.lattice.Results
import com.lattice.SortOrder
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Conformance-corpus interpreter for latticekotlin.
 *
 * Executes the declarative scenarios from the LatticeCore conformance corpus
 * (`conformance/corpus/<suite>.yaml` on the `conformance-corpus` branch) against
 * latticekotlin's PUBLIC API only: the pre-compiled catalog @Model classes in
 * CorpusModels.kt, `Lattice` for the database, `Results` (+ the public
 * QueryExpr AST) for reads, `LatticeList` for list relations.
 *
 * Vocabulary handling follows the corpus README: unknown format_version, op,
 * expect form, where-operator, or property type is a HARD ERROR (CorpusError),
 * never a silent skip.
 *
 * Capability registry (see the README's Capabilities table). latticekotlin
 * declares NO optional capabilities — probe evidence for each undeclared one:
 *
 * - `geo`            — GeoBounds is an in-memory data class only. The compiler
 *                      plugin has no GeoBounds property support (no descriptor
 *                      emission, no accessor transform),
 *                      NativeBridgeImpl.createDbWithSchemaArrays hard-codes
 *                      is_geo_bounds = false, and the bounding-box query entry
 *                      point (C ABI lattice_db_query_within_bounds) is not
 *                      wrapped. No write path, no query path.
 * - `virtual`        — VirtualList IS wired (plugin emits kind VIRTUAL_LIST
 *                      with getter/setter transforms), but VirtualLink is
 *                      inert: the plugin never emits kind VIRTUAL_LINK and
 *                      generates no accessor transform for VirtualLink<T>
 *                      properties. virtuals.yaml's CfBinder requires BOTH
 *                      (`main` is a virtual_link), so the capability cannot be
 *                      declared.
 * - `migration-row-transform`
 *                    — the C ABI's lattice_db_create_with_migration is not
 *                      wrapped by NativeBridge; Kotlin's Migration /
 *                      LatticeConfigurationWithMigration are inert data
 *                      classes accepted by no Lattice constructor.
 * - `row-cache`      — lattice_object_enable/disable/refresh_row_cache not
 *                      wrapped.
 * - `increment`      — lattice_object_increment_int not wrapped.
 */
internal const val FORMAT_VERSION = 1

internal val CAPABILITIES: Set<String> = emptySet()

/** Human rationale printed with each capability skip (loud, never silent). */
internal val CAPABILITY_SKIP_RATIONALE: Map<String, String> = mapOf(
    "geo" to "GeoBounds is schema-less in the Kotlin binding: no compiler-plugin property support, " +
        "is_geo_bounds hard-coded false at DB creation, lattice_db_query_within_bounds unwrapped",
    "virtual" to "VirtualLink is inert in the Kotlin binding (compiler plugin never emits kind " +
        "VIRTUAL_LINK); virtuals.yaml's CfBinder.main requires it (VirtualList alone is wired)",
    "migration-row-transform" to "lattice_db_create_with_migration is not wrapped by NativeBridge; " +
        "Migration/LatticeConfigurationWithMigration are inert data classes",
    "row-cache" to "lattice_object_*_row_cache C-ABI entry points are not wrapped by NativeBridge",
    "increment" to "lattice_object_increment_int is not wrapped by NativeBridge",
)

/** Malformed corpus / unknown vocabulary. Hard error, never a skip. */
internal class CorpusError(message: String) : Exception(message)

/** The SDK's observed behavior diverged from the corpus expectation. */
internal class ScenarioFailure(message: String) : AssertionError(message)

/** An op failed; carries the canonical error id it classified to. */
private class OpError(val canonicalId: String, override val cause: Throwable) :
    Exception("$canonicalId: ${cause.message}", cause)

/** Control-flow marker for the 'abort' op inside a transaction. */
private class AbortSignal : Exception()

// =============================================================================
// Per-scenario execution state
// =============================================================================

private class Env(val dbPath: String) {
    var schemaVersion: Int = 1
    var db: Lattice? = null
    val handles = mutableMapOf<String, LatticeObject>()
    val ids = mutableMapOf<String, Long>()
    val vars = mutableMapOf<String, JsonElement>()

    fun requireDb(): Lattice = db ?: throw ScenarioFailure("database is not open")

    fun spec(table: String): TableSpec =
        Catalog.tables[table to schemaVersion]
            ?: throw CorpusError("no compiled catalog model for $table at schema version $schemaVersion")

    fun specOfInstance(obj: LatticeObject): TableSpec = spec(obj._latticeTableName)

    /** Validate the scenario's inline schema against the compiled catalog, then open. */
    fun open(schema: JsonObject) {
        schemaVersion = schema["version"]?.jsonPrimitive?.int ?: 1
        val tables = schema["tables"]?.jsonObject
            ?: throw CorpusError("schema has no tables")
        val specs = tables.entries.map { (tname, tdef) ->
            val spec = Catalog.tables[tname to schemaVersion]
                ?: throw CorpusError("no compiled catalog model for $tname at schema version $schemaVersion")
            validateTable(spec, tdef.jsonObject)
            spec
        }
        Catalog.registerFactories()
        val ordered = topoSort(specs)
        @Suppress("UNCHECKED_CAST")
        db = Lattice(dbPath, *ordered.map { it.kclass as KClass<LatticeObject> }.toTypedArray())
    }
}

private val KNOWN_PROPERTY_TYPES = setOf(
    "int", "double", "bool", "string", "bytes", "vector", "geo",
    "link", "list", "virtual_link", "virtual_list",
)

/** Hard-error when the inline corpus declaration disagrees with the compiled shape. */
private fun validateTable(spec: TableSpec, tdef: JsonObject) {
    val declared = tdef["properties"]?.jsonObject
        ?: throw CorpusError("table ${spec.name}: no properties")
    if (declared.keys != spec.props.keys) {
        throw CorpusError(
            "table ${spec.name}@v-catalog: property set mismatch — corpus ${declared.keys} vs compiled ${spec.props.keys}"
        )
    }
    for ((pname, pdefEl) in declared) {
        val pdef = pdefEl.jsonObject
        val compiled = spec.props.getValue(pname)
        val ptype = pdef["type"]?.jsonPrimitive?.content
            ?: throw CorpusError("table ${spec.name}.$pname: missing type")
        if (ptype !in KNOWN_PROPERTY_TYPES) throw CorpusError("unknown property type: '$ptype'")
        fun flag(key: String): Boolean = pdef[key]?.jsonPrimitive?.boolean ?: false
        val corpusSide = PropSpec(
            type = ptype,
            optional = flag("optional"),
            indexed = flag("indexed"),
            unique = flag("unique"),
            fullText = flag("full_text"),
            dims = pdef["dims"]?.jsonPrimitive?.int,
            target = pdef["target"]?.jsonPrimitive?.content,
            protocol = pdef["protocol"]?.jsonPrimitive?.content,
        )
        if (corpusSide != compiled) {
            throw CorpusError(
                "table ${spec.name}.$pname: shape mismatch — corpus $corpusSide vs compiled $compiled"
            )
        }
    }
}

/** Order model classes so link/list targets are registered before referrers. */
private fun topoSort(specs: List<TableSpec>): List<TableSpec> {
    val byName = specs.associateBy { it.name }
    val ordered = mutableListOf<TableSpec>()
    val done = mutableSetOf<String>()
    fun visit(spec: TableSpec, stack: Set<String>) {
        if (spec.name in done) return
        if (spec.name in stack) return // cycle: registration order no longer matters
        for (p in spec.props.values) {
            val target = p.target ?: continue
            byName[target]?.let { visit(it, stack + spec.name) }
        }
        done += spec.name
        ordered += spec
    }
    specs.forEach { visit(it, emptySet()) }
    return ordered
}

// =============================================================================
// Values: corpus literal -> Kotlin, Kotlin -> canonical JSON
// =============================================================================

private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd-length hex: $hex" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { b ->
        val v = b.toInt() and 0xFF
        v.toString(16).padStart(2, '0')
    }

/** Convert a corpus literal into the public-API value for a property. */
private fun convertValue(env: Env, table: String, prop: String, v: JsonElement): Any? {
    val pdef = env.spec(table).props[prop]
        ?: throw CorpusError("unknown property $table.$prop")
    if (v is JsonNull) return null
    if (v is JsonObject) {
        v["\$hex"]?.let { return hexToBytes(it.jsonPrimitive.content) }
        v["\$ref"]?.let {
            val name = it.jsonPrimitive.content
            return env.handles[name] ?: throw CorpusError("unknown handle: $name")
        }
        throw CorpusError("unknown value form for $table.$prop: $v")
    }
    return when (pdef.type) {
        "int" -> v.jsonPrimitive.long
        "double" -> v.jsonPrimitive.double
        "bool" -> v.jsonPrimitive.boolean
        "string" -> v.jsonPrimitive.content
        "vector" -> FloatVector(v.jsonArray.map { it.jsonPrimitive.float })
        else -> throw CorpusError("cannot convert literal $v for $table.$prop of type ${pdef.type}")
    }
}

/** Canonical read-back form (README "Values"). */
private fun canonicalize(value: Any?, type: String): JsonElement = when {
    value == null -> JsonNull
    type == "int" -> JsonPrimitive(value as Long)
    type == "double" -> JsonPrimitive(value as Double)
    type == "bool" -> JsonPrimitive(value as Boolean)
    type == "string" -> JsonPrimitive(value as String)
    type == "bytes" -> buildJsonObject { put("\$hex", JsonPrimitive(bytesToHex(value as ByteArray))) }
    type == "vector" -> JsonArray((value as FloatVector).map { JsonPrimitive(it.toDouble()) })
    else -> throw CorpusError("cannot canonicalize value of corpus type '$type'")
}

/** Dotted-path read off a handle; a null link short-circuits to null. */
private fun readField(env: Env, obj: LatticeObject, dotted: String): JsonElement {
    val parts = dotted.split(".")
    var cur: LatticeObject = obj
    for (part in parts.dropLast(1)) {
        val spec = env.specOfInstance(cur)
        val getter = spec.getters[part] ?: throw CorpusError("unknown property ${spec.name}.$part")
        cur = (getter(cur) ?: return JsonNull) as LatticeObject
    }
    val last = parts.last()
    val spec = env.specOfInstance(cur)
    val getter = spec.getters[last] ?: throw CorpusError("unknown property ${spec.name}.$last")
    val pdef = spec.props[last] ?: throw CorpusError("unknown property ${spec.name}.$last")
    return canonicalize(getter(cur), pdef.type)
}

// =============================================================================
// Deep (JSON-typed) equality
// =============================================================================

private fun deepEq(actual: JsonElement, expected: JsonElement): Boolean = when {
    actual is JsonNull && expected is JsonNull -> true
    actual is JsonPrimitive && expected is JsonPrimitive ->
        actual.isString == expected.isString && actual.content == expected.content
    actual is JsonArray && expected is JsonArray ->
        actual.size == expected.size && actual.zip(expected).all { (a, e) -> deepEq(a, e) }
    actual is JsonObject && expected is JsonObject ->
        actual.keys == expected.keys && expected.keys.all { deepEq(actual.getValue(it), expected.getValue(it)) }
    else -> false
}

private fun rowsEqual(actual: JsonElement, expected: JsonElement, unordered: Boolean): Boolean {
    if (!unordered) return deepEq(actual, expected)
    if (actual !is JsonArray || expected !is JsonArray || actual.size != expected.size) return false
    val sa = actual.sortedBy { it.toString() }
    val se = expected.sortedBy { it.toString() }
    return sa.zip(se).all { (a, e) -> deepEq(a, e) }
}

// =============================================================================
// Where predicates (public QueryExpr AST)
// =============================================================================

private val LEAF_OPS = setOf(
    "eq", "ne", "lt", "le", "gt", "ge", "contains", "starts_with", "ends_with",
    "like", "in", "between", "is_null", "is_not_null",
)

private fun buildWhere(w: JsonObject, spec: TableSpec): QueryExpr {
    w["all"]?.let { return it.jsonArray.map { e -> buildWhere(e.jsonObject, spec) }.reduce { a, b -> a and b } }
    w["any"]?.let { return it.jsonArray.map { e -> buildWhere(e.jsonObject, spec) }.reduce { a, b -> a or b } }
    w["not"]?.let { return !buildWhere(it.jsonObject, spec) }

    val op = w["op"]?.jsonPrimitive?.content ?: throw CorpusError("where clause missing op: $w")
    if (op !in LEAF_OPS) throw CorpusError("unknown where-operator: '$op'")
    val field = w["field"]?.jsonPrimitive?.content ?: throw CorpusError("where clause missing field")
    val pdef = spec.props[field] ?: throw CorpusError("unknown property ${spec.name}.$field")
    val value = w["value"]

    fun scalar(el: JsonElement): Any = when (pdef.type) {
        "int" -> el.jsonPrimitive.long
        "double" -> el.jsonPrimitive.double
        "bool" -> el.jsonPrimitive.boolean
        "string" -> el.jsonPrimitive.content
        else -> throw CorpusError("where on unsupported property type '${pdef.type}'")
    }

    fun str(): String = value!!.jsonPrimitive.content

    return when (op) {
        "eq" -> CompareExpr(field, "=", value?.let { if (it is JsonNull) null else scalar(it) })
        "ne" -> CompareExpr(field, "!=", value?.let { if (it is JsonNull) null else scalar(it) })
        "lt" -> CompareExpr(field, "<", scalar(value!!))
        "le" -> CompareExpr(field, "<=", scalar(value!!))
        "gt" -> CompareExpr(field, ">", scalar(value!!))
        "ge" -> CompareExpr(field, ">=", scalar(value!!))
        "contains" -> LikeExpr(field, "%${str()}%")
        "starts_with" -> LikeExpr(field, "${str()}%")
        "ends_with" -> LikeExpr(field, "%${str()}")
        "like" -> LikeExpr(field, str())
        "in" -> InExpr(field, value!!.jsonArray.map { scalar(it) })
        "between" -> {
            val v = value!!.jsonObject
            BetweenExpr(field, scalar(v.getValue("low")), scalar(v.getValue("high")))
        }
        "is_null" -> CompareExpr(field, "=", null)
        "is_not_null" -> CompareExpr(field, "!=", null)
        else -> throw CorpusError("unknown where-operator: '$op'")
    }
}

// =============================================================================
// Query shaping (snapshot / rows)
// =============================================================================

@Suppress("UNCHECKED_CAST")
private fun buildResults(env: Env, spec: TableSpec, shape: JsonObject): Results<LatticeObject> {
    var q = env.requireDb().objects(spec.kclass as KClass<LatticeObject>)
    shape["where"]?.takeIf { it !is JsonNull }?.let { w ->
        val expr = buildWhere(w.jsonObject, spec)
        q = q.where { expr }
    }
    shape["distinct_by"]?.takeIf { it !is JsonNull }?.let { q = q.distinct(it.jsonPrimitive.content) }
    shape["sort"]?.takeIf { it !is JsonNull }?.let {
        val sort = it.jsonObject
        val order = if (sort["order"]?.jsonPrimitive?.content == "desc") SortOrder.DESCENDING else SortOrder.ASCENDING
        q = q.orderBy(sort.getValue("by").jsonPrimitive.content, order)
    }
    shape["limit"]?.takeIf { it !is JsonNull }?.let { q = q.limit(it.jsonPrimitive.int) }
    shape["offset"]?.takeIf { it !is JsonNull }?.let { q = q.offset(it.jsonPrimitive.int) }
    return q
}

private fun extractRows(env: Env, objs: List<LatticeObject>, columns: List<String>): JsonArray =
    JsonArray(objs.map { o -> JsonArray(columns.map { readField(env, o, it) }) })

private fun snapshot(env: Env, shape: JsonObject): JsonArray {
    val spec = env.spec(shape.getValue("table").jsonPrimitive.content)
    val columns = shape.getValue("columns").jsonArray.map { it.jsonPrimitive.content }
    return extractRows(env, buildResults(env, spec, shape).toList(), columns)
}

// =============================================================================
// Ops
// =============================================================================

private fun classifiedAdd(env: Env, obj: LatticeObject) {
    val wasManaged = obj.isManaged
    try {
        env.requireDb().add(obj)
    } catch (e: LatticeException) {
        // Classification by failure site (README "Canonical error identifiers"):
        // re-adding a handle that was already persisted -> already_managed;
        // any other backend rejection of the insert -> add_failed.
        throw OpError(if (wasManaged) "already_managed" else "add_failed", e)
    }
}

private fun opInsert(env: Env, op: JsonObject) {
    val table = op.getValue("table").jsonPrimitive.content
    val spec = env.spec(table)
    val obj = spec.factory()
    for ((k, v) in op.getValue("values").jsonObject) {
        val setter = spec.setters[k] ?: throw CorpusError("unknown property $table.$k")
        setter(obj, convertValue(env, table, k, v))
    }
    classifiedAdd(env, obj)
    op["as"]?.let { env.handles[it.jsonPrimitive.content] = obj }
    op["save_id"]?.let { env.ids[it.jsonPrimitive.content] = obj.id }
}

private fun resolveId(env: Env, idSpec: JsonObject): Long {
    idSpec["\$id_of"]?.let {
        val name = it.jsonPrimitive.content
        return (env.handles[name] ?: throw CorpusError("unknown handle: $name")).id
    }
    idSpec["\$saved_id"]?.let {
        val name = it.jsonPrimitive.content
        return env.ids[name] ?: throw CorpusError("unknown saved id: $name")
    }
    throw CorpusError("unknown id form: $idSpec")
}

private fun handleOf(env: Env, op: JsonObject, key: String = "ref"): LatticeObject {
    val name = op.getValue(key).jsonPrimitive.content
    return env.handles[name] ?: throw CorpusError("unknown handle: $name")
}

private fun opTransaction(env: Env, op: JsonObject) {
    val db = env.requireDb()
    try {
        db.transaction {
            for (inner in op.getValue("ops").jsonArray) {
                val innerOp = inner.jsonObject
                if (innerOp.getValue("op").jsonPrimitive.content == "abort") throw AbortSignal()
                runOp(env, innerOp, inTxn = true)
            }
        }
    } catch (_: AbortSignal) {
        // rolled back; scenario continues
    }
}

private fun opReopen(env: Env, op: JsonObject) {
    val schema = op.getValue("schema").jsonObject
    val transforms = op["migration"]?.jsonObject?.get("transforms")?.jsonObject
    if (transforms != null && transforms.isNotEmpty()) {
        // Row transforms are gated behind migration-row-transform, which this
        // runner does not declare. Reaching here means the gate was bypassed.
        throw CorpusError("op 'reopen' with row transforms requires the migration-row-transform capability")
    }
    env.handles.clear() // pre-reopen handles are invalid by contract
    try {
        // latticekotlin has no version/migration open path (see registry notes):
        // a reopen is a plain re-open with the new compiled schema. Column adds
        // are the core's ensure_tables/migrate_model_table responsibility.
        env.open(schema)
    } catch (e: LatticeException) {
        env.db = null
        val cid = if (e.message?.contains("BLOB") == true) "migration_blob_unsupported" else "migration_failed"
        op["save_outcome"]?.let {
            env.vars[it.jsonPrimitive.content] = JsonPrimitive(cid)
            return
        }
        throw OpError(cid, e)
    }
    op["save_outcome"]?.let { env.vars[it.jsonPrimitive.content] = JsonPrimitive("ok") }
}

private fun dispatch(env: Env, op: JsonObject) {
    when (val name = op.getValue("op").jsonPrimitive.content) {
        "insert" -> opInsert(env, op)

        "add_existing" -> classifiedAdd(env, handleOf(env, op))

        "get" -> {
            val spec = env.spec(op.getValue("table").jsonPrimitive.content)
            @Suppress("UNCHECKED_CAST")
            val obj = env.requireDb().find(spec.kclass as KClass<LatticeObject>, resolveId(env, op.getValue("id").jsonObject))
            op["save_found"]?.let { env.vars[it.jsonPrimitive.content] = JsonPrimitive(obj != null) }
            if (obj != null) op["as"]?.let { env.handles[it.jsonPrimitive.content] = obj }
        }

        "update" -> {
            val obj = handleOf(env, op)
            val table = obj._latticeTableName
            val spec = env.spec(table)
            for ((k, v) in op.getValue("values").jsonObject) {
                val setter = spec.setters[k] ?: throw CorpusError("unknown property $table.$k")
                setter(obj, convertValue(env, table, k, v))
            }
        }

        "delete" -> env.requireDb().remove(handleOf(env, op))

        "delete_where" -> {
            val spec = env.spec(op.getValue("table").jsonPrimitive.content)
            buildResults(env, spec, op).deleteAll()
        }

        "count" -> {
            val spec = env.spec(op.getValue("table").jsonPrimitive.content)
            env.vars[op.getValue("save").jsonPrimitive.content] =
                JsonPrimitive(buildResults(env, spec, op).count)
        }

        "snapshot" -> env.vars[op.getValue("save").jsonPrimitive.content] = snapshot(env, op)

        "read" -> {
            val obj = handleOf(env, op)
            env.vars[op.getValue("save").jsonPrimitive.content] =
                JsonArray(op.getValue("fields").jsonArray.map { readField(env, obj, it.jsonPrimitive.content) })
        }

        "fts" -> {
            val spec = env.spec(op.getValue("table").jsonPrimitive.content)
            @Suppress("UNCHECKED_CAST")
            var q = env.requireDb().objects(spec.kclass as KClass<LatticeObject>)
                .matching(op.getValue("match").jsonPrimitive.content, on = op.getValue("column").jsonPrimitive.content)
            op["limit"]?.takeIf { it !is JsonNull }?.let { q = q.limit(it.jsonPrimitive.int) }
            val columns = op.getValue("columns").jsonArray.map { it.jsonPrimitive.content }
            env.vars[op.getValue("save").jsonPrimitive.content] = extractRows(env, q.toList(), columns)
        }

        "knn" -> throw ScenarioFailure(
            "op 'knn': latticekotlin has no public KNN query API — DistanceMetric/NearestMatch are " +
                "inert data classes, Results has no nearest(), and the C ABI's " +
                "lattice_db_query_nearest is not wrapped by NativeBridge"
        )

        "geo_within" ->
            // Gated behind `geo`, which this runner does not declare.
            throw CorpusError("op 'geo_within' requires the geo capability, which latticekotlin does not declare")

        "list_append" -> {
            val obj = handleOf(env, op)
            val spec = env.specOfInstance(obj)
            val prop = op.getValue("property").jsonPrimitive.content
            val acc = spec.lists[prop] ?: throw CorpusError("unknown list property ${spec.name}.$prop")
            val itemName = op.getValue("item").jsonObject.getValue("\$ref").jsonPrimitive.content
            val item = env.handles[itemName] ?: throw CorpusError("unknown handle: $itemName")
            acc.append(obj, item)
        }

        "list_remove_at" -> {
            val obj = handleOf(env, op)
            val spec = env.specOfInstance(obj)
            val prop = op.getValue("property").jsonPrimitive.content
            val acc = spec.lists[prop] ?: throw CorpusError("unknown list property ${spec.name}.$prop")
            acc.removeAt(obj, op.getValue("index").jsonPrimitive.int)
        }

        "list_size" -> {
            val obj = handleOf(env, op)
            val spec = env.specOfInstance(obj)
            val prop = op.getValue("property").jsonPrimitive.content
            val acc = spec.lists[prop] ?: throw CorpusError("unknown list property ${spec.name}.$prop")
            env.vars[op.getValue("save").jsonPrimitive.content] = JsonPrimitive(acc.size(obj))
        }

        "list_read" -> {
            val obj = handleOf(env, op)
            val spec = env.specOfInstance(obj)
            val prop = op.getValue("property").jsonPrimitive.content
            val acc = spec.lists[prop] ?: throw CorpusError("unknown list property ${spec.name}.$prop")
            val field = op.getValue("field").jsonPrimitive.content
            env.vars[op.getValue("save").jsonPrimitive.content] =
                JsonArray(acc.items(obj).map { readField(env, it, field) })
        }

        "transaction" -> opTransaction(env, op)

        "close" -> {
            env.requireDb().close()
            env.db = null
            env.handles.clear()
        }

        "reopen" -> opReopen(env, op)

        "materialize", "dematerialize", "refresh", "increment" ->
            // In the corpus vocabulary but gated behind row-cache/increment,
            // which this runner does not declare (not wrapped by latticekotlin).
            throw CorpusError("op '$name' requires a capability latticekotlin does not declare")

        "abort" -> throw CorpusError("'abort' is only valid inside a transaction block")

        else -> throw CorpusError("unknown op: '$name'")
    }
}

private fun runOp(env: Env, op: JsonObject, inTxn: Boolean = false) {
    val opName = op.getValue("op").jsonPrimitive.content
    val expectError = op["expect_error"]?.jsonPrimitive?.content
    try {
        dispatch(env, op)
    } catch (e: OpError) {
        if (expectError != null) {
            if (e.canonicalId == expectError) return
            throw ScenarioFailure(
                "op '$opName' failed with '${e.canonicalId}', expected '$expectError' (cause: ${e.cause.message})"
            )
        }
        if (inTxn) throw e // surfaces as the transaction's error after rollback
        throw ScenarioFailure("op '$opName' failed unexpectedly with '${e.canonicalId}': ${e.cause.message}")
    }
    if (expectError != null) {
        throw ScenarioFailure("op '$opName' succeeded but expected error '$expectError'")
    }
}

// =============================================================================
// Expects
// =============================================================================

private fun checkExpect(env: Env, e: JsonObject) {
    val unordered = e["unordered"]?.jsonPrimitive?.boolean ?: false
    when {
        "var" in e -> {
            val name = e.getValue("var").jsonPrimitive.content
            val actual = env.vars[name] ?: throw ScenarioFailure("var '$name' was never captured")
            val expected = e.getValue("equals")
            if (!rowsEqual(actual, expected, unordered)) {
                throw ScenarioFailure("var '$name': expected $expected, got $actual")
            }
        }
        "count" in e -> {
            val cSpec = e.getValue("count").jsonObject
            val spec = env.spec(cSpec.getValue("table").jsonPrimitive.content)
            val actual = buildResults(env, spec, cSpec).count
            val expected = cSpec.getValue("equals").jsonPrimitive.int
            if (actual != expected) {
                throw ScenarioFailure("count(${spec.name}): expected $expected, got $actual")
            }
        }
        "rows" in e -> {
            val rSpec = e.getValue("rows").jsonObject
            val actual = snapshot(env, rSpec)
            val expected = rSpec.getValue("equals")
            val rowsUnordered = rSpec["unordered"]?.jsonPrimitive?.boolean ?: false
            if (!rowsEqual(actual, expected, rowsUnordered)) {
                throw ScenarioFailure(
                    "rows(${rSpec.getValue("table").jsonPrimitive.content}): expected $expected, got $actual"
                )
            }
        }
        "field" in e -> {
            val fSpec = e.getValue("field").jsonObject
            val obj = handleOf(env, fSpec)
            val name = fSpec.getValue("name").jsonPrimitive.content
            val actual = readField(env, obj, name)
            val expected = fSpec.getValue("equals")
            if (!deepEq(actual, expected)) {
                throw ScenarioFailure("field ${fSpec.getValue("ref").jsonPrimitive.content}.$name: expected $expected, got $actual")
            }
        }
        "one_of" in e -> {
            val passed = mutableListOf<Int>()
            e.getValue("one_of").jsonArray.forEachIndexed { i, branch ->
                try {
                    branch.jsonArray.forEach { checkExpect(env, it.jsonObject) }
                    passed += i
                } catch (_: Exception) {
                    // a failing branch, whatever the failure mode
                }
            }
            if (passed.size != 1) {
                throw ScenarioFailure("one_of: expected exactly one passing branch, got $passed")
            }
        }
        else -> throw CorpusError("unknown expect form: ${e.keys.sorted()}")
    }
}

// =============================================================================
// Entry point
// =============================================================================

/**
 * Execute one corpus scenario against a fresh file-backed database.
 *
 * Throws [ScenarioFailure] on divergence, [CorpusError] on malformed corpus.
 * The database files are removed afterwards.
 */
internal fun runScenario(scenario: JsonObject, dbPath: String) {
    val env = Env(dbPath)
    try {
        env.open(scenario.getValue("schema").jsonObject)
        scenario.getValue("ops").jsonArray.forEach { runOp(env, it.jsonObject) }
        scenario.getValue("expect").jsonArray.forEach { checkExpect(env, it.jsonObject) }
    } finally {
        env.db?.close()
        env.db = null
        Lattice.deleteDatabase(dbPath)
    }
}
