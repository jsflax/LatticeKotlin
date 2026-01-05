package com.lattice.compiler

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CommandLineProcessor::class)
class LatticeCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = "com.lattice.compiler"

    override val pluginOptions: Collection<AbstractCliOption> = emptyList()

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        // No options to process currently
    }
}
