package com.lattice.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * FIR extension that generates synthetic properties for @Model classes:
 * - _latticeHandle: Long (mutable, for tracking native object pointer)
 * - _latticeTableName: String (read-only, returns class name)
 * - _latticeSchema: List<LatticePropertyDescriptor> (schema info for the model)
 *
 * These properties are declared by the LatticeObject interface and
 * implemented by the compiler plugin.
 */
class LatticeFirDeclarationGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {

    companion object {
        val MODEL_ANNOTATION = ClassId.topLevel(FqName("com.lattice.Model"))
        val MODEL_ANNOTATION_SHORT = "Model"

        val LATTICE_HANDLE = Name.identifier("_latticeHandle")
        val LATTICE_TABLE_NAME = Name.identifier("_latticeTableName")
        val LATTICE_SCHEMA = Name.identifier("_latticeSchema")
        val COPY_TO_NATIVE = Name.identifier("_copyPropertiesToNative")

        val LATTICE_PROPERTY_DESCRIPTOR = ClassId.topLevel(FqName("com.lattice.LatticePropertyDescriptor"))

        // Key to identify our generated declarations in IR phase
        object Key : GeneratedDeclarationKey()
    }

    @OptIn(SymbolInternals::class)
    private fun FirClassSymbol<*>.isModelClass(): Boolean {
        // Try resolved annotation check first (works in later FIR phases)
        try {
            if (this.hasAnnotation(MODEL_ANNOTATION, session)) {
                return true
            }
        } catch (_: Exception) {
            // Fall through to unresolved check
        }

        // Fall back to unresolved annotation check (for early phases)
        return this.fir.annotations.any { annotation ->
            val typeRef = annotation.annotationTypeRef
            if (typeRef is FirUserTypeRef) {
                val qualifier = typeRef.qualifier
                qualifier.lastOrNull()?.name?.asString() == MODEL_ANNOTATION_SHORT
            } else {
                false
            }
        }
    }

    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext
    ): Set<Name> {
        // Only generate for @Model classes
        if (!classSymbol.isModelClass()) {
            return emptySet()
        }

        return setOf(LATTICE_HANDLE, LATTICE_TABLE_NAME, LATTICE_SCHEMA, COPY_TO_NATIVE)
    }

    override fun generateProperties(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirPropertySymbol> {
        val owner = context?.owner ?: return emptyList()

        if (!owner.isModelClass()) {
            return emptyList()
        }

        return when (callableId.callableName) {
            LATTICE_HANDLE -> listOf(generateLatticeHandleProperty(owner))
            LATTICE_TABLE_NAME -> listOf(generateTableNameProperty(owner))
            LATTICE_SCHEMA -> listOf(generateSchemaProperty(owner))
            else -> emptyList()
        }
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirNamedFunctionSymbol> {
        val owner = context?.owner ?: return emptyList()

        if (!owner.isModelClass()) {
            return emptyList()
        }

        return when (callableId.callableName) {
            COPY_TO_NATIVE -> listOf(generateCopyToNativeFunction(owner))
            else -> emptyList()
        }
    }

    private fun generateCopyToNativeFunction(
        owner: FirClassSymbol<*>
    ): FirNamedFunctionSymbol {
        // Generate: override fun _copyPropertiesToNative(nativeHandle: Long)
        return createMemberFunction(
            owner = owner,
            key = Key,
            name = COPY_TO_NATIVE,
            returnType = session.builtinTypes.unitType.type
        ) {
            valueParameter(Name.identifier("nativeHandle"), session.builtinTypes.longType.type)
        }.symbol
    }

    private fun generateLatticeHandleProperty(
        owner: FirClassSymbol<*>
    ): FirPropertySymbol {
        // Generate: override var _latticeHandle: Long = 0L
        return createMemberProperty(
            owner = owner,
            key = Key,
            name = LATTICE_HANDLE,
            returnType = session.builtinTypes.longType.type,
            isVal = false,  // var
            hasBackingField = true
        ).symbol
    }

    private fun generateTableNameProperty(
        owner: FirClassSymbol<*>
    ): FirPropertySymbol {
        // Generate: override val _latticeTableName: String get() = "ClassName"
        return createMemberProperty(
            owner = owner,
            key = Key,
            name = LATTICE_TABLE_NAME,
            returnType = session.builtinTypes.stringType.type,
            isVal = true,
            hasBackingField = false
        ).symbol
    }

    private fun generateSchemaProperty(
        owner: FirClassSymbol<*>
    ): FirPropertySymbol {
        // Generate: override val _latticeSchema: List<LatticePropertyDescriptor> get() = ...
        // Create the type: List<LatticePropertyDescriptor>
        val descriptorType = LATTICE_PROPERTY_DESCRIPTOR.constructClassLikeType(
            typeArguments = emptyArray(),
            isNullable = false
        )
        val listType = StandardClassIds.List.constructClassLikeType(
            typeArguments = arrayOf(descriptorType),
            isNullable = false
        )

        return createMemberProperty(
            owner = owner,
            key = Key,
            name = LATTICE_SCHEMA,
            returnType = listType,
            isVal = true,
            hasBackingField = false
        ).symbol
    }
}
