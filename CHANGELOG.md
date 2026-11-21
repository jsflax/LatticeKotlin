# Changelog

## 3.1.2 — 2026-11-21

### Added

- **LatticeCore 3.1.2 (`LatticeCAPI` 1.1.0) alignment.** Advances the core pin
  from `1.0.1` to `3.1.2`, bringing cross-process notification watch sharing,
  6-argument sync progress callbacks, replication slots, audit log retention,
  and CAPI 1.1.0 schema v2 extensions to Android/JVM and Kotlin/Native.
- **Sum-type `@Union` properties.** Sealed interfaces and sealed classes
  annotated with `@Union` and `@UnionCase` (including `@Unlabeled` single
  `@Embedded` payloads) compile to `LATTICE_KIND_UNION` tables with full local
  query and WebSocket sync parity across Android JNI and Kotlin/Native.
- **`@NoHistory` streaming properties, `@Unique(compoundedWith, allowsUpsert)`,
  `@Model(tableName)`, and `@Property(name)`.** Full K2 FIR/IR compiler plugin
  support for audit-exempt high-frequency columns, compound/upsert unique
  constraints, and custom SQLite table/column names.
- **Vector KNN, Geospatial R*Tree, and Collection types.** `FloatVector`
  (`Results.nearest`), `GeoBounds` (`Results.withinBounds`), `List<@Embedded>`,
  `Set<@LatticeEnum>`, `List<@LatticeEnum>`, and `List<Int|Long|Double>`.
- **Off-Main-Thread Compose `Results.asFlow(cacheRows = true)`.** Materializes
  per-object C++ row caches on `defaultQueryDispatcher` so UI recomposition
  reads scalar, `@Embedded`, enum, temporal, and collection properties with zero
  SQLite statements on Android's Main thread.
- **Ktor 3.0.0 WebSocket sync & TLS leaf SHA-256 pinning.** Generation-fenced
  receive-recovery redials (`connect()` while `OPEN`), deterministic `close()`
  teardown, and OkHttp leaf DER SHA-256 certificate pinning.
- **Gradle plugin publication.** Publishes `io.github.jsflax:lattice-gradle-plugin:3.1.2`
  with plugin IDs `io.github.jsflax.lattice` and `com.lattice`.
- **Android 16 KB ELF page-size compliance.** Ships NDK r27c (`27.2.12479018`)
  `arm64-v8a` and `x86_64` native libraries (`libLatticeCAPI.so` and
  `liblattice_jni.so`) with `PT_LOAD` `p_align = 16384` and bundled R8 consumer
  keep rules (`consumer-rules.pro`).

### Fixed

- **Zero-leak native handle lifecycle & SQLite FD release.** Adds FIR/IR
  hydration constructor `(Long, LatticeHydrationMarker)`, `NativeHandleArena`
  with Android API-26+ `PhantomReference` reaper (`Lattice` `dbHandle` is never
  GC-closed), and balanced pre-add unmanaged handle release in `Lattice.add`.
- **Zero cross-SDK conformance divergences.** Clears every remaining entry in
  `ConformanceCorpusTest` (`DIVERGENCE_LEDGER = emptyMap()`).

## 1.1.0 — 2026-08-09

### Breaking

- **Kotlin 2.4.10 required.** The compiler plugin is built against 2.4.10
  compiler APIs; a 2.0.x compiler fails to load it with
  `NoClassDefFoundError: ExtensionPointDescriptor`. Bump your Kotlin plugin
  and the `lattice-*` pins together.

### ⚠️ Upgrade hazard: `@Unique` over existing duplicate data

- `@Unique` / `@Indexed` / `@FullText` were declared but **silently inert**
  in 1.0.0 — the compiler plugin never read them. In 1.1.0 they are emitted
  end-to-end and **enforced** (verified by conformance: the duplicate insert
  fails and the enclosing transaction rolls back).
- Because enforcement is new, a model that gains — or already carries —
  `@Unique` on a column containing duplicate rows triggers LatticeCore's
  constraint recovery on the next database open: **duplicates are deleted,
  keeping only the newest row per value**, with only a native log warning.
  Deduplicate (or wipe rehydratable caches) before upgrading models that may
  hold duplicate data.
- `@Unique(compoundedWith = [...])` and `allowsUpsert` are **not yet
  supported** — only the single-column presence bit is emitted. Setting
  either now produces a compiler warning instead of silently enforcing a
  weaker constraint than declared.

### Fixed

- **Android: link target tables were silently dropped.** `lattice_jni.c`
  parsed camelCase schema-JSON keys while the sole producer emits snake_case
  (which the native bridge parses), so every `@Link`/`@LinkList`
  `target_table` was `NULL` on Android and nowhere else. Keys are unified on
  snake_case.
- **`@LatticeEnum` getters no longer throw on unknown stored values.** A
  value written by a newer client (or an entry removed since) previously
  crashed `valueOf`; Swift falls back. Name-stored enum getters now resolve
  through a fallback first — `@LatticeEnum(defaultValue = "...")` names the
  entry (default: first declared); nullable properties read unknown values
  as `null`. Note this is a behavior change: unknown values that previously
  threw now return the fallback/`null`. (Ordinal-stored enums still trap on
  out-of-range ordinals.)

