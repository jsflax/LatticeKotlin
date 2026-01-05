pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "LatticeKotlin"

include(":lattice-runtime")
include(":lattice-compiler-plugin")
include(":lattice-gradle-plugin")
include(":example")
