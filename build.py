#!/usr/bin/env python3
"""
Build script for LatticeKotlin.

Builds the LatticeCpp library and copies it into the Kotlin project.
Supports both native builds and Android cross-compilation.
"""
import subprocess
import shutil
import sys
import os
from pathlib import Path

# Paths
SCRIPT_DIR = Path(__file__).parent.resolve()
LATTICE_CPP_DIR = SCRIPT_DIR / "LatticeCore"
LIBS_DIR = SCRIPT_DIR / "lattice-runtime" / "libs"
CMAKE_BUILD_DIR = LATTICE_CPP_DIR / "cmake-build"

# Android paths
ANDROID_JNI_LIBS_DIR = SCRIPT_DIR / "lattice-runtime" / "src" / "androidMain" / "jniLibs"
ANDROID_ABIS = {
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
}


def find_android_ndk() -> Path | None:
    """Find the Android NDK installation."""
    # Check common locations
    candidates = [
        Path(os.environ.get("ANDROID_NDK_HOME", "")),
        Path(os.environ.get("ANDROID_NDK_ROOT", "")),
        Path(os.environ.get("NDK_HOME", "")),
        Path.home() / "Library/Android/sdk/ndk",  # macOS Android Studio
        Path.home() / "Android/Sdk/ndk",  # Linux
        Path("/usr/local/android-ndk"),
    ]

    for candidate in candidates:
        if not candidate or not candidate.exists():
            continue
        # NDK directory contains ndk-build
        if (candidate / "ndk-build").exists():
            return candidate
        # Or it might be a directory containing versioned NDKs
        for sub in sorted(candidate.iterdir(), reverse=True):
            if (sub / "ndk-build").exists():
                return sub

    return None


def get_lib_name() -> str:
    """Get platform-specific library name."""
    if sys.platform == "darwin":
        return "libLatticeCAPI.dylib"
    elif sys.platform == "win32":
        return "LatticeCAPI.dll"
    else:
        return "libLatticeCAPI.so"


def run_command(cmd: list[str], cwd: Path = None, env: dict = None) -> None:
    """Run a command and raise on failure."""
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        print("STDOUT:", result.stdout)
        print("STDERR:", result.stderr)
        raise RuntimeError(f"Command failed: {' '.join(cmd)}")
    if result.stdout.strip():
        print(result.stdout)


def configure_cmake() -> None:
    """Configure the CMake build."""
    print(f"\nConfiguring CMake in {CMAKE_BUILD_DIR}...")

    if not LATTICE_CPP_DIR.exists():
        raise RuntimeError(f"LatticeCpp not found at {LATTICE_CPP_DIR}")

    CMAKE_BUILD_DIR.mkdir(parents=True, exist_ok=True)

    cmake_args = [
        "cmake",
        str(LATTICE_CPP_DIR),
        "-DCMAKE_BUILD_TYPE=Release",
    ]

    run_command(cmake_args, cwd=CMAKE_BUILD_DIR)


def build_cmake() -> None:
    """Build using CMake."""
    print("\nBuilding with CMake...")

    build_args = [
        "cmake",
        "--build", str(CMAKE_BUILD_DIR),
        "--config", "Release",
        "--parallel",
    ]

    run_command(build_args)


def copy_library() -> None:
    """Copy the built library into the Kotlin project."""
    lib_name = get_lib_name()
    src = CMAKE_BUILD_DIR / lib_name

    if not src.exists():
        raise RuntimeError(f"Built library not found at {src}")

    # Create libs directory
    LIBS_DIR.mkdir(parents=True, exist_ok=True)

    # Copy the library under every name CMake produced (the unversioned
    # linker name plus soname/realname variants like libLatticeCAPI.0.dylib).
    # Since LatticeCore 1.0.0-rc.1 the install name is the versioned soname,
    # so the runtime loader needs the versioned file present too.
    # copy2 dereferences symlinks, so each name becomes a regular file.
    stem = lib_name.split(".")[0]  # e.g. "libLatticeCAPI"
    for candidate in sorted(CMAKE_BUILD_DIR.glob(f"{stem}*")):
        dst = LIBS_DIR / candidate.name
        print(f"\nCopying {candidate} -> {dst}")
        shutil.copy2(candidate, dst)

    # Copy the C header
    header_src = LATTICE_CPP_DIR / "Sources" / "LatticeCAPI" / "include" / "lattice.h"
    if header_src.exists():
        header_dst = LIBS_DIR / "lattice.h"
        print(f"Copying {header_src} -> {header_dst}")
        shutil.copy2(header_src, header_dst)

    print("\nLibrary copied successfully!")


