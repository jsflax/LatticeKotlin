package com.lattice.conformance

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen

/**
 * Cross-SDK conformance corpus runner (see CorpusRunner.kt for the interpreter).
 *
 * Corpus source
 * =============
 * The corpus lives in the LatticeCore repository on the `conformance-corpus`
 * branch under `conformance/corpus/<suite>.yaml`. The submodule checkout stays
 * pinned to the release tag; the corpus is read straight from the submodule's
 * git object database. One-time setup (the runner also attempts it itself,
 * and CI does it explicitly):
 *
 *     git -C LatticeCore fetch origin conformance-corpus:refs/remotes/origin/conformance-corpus
 *
 * Resolution order for the corpus directory:
 *
 * 1. `LATTICE_CONFORMANCE_DIR` — path to a checked-out `conformance`
 *    directory (containing a `corpus` subdirectory of .yaml files), or
 *    directly to a directory of .yaml corpus files.
 * 2. Default — `git -C LatticeCore show origin/conformance-corpus:...`,
 *    with one automatic `git fetch origin conformance-corpus:refs/remotes/origin/conformance-corpus` retry.
 *
 * If neither resolves, every suite test FAILS with instructions (never a
 * silent pass).
 *
 * Corpus files are restricted to the JSON subset of YAML, so they are parsed
 * with kotlinx-serialization-json — no YAML dependency.
 *
 * Capabilities: this runner declares NONE (see CorpusRunner.kt's registry
 * with per-capability probe evidence). Scenarios gated on undeclared
 * capabilities are reported as loud SKIPs naming the missing capability.
 *
 * Known-divergence ledger: scenarios listed in [DIVERGENCE_LEDGER] still
 * execute; their failures report as expected divergences (xfail) with the
 * root cause, and a ledgered scenario that starts passing FAILS the suite so
 * the entry gets removed. The ledger may only shrink by fixing the SDK.
 */
@OptIn(ExperimentalForeignApi::class)
class ConformanceCorpusTest {

    // =========================================================================
    // Expected-divergence ledger: "suite/name" -> root-cause summary.
    //
    // Layer legend — "core" divergences live below every binding in
    // LatticeCore's C-ABI/core layer (all three are fixed on core main
    // @a6c20d6 but NOT in the 1.0.0-rc.1 submodule pin; they will clear on
    // the next core bump). "binding" divergences live in latticekotlin
    // itself (compiler plugin / NativeBridge / Results) and only shrink by
    // fixing the Kotlin SDK.
    // =========================================================================
    private val DIVERGENCE_LEDGER: Map<String, String> = buildMap {
        // --- core layer (same three scenarios xfail in latticepython) ---

        // op: insert(expect_error=add_failed). Expected: duplicate insert into
        // a unique column fails, table keeps 2 rows. Actual: the insert
        // SUCCEEDS (3 rows, dup present). Kotlin reaches the core gap through
        // an extra binding gap: the compiler plugin never reads @Unique (no
        // is_unique in generated descriptors) and NativeBridgeImpl hard-codes
        // is_unique=false — but even a correctly-passed flag is dropped at DDL
        // generation by the core (create_model_table emits no UNIQUE; the
        // CREATE UNIQUE INDEX pass exists only in the Swift bridge).
        put(
            "errors/unique-violation-add-failed",
            "core (plus binding): unique constraint never reaches DDL on the C-ABI path — " +
                "plugin drops @Unique, NativeBridgeImpl hard-codes is_unique=false, and core " +
                "create_model_table emits no UNIQUE (fixed on core main a6c20d6)"
        )
        // op: transaction(expect_error=add_failed). Expected: inner duplicate
        // insert fails, transaction rolls back, final rows [[x, first]].
        // Actual: both inner inserts succeed and commit. Same root cause.
        put(
            "transactions/error-inside-rolls-back",
            "core (plus binding): same unique-DDL gap — the duplicate insert inside the " +
                "transaction succeeds instead of failing with add_failed, so nothing rolls back"
        )
        // op: count inside transaction. Expected: a transaction reads its own
        // uncommitted insert (inside == 1). Actual: inside == 0 — the C-ABI
        // read entry points (lattice_db_count/query) always use the dedicated
        // read-only connection, which under WAL cannot see the write
        // connection's open transaction.
        put(
            "transactions/own-writes-visible-inside",
            "core: lattice_db_count/lattice_db_query route through the read-only connection, " +
                "so an explicit transaction on the write connection cannot read its own " +
                "uncommitted writes (fixed on core main a6c20d6)"
        )

        // --- binding layer (latticekotlin-specific; NOT present in python) ---

        // FTS is core-required (undeclarable), but the Kotlin binding never
        // creates the FTS5 shadow table: the compiler plugin does not read
        // @FullText (no is_full_text in generated descriptors) and
        // NativeBridgeImpl.createDbWithSchemaArrays hard-codes
        // is_full_text=false, so _CfArticle_content_fts does not exist and
        // Results.matching()'s "id IN (SELECT rowid FROM ...)" query fails,
        // yielding [] for every match. Expected hit lists vs actual [].
        val ftsCause = "binding: FTS5 table never created — plugin ignores @FullText and " +
            "NativeBridgeImpl hard-codes is_full_text=false, so Results.matching() queries a " +
            "missing _<table>_<col>_fts table and returns []"
        put("query-fts/single-term", ftsCause)
        put("query-fts/implicit-and", ftsCause)
        put("query-fts/explicit-or", ftsCause)
        put("query-fts/phrase", ftsCause)
        put("query-fts/prefix", ftsCause)
        put("query-fts/match-update-visibility", ftsCause)

        // KNN is core-required (undeclarable), but latticekotlin has no public
        // KNN query API at all: DistanceMetric/NearestMatch are inert data
        // classes, Results has no nearest(), and lattice_db_query_nearest is
        // not wrapped by NativeBridge.
        val knnCause = "binding: no public KNN API — lattice_db_query_nearest unwrapped, " +
            "Results has no nearest() (DistanceMetric/NearestMatch are inert)"
        put("query-knn/l2-order-and-distances", knnCause)
        put("query-knn/l2-top-k", knnCause)
        put("query-knn/cosine-order", knnCause)
        put("query-knn/knn-with-where-prefilter", knnCause)

        // distinct_by enumeration. Expected one row per distinct city;
        // actual: all four rows. Results.distinct(by) routes the column into
        // count (queryCountDistinct) only — enumeration (toList/iterator/get)
        // calls queryObjects without it. NativeBridge.queryDistinct exists but
        // Results never uses it.
        put(
            "query-shape/distinct-by",
            "binding: Results.distinct(by) affects count only — enumeration ignores distinctBy " +
                "(queryObjects path; NativeBridge.queryDistinct is wrapped but unused by Results)"
        )
    }

