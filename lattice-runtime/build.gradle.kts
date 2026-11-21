import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.4.10"
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

val latticeCoreSrc = rootProject.file("LatticeCore")
val libsDir = project.file("libs")
val compilerPluginProject = project(":lattice-compiler-plugin")

kotlin {
    // Android target (JVM-based, produces AAR)
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Native targets - Desktop/Server
    macosArm64()
    macosX64()
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-websockets:3.0.0")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }

        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("io.ktor:ktor-client-cio:3.0.0")
            }
        }

        val nativeTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation("io.ktor:ktor-server-core:3.0.0")
                implementation("io.ktor:ktor-server-cio:3.0.0")
                implementation("io.ktor:ktor-server-websockets:3.0.0")
            }
        }

        val macosArm64Main by getting { dependsOn(nativeMain) }
        val macosX64Main by getting { dependsOn(nativeMain) }
        val linuxX64Main by getting { dependsOn(nativeMain) }
        val macosArm64Test by getting { dependsOn(nativeTest) }
        val macosX64Test by getting { dependsOn(nativeTest) }
        val linuxX64Test by getting { dependsOn(nativeTest) }

        val androidMain by getting {
            dependsOn(commonMain.get())
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.0.0")
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("org.ow2.asm:asm-util:9.7")
            }
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test:core:1.6.1")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        val target = this

        compilations.getByName("main") {
            cinterops {
                create("lattice") {
                    definitionFile.set(file("src/nativeInterop/cinterop/lattice.def"))
                    includeDirs(libsDir)
                    extraOpts("-libraryPath", libsDir.absolutePath)
                }
            }
        }

        binaries.all {
            linkerOpts("-L${libsDir.absolutePath}", "-lLatticeCAPI")

            when {
                target.konanTarget.family.isAppleFamily -> {
                    linkerOpts("-Wl,-rpath,@executable_path")
                    linkerOpts("-Wl,-rpath,${libsDir.absolutePath}")
                }
                else -> {
                    linkerOpts("-Wl,-rpath,\$ORIGIN")
                    linkerOpts("-Wl,-rpath,${libsDir.absolutePath}")
                }
            }
        }
    }
}

android {
    namespace = "com.lattice"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val localR27c = sdkRoot?.let { File(it, "ndk/android-ndk-r27c") }
    if (localR27c != null && localR27c.isDirectory) {
        ndkPath = localR27c.absolutePath
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/androidMain/jniLibs")
        }
    }
}

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

                Run 'python3 build.py' to build and copy the LatticeCAPI library.
                """.trimIndent()
            )
        }
        println("Found native library: $libFile")
    }
}

tasks.matching { it.name.contains("cinterop", ignoreCase = true) }.configureEach {
    dependsOn("checkNativeLib")
}

afterEvaluate {
    val hostIsMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
    val hostIsLinux = org.gradle.internal.os.OperatingSystem.current().isLinux
    val hostIsArm = System.getProperty("os.arch") in listOf("aarch64", "arm64")
    val targetLibAvailable = mapOf(
        "MacosArm64" to (hostIsMac && hostIsArm && libsDir.resolve("libLatticeCAPI.dylib").exists()),
        "MacosX64" to (hostIsMac && !hostIsArm && libsDir.resolve("libLatticeCAPI.dylib").exists()),
        "LinuxX64" to (hostIsLinux && libsDir.resolve("libLatticeCAPI.so").exists())
    )
    for ((target, available) in targetLibAvailable) {
        if (!available) {
            tasks.matching {
                it.name == "linkDebugTest$target" || it.name == "${target.replaceFirstChar(Char::lowercase)}Test"
            }.configureEach { enabled = false }
        }
    }
}

val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.keyId").isPresent

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (hasSigningKey) {
        signAllPublications()
    }
    coordinates("io.github.jsflax", "lattice-runtime", version.toString())
    pom {
        name.set("Lattice Runtime")
        description.set("Kotlin Multiplatform runtime for Lattice — an embedded reactive object store with sync.")
        url.set("https://github.com/jsflax/LatticeKotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("jsflax")
                name.set("Jason Flax")
                url.set("https://github.com/jsflax")
            }
        }
        scm {
            url.set("https://github.com/jsflax/LatticeKotlin")
            connection.set("scm:git:git://github.com/jsflax/LatticeKotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/jsflax/LatticeKotlin.git")
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("lattice.module.dir", projectDir.absolutePath)
    systemProperty("lattice.host.native.dir", libsDir.absolutePath)
    System.getProperty("lattice.golden.update")?.let { systemProperty("lattice.golden.update", it) }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    providers.environmentVariable("LATTICE_CONFORMANCE_DIR").orNull?.let {
        environment("LATTICE_CONFORMANCE_DIR", it)
    }
}

tasks.withType<KotlinCompilationTask<*>>().matching {
    it.name.contains("Test", ignoreCase = true)
}.configureEach {
    dependsOn(":lattice-compiler-plugin:jar")
    val pluginJar = compilerPluginProject.layout.buildDirectory
        .file("libs/lattice-compiler-plugin-${project.version}.jar")
    compilerOptions {
        freeCompilerArgs.add("-Xplugin=${pluginJar.get().asFile.absolutePath}")
    }

    inputs.file(pluginJar)
        .withPropertyName("latticeCompilerPluginJar")
        .withPathSensitivity(PathSensitivity.NONE)

    doFirst {
        val jar = pluginJar.get().asFile
        check(jar.exists()) { "Lattice compiler plugin jar missing: $jar — the @Model transform would silently no-op." }
    }
}
