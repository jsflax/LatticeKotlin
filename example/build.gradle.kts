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
    compilerOptions {
        val pluginJar = project(":lattice-compiler-plugin").layout.buildDirectory.file("libs/lattice-compiler-plugin-0.1.0-SNAPSHOT.jar")
        freeCompilerArgs.add("-Xplugin=${pluginJar.get().asFile.absolutePath}")
    }
}