    // =========================================================================
    // Suite tests — one per corpus file, plus a coverage completeness check.
    // =========================================================================

    @Test fun crud() = runSuite("crud")
    @Test fun errors() = runSuite("errors")
    @Test fun links() = runSuite("links")
    @Test fun migrations() = runSuite("migrations")
    @Test fun queryFilter() = runSuite("query-filter")
    @Test fun queryFts() = runSuite("query-fts")
    @Test fun queryGeo() = runSuite("query-geo")
    @Test fun queryKnn() = runSuite("query-knn")
    @Test fun queryShape() = runSuite("query-shape")
    @Test fun rowcacheIncrement() = runSuite("rowcache-increment")
    @Test fun transactions() = runSuite("transactions")
    @Test fun virtuals() = runSuite("virtuals")

    /** A new corpus file must fail loudly until a suite test above covers it. */
    @Test
    fun corpusCoverageComplete() {
        val covered = setOf(
            "crud", "errors", "links", "migrations", "query-filter", "query-fts",
            "query-geo", "query-knn", "query-shape", "rowcache-increment",
            "transactions", "virtuals",
        )
        val available = CorpusSource.listSuites().toSet()
        if (available != covered) {
            fail(
                "corpus suite set changed — add/remove @Test coverage: " +
                    "new=${available - covered} removed=${covered - available}"
            )
        }
    }

    // =========================================================================
    // Suite execution and reporting
    // =========================================================================

    private fun runSuite(suite: String) {
        val doc = CorpusSource.loadSuite(suite)
        val formatVersion = doc.getValue("format_version").jsonPrimitive.int
        if (formatVersion != FORMAT_VERSION) {
            throw CorpusError(
                "suite '$suite': unknown format_version $formatVersion (runner implements $FORMAT_VERSION)"
            )
        }
        var passed = 0
        var skipped = 0
        var xfailed = 0
        val failures = mutableListOf<String>()

        for (scenarioEl in doc.getValue("scenarios").jsonArray) {
            val scenario = scenarioEl.jsonObject
            val name = scenario.getValue("name").jsonPrimitive.content
            val key = "$suite/$name"

            val required = scenario["capabilities"]?.jsonArray
                ?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            val missing = (required - CAPABILITIES).sorted()
            if (missing.isNotEmpty()) {
                skipped++
                for (cap in missing) {
                    println(
                        "SKIP $key — undeclared capability '$cap': " +
                            (CAPABILITY_SKIP_RATIONALE[cap] ?: "no rationale registered (add one!)")
                    )
                }
                continue
            }

            val ledgered = DIVERGENCE_LEDGER[key]
            val dbPath = "/tmp/lattice_conformance_${sanitize(key)}_${Random.nextLong().toString(16)}.sqlite"
            val failure: Throwable? = try {
                runScenario(scenario, dbPath)
                null
            } catch (e: ScenarioFailure) {
                e
            }
            // CorpusError and anything else propagates: malformed corpus /
            // interpreter bugs are hard errors, never xfail material.

            when {
                failure == null && ledgered == null -> {
                    passed++
                    println("PASS $key")
                }
                failure == null && ledgered != null -> failures +=
                    "$key: ledgered scenario now PASSES — remove its DIVERGENCE_LEDGER entry " +
                        "(ledgers may only shrink by SDK fixes)"
                failure != null && ledgered != null -> {
                    xfailed++
                    println("XFAIL $key — known divergence: $ledgered\n      (${failure.message})")
                }
                else -> failures += "$key: ${failure!!.message}"
            }
        }

        println(
            "suite '$suite': $passed passed, $xfailed expected divergences, " +
                "$skipped skipped, ${failures.size} FAILED"
        )
        if (failures.isNotEmpty()) {
            fail("conformance suite '$suite' diverged:\n" + failures.joinToString("\n\n"))
        }
    }

