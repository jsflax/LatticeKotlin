# Changelog

## 0.10.0-SNAPSHOT (unreleased)

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
