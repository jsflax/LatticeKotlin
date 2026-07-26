# Changelog

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