    private fun sanitize(key: String): String = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}

// =============================================================================
// Corpus source resolution
// =============================================================================

@OptIn(ExperimentalForeignApi::class)
internal object CorpusSource {
    private const val CORPUS_REF = "origin/conformance-corpus"
    private const val CORPUS_TREE = "conformance/corpus"
    private const val ENV_VAR = "LATTICE_CONFORMANCE_DIR"

    private val json = Json { ignoreUnknownKeys = false }
    private val cache = mutableMapOf<String, JsonObject>()
    private var fetchAttempted = false

    private val submoduleDir: String? by lazy {
        // The native test binary's working directory is the lattice-runtime
        // project dir under Gradle; probe upward for the submodule checkout.
        listOf("LatticeCore", "../LatticeCore", "../../LatticeCore")
            .firstOrNull { fileExists("$it/.git") || fileExists("$it/Package.swift") }
    }

    private val envDir: String? by lazy { getenv(ENV_VAR)?.toKString()?.takeIf { it.isNotEmpty() } }

    private fun fetchHint(): String =
        "conformance corpus not available — run: git -C ${submoduleDir ?: "LatticeCore"} fetch origin " +
            "conformance-corpus (or set $ENV_VAR to a checked-out conformance directory)"

    fun listSuites(): List<String> {
        envDir?.let { dir ->
            val corpusDir = if (fileExists("$dir/corpus")) "$dir/corpus" else dir
            val (status, out) = shell("ls '$corpusDir'")
            if (status != 0) error("$ENV_VAR=$dir: cannot list $corpusDir")
            val names = out.lines().filter { it.endsWith(".yaml") }.map { it.removeSuffix(".yaml") }
            if (names.isEmpty()) error("$ENV_VAR=$dir: no *.yaml corpus files found")
            return names.sorted()
        }
        val out = gitShow("--name-only listing") {
            shell("git -C '$it' ls-tree -r --name-only $CORPUS_REF -- $CORPUS_TREE 2>/dev/null")
        }
        return out.lines()
            .filter { it.endsWith(".yaml") }
            .map { it.substringAfterLast('/').removeSuffix(".yaml") }
            .sorted()
    }

    fun loadSuite(suite: String): JsonObject = cache.getOrPut(suite) {
        val text = envDir?.let { dir ->
            val corpusDir = if (fileExists("$dir/corpus")) "$dir/corpus" else dir
            readFile("$corpusDir/$suite.yaml") ?: error("$ENV_VAR=$dir: cannot read $corpusDir/$suite.yaml")
        } ?: gitShow(suite) {
            shell("git -C '$it' show $CORPUS_REF:$CORPUS_TREE/$suite.yaml 2>/dev/null")
        }
        json.parseToJsonElement(text).jsonObject
    }

    /** git-show with a single automatic fetch retry; fails loudly otherwise. */
    private fun gitShow(what: String, cmd: (String) -> Pair<Int, String>): String {
        val sub = submoduleDir ?: error(fetchHint())
        var (status, out) = cmd(sub)
        if (status != 0 && !fetchAttempted) {
            fetchAttempted = true
            println("conformance: fetching corpus branch ($what unavailable locally)…")
            shell("git -C '$sub' fetch origin conformance-corpus:refs/remotes/origin/conformance-corpus 2>&1")
            val retry = cmd(sub)
            status = retry.first
            out = retry.second
        }
        if (status != 0) error(fetchHint())
        return out
    }

    private fun fileExists(path: String): Boolean {
        val f = fopen(path, "r") ?: return platform.posix.opendir(path)?.let {
            platform.posix.closedir(it); true
        } ?: false
        fclose(f)
        return true
    }

    private fun readFile(path: String): String? {
        val f = fopen(path, "r") ?: return null
        try {
            val sb = StringBuilder()
            val buf = ByteArray(65536)
            while (true) {
                val line = fgets(buf.refTo(0), buf.size, f)?.toKString() ?: break
                sb.append(line)
            }
            return sb.toString()
        } finally {
            fclose(f)
        }
    }

    private fun shell(cmd: String): Pair<Int, String> {
        val pipe = popen(cmd, "r") ?: return 127 to ""
        val sb = StringBuilder()
        val buf = ByteArray(65536)
        while (true) {
            val line = fgets(buf.refTo(0), buf.size, pipe)?.toKString() ?: break
            sb.append(line)
        }
        val status = pclose(pipe)
        return status to sb.toString()
    }
}
