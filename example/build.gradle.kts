import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
}

val libsDir = project(":lattice-runtime").file("libs")

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "com.example.main"
                // Link against LatticeCAPI
                linkerOpts("-L${libsDir.absolutePath}", "-lLatticeCAPI")
                linkerOpts("-Wl,-rpath,@executable_path")
                linkerOpts("-Wl,-rpath,${libsDir.absolutePath}")
            }
        }
    }

    sourceSets {
        macosArm64Main {
            dependencies {
                implementation(project(":lattice-runtime"))
            }
        }
    }
}

// Apply the compiler plugin to Kotlin Native compilations
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(":lattice-compiler-plugin:jar")
    // Version-derived: a hardcoded name survives a version bump as a path to a
    // MISSING jar, which silently disables the @Model transform instead of failing.
    val pluginJar = project(":lattice-compiler-plugin").layout.buildDirectory
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
