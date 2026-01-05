package com.lattice.compiler.fir

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/**
 * Registrar for Lattice FIR extensions.
 *
 * This registers:
 * - LatticeFirSupertypeGenerator: Adds LatticeObject interface to @Model classes
 * - LatticeFirDeclarationGenerator: Generates _latticeHandle and _latticeTableName properties
 */
class LatticeFirExtensionRegistrar : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        // Register the supertype generator (adds LatticeObject interface)
        +::LatticeFirSupertypeGenerator

        // Register the declaration generator (generates synthetic properties)
        +::LatticeFirDeclarationGenerator
    }
}
