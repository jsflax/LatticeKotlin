# Lattice Android Architecture

## Goal
Enable Android apps to use Lattice with Jetpack Compose while minimizing code duplication between native (cinterop) and Android (JNI) implementations.

## Architecture

```
commonMain/
├── Lattice.kt              # Regular class (NOT expect/actual)
│                           # Contains ALL the implementation logic
│                           # Uses NativeBridge for native calls
│
└── NativeBridge.kt         # expect object NativeBridge
                            # Declares all the native call methods

nativeMain/
└── NativeBridgeImpl.kt     # actual object NativeBridge
                            # Implements methods using cinterop

androidMain/
└── NativeBridgeImpl.kt     # actual object NativeBridge
                            # Implements methods using JNI
```

## Key Points

1. **`Lattice` becomes a regular class, not `expect class`**
   - Single implementation in commonMain
   - Contains all business logic (model registry, schema building, etc.)
   - Calls `NativeBridge.xxx()` for native operations

2. **Only ONE implementation of `Lattice`**
   - Lives in commonMain
   - No duplication between platforms

3. **Platform-specific code is ONLY in `NativeBridge` actual implementations**
   - `nativeMain`: Uses `kotlinx.cinterop` to call C library
   - `androidMain`: Uses JNI to call C library

4. **Migration path**
   - Take existing `LatticeImpl.kt` from nativeMain
   - Replace cinterop calls (`lattice_db_create()`, etc.) with `NativeBridge.xxx()` calls
   - Remove cinterop imports
   - Move to commonMain

## NativeBridge Methods (from existing cinterop usage)

- `createDbInMemory(): Long`
- `createDbAtPath(path: String): Long`
- `createDbWithSchemas(path: String, schemasJson: String): Long`
- `releaseDb(dbHandle: Long)`
- `getLastError(): String?`
- `addObject(dbHandle: Long, objectHandle: Long): Long`
- `findObject(dbHandle: Long, tableName: String, id: Long): Long`
- `findObjectByGlobalId(dbHandle: Long, tableName: String, globalId: String): Long`
- `removeObject(dbHandle: Long, objectHandle: Long): Boolean`
- `queryCount(dbHandle: Long, tableName: String, whereClause: String?): Int`
- `queryObjects(...): LongArray`
- `deleteWhere(dbHandle: Long, tableName: String, whereClause: String?): Int`
- `beginTransaction(dbHandle: Long): Boolean`
- `commitTransaction(dbHandle: Long): Boolean`
- `rollbackTransaction(dbHandle: Long)`
- Object property access methods
- Sync methods

## Notes

- All native handles are represented as `Long` (pointers on native, JNI handles on JVM)
- The Android app loads the native library via `System.loadLibrary()`
- JNI bindings call the same C functions that cinterop calls

## Building for Android

### Prerequisites

1. Install Android NDK via Android Studio:
   - Open Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools
   - Check "NDK (Side by side)" and click Apply

2. Set the NDK environment variable (optional, auto-detected from Android Studio):
   ```bash
   export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/<version>
   ```

### Build Commands

```bash
# Build native library for all Android ABIs (arm64-v8a, x86_64)
python build.py --android

# Build for specific ABI
python build.py --android --abi arm64-v8a

# Build the Kotlin library (includes Android AAR)
./gradlew :lattice-runtime:assembleRelease
```

### Output Files

Native libraries are copied to:
- `lattice-runtime/src/androidMain/jniLibs/arm64-v8a/libLatticeCAPI.so`
- `lattice-runtime/src/androidMain/jniLibs/x86_64/libLatticeCAPI.so`

The CMake build also creates `liblattice_jni.so` which is the JNI bridge.

### Using in Android App

```kotlin
dependencies {
    implementation("com.lattice:lattice-runtime")
}
```

The library automatically loads the native code when `NativeBridge` is first accessed.
