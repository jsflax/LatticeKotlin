plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    id("com.android.library") version "8.6.0" apply false
    id("com.gradle.plugin-publish") version "1.2.1" apply false
}

group = "com.lattice"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
