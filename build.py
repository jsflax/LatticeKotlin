#!/usr/bin/env python3
"""
Build script for LatticeKotlin.

Builds the LatticeCAPI native library (and host JNI library for JVM unit tests)
and copies it into the Kotlin project. Supports both host builds and Android
NDK r27c cross-compilation with mandatory Android 15/16 16 KB ELF page alignment.
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.resolve()
LATTICE_CPP_DIR = Path(os.environ.get("LATTICE_CORE_DIR", str(SCRIPT_DIR / "LatticeCore"))).resolve()
LIBS_DIR = SCRIPT_DIR / "lattice-runtime" / "libs"
BUILD_ROOT = Path(os.environ.get("LATTICE_CMAKE_BUILD_ROOT", str(LATTICE_CPP_DIR))).resolve()
CMAKE_BUILD_DIR = BUILD_ROOT / "cmake-build"

ANDROID_JNI_LIBS_DIR = SCRIPT_DIR / "lattice-runtime" / "src" / "androidMain" / "jniLibs"
JNI_SRC = SCRIPT_DIR / "lattice-runtime" / "src" / "androidMain" / "jni" / "lattice_jni.c"
ANDROID_ABIS = {
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
}
PREFERRED_NDK_VERSION = "27.2.12479018"


def find_android_ndk() -> Path | None:
    """Find the Android NDK installation, preferring NDK r27c (27.2.12479018)."""
    env_candidates = [
        os.environ.get("ANDROID_NDK_HOME", ""),
        os.environ.get("ANDROID_NDK_ROOT", ""),
        os.environ.get("NDK_HOME", ""),
    ]
    for raw in env_candidates:
        if raw:
            p = Path(raw)
            if (p / "ndk-build").exists():
                return p

    sdk_roots = [
        Path.home() / "Library/Android/sdk/ndk",
        Path.home() / "Android/Sdk/ndk",
        Path("/usr/local/lib/android/sdk/ndk"),
    ]
    for root in sdk_roots:
        if not root.exists():
            continue
        preferred = root / PREFERRED_NDK_VERSION
        if (preferred / "ndk-build").exists():
            return preferred
        r27c = root / "android-ndk-r27c"
        if (r27c / "ndk-build").exists():
            return r27c
        for sub in sorted(root.iterdir(), reverse=True):
            if (sub / "ndk-build").exists():
                return sub
    return None


def find_ndk_tool(ndk_path: Path, tool_name: str) -> Path | None:
    prebuilt = ndk_path / "toolchains" / "llvm" / "prebuilt"
    if not prebuilt.exists():
        return None
    for host_dir in sorted(prebuilt.iterdir()):
        candidate = host_dir / "bin" / tool_name
        if candidate.exists():
            return candidate
    return None


def get_lib_name() -> str:
    if sys.platform == "darwin":
        return "libLatticeCAPI.dylib"
    elif sys.platform == "win32":
        return "LatticeCAPI.dll"
    return "libLatticeCAPI.so"


def run_command(cmd: list[str], cwd: Path = None, env: dict = None) -> str:
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        print("STDOUT:", result.stdout)
        print("STDERR:", result.stderr)
        raise RuntimeError(f"Command failed: {' '.join(cmd)}")
    if result.stdout.strip():
        print(result.stdout)
    return result.stdout


def configure_cmake() -> None:
    print(f"\nConfiguring CMake in {CMAKE_BUILD_DIR}...")
    if not LATTICE_CPP_DIR.exists():
        raise RuntimeError(f"LatticeCore not found at {LATTICE_CPP_DIR}")
    CMAKE_BUILD_DIR.mkdir(parents=True, exist_ok=True)
    cmake_args = [
        "cmake",
        str(LATTICE_CPP_DIR),
        "-DCMAKE_BUILD_TYPE=Release",
    ]
    run_command(cmake_args, cwd=CMAKE_BUILD_DIR)


def build_cmake() -> None:
    print("\nBuilding LatticeCAPI with CMake...")
    build_args = [
        "cmake",
        "--build", str(CMAKE_BUILD_DIR),
        "--config", "Release",
        "--target", "LatticeCAPI",
        "--parallel", "4",
    ]
    run_command(build_args)


def find_java_home() -> Path | None:
    env_jh = os.environ.get("JAVA_HOME", "")
    if env_jh and (Path(env_jh) / "include" / "jni.h").exists():
        return Path(env_jh)
    candidates = [
        Path.home() / ".sdkman/candidates/java/current",
        Path.home() / ".sdkman/candidates/java/17.0.9-tem",
        Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home"),
    ]
    for c in candidates:
        if (c / "include" / "jni.h").exists():
            return c
    return None


def build_host_jni() -> None:
    """Build host liblattice_jni.{dylib,so} so JVM unit tests can exercise JNI on macOS/Linux."""
    java_home = find_java_home()
    if java_home is None or not JNI_SRC.exists():
        print("Skipping host JNI build (JAVA_HOME with include/jni.h not found)")
        return
    os_inc = "darwin" if sys.platform == "darwin" else "linux"
    out_name = "liblattice_jni.dylib" if sys.platform == "darwin" else "liblattice_jni.so"
    out_path = LIBS_DIR / out_name
    cc = os.environ.get("CC", "clang")
    cmd = [
        cc, "-shared", "-fPIC", "-O2",
        f"-I{java_home / 'include'}",
        f"-I{java_home / 'include' / os_inc}",
        f"-I{LIBS_DIR}",
        str(JNI_SRC),
        f"-L{LIBS_DIR}", "-lLatticeCAPI",
        "-o", str(out_path),
    ]
    if sys.platform == "darwin":
        cmd.extend(["-Wl,-rpath,@loader_path"])
    else:
        cmd.extend(["-Wl,-rpath,$ORIGIN", "-lpthread"])
    run_command(cmd)
    print(f"Built host JNI library: {out_path}")


def copy_library(src_dir: Path | None = None) -> None:
    build_dir = src_dir or CMAKE_BUILD_DIR
    lib_name = get_lib_name()
    src = build_dir / lib_name
    if not src.exists():
        raise RuntimeError(f"Built library not found at {src}")

    LIBS_DIR.mkdir(parents=True, exist_ok=True)
    stem = lib_name.split(".")[0]
    for candidate in sorted(build_dir.glob(f"{stem}*")):
        dst = LIBS_DIR / candidate.name
        print(f"Copying {candidate} -> {dst}")
        shutil.copy2(candidate, dst)

    header_src = LATTICE_CPP_DIR / "Sources" / "LatticeCAPI" / "include" / "lattice.h"
    if header_src.exists():
        header_dst = LIBS_DIR / "lattice.h"
        shutil.copy2(header_src, header_dst)

    build_host_jni()
    print("\nHost library + JNI copied successfully!")


def verify_16kb_alignment(ndk_path: Path, so_path: Path) -> None:
    readobj = find_ndk_tool(ndk_path, "llvm-readobj")
    if readobj is None:
        return
    out = subprocess.check_output([str(readobj), "-l", str(so_path)], text=True)
    # Match PT_LOAD blocks and verify Alignment >= 16384
    blocks = out.split("ProgramHeader {")
    load_alignments = []
    for blk in blocks[1:]:
        if "Type: PT_LOAD" in blk:
            m = re.search(r"Alignment:\s*(\d+)", blk)
            if m:
                load_alignments.append(int(m.group(1)))
    if not load_alignments or any(a < 16384 for a in load_alignments):
        raise RuntimeError(
            f"16 KB ELF alignment check FAILED for {so_path}: PT_LOAD alignments={load_alignments}"
        )
    print(f"Verified 16 KB ELF PT_LOAD alignment for {so_path.name}: {load_alignments}")


def configure_android_cmake(ndk_path: Path, abi: str) -> Path:
    build_dir = BUILD_ROOT / f"cmake-build-android-{abi}"
    build_dir.mkdir(parents=True, exist_ok=True)
    toolchain = ndk_path / "build" / "cmake" / "android.toolchain.cmake"
    linker_flags = "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--gc-sections"
    cmake_args = [
        "cmake",
        str(LATTICE_CPP_DIR),
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        f"-DANDROID_ABI={abi}",
        "-DANDROID_PLATFORM=android-26",
        "-DANDROID_STL=c++_static",
        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
        f"-DCMAKE_SHARED_LINKER_FLAGS={linker_flags}",
    ]
    run_command(cmake_args, cwd=build_dir)
    return build_dir


def build_android_cmake(build_dir: Path) -> None:
    build_args = [
        "cmake",
        "--build", str(build_dir),
        "--config", "Release",
        "--target", "LatticeCAPI",
        "--parallel", "4",
    ]
    run_command(build_args)


def copy_android_library(ndk_path: Path, build_dir: Path, abi: str) -> None:
    lib_name = "libLatticeCAPI.so"
    src = build_dir / lib_name
    if not src.exists():
        raise RuntimeError(f"Built library not found at {src}")

    dst_dir = ANDROID_JNI_LIBS_DIR / abi
    dst_dir.mkdir(parents=True, exist_ok=True)
    dst = dst_dir / lib_name
    shutil.copy2(src, dst)

    strip_tool = find_ndk_tool(ndk_path, "llvm-strip")
    if strip_tool is not None:
        run_command([str(strip_tool), "--strip-unneeded", str(dst)])

    verify_16kb_alignment(ndk_path, dst)
    size_mb = dst.stat().st_size / (1024 * 1024)
    print(f"Installed stripped 16KB-aligned {dst} ({size_mb:.2f} MiB)")


def build_android(abis: list[str] | None = None) -> None:
    ndk_path = find_android_ndk()
    if ndk_path is None:
        raise RuntimeError("Android NDK not found. Set ANDROID_NDK_HOME.")
    print(f"Using Android NDK: {ndk_path}")
    for abi in (abis or list(ANDROID_ABIS.keys())):
        print(f"\n{'=' * 60}\nBuilding for Android {abi}\n{'=' * 60}")
        build_dir = configure_android_cmake(ndk_path, abi)
        build_android_cmake(build_dir)
        copy_android_library(ndk_path, build_dir, abi)


def clean() -> None:
    if LIBS_DIR.exists():
        shutil.rmtree(LIBS_DIR)


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser(description="Build LatticeKotlin native dependencies")
    parser.add_argument("--clean", action="store_true")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--from-build-dir", type=str, help="Copy host library from existing CMake build dir")
    parser.add_argument("--reconfigure", action="store_true")
    parser.add_argument("--android", action="store_true")
    parser.add_argument("--abi", type=str)
    args = parser.parse_args()

    if args.clean:
        clean()
        return
    if args.android:
        build_android([args.abi] if args.abi else None)
        return
    if args.from_build_dir:
        copy_library(Path(args.from_build_dir).resolve())
        return
    if not args.skip_build:
        if not (CMAKE_BUILD_DIR / "CMakeCache.txt").exists() or args.reconfigure:
            configure_cmake()
        build_cmake()
    copy_library()


if __name__ == "__main__":
    main()
