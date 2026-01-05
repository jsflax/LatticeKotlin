package com.lattice.compiler

import com.google.auto.service.AutoService
import com.lattice.compiler.fir.LatticeFirExtensionRegistrar
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class LatticeCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(
            CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
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
