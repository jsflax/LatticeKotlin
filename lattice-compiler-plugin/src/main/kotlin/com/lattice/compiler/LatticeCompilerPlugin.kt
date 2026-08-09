package com.lattice.compiler

import com.google.auto.service.AutoService
import com.lattice.compiler.fir.LatticeFirExtensionRegistrar
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class LatticeCompilerPluginRegistrar : CompilerPluginRegistrar() {

    // Abstract in 2.4: the compiler uses it to key plugin state, and it must be
    // stable across releases. Matches the id the Gradle plugin passes.
    override val pluginId: String = "com.lattice.compiler"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(
            CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            MessageCollector.NONE
        )

        // Register FIR extensions (K2 frontend)
        // These run during frontend analysis and add LatticeObject interface + synthetic properties
        FirExtensionRegistrarAdapter.registerExtension(LatticeFirExtensionRegistrar())

        // Register IR extensions (backend)
        // These transform property getters/setters to delegate to native layer
        IrGenerationExtension.registerExtension(
            LatticeIrGenerationExtension(messageCollector)
        )
    }
}
