package com.lattice.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class LatticeGradlePlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {

    override fun apply(project: Project) {
        project.extensions.create("lattice", LatticeExtension::class.java)

        // Add runtime dependency
        project.afterEvaluate {
            project.dependencies.add(
                "implementation",
                "com.lattice:lattice-runtime:${LatticeVersion.VERSION}"
            )
        }
    }

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider { emptyList() }
    }

    override fun getCompilerPluginId(): String = "com.lattice.compiler"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.lattice",
        artifactId = "lattice-compiler-plugin",
        version = LatticeVersion.VERSION
    )

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true
}

open class LatticeExtension {
    // Future configuration options can go here
    // e.g., schema validation mode, sync settings, etc.
}

object LatticeVersion {
    const val VERSION = "0.10.0-SNAPSHOT"
}