def clean() -> None:
    """Clean build artifacts."""
    print("Cleaning build artifacts...")

    if LIBS_DIR.exists():
        shutil.rmtree(LIBS_DIR)
        print(f"Removed {LIBS_DIR}")


def configure_android_cmake(ndk_path: Path, abi: str) -> Path:
    """Configure CMake for Android cross-compilation."""
    build_dir = LATTICE_CPP_DIR / f"cmake-build-android-{abi}"
    build_dir.mkdir(parents=True, exist_ok=True)

    toolchain = ndk_path / "build" / "cmake" / "android.toolchain.cmake"

    cmake_args = [
        "cmake",
        str(LATTICE_CPP_DIR),
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        f"-DANDROID_ABI={abi}",
        "-DANDROID_PLATFORM=android-26",
        "-DANDROID_STL=c++_static",
    ]

    run_command(cmake_args, cwd=build_dir)
    return build_dir


def build_android_cmake(build_dir: Path) -> None:
    """Build the Android library (LatticeCAPI target only, skip tests)."""
    build_args = [
        "cmake",
        "--build", str(build_dir),
        "--config", "Release",
        "--target", "LatticeCAPI",
        "--parallel",
    ]
    run_command(build_args)


def copy_android_library(build_dir: Path, abi: str) -> None:
    """Copy the built Android library to jniLibs."""
    lib_name = "libLatticeCAPI.so"
    src = build_dir / lib_name

    if not src.exists():
        raise RuntimeError(f"Built library not found at {src}")

    dst_dir = ANDROID_JNI_LIBS_DIR / abi
    dst_dir.mkdir(parents=True, exist_ok=True)

    dst = dst_dir / lib_name
    print(f"Copying {src} -> {dst}")
    shutil.copy2(src, dst)


def build_android(abis: list[str] | None = None) -> None:
    """Build the library for Android architectures."""
    ndk_path = find_android_ndk()
    if ndk_path is None:
        raise RuntimeError(
            "Android NDK not found. Set ANDROID_NDK_HOME or install via Android Studio."
        )

    print(f"Using Android NDK: {ndk_path}")

    if abis is None:
        abis = list(ANDROID_ABIS.keys())

    for abi in abis:
        print(f"\n{'=' * 60}")
        print(f"Building for Android {abi}")
        print('=' * 60)

        build_dir = configure_android_cmake(ndk_path, abi)
        build_android_cmake(build_dir)
        copy_android_library(build_dir, abi)

    print(f"\nAndroid libraries installed to: {ANDROID_JNI_LIBS_DIR}")


def main() -> None:
    """Main entry point."""
    import argparse

    parser = argparse.ArgumentParser(description="Build LatticeKotlin native dependencies")
    parser.add_argument("--clean", action="store_true", help="Clean build artifacts")
    parser.add_argument("--skip-build", action="store_true", help="Skip building, just copy existing library")
    parser.add_argument("--reconfigure", action="store_true", help="Force CMake reconfiguration")
    parser.add_argument("--android", action="store_true", help="Build for Android (cross-compile)")
    parser.add_argument("--abi", type=str, help="Specific Android ABI to build (e.g., arm64-v8a)")
    args = parser.parse_args()

    if args.clean:
        clean()
        return

    if args.android:
        abis = [args.abi] if args.abi else None
        build_android(abis)
        print("\n" + "=" * 60)
        print("Android build complete!")
        print(f"Libraries installed to: {ANDROID_JNI_LIBS_DIR}")
        print("=" * 60)
        return

    if not args.skip_build:
        # Configure if needed
        cmake_cache = CMAKE_BUILD_DIR / "CMakeCache.txt"
        if args.reconfigure or not cmake_cache.exists():
            configure_cmake()

        build_cmake()

    copy_library()

    print("\n" + "=" * 60)
    print("Build complete!")
    print(f"Library installed to: {LIBS_DIR / get_lib_name()}")
    print("\nTo build the Kotlin project:")
    print("  ./gradlew build")
    print("\nTo build for Android:")
    print("  python build.py --android")
    print("=" * 60)


if __name__ == "__main__":
    main()
