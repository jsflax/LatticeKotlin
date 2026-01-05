# LatticeKotlin

Kotlin Multiplatform ORM with sync capabilities, built on LatticeCpp.

## Project Structure

```
LatticeKotlin/
├── lattice-runtime/          # KMP runtime library
│   ├── libs/                 # Bundled native library
│   │   ├── libLatticeCAPI.dylib
│   │   └── lattice.h
│   └── src/
│       ├── commonMain/       # Cross-platform API
│       └── nativeMain/       # Native implementation via cinterop
├── lattice-compiler-plugin/  # Kotlin compiler plugin
├── lattice-gradle-plugin/    # Gradle plugin for easy setup
└── example/                  # Example usage
```

## Quick Start

### 1. Build native library

```bash
python build.py
```

This builds LatticeCpp and copies `libLatticeCAPI.dylib` into `lattice-runtime/libs/`.

### 2. Build Kotlin project

```bash
./gradlew build
```

### 3. Use in your code

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("com.lattice")
}

// Your code
@Model
class Trip {
    var name: String = ""
    var days: Int = 0
}

fun main() {
    val lattice = Lattice(":memory:", Trip::class)

    val trip = Trip().apply {
        name = "Costa Rica"
        days = 10
    }

    lattice.add(trip)  // Now managed - writes go to native DB
    trip.name = "Panama"  // Writes directly to C layer

    for (t in lattice.objects<Trip>()) {
        println(t.name)
    }
}
```

## How It Works

The `@Model` annotation marks a class for Lattice management. The compiler plugin transforms the class at compile time:

```kotlin
// What you write:
@Model
class Trip {
    var name: String = ""
    var days: Int = 0
}

// What it becomes (at compile time):
class Trip : LatticeObject {
    override var _latticeHandle: Long = 0L
    override val _latticeTableName = "Trip"

    private var _unmanaged_name = ""
    var name: String
        get() = if (_latticeHandle != 0L)
            LatticeNative.getString(_latticeHandle, "name")
        else _unmanaged_name
        set(value) {
            if (_latticeHandle != 0L)
                LatticeNative.setString(_latticeHandle, "name", value)
            else _unmanaged_name = value
        }
    // ...
}
```

## Architecture

| Module | Description |
|--------|-------------|
| `lattice-runtime` | KMP library with cinterop bindings to LatticeCAPI |
| `lattice-compiler-plugin` | Kotlin IR transformer for `@Model` classes |
| `lattice-gradle-plugin` | Wires compiler plugin + runtime dependency |

## Targets

- macOS (arm64, x64)
- Linux (x64)
- iOS (arm64, simulator) - commented out, enable as needed
- Android Native - commented out, enable as needed

## Development

```bash
# Rebuild native library
python build.py

# Build all
./gradlew build

# Run example
./gradlew :example:runDebugExecutableMacosArm64
```
