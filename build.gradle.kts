plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("jvm") version "2.4.10" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.gradle.plugin-publish") version "1.2.1" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

group = "io.github.jsflax"
version = "3.1.2"

val externalBuildRoot = System.getenv("LATTICE_GRADLE_BUILD_DIR")?.takeIf { it.isNotBlank() }

if (externalBuildRoot != null) {
    layout.buildDirectory.set(file("$externalBuildRoot/root"))
}

subprojects {
    group = rootProject.group
    version = rootProject.version
    if (externalBuildRoot != null) {
        layout.buildDirectory.set(file("$externalBuildRoot/${project.name}"))
    }
}
