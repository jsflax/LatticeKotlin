package com.lattice.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * FIR extension that adds LatticeObject as a supertype to @Model classes.
 *
 * This runs during the SUPERTYPES phase, before type checking, so the frontend
 * knows that @Model classes implement LatticeObject.
 */
class LatticeFirSupertypeGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {

    companion object {
        val MODEL_ANNOTATION = ClassId.topLevel(FqName("com.lattice.Model"))
        val LATTICE_OBJECT = ClassId.topLevel(FqName("com.lattice.LatticeObject"))
        val MODEL_ANNOTATION_SHORT = "Model"
    }

    override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
        // During SUPERTYPES phase, annotations aren't resolved yet.
        // Check for @Model annotation by looking at the unresolved annotation names.
        return declaration.annotations.any { annotation ->
            val typeRef = annotation.annotationTypeRef
            if (typeRef is FirUserTypeRef) {
                // Check if the last part of the qualified name is "Model"
                val qualifier = typeRef.qualifier
                qualifier.lastOrNull()?.name?.asString() == MODEL_ANNOTATION_SHORT
            } else {
                false
            }
        }
    }

    override fun computeAdditionalSupertypes(
        classLikeDeclaration: FirClassLikeDeclaration,
        resolvedSupertypes: List<FirResolvedTypeRef>,
        typeResolver: TypeResolveService
    ): List<FirResolvedTypeRef> {
        // Check if LatticeObject is already a supertype
        val alreadyHasLatticeObject = resolvedSupertypes.any { typeRef ->
            typeRef.type.classId == LATTICE_OBJECT
        }

        if (alreadyHasLatticeObject) {
            return emptyList()
        }

        // Find the LatticeObject interface symbol
        val latticeObjectSymbol = session.symbolProvider
            .getClassLikeSymbolByClassId(LATTICE_OBJECT) as? FirRegularClassSymbol
            ?: return emptyList()

        // Create a ConeKotlinType for LatticeObject
        val latticeObjectType = LATTICE_OBJECT.constructClassLikeType(
            typeArguments = emptyArray(),
            isNullable = false
        )

        return listOf(
            buildResolvedTypeRef {
                type = latticeObjectType
            }
        )
    }
}