### Conformance

- The six `query-fts/*` scenarios and both unique-violation scenarios left
  the divergence ledger: FTS shadow tables are created and match, and
  uniqueness is enforced. (The unique scenarios' residual divergence was the
  corpus runner misclassifying insert failures — `isManaged` is true for
  every freshly constructed object — not the SDK; the runner now classifies
  by call site.)

### Internal

- Schema descriptor string gained an optional 6th field (constraint bits:
  1 = unique, 2 = indexed, 4 = full-text); 5-field strings from older
  plugins still parse.
- New codegen golden harness (`androidUnitTest`): every generated accessor
  is pinned as an opcode-shape golden compiled through the real `-Xplugin`
  path, with the plugin jar registered as a tracked input (its contents were
  previously invisible to Gradle, so edits could validate stale bytecode).
  Coverage includes embedded, VirtualList, FloatVector, collections, links,
  both enum encodings, and nonzero constraint flags.
- Version-derived compiler-plugin jar paths everywhere; a missing jar now
  fails loudly instead of silently disabling the transform.

## 1.0.0 — 2026-07-25

### Core

- **LatticeCore pinned to `1.0.1`** (from `1.0.0-rc.1`, b5337c3 → 16828cb).
  Core 1.0.1 ships the 62-scenario conformance corpus and the unique-DDL,
  in-transaction read-visibility (thread-scoped), and geo-bounds dynamic-add
  fixes. The C ABI is unchanged (header identical to the rc.1 pin); the
  bundled `libLatticeCAPI` dylibs are rebuilt from the 1.0.1 tag.

### Conformance

- `transactions/own-writes-visible-inside` now PASSES and left the
  divergence ledger: core 1.0.1 routes in-transaction reads through the
  txn-owning (write) connection, thread-scoped — which holds here because
  the conformance runner executes all ops on the test thread.
- The remaining divergences are **binding parity gaps, not regressions** —
  they are the C3 parity backlog tracked for 1.x. Ledgered (execute + xfail):
  FTS (plugin ignores `@FullText`), KNN (`lattice_db_query_nearest`
  unwrapped), distinct-by enumeration (`Results.distinct` affects count
  only), and unique (`@Unique` never reaches the C ABI — core's DDL half is
  fixed in 1.0.1, the plugin/NativeBridge half is not; the two
  unique-violation scenarios were re-ledgered from "core (plus binding)" to
  binding-only causes). Undeclared capabilities (loud skips): virtual
  (VirtualLink/VirtualList/VirtualModel), geo, migration-row-transform,
  row-cache, increment.

### Changed

- Root project version `0.10.0-SNAPSHOT` → `1.0.0`, aligning the Kotlin SDK
  with the core 1.0.x line (compiler-plugin jar references updated to
  `lattice-compiler-plugin-1.0.0.jar`).

## 0.10.0-SNAPSHOT (never released — rolled into 1.0.0)

### Core

- **LatticeCore pinned to `1.0.0-rc.1`** (from a 0.9.0-era commit). rc.1
  freezes the C ABI at exactly 118 `lattice_*` exports (additive-only) and
  builds the shared library with default-hidden visibility — only
  `lattice_*` symbols are exported. The header diff against the previous pin
  is purely additive (version/introspection/feature-probe APIs); no function
  the bindings use changed shape.

### Added

- Android/JVM schema materialization: the compiler plugin now generates the
  `_latticeSchema` getter as a compact compile-time descriptor string decoded
  by `LatticeNative.buildSchemaFromString()`, replacing vararg-`listOf` IR
  that broke JVM/Android codegen. Descriptors carry the link target table
  (`name:type:kind:nullable[:targetTable]`).
- `NativeBridge.createDbObject(dbHandle, tableName)`: create an object using
  the database's registered schema (`lattice_db_create_object`), on both
  cinterop and JNI backends.
- JNI `nativeCreateObjectWithSchema` now parses the schema JSON and builds a
  real `lattice_property_t` array (was a TODO that ignored the schema).
- `NativeBridge.releaseObject()`: object handles are reference-counted by
  the C layer and are now released.

### Fixed

- **Native object-handle leak.** The binding never called
  `lattice_object_release`; under rc.1 semantics (`database::close()` is
  logical-only, connections are freed when the last reference drops) a single
  leaked managed handle pinned its database's file descriptors. `Lattice`
  now tracks every handle it issues and releases them on `close()`;
  `add()` releases the superseded unmanaged handle; the AuditLog
  changeStream callback releases its transient lookup handle.
- `Lattice.close()` is idempotent.
- `build.py` copies the soname-versioned dylib variants
  (`libLatticeCAPI.0.dylib`, …) required by rc.1's versioned install name.
- Cross-target native test binaries are skipped when the target's native
  library is not present, so `./gradlew build` passes on single-platform
  hosts.

### Behavior notes

- Objects obtained from a `Lattice` must not be accessed after `close()`.
- Root project version moved `0.1.0-SNAPSHOT` → `0.10.0-SNAPSHOT` to track
  the LatticeCore 1.0 release train while the Kotlin SDK remains 0.x.
