package com.lattice.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

class LatticeIrGenerationExtension(
    private val messageCollector: MessageCollector
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        messageCollector.report(
            CompilerMessageSeverity.WARNING,
            "Lattice: Processing module ${moduleFragment.name}"
        )

        // Log all files in the module for debugging
        moduleFragment.files.forEach { file ->
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice:   File: ${file.fileEntry.name}"
            )
        }

        val transformer = LatticeIrTransformer(pluginContext, messageCollector)
        moduleFragment.transform(transformer, null)
    }
}
