import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.example.notes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.notes"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Lattice runtime
    implementation("com.lattice:lattice-runtime")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // DateTime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // Serialization (for auth API)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// Apply the Lattice compiler plugin.
// Resolved by glob rather than a hardcoded version: this is a composite build, so
// a version bump in latticekotlin would otherwise leave -Xplugin pointing at a
// MISSING jar, which silently disables the @Model transform instead of failing.
val compilerPluginLibs = rootProject.file("../../lattice-compiler-plugin/build/libs")
val compilerPluginJar: File
    get() = compilerPluginLibs.listFiles()
        ?.filter { it.name.startsWith("lattice-compiler-plugin-") && it.name.endsWith(".jar") }
        ?.maxByOrNull { it.lastModified() }
        ?: error("No lattice-compiler-plugin jar in $compilerPluginLibs — run :lattice-compiler-plugin:jar")

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        freeCompilerArgs.add(providers.provider { "-Xplugin=${compilerPluginJar.absolutePath}" }.get())
    }
}

// Ensure the compiler plugin is built before compiling
gradle.projectsEvaluated {
    tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
        dependsOn(gradle.includedBuild("latticekotlin").task(":lattice-compiler-plugin:jar"))
    }
}
