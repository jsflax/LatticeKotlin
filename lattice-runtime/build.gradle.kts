import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.0.21"
    id("com.android.library")
    `maven-publish`
}

// LatticeCore is a git submodule pinned to a release tag.
// The native library is either pre-built in libs/ or built from source via CMake.
val latticeCoreSrc = rootProject.file("LatticeCore")
val latticeCoreHeader = latticeCoreSrc.resolve("Sources/LatticeCAPI/include/lattice.h")
val libsDir = project.file("libs")
val compilerPluginProject = project(":lattice-compiler-plugin")

kotlin {
    // Android target (JVM-based, produces AAR)
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    // Native targets - Desktop/Server
    macosArm64()
    macosX64()
    linuxX64()
    // iosArm64()
    // iosSimulatorArm64()

    // Note: Android Native targets (androidNativeArm64, etc.) are NOT included
    // because Ktor doesn't support them. Android apps use the JVM-based
    // androidTarget with JNI instead.

    sourceSets {
        commonMain {
            dependencies {
                // JSON serialization for embedded models
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // Coroutines for scheduler support
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                // Date/time support
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                // UUID: using kotlin.uuid.Uuid from stdlib (Kotlin 2.0+)
                // Ktor for WebSocket support (multiplatform)
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation("io.ktor:ktor-client-websockets:2.3.7")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }

        // Native source set for desktop native targets
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Ktor CIO engine for native platforms (macOS, Linux)
                implementation("io.ktor:ktor-client-cio:2.3.7")
            }
        }

        val nativeTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                // Ktor Server CIO for in-process sync relay (mirrors Swift's Vapor test server)
                implementation("io.ktor:ktor-server-core:2.3.7")
                implementation("io.ktor:ktor-server-cio:2.3.7")
                implementation("io.ktor:ktor-server-websockets:2.3.7")
            }
        }

        // All native targets depend on nativeMain/nativeTest
        val macosArm64Main by getting { dependsOn(nativeMain) }
        val macosX64Main by getting { dependsOn(nativeMain) }
        val linuxX64Main by getting { dependsOn(nativeMain) }
        val macosArm64Test by getting { dependsOn(nativeTest) }
        val macosX64Test by getting { dependsOn(nativeTest) }
        val linuxX64Test by getting { dependsOn(nativeTest) }

        // Android JVM target has its own implementation (JNI)
        // Does NOT depend on nativeMain (cinterop not available on JVM)
        val androidMain by getting {
            dependsOn(commonMain.get())
            dependencies {
                // Ktor OkHttp engine for Android
                implementation("io.ktor:ktor-client-okhttp:2.3.7")
            }
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        val target = this

        compilations.getByName("main") {
            cinterops {
                create("lattice") {
                    defFile = file("src/nativeInterop/cinterop/lattice.def")

                    // Set include and library paths
                    includeDirs(libsDir)

                    extraOpts("-libraryPath", libsDir.absolutePath)
                }
            }
        }

        // Configure all binaries (including test binaries)
        binaries.all {
            linkerOpts("-L${libsDir.absolutePath}", "-lLatticeCAPI")

            // Set rpath for runtime library loading
            when {
                target.konanTarget.family.isAppleFamily -> {
                    linkerOpts("-Wl,-rpath,@executable_path")
                    linkerOpts("-Wl,-rpath,${libsDir.absolutePath}")
                }
                else -> {
                    // Linux and other Unix-like systems
                    linkerOpts("-Wl,-rpath,\$ORIGIN")
                    linkerOpts("-Wl,-rpath,${libsDir.absolutePath}")
                }
            }
        }
    }
}

// Android library configuration
android {
    namespace = "com.lattice"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        // Configure NDK build
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Configure CMake for JNI build
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Location of prebuilt native libraries
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/androidMain/jniLibs")
        }
    }
}

// Task to verify native library exists
tasks.register("checkNativeLib") {
    doLast {
        val libName = when {
            org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "libLatticeCAPI.dylib"
            org.gradle.internal.os.OperatingSystem.current().isWindows -> "LatticeCAPI.dll"
            else -> "libLatticeCAPI.so"
        }
        val libFile = libsDir.resolve(libName)
        if (!libFile.exists()) {
            throw GradleException(
                """
                Native library not found at: $libFile

                Run 'python build.py' to build and copy the LatticeCAPI library.
                """.trimIndent()
            )
        }
        println("Found native library: $libFile")
    }
}

// Make cinterop depend on library check
tasks.matching { it.name.contains("cinterop", ignoreCase = true) }.configureEach {
    dependsOn("checkNativeLib")
}

publishing {
    publications.withType<MavenPublication> {
        artifactId = "lattice-runtime-${name}"
    }
}

// Apply compiler plugin to test compilations
tasks.withType<KotlinCompilationTask<*>>().matching {
    it.name.contains("Test", ignoreCase = true)
}.configureEach {
    dependsOn(":lattice-compiler-plugin:jar")
    compilerOptions {
        val pluginJar = compilerPluginProject.layout.buildDirectory.file("libs/lattice-compiler-plugin-0.10.0-SNAPSHOT.jar")
        freeCompilerArgs.add("-Xplugin=${pluginJar.get().asFile.absolutePath}")
    }
}
