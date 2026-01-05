pluginManagement {
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

// Include the lattice modules from parent project
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.lattice:lattice-runtime")).using(project(":lattice-runtime"))
    }
}

include(":app")
