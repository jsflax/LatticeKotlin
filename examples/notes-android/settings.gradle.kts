pluginManagement {
    includeBuild("../..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LatticeNotesAndroid"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.jsflax:lattice-runtime")).using(project(":lattice-runtime"))
    }
}

include(":app")
