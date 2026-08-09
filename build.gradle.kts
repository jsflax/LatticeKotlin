plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    id("com.android.library") version "8.6.0" apply false
    id("com.gradle.plugin-publish") version "1.2.1" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

group = "io.github.jsflax"
version = "1.0.0"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
