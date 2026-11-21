package com.lattice.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * FIR extension that generates synthetic members & hydration constructor for `@Model` classes:
 * - `constructor(handle: Long, marker: LatticeHydrationMarker)` (zero-leak managed row hydration)
 * - `_latticeHandle: Long`
 * - `_latticeTableName: String`
 * - `_latticeSchema: List<LatticePropertyDescriptor>`
 * - `_copyPropertiesToNative(nativeHandle: Long)`
 */
class LatticeFirDeclarationGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {

    companion object {
        val MODEL_ANNOTATION = ClassId.topLevel(FqName("com.lattice.Model"))
        const val MODEL_ANNOTATION_SHORT = "Model"

        val LATTICE_HANDLE = Name.identifier("_latticeHandle")
        val LATTICE_TABLE_NAME = Name.identifier("_latticeTableName")
        val LATTICE_SCHEMA = Name.identifier("_latticeSchema")
        val COPY_TO_NATIVE = Name.identifier("_copyPropertiesToNative")

        val LATTICE_PROPERTY_DESCRIPTOR = ClassId.topLevel(FqName("com.lattice.LatticePropertyDescriptor"))
        val LATTICE_HYDRATION_MARKER = ClassId.topLevel(FqName("com.lattice.LatticeHydrationMarker"))

        object Key : GeneratedDeclarationKey()
    }

    @OptIn(SymbolInternals::class)
    private fun FirClassSymbol<*>.isModelClass(): Boolean {
        try {
            if (this.hasAnnotation(MODEL_ANNOTATION, session)) {
                return true
            }
        } catch (_: Exception) {
            // Fall through to unresolved check in early FIR phases
        }

        return this.fir.annotations.any { annotation ->
            val typeRef = annotation.annotationTypeRef
            if (typeRef is FirUserTypeRef) {
                typeRef.qualifier.lastOrNull()?.name?.asString() == MODEL_ANNOTATION_SHORT
            } else {
                false
            }
        }
    }

    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext
    ): Set<Name> {
        if (!classSymbol.isModelClass()) {
            return emptySet()
        }

        return setOf(
            SpecialNames.INIT,
            LATTICE_HANDLE,
            LATTICE_TABLE_NAME,
            LATTICE_SCHEMA,
            COPY_TO_NATIVE
        )
    }

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        val owner = context.owner
        if (!owner.isModelClass()) {
            return emptyList()
        }

        val markerType = LATTICE_HYDRATION_MARKER.constructClassLikeType(
            typeArguments = emptyArray(),
            isMarkedNullable = false
        )

        val ctor = createConstructor(
            owner = owner,
            key = Key,
            isPrimary = false,
            generateDelegatedNoArgConstructorCall = false
        ) {
            valueParameter(Name.identifier("handle"), session.builtinTypes.longType.coneType)
            valueParameter(Name.identifier("marker"), markerType)
        }
        return listOf(ctor.symbol)
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

    private fun generateCopyToNativeFunction(owner: FirClassSymbol<*>): FirNamedFunctionSymbol {
        return createMemberFunction(
            owner = owner,
            key = Key,
            name = COPY_TO_NATIVE,
            returnType = session.builtinTypes.unitType.coneType
        ) {
            valueParameter(Name.identifier("nativeHandle"), session.builtinTypes.longType.coneType)
        }.symbol
    }

    private fun generateLatticeHandleProperty(owner: FirClassSymbol<*>): FirPropertySymbol {
        return createMemberProperty(
            owner = owner,
            key = Key,
            name = LATTICE_HANDLE,
            returnType = session.builtinTypes.longType.coneType,
            isVal = false,
            hasBackingField = true
        ).symbol
    }

    private fun generateTableNameProperty(owner: FirClassSymbol<*>): FirPropertySymbol {
        return createMemberProperty(
            owner = owner,
            key = Key,
            name = LATTICE_TABLE_NAME,
            returnType = session.builtinTypes.stringType.coneType,
            isVal = true,
            hasBackingField = false
        ).symbol
    }

    private fun generateSchemaProperty(owner: FirClassSymbol<*>): FirPropertySymbol {
        val descriptorType = LATTICE_PROPERTY_DESCRIPTOR.constructClassLikeType(
            typeArguments = emptyArray(),
            isMarkedNullable = false
        )
        val listType = StandardClassIds.List.constructClassLikeType(
            typeArguments = arrayOf(descriptorType),
            isMarkedNullable = false
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
