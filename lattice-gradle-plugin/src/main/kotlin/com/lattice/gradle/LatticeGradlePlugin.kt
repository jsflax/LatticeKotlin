package com.lattice.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class LatticeGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        val ext = target.extensions.findByType(LatticeExtension::class.java)
            ?: target.extensions.create("lattice", LatticeExtension::class.java)

        target.afterEvaluate {
            if (!ext.addRuntimeDependency) return@afterEvaluate
            val coord = "io.github.jsflax:lattice-runtime:${LatticeVersion.VERSION}"
            when {
                target.configurations.findByName("commonMainImplementation") != null -> {
                    target.dependencies.add("commonMainImplementation", coord)
                }
                target.configurations.findByName("implementation") != null -> {
                    target.dependencies.add("implementation", coord)
                }
            }
        }
    }

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider { emptyList() }
    }

    override fun getCompilerPluginId(): String = "com.lattice.compiler"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "io.github.jsflax",
        artifactId = "lattice-compiler-plugin",
        version = LatticeVersion.VERSION
    )

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true
}

open class LatticeExtension {
    var addRuntimeDependency: Boolean = true
}
