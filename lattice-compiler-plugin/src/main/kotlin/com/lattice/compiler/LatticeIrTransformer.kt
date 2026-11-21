package com.lattice.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR Transformer for `@Model` classes (LatticeKotlin 3.1.2 / Kotlin 2.4 K2 FIR+IR).
 *
 * Implements:
 * - Zero-leak hydration constructor `constructor(handle: Long, marker: LatticeHydrationMarker)`
 * - `_latticeHandle`, `_latticeTableName` (`@Model(tableName)`), `_latticeSchema`, `_copyPropertiesToNative`
 * - Schema flags: `@Unique(compoundedWith, allowsUpsert)`, `@Indexed`, `@FullText`, `@NoHistory`,
 *   `FloatVector`/`DoubleVector` (`FLAG_VECTOR`), `GeoBounds` (`FLAG_GEO_BOUNDS`), `@Property(name)`
 * - `@Union` sealed interfaces & sealed classes (`LATTICE_KIND_UNION = 5`, including unlabeled single
 *   `@Embedded` payloads matching Swift `case verdict(VerdictPayload)`)
 * - Collections: `List<String|Int|Long|Double>`, `Set<String>`, `Map<String, String>`,
 *   `List<@Embedded>`, `List<@LatticeEnum>`, `Set<@LatticeEnum>`
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class LatticeIrTransformer(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector,
    private val moduleFragment: IrModuleFragment
) : IrElementTransformerVoidWithContext() {

    private val modelAnnotationFqn = FqName("com.lattice.Model")
    private val ignoreAnnotationFqn = FqName("com.lattice.Ignore")
    private val linkAnnotationFqn = FqName("com.lattice.Link")
    private val linkListAnnotationFqn = FqName("com.lattice.LinkList")
    private val embeddedAnnotationFqn = FqName("com.lattice.Embedded")
    private val latticeEnumAnnotationFqn = FqName("com.lattice.LatticeEnum")
    private val uniqueAnnotationFqn = FqName("com.lattice.Unique")
    private val indexedAnnotationFqn = FqName("com.lattice.Indexed")
    private val fullTextAnnotationFqn = FqName("com.lattice.FullText")
    private val noHistoryAnnotationFqn = FqName("com.lattice.NoHistory")
    private val propertyAnnotationFqn = FqName("com.lattice.Property")
    private val unionAnnotationFqn = FqName("com.lattice.Union")
    private val unionCaseAnnotationFqn = FqName("com.lattice.UnionCase")
    private val unlabeledAnnotationFqn = FqName("com.lattice.Unlabeled")

    private val latticeObjectFqn = FqName("com.lattice.LatticeObject")
    private val latticeNativeFqn = FqName("com.lattice.LatticeNative")
    private val latticeHydrationMarkerFqn = FqName("com.lattice.LatticeHydrationMarker")

    private val irFactory get() = pluginContext.irFactory
    private val irBuiltIns get() = pluginContext.irBuiltIns

    private val latticeObjectClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticeObjectFqn))
    }

    private val latticeNativeClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticeNativeFqn))
    }

    private val latticeHydrationMarkerClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticeHydrationMarkerFqn))
    }

    companion object {
        const val FLAG_UNIQUE = 1
        const val FLAG_INDEXED = 2
        const val FLAG_FULL_TEXT = 4
        const val FLAG_VECTOR = 8
        const val FLAG_NO_HISTORY = 16
        const val FLAG_GEO_BOUNDS = 32
        const val FLAG_ALLOWS_UPSERT = 64
    }

    override fun visitClassNew(declaration: IrClass): IrStatement {
        if (!declaration.hasAnnotation(modelAnnotationFqn)) {
            return super.visitClassNew(declaration)
        }

        val latticeObj = latticeObjectClass
        val latticeNative = latticeNativeClass

        if (latticeObj == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Lattice: Cannot find LatticeObject interface. Is lattice-runtime on classpath?"
            )
            return super.visitClassNew(declaration)
        }

        if (latticeNative == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Lattice: Cannot find LatticeNative object. Is lattice-runtime on classpath?"
            )
            return super.visitClassNew(declaration)
        }

        val handleField = findOrCreateHandleField(declaration)
        if (handleField !in declaration.declarations) {
            declaration.declarations.add(handleField)
        }

        // Implement FIR-generated zero-leak hydration constructor (Long, LatticeHydrationMarker)
        implementHydrationConstructor(declaration, handleField)

        // Implement FIR-generated _latticeHandle property
        val handleProperty = declaration.properties.find { it.name.asString() == "_latticeHandle" }
        if (handleProperty != null) {
            implementHandleProperty(handleProperty, handleField)
        }

        // Implement FIR-generated _latticeTableName property
        val tableNameProperty = declaration.properties.find { it.name.asString() == "_latticeTableName" }
        if (tableNameProperty != null) {
            implementTableNameProperty(declaration, tableNameProperty)
        }

        val userProperties = declaration.properties.filter { prop ->
            val name = prop.name.asString()
            name !in setOf("_latticeHandle", "_latticeTableName", "_latticeSchema", "isManaged", "id", "globalId") &&
                !prop.hasAnnotation(ignoreAnnotationFqn) &&
                prop.backingField != null
        }.toList()

        // Implement FIR-generated _latticeSchema property
        val allSchemaProps = declaration.properties.filter { it.name.asString() == "_latticeSchema" }.toList()
        val schemaProperty = allSchemaProps.find { !it.isFakeOverride } ?: allSchemaProps.firstOrNull()
        if (schemaProperty != null) {
            implementSchemaProperty(schemaProperty, userProperties, latticeNative)
        }

        // Implement FIR-generated _copyPropertiesToNative function
        val copyFunction = declaration.functions.find { it.name.asString() == "_copyPropertiesToNative" }
        if (copyFunction != null) {
            implementCopyToNativeFunction(copyFunction, userProperties, latticeNative)
        }

        // Add primary-constructor init block that allocates the unmanaged native object
        addInitBlock(declaration, handleField, userProperties, latticeNative)

        userProperties.forEach { property ->
            transformProperty(declaration, property, handleField, latticeNative)
        }

        addFactoryRegistration(declaration)

        return super.visitClassNew(declaration)
    }

    // =========================================================================
    // Hydration Constructor & Factory Registration
    // =========================================================================

    private fun findHydrationConstructor(irClass: IrClass): IrConstructor? {
        return irClass.constructors.find { ctor ->
            !ctor.isPrimary &&
                ctor.regularParameters.size == 2 &&
                ctor.regularParameters[0].type.isLong() &&
                ctor.regularParameters[1].type.classOrNull?.owner?.fqNameWhenAvailable == latticeHydrationMarkerFqn
        }
    }

    private fun implementHydrationConstructor(
        irClass: IrClass,
        handleField: IrField
    ) {
        val hydrationCtor = findHydrationConstructor(irClass) ?: return
        val handleParam = hydrationCtor.regularParameters.getOrNull(0) ?: return
        val thisReceiver = irClass.thisReceiver ?: return

        val superClass = irClass.superTypes
            .mapNotNull { it.classOrNull?.owner }
            .firstOrNull { it.kind == ClassKind.CLASS }
            ?: irBuiltIns.anyClass.owner
        val superCtor = superClass.constructors.firstOrNull { it.regularParameters.isEmpty() }
            ?: irBuiltIns.anyClass.owner.constructors.first()

        // Do NOT emit IrInstanceInitializerCall in a non-primary constructor:
        // Kotlin/Native InitializersLowering asserts `declaration.isPrimary || allInitializers.isEmpty()`
        // and delegating directly to superCtor avoids running addInitBlock (zero throwaway C++ allocation).
        // Initialize @Ignore backing fields directly from their declared initializers.
        val ignoredProperties = irClass.properties.filter { prop ->
            prop.hasAnnotation(ignoreAnnotationFqn) && prop.backingField != null
        }.toList()

        hydrationCtor.origin = LatticeDeclarationOrigin
        hydrationCtor.body = DeclarationIrBuilder(pluginContext, hydrationCtor.symbol).irBlockBody {
            +irDelegatingConstructorCall(superCtor)
            +irSetField(irGet(thisReceiver), handleField, irGet(handleParam))
            for (prop in ignoredProperties) {
                val bf = prop.backingField ?: continue
                val rawInit = bf.initializer?.expression ?: continue
                val sourceExpr = if (
                    rawInit is IrGetValue &&
                    (rawInit.symbol.owner as? IrValueParameter)?.parent == irClass.primaryConstructor
                ) {
                    (rawInit.symbol.owner as IrValueParameter).defaultValue?.expression
                } else {
                    rawInit
                }
                if (sourceExpr != null) {
                    +irSetField(irGet(thisReceiver), bf, sourceExpr.deepCopyWithSymbols(hydrationCtor))
                }
            }
        }
    }

    private fun addFactoryRegistration(irClass: IrClass) {
        val companion = irClass.declarations
            .filterIsInstance<IrClass>()
            .find { it.isCompanion }
            ?: return

        val modelConstructor = irClass.primaryConstructor ?: return
        val hydrationConstructor = findHydrationConstructor(irClass)
        val markerSymbol = latticeHydrationMarkerClass
        val tableName = resolveModelTableName(irClass)

        val latticeClass = pluginContext.referenceClass(ClassId.topLevel(FqName("com.lattice.Lattice"))) ?: return
        val latticeCompanion = latticeClass.owner.declarations
            .filterIsInstance<IrClass>()
            .find { it.isCompanion } ?: return

        val registerFactoryFn = latticeCompanion.declarations
            .filterIsInstance<IrSimpleFunction>()
            .find { it.name.asString() == "registerFactory" } ?: return

        val registerHydrationFactoryFn = latticeCompanion.declarations
            .filterIsInstance<IrSimpleFunction>()
            .find { it.name.asString() == "registerHydrationFactory" }

        val virtualModelRegistryClass = pluginContext.referenceClass(
            ClassId.topLevel(FqName("com.lattice.VirtualModelRegistry"))
        )
        val registerTableFactoryFn = virtualModelRegistryClass?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.find { it.name.asString() == "registerTableFactory" }
        val registerTableHydrationFactoryFn = virtualModelRegistryClass?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.find { it.name.asString() == "registerTableHydrationFactory" }

        val initBlock = irFactory.createAnonymousInitializer(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = LatticeDeclarationOrigin,
            symbol = IrAnonymousInitializerSymbolImpl()
        ).apply {
            parent = companion

            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                val kClassType = irBuiltIns.kClassClass.typeWith(irClass.defaultType)

                fun makeKClassRef() = IrClassReferenceImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = kClassType,
                    symbol = irClass.symbol,
                    classType = irClass.defaultType
                )

                // 1. Lattice.registerFactory(MyClass::class) { MyClass() }
                val lambdaType = irBuiltIns.functionN(0).typeWith(irClass.defaultType)
                val lambdaFn = irFactory.createSimpleFunction(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    origin = LatticeDeclarationOrigin,
                    name = Name.special("<anonymous>"),
                    visibility = DescriptorVisibilities.LOCAL,
                    isInline = false,
                    isExpect = false,
                    returnType = irClass.defaultType,
                    modality = Modality.FINAL,
                    symbol = IrSimpleFunctionSymbolImpl(),
                    isTailrec = false,
                    isSuspend = false,
                    isOperator = false,
                    isInfix = false,
                    isExternal = false
                ).apply {
                    parent = this@irBlockBody.scope.getLocalDeclarationParent()
                    body = DeclarationIrBuilder(pluginContext, this.symbol).irBlockBody {
                        +irReturn(irCallConstructor(modelConstructor.symbol, emptyList()))
                    }
                }
                +irCall(registerFactoryFn).apply {
                    dispatchReceiver = irGetObject(latticeCompanion.symbol)
                    typeArguments[0] = irClass.defaultType
                    putValueArgument(0, makeKClassRef())
                    putValueArgument(1, IrFunctionExpressionImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET, lambdaType, lambdaFn, IrStatementOrigin.LAMBDA
                    ))
                }

                // 2. Lattice.registerHydrationFactory(MyClass::class) { h -> MyClass(h, LatticeHydrationMarker) }
                if (registerHydrationFactoryFn != null && hydrationConstructor != null && markerSymbol != null) {
                    val hLambdaType = irBuiltIns.functionN(1).typeWith(irBuiltIns.longType, irClass.defaultType)
                    val hLambdaFn = irFactory.createSimpleFunction(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        origin = LatticeDeclarationOrigin,
                        name = Name.special("<anonymous>"),
                        visibility = DescriptorVisibilities.LOCAL,
                        isInline = false,
                        isExpect = false,
                        returnType = irClass.defaultType,
                        modality = Modality.FINAL,
                        symbol = IrSimpleFunctionSymbolImpl(),
                        isTailrec = false,
                        isSuspend = false,
                        isOperator = false,
                        isInfix = false,
                        isExternal = false
                    ).apply {
                        parent = this@irBlockBody.scope.getLocalDeclarationParent()
                        val hParam = addValueParameter("handle", irBuiltIns.longType)
                        body = DeclarationIrBuilder(pluginContext, this.symbol).irBlockBody {
                            +irReturn(irCallConstructor(hydrationConstructor.symbol, emptyList()).apply {
                                putValueArgument(0, irGet(hParam))
                                putValueArgument(1, irGetObject(markerSymbol))
                            })
                        }
                    }
                    +irCall(registerHydrationFactoryFn).apply {
                        dispatchReceiver = irGetObject(latticeCompanion.symbol)
                        typeArguments[0] = irClass.defaultType
                        putValueArgument(0, makeKClassRef())
                        putValueArgument(1, IrFunctionExpressionImpl(
                            UNDEFINED_OFFSET, UNDEFINED_OFFSET, hLambdaType, hLambdaFn, IrStatementOrigin.LAMBDA
                        ))
                    }
                }

                // 3. VirtualModelRegistry registrations
                val loClass = latticeObjectClass
                if (virtualModelRegistryClass != null && loClass != null) {
                    if (registerTableFactoryFn != null) {
                        val vmrLambdaType = irBuiltIns.functionN(0).typeWith(loClass.defaultType)
                        val vmrLambdaFn = irFactory.createSimpleFunction(
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            origin = LatticeDeclarationOrigin,
                            name = Name.special("<anonymous>"),
                            visibility = DescriptorVisibilities.LOCAL,
                            isInline = false,
                            isExpect = false,
                            returnType = loClass.defaultType,
                            modality = Modality.FINAL,
                            symbol = IrSimpleFunctionSymbolImpl(),
                            isTailrec = false,
                            isSuspend = false,
                            isOperator = false,
                            isInfix = false,
                            isExternal = false
                        ).apply {
                            parent = this@irBlockBody.scope.getLocalDeclarationParent()
                            body = DeclarationIrBuilder(pluginContext, this.symbol).irBlockBody {
                                +irReturn(irCallConstructor(modelConstructor.symbol, emptyList()))
                            }
                        }
                        +irCall(registerTableFactoryFn).apply {
                            dispatchReceiver = irGetObject(virtualModelRegistryClass)
                            putValueArgument(0, irString(tableName))
                            putValueArgument(1, IrFunctionExpressionImpl(
                                UNDEFINED_OFFSET, UNDEFINED_OFFSET, vmrLambdaType, vmrLambdaFn, IrStatementOrigin.LAMBDA
                            ))
                        }
                    }

                    if (registerTableHydrationFactoryFn != null && hydrationConstructor != null && markerSymbol != null) {
                        val vmrHLambdaType = irBuiltIns.functionN(1).typeWith(irBuiltIns.longType, loClass.defaultType)
                        val vmrHLambdaFn = irFactory.createSimpleFunction(
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            origin = LatticeDeclarationOrigin,
                            name = Name.special("<anonymous>"),
                            visibility = DescriptorVisibilities.LOCAL,
                            isInline = false,
                            isExpect = false,
                            returnType = loClass.defaultType,
                            modality = Modality.FINAL,
                            symbol = IrSimpleFunctionSymbolImpl(),
                            isTailrec = false,
                            isSuspend = false,
                            isOperator = false,
                            isInfix = false,
                            isExternal = false
                        ).apply {
                            parent = this@irBlockBody.scope.getLocalDeclarationParent()
                            val hParam = addValueParameter("handle", irBuiltIns.longType)
                            body = DeclarationIrBuilder(pluginContext, this.symbol).irBlockBody {
                                +irReturn(irCallConstructor(hydrationConstructor.symbol, emptyList()).apply {
                                    putValueArgument(0, irGet(hParam))
                                    putValueArgument(1, irGetObject(markerSymbol))
                                })
                            }
                        }
                        +irCall(registerTableHydrationFactoryFn).apply {
                            dispatchReceiver = irGetObject(virtualModelRegistryClass)
                            putValueArgument(0, irString(tableName))
                            putValueArgument(1, IrFunctionExpressionImpl(
                                UNDEFINED_OFFSET, UNDEFINED_OFFSET, vmrHLambdaType, vmrHLambdaFn, IrStatementOrigin.LAMBDA
                            ))
                        }
                    }
                }
            }
        }

        companion.declarations.add(initBlock)
    }

    // =========================================================================
    // Synthetic Fields & Metadata Properties
    // =========================================================================

    private fun findOrCreateHandleField(irClass: IrClass): IrField {
        val fieldName = "_lattice_handle_field_${irClass.name.asString()}"
        irClass.declarations.filterIsInstance<IrField>().find { it.name.asString() == fieldName }
            ?.let { return it }

        return irFactory.createField(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = LatticeDeclarationOrigin,
            name = Name.identifier(fieldName),
            visibility = DescriptorVisibilities.PRIVATE,
            symbol = IrFieldSymbolImpl(),
            type = irBuiltIns.longType,
            isFinal = false,
            isStatic = false,
            isExternal = false
        ).apply {
            parent = irClass
            initializer = irFactory.createExpressionBody(
                IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.longType, 0L)
            )
        }
    }

    private fun implementHandleProperty(property: IrProperty, field: IrField) {
        property.getter?.let { getter ->
            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                +irReturn(irGetField(irGet(getter.dispatchReceiverParameter!!), field))
            }
        }
        property.setter?.let { setter ->
            val valueParam = setter.regularParameters.firstOrNull() ?: return@let
            setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
                +irSetField(irGet(setter.dispatchReceiverParameter!!), field, irGet(valueParam))
            }
        }
    }

    private fun resolveModelTableName(irClass: IrClass): String {
        val ann = irClass.annotations.find {
            it.type.classOrNull?.owner?.fqNameWhenAvailable == modelAnnotationFqn
        }
        val explicit = (ann?.getValueArgument(Name.identifier("tableName")) as? IrConst)?.value as? String
        return explicit?.takeIf { it.isNotEmpty() } ?: irClass.name.asString()
    }

    private fun resolveCustomColumnName(prop: IrProperty): String? {
        val ann = prop.annotations.find {
            it.type.classOrNull?.owner?.fqNameWhenAvailable == propertyAnnotationFqn
        } ?: return null
        val raw = (ann.getValueArgument(Name.identifier("name")) as? IrConst)?.value as? String
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun implementTableNameProperty(irClass: IrClass, property: IrProperty) {
        val tableName = resolveModelTableName(irClass)
        property.getter?.let { getter ->
            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                +irReturn(irString(tableName))
            }
        }
    }

    // =========================================================================
    // @Union Compile-Time Introspection
    // =========================================================================

    private data class UnionParamInfo(
        val kotlinPropName: String,
        val wireParamName: String,
        val uvKey: String,
        val paramType: IrType,
        val sqlTypeOrd: Int,
        val isLink: Boolean,
        val linkTarget: String?
    )

    private data class UnionCaseInfo(
        val caseClass: IrClass,
        val caseName: String,
        val isObject: Boolean,
        val params: List<UnionParamInfo>
    )

    private data class UnionMeta(
        val unionClass: IrClass,
        val unionTableName: String,
        val cases: List<UnionCaseInfo>
    ) {
        fun encodeSpec(): String {
            val casesPart = cases.joinToString(";") { c ->
                if (c.params.isEmpty()) {
                    c.caseName
                } else {
                    c.caseName + "~" + c.params.joinToString("~") { p ->
                        "${p.wireParamName}^${p.sqlTypeOrd}^${if (p.isLink) "1" else "0"}^${p.linkTarget ?: ""}"
                    }
                }
            }
            return "$unionTableName|$casesPart"
        }
    }

    private fun isUnionType(type: IrType): Boolean {
        val base = if (type.isNullable()) type.makeNotNull() else type
        return base.classOrNull?.owner?.hasAnnotation(unionAnnotationFqn) == true
    }

    private fun findUnionSubclasses(unionClass: IrClass): List<IrClass> {
        val fromSealed = unionClass.sealedSubclasses.map { it.owner }
        if (fromSealed.isNotEmpty()) return fromSealed

        val candidates = LinkedHashSet<IrClass>()
        fun collectFrom(classes: Iterable<IrClass>) {
            for (cls in classes) {
                if (!cls.isInterface &&
                    cls.modality != Modality.ABSTRACT &&
                    cls.modality != Modality.SEALED &&
                    cls.superTypes.any { it.classOrNull == unionClass.symbol }
                ) {
                    candidates.add(cls)
                }
                collectFrom(cls.declarations.filterIsInstance<IrClass>())
            }
        }
        collectFrom(unionClass.declarations.filterIsInstance<IrClass>())
        for (file in moduleFragment.files) {
            collectFrom(file.declarations.filterIsInstance<IrClass>())
        }
        return candidates.toList()
    }

    private fun inspectUnion(unionType: IrType): UnionMeta? {
        val base = if (unionType.isNullable()) unionType.makeNotNull() else unionType
        val unionClass = base.classOrNull?.owner ?: return null
        if (!unionClass.hasAnnotation(unionAnnotationFqn)) return null

        val unionAnn = unionClass.annotations.find {
            it.type.classOrNull?.owner?.fqNameWhenAvailable == unionAnnotationFqn
        }
        val customTable = (unionAnn?.getValueArgument(Name.identifier("tableName")) as? IrConst)?.value as? String
        val unionTableName = customTable?.takeIf { it.isNotEmpty() } ?: "_${unionClass.name.asString()}"

        val subclasses = findUnionSubclasses(unionClass)
        val cases = subclasses.map { sub ->
            val caseAnn = sub.annotations.find {
                it.type.classOrNull?.owner?.fqNameWhenAvailable == unionCaseAnnotationFqn
            }
            val explicitCaseName = (caseAnn?.getValueArgument(Name.identifier("name")) as? IrConst)?.value as? String
            val caseName = explicitCaseName?.takeIf { it.isNotEmpty() }
                ?: sub.name.asString().replaceFirstChar { it.lowercaseChar() }

            if (sub.kind == ClassKind.OBJECT) {
                UnionCaseInfo(sub, caseName, isObject = true, params = emptyList())
            } else {
                val ctorParams = sub.primaryConstructor?.regularParameters ?: emptyList()
                val paramInfos = ctorParams.map { vp ->
                    val propName = vp.name.asString()
                    val pType = vp.type
                    val pBase = if (pType.isNullable()) pType.makeNotNull() else pType
                    val matchingProp = sub.properties.find { it.name.asString() == propName }

                    val hasUnlabeledAnn = vp.annotations.any {
                        it.type.classOrNull?.owner?.fqNameWhenAvailable == unlabeledAnnotationFqn
                    } || (matchingProp?.hasAnnotation(unlabeledAnnotationFqn) == true)

                    val isEmbeddedPayload = pBase.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true
                    val isUnlabeledSingle = (ctorParams.size == 1) &&
                        (hasUnlabeledAnn || (isEmbeddedPayload && (propName == "value" || propName == "_0")))

                    val wireParamName = if (isUnlabeledSingle) "" else propName
                    val uvKey = wireParamName.ifEmpty { "_0" }

                    val isLink = pBase.classOrNull?.owner?.hasAnnotation(modelAnnotationFqn) == true
                    val linkTarget = if (isLink) resolveModelTableName(pBase.classOrNull!!.owner) else null

                    val sqlTypeOrd = when {
                        isLink -> 2 // TEXT globalId in _UnionTable
                        pBase.isInt() || pBase.isLong() || pBase.isBoolean() -> 0
                        pBase.isFloat() || pBase.isDouble() || isInstant(pBase) -> 1
                        isByteArray(pBase) -> 3
                        isLatticeEnum(pBase) && getEnumStoreAsOrdinal(pBase) -> 0
                        else -> 2
                    }

                    UnionParamInfo(
                        kotlinPropName = propName,
                        wireParamName = wireParamName,
                        uvKey = uvKey,
                        paramType = pType,
                        sqlTypeOrd = sqlTypeOrd,
                        isLink = isLink,
                        linkTarget = linkTarget
                    )
                }
                UnionCaseInfo(sub, caseName, isObject = false, params = paramInfos)
            }
        }

        return UnionMeta(unionClass, unionTableName, cases)
    }

    // =========================================================================
    // Init Block & _copyPropertiesToNative
    // =========================================================================

    private fun addInitBlock(
        irClass: IrClass,
        handleField: IrField,
        userProperties: List<IrProperty>,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val tableName = resolveModelTableName(irClass)
        val createObjectWithSchemaFn = findLatticeNativeFunction(latticeNativeSymbol, "createObjectWithSchema")
        val createObjectFn = createObjectWithSchemaFn
            ?: findLatticeNativeFunction(latticeNativeSymbol, "createObject")
            ?: return

        val schemaProperty = irClass.properties.find { it.name.asString() == "_latticeSchema" }

        val initBlock = irFactory.createAnonymousInitializer(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = LatticeDeclarationOrigin,
            symbol = IrAnonymousInitializerSymbolImpl()
        ).apply {
            parent = irClass

            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                val thisReceiver = irClass.thisReceiver!!
                +irBlock {
                    val createCall = if (createObjectWithSchemaFn != null && schemaProperty?.getter != null) {
                        irCall(createObjectWithSchemaFn).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irString(tableName))
                            putValueArgument(1, irCall(schemaProperty.getter!!).apply {
                                dispatchReceiver = irGet(thisReceiver)
                            })
                        }
                    } else {
                        irCall(createObjectFn).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irString(tableName))
                        }
                    }
                    +irSetField(irGet(thisReceiver), handleField, createCall)

                    emitCopyBackingFieldsToHandle(
                        builder = this@irBlockBody,
                        blockBuilder = this,
                        thisReceiver = thisReceiver,
                        targetHandleExpr = { irGetField(irGet(thisReceiver), handleField) },
                        userProperties = userProperties,
                        latticeNativeSymbol = latticeNativeSymbol
                    )
                }
            }
        }

        irClass.declarations.add(initBlock)
    }

    private fun implementCopyToNativeFunction(
        function: IrSimpleFunction,
        userProperties: List<IrProperty>,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val nativeHandleParam = function.regularParameters.firstOrNull() ?: return

        function.body = DeclarationIrBuilder(pluginContext, function.symbol).irBlockBody {
            val thisReceiver = function.dispatchReceiverParameter!!
            +irBlock {
                emitCopyBackingFieldsToHandle(
                    builder = this@irBlockBody,
                    blockBuilder = this,
                    thisReceiver = thisReceiver,
                    targetHandleExpr = { irGet(nativeHandleParam) },
                    userProperties = userProperties,
                    latticeNativeSymbol = latticeNativeSymbol
                )
            }
        }
    }

    private fun emitCopyBackingFieldsToHandle(
        builder: IrBuilderWithScope,
        blockBuilder: IrBlockBuilder,
        thisReceiver: IrValueParameter,
        targetHandleExpr: () -> IrExpression,
        userProperties: List<IrProperty>,
        latticeNativeSymbol: IrClassSymbol
    ) = with(blockBuilder) {
        userProperties.forEach { prop ->
            if (prop.hasAnnotation(linkAnnotationFqn) || prop.hasAnnotation(linkListAnnotationFqn)) {
                return@forEach
            }
            val backingField = prop.backingField ?: return@forEach
            val propName = prop.name.asString()
            val propType = backingField.type

            val typeSimpleName = propType.classOrNull?.owner?.name?.asString()
            if (typeSimpleName == "LatticeList" || typeSimpleName == "VirtualList") {
                return@forEach
            }

            if (isNativeCollection(propType)) {
                val callExpr = buildCollectionSetCall(
                    builder = builder,
                    handleExpr = targetHandleExpr(),
                    propertyName = propName,
                    propertyType = propType,
                    valueExpr = irGetField(irGet(thisReceiver), backingField),
                    latticeNativeSymbol = latticeNativeSymbol
                )
                if (callExpr != null) +callExpr
                return@forEach
            }

            val baseType = if (propType.isNullable()) propType.makeNotNull() else propType

            if (isUnionType(baseType)) {
                val unionMeta = inspectUnion(baseType)
                if (unionMeta != null) {
                    emitSetUnionStatements(
                        blockBuilder = this,
                        handleExpr = targetHandleExpr,
                        propertyName = propName,
                        propertyType = propType,
                        valueExpr = irGetField(irGet(thisReceiver), backingField),
                        unionMeta = unionMeta,
                        latticeNativeSymbol = latticeNativeSymbol
                    )
                }
                return@forEach
            }

            val isEmbedded = baseType.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true
            if (isEmbedded) {
                val setEmbedded = findLatticeNativeFunction(latticeNativeSymbol, "setEmbedded")
                if (setEmbedded != null && baseType.classOrNull != null) {
                    val kClassType = irBuiltIns.kClassClass.typeWith(baseType)
                    val kClassRef = IrClassReferenceImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, baseType.classOrNull!!, baseType
                    )
                    +irCall(setEmbedded).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        typeArguments[0] = baseType
                        putValueArgument(0, targetHandleExpr())
                        putValueArgument(1, irString(propName))
                        putValueArgument(2, irGetField(irGet(thisReceiver), backingField))
                        putValueArgument(3, kClassRef)
                    }
                }
                return@forEach
            }

            if (isLatticeEnum(baseType)) {
                val isNullable = propType.isNullable()
                val storeAsOrdinal = getEnumStoreAsOrdinal(baseType)
                val enumClass = baseType.classOrNull?.owner ?: return@forEach
                val fieldValue = irGetField(irGet(thisReceiver), backingField)

                if (storeAsOrdinal) {
                    val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setInt") ?: return@forEach
                    val ordinalProp = enumClass.properties.find { it.name.asString() == "ordinal" }
                        ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))
                            ?.owner?.properties?.find { it.name.asString() == "ordinal" }
                    if (ordinalProp?.getter != null) {
                        if (isNullable) {
                            val valueVar = irTemporary(fieldValue, nameHint = "enumVal")
                            val setNull = findLatticeNativeFunction(latticeNativeSymbol, "setNull")
                            +irIfThenElse(
                                type = irBuiltIns.unitType,
                                condition = irEqualsNull(irGet(valueVar)),
                                thenPart = if (setNull != null) {
                                    irCall(setNull).apply {
                                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                                        putValueArgument(0, targetHandleExpr())
                                        putValueArgument(1, irString(propName))
                                    }
                                } else {
                                    irCall(nativeSetter).apply {
                                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                                        putValueArgument(0, targetHandleExpr())
                                        putValueArgument(1, irString(propName))
                                        putValueArgument(2, irInt(0))
                                    }
                                },
                                elsePart = irCall(nativeSetter).apply {
                                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                                    putValueArgument(0, targetHandleExpr())
                                    putValueArgument(1, irString(propName))
                                    putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                                        dispatchReceiver = irGet(valueVar)
                                    })
                                }
                            )
                        } else {
                            +irCall(nativeSetter).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, targetHandleExpr())
                                putValueArgument(1, irString(propName))
                                putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                                    dispatchReceiver = fieldValue
                                })
                            }
                        }
                    }
                } else {
                    val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setString") ?: return@forEach
                    val nameProp = enumClass.properties.find { it.name.asString() == "name" }
                        ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))
                            ?.owner?.properties?.find { it.name.asString() == "name" }
                    if (nameProp?.getter != null) {
                        if (isNullable) {
                            val valueVar = irTemporary(fieldValue, nameHint = "enumVal")
                            val setNull = findLatticeNativeFunction(latticeNativeSymbol, "setNull")
                            +irIfThenElse(
                                type = irBuiltIns.unitType,
                                condition = irEqualsNull(irGet(valueVar)),
                                thenPart = if (setNull != null) {
                                    irCall(setNull).apply {
                                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                                        putValueArgument(0, targetHandleExpr())
                                        putValueArgument(1, irString(propName))
                                    }
                                } else {
                                    irCall(nativeSetter).apply {
                                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                                        putValueArgument(0, targetHandleExpr())
                                        putValueArgument(1, irString(propName))
                                        putValueArgument(2, irString(""))
                                    }
                                },
                                elsePart = irCall(nativeSetter).apply {
                                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                                    putValueArgument(0, targetHandleExpr())
                                    putValueArgument(1, irString(propName))
                                    putValueArgument(2, irCall(nameProp.getter!!).apply {
                                        dispatchReceiver = irGet(valueVar)
                                    })
                                }
                            )
                        } else {
                            +irCall(nativeSetter).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, targetHandleExpr())
                                putValueArgument(1, irString(propName))
                                putValueArgument(2, irCall(nameProp.getter!!).apply {
                                    dispatchReceiver = fieldValue
                                })
                            }
                        }
                    }
                }
                return@forEach
            }

            val nativeSetterName = getNativeSetterName(propType)
            val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, nativeSetterName) ?: return@forEach
            +irCall(nativeSetter).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, targetHandleExpr())
                putValueArgument(1, irString(propName))
                putValueArgument(2, irGetField(irGet(thisReceiver), backingField))
            }
        }
    }

    // =========================================================================
    // Schema Descriptor Generation (`_latticeSchema`)
    // =========================================================================

    private fun extractUniqueArgs(prop: IrProperty): Pair<List<String>, Boolean> {
        val ann = prop.annotations.find {
            it.type.classOrNull?.owner?.fqNameWhenAvailable == uniqueAnnotationFqn
        } ?: return emptyList<String>() to false

        val allowsUpsert = (ann.getValueArgument(Name.identifier("allowsUpsert")) as? IrConst)?.value as? Boolean ?: false
        val compArg = ann.getValueArgument(Name.identifier("compoundedWith"))
        val compounded = when (compArg) {
            is IrVararg -> compArg.elements.mapNotNull { (it as? IrConst)?.value as? String }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return compounded to allowsUpsert
    }

    private fun implementSchemaProperty(
        property: IrProperty,
        userProperties: List<IrProperty>,
        latticeNativeSymbol: IrClassSymbol
    ) {
        property.getter?.let { getter ->
            getter.origin = LatticeDeclarationOrigin
            property.origin = LatticeDeclarationOrigin
            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                val buildFn = findLatticeNativeFunction(latticeNativeSymbol, "buildSchemaFromString")

                if (userProperties.isNotEmpty() && buildFn != null) {
                    val schemaStr = userProperties.mapNotNull { prop ->
                        val bf = prop.backingField ?: return@mapNotNull null
                        val pt = bf.type
                        val isNullable = pt.isNullable()
                        val baseForType = if (isNullable) pt.makeNotNull() else pt

                        val isLink = prop.hasAnnotation(linkAnnotationFqn)
                        val isLinkList = prop.hasAnnotation(linkListAnnotationFqn) ||
                            pt.classOrNull?.owner?.name?.asString() == "LatticeList"
                        val isVirtualList = pt.classOrNull?.owner?.name?.asString() == "VirtualList"
                        val isUnion = isUnionType(baseForType)
                        val isVector = isFloatVector(baseForType) || isDoubleVector(baseForType)
                        val isGeo = isGeoBounds(baseForType)

                        val typeOrd = when {
                            isLink || isLinkList || isVirtualList -> 0
                            isUnion -> 2 // TEXT globalId pointing into _UnionTable
                            pt.isString() || (isNullable && baseForType.isString()) -> 2
                            pt.isInt() || (isNullable && baseForType.isInt()) -> 0
                            pt.isLong() || (isNullable && baseForType.isLong()) -> 0
                            pt.isBoolean() || (isNullable && baseForType.isBoolean()) -> 0
                            pt.isFloat() || (isNullable && baseForType.isFloat()) -> 1
                            pt.isDouble() || (isNullable && baseForType.isDouble()) -> 1
                            isGeo -> 1
                            isByteArray(baseForType) || isVector -> 3
                            isInstant(baseForType) -> 1
                            isUuid(baseForType) -> 2
                            isLatticeEnum(baseForType) -> if (getEnumStoreAsOrdinal(baseForType)) 0 else 2
                            else -> 2
                        }

                        val kindOrd = when {
                            isLink -> 1
                            isLinkList -> 2
                            isVirtualList -> 3
                            isUnion -> 5
                            else -> 0
                        }

                        val targetTable = when {
                            isLink -> baseForType.classOrNull?.owner?.let { resolveModelTableName(it) }
                            isLinkList -> (pt as? IrSimpleType)
                                ?.arguments?.firstOrNull()?.typeOrNull
                                ?.classOrNull?.owner?.let { resolveModelTableName(it) }
                            else -> null
                        } ?: ""

                        val hasUnique = prop.hasAnnotation(uniqueAnnotationFqn)
                        val (compoundedWith, allowsUpsert) = if (hasUnique) extractUniqueArgs(prop) else (emptyList<String>() to false)

                        val flags = (if (hasUnique) FLAG_UNIQUE else 0) or
                            (if (prop.hasAnnotation(indexedAnnotationFqn)) FLAG_INDEXED else 0) or
                            (if (prop.hasAnnotation(fullTextAnnotationFqn)) FLAG_FULL_TEXT else 0) or
                            (if (isVector) FLAG_VECTOR else 0) or
                            (if (prop.hasAnnotation(noHistoryAnnotationFqn)) FLAG_NO_HISTORY else 0) or
                            (if (isGeo) FLAG_GEO_BOUNDS else 0) or
                            (if (allowsUpsert) FLAG_ALLOWS_UPSERT else 0)

                        val customCol = resolveCustomColumnName(prop) ?: ""
                        val compoundedStr = compoundedWith.joinToString("+")
                        val unionSpec = if (isUnion) inspectUnion(baseForType)?.encodeSpec().orEmpty() else ""

                        val baseEntry = "${prop.name.asString()}:$typeOrd:$kindOrd:${if (isNullable) "1" else "0"}:$targetTable:$flags"
                        if (customCol.isEmpty() && compoundedStr.isEmpty() && unionSpec.isEmpty()) {
                            baseEntry
                        } else {
                            "$baseEntry:$customCol:$compoundedStr:$unionSpec"
                        }
                    }.joinToString(",")

                    +irReturn(irCall(buildFn).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irString(schemaStr))
                    })
                } else {
                    val emptyList = pluginContext.referenceFunctions(
                        CallableId(FqName("kotlin.collections"), Name.identifier("emptyList"))
                    ).firstOrNull()
                    if (emptyList != null) {
                        +irReturn(irCall(emptyList))
                    } else {
                        +irReturn(irNull())
                    }
                }
            }
        }
    }

    // =========================================================================
    // Property Getter/Setter Transformation
    // =========================================================================

    private fun transformProperty(
        irClass: IrClass,
        property: IrProperty,
        handleField: IrField,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val backingField = property.backingField ?: return
        val propertyName = property.name.asString()
        val propertyType = backingField.type

        val isLink = property.hasAnnotation(linkAnnotationFqn)
        val isVirtualList = propertyType.classOrNull?.owner?.name?.asString() == "VirtualList"
        val isLinkList = property.hasAnnotation(linkListAnnotationFqn) ||
            propertyType.classOrNull?.owner?.name?.asString() == "LatticeList"
        val isNativeCollection = isNativeCollection(propertyType)

        val baseType = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType
        val isUnion = isUnionType(baseType)
        val isEmbedded = baseType.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true
        val isEnum = isLatticeEnum(baseType)

        when {
            isNativeCollection -> {
                property.getter?.let { transformCollectionGetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
                property.setter?.let { transformCollectionSetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
            }
            isLink -> {
                property.getter?.let { transformLinkGetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
                property.setter?.let { transformLinkSetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
            }
            isVirtualList -> {
                property.getter?.let { transformVirtualListGetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
                property.setter?.let { transformVirtualListSetter(it) }
            }
            isLinkList -> {
                property.getter?.let { transformLinkListGetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
                property.setter?.let { transformLinkListSetter(it) }
            }
            isUnion -> {
                val unionMeta = inspectUnion(baseType)
                if (unionMeta != null) {
                    property.getter?.let { transformUnionGetter(it, handleField, propertyName, propertyType, unionMeta, latticeNativeSymbol) }
                    property.setter?.let { transformUnionSetter(it, handleField, propertyName, propertyType, unionMeta, latticeNativeSymbol) }
                }
            }
            isEmbedded -> {
                property.getter?.let { transformEmbeddedGetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
                property.setter?.let { transformEmbeddedSetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
            }
            isEnum -> {
                property.getter?.let { transformEnumGetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
                property.setter?.let { transformEnumSetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
            }
            else -> {
                property.getter?.let { transformGetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
                property.setter?.let { transformSetter(it, handleField, propertyName, propertyType, latticeNativeSymbol) }
            }
        }
    }

    private fun transformGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val nativeGetterName = getNativeGetterName(propertyType)
        val nativeGetter = findLatticeNativeFunction(latticeNativeSymbol, nativeGetterName) ?: return

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!
            +irReturn(
                irCall(nativeGetter).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                }
            )
        }
    }

    private fun transformSetter(
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val nativeSetterName = getNativeSetterName(propertyType)
        val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, nativeSetterName) ?: return
        val valueParam = setter.regularParameters.firstOrNull() ?: return

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            val thisReceiver = setter.dispatchReceiverParameter!!
            +irCall(nativeSetter).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
                putValueArgument(2, irGet(valueParam))
            }
        }
    }

    // =========================================================================
    // @Link, @LinkList, VirtualList (Zero-Leak Hydration Constructor)
    // =========================================================================

    private fun buildHydratedModelExpr(
        builder: IrBuilderWithScope,
        targetClass: IrClass,
        handleExpr: () -> IrExpression,
        latticeNativeSymbol: IrClassSymbol
    ): IrExpression = with(builder) {
        val setHandle = findLatticeNativeFunction(latticeNativeSymbol, "setHandle")
        val hydrationCtor = findHydrationConstructor(targetClass)
        val markerSym = latticeHydrationMarkerClass
        val primaryCtor = targetClass.primaryConstructor

        irBlock {
            val hVar = irTemporary(handleExpr(), nameHint = "h")
            val instExpr = if (hydrationCtor != null && markerSym != null) {
                irCallConstructor(hydrationCtor.symbol, emptyList()).apply {
                    putValueArgument(0, irGet(hVar))
                    putValueArgument(1, irGetObject(markerSym))
                }
            } else {
                irCallConstructor(primaryCtor!!.symbol, emptyList())
            }
            val instVar = irTemporary(instExpr, nameHint = "linkedObj")
            if (setHandle != null) {
                +irCall(setHandle).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(instVar))
                    putValueArgument(1, irGet(hVar))
                }
            }
            +irGet(instVar)
        }
    }

    private fun transformLinkGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getObjectHandle = findLatticeNativeFunction(latticeNativeSymbol, "getObjectHandle") ?: return
        val setHandle = findLatticeNativeFunction(latticeNativeSymbol, "setHandle") ?: return

        val targetType = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType
        val targetClass = targetType.classOrNull?.owner ?: return
        val hydrationCtor = findHydrationConstructor(targetClass)
        val markerSym = latticeHydrationMarkerClass
        val primaryCtor = targetClass.primaryConstructor
        if (hydrationCtor == null && primaryCtor == null) return

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!

            val handleVar = irTemporary(
                irCall(getObjectHandle).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                },
                nameHint = "linkedHandle"
            )

            +irIfThen(
                type = irBuiltIns.unitType,
                condition = irEquals(irGet(handleVar), irLong(0L)),
                thenPart = irReturn(irNull())
            )

            val instExpr = if (hydrationCtor != null && markerSym != null) {
                irCallConstructor(hydrationCtor.symbol, emptyList()).apply {
                    putValueArgument(0, irGet(handleVar))
                    putValueArgument(1, irGetObject(markerSym))
                }
            } else {
                irCallConstructor(primaryCtor!!.symbol, emptyList())
            }

            val instance = irTemporary(instExpr, nameHint = "linkedObj")

            +irCall(setHandle).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGet(instance))
                putValueArgument(1, irGet(handleVar))
            }

            +irReturn(irGet(instance))
        }
    }

    private fun transformLinkSetter(
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val setObjectHandle = findLatticeNativeFunction(latticeNativeSymbol, "setObjectHandle") ?: return
        val getHandle = findLatticeNativeFunction(latticeNativeSymbol, "getHandle") ?: return
        val valueParam = setter.regularParameters.firstOrNull() ?: return

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            val thisReceiver = setter.dispatchReceiverParameter!!

            if (propertyType.isNullable()) {
                val linkedHandle = irTemporary(
                    irIfNull(
                        type = irBuiltIns.longType,
                        subject = irGet(valueParam),
                        thenPart = irLong(0L),
                        elsePart = irCall(getHandle).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irGet(valueParam))
                        }
                    ),
                    nameHint = "linkedHandle"
                )

                +irCall(setObjectHandle).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, irGet(linkedHandle))
                }
            } else {
                +irCall(setObjectHandle).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, irCall(getHandle).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irGet(valueParam))
                    })
                }
            }
        }
    }

    private fun transformLinkListGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getLinkListHandle = findLatticeNativeFunction(latticeNativeSymbol, "getLinkListHandle") ?: return
        val latticeListClass = pluginContext.referenceClass(ClassId.topLevel(FqName("com.lattice.LatticeList"))) ?: return
        val listTypeArg = (propertyType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull ?: return
        val latticeListConstructor = latticeListClass.owner.constructors.firstOrNull() ?: return
        val nativeHandleProp = latticeListClass.owner.properties.find { it.name.asString() == "_nativeHandle" } ?: return
        val setElementClassFun = latticeListClass.owner.functions.find { it.name.asString() == "setElementClass" }

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!
            val listInstance = irTemporary(
                irCallConstructor(latticeListConstructor.symbol, listOf(listTypeArg)),
                nameHint = "linkList"
            )

            val handleValue = irCall(getLinkListHandle).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
            }

            nativeHandleProp.setter?.let { handleSetter ->
                +irCall(handleSetter).apply {
                    dispatchReceiver = irGet(listInstance)
                    putValueArgument(0, handleValue)
                }
            }

            if (setElementClassFun != null && listTypeArg.classOrNull != null) {
                val kClassType = irBuiltIns.kClassClass.typeWith(listTypeArg)
                +irCall(setElementClassFun).apply {
                    dispatchReceiver = irGet(listInstance)
                    putValueArgument(0, IrClassReferenceImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, listTypeArg.classOrNull!!, listTypeArg
                    ))
                }
            }

            +irReturn(irGet(listInstance))
        }
    }

    private fun transformLinkListSetter(setter: IrSimpleFunction) {
        if (setter.regularParameters.isEmpty()) return
        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {}
    }

    private fun transformVirtualListGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getLinkListHandle = findLatticeNativeFunction(latticeNativeSymbol, "getLinkListHandle") ?: return
        val virtualListClass = pluginContext.referenceClass(ClassId.topLevel(FqName("com.lattice.VirtualList"))) ?: return
        val listTypeArg = (propertyType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull ?: return
        val virtualListConstructor = virtualListClass.owner.constructors.firstOrNull() ?: return
        val nativeHandleProp = virtualListClass.owner.properties.find { it.name.asString() == "_nativeHandle" } ?: return

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!
            val listInstance = irTemporary(
                irCallConstructor(virtualListConstructor.symbol, listOf(listTypeArg)),
                nameHint = "virtualList"
            )
            val handleValue = irCall(getLinkListHandle).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
            }
            nativeHandleProp.setter?.let { handleSetter ->
                +irCall(handleSetter).apply {
                    dispatchReceiver = irGet(listInstance)
                    putValueArgument(0, handleValue)
                }
            }
            +irReturn(irGet(listInstance))
        }
    }

    private fun transformVirtualListSetter(setter: IrSimpleFunction) {
        if (setter.regularParameters.isEmpty()) return
        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {}
    }

    // =========================================================================
    // @Union Getter & Setter Lowering
    // =========================================================================

    private fun buildDefaultUnionCaseExpr(
        builder: IrBuilderWithScope,
        propertyType: IrType,
        unionMeta: UnionMeta
    ): IrExpression = with(builder) {
        if (propertyType.isNullable()) return irNull()
        val objCase = unionMeta.cases.firstOrNull { it.isObject }
        if (objCase != null) return irGetObject(objCase.caseClass.symbol)
        val firstCase = unionMeta.cases.first()
        val ctor = firstCase.caseClass.primaryConstructor!!
        irCallConstructor(ctor.symbol, emptyList())
    }

    private fun buildReadUnionFieldExpr(
        builder: IrBuilderWithScope,
        uvVar: IrVariable,
        param: UnionParamInfo,
        latticeNativeSymbol: IrClassSymbol
    ): IrExpression = with(builder) {
        val pType = param.paramType
        val isNullable = pType.isNullable()
        val pBase = if (isNullable) pType.makeNotNull() else pType
        val hasFieldFn = findLatticeNativeFunction(latticeNativeSymbol, "hasUnionField")

        fun scalarCall(fnName: String): IrExpression {
            val fn = findLatticeNativeFunction(latticeNativeSymbol, fnName)!!
            return irCall(fn).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGet(uvVar))
                putValueArgument(1, irString(param.uvKey))
            }
        }

        val nonNullExpr: IrExpression = when {
            param.isLink -> {
                val targetCls = pBase.classOrNull!!.owner
                val getLinkFn = findLatticeNativeFunction(latticeNativeSymbol, "getUnionLinkHandle")!!
                irBlock {
                    val lh = irTemporary(irCall(getLinkFn).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irGet(uvVar))
                        putValueArgument(1, irString(param.uvKey))
                    }, nameHint = "uLinkH")
                    +irIfThenElse(
                        type = pType,
                        condition = irEquals(irGet(lh), irLong(0L)),
                        thenPart = if (isNullable) irNull() else irCallConstructor(targetCls.primaryConstructor!!.symbol, emptyList()),
                        elsePart = buildHydratedModelExpr(this, targetCls, { irGet(lh) }, latticeNativeSymbol)
                    )
                }
            }
            pBase.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getUnionEmbedded")!!
                val kClassType = irBuiltIns.kClassClass.typeWith(pBase)
                val kClassRef = IrClassReferenceImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, pBase.classOrNull!!, pBase
                )
                val raw = irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    typeArguments[0] = pBase
                    putValueArgument(0, irGet(uvVar))
                    putValueArgument(1, irString(param.uvKey))
                    putValueArgument(2, kClassRef)
                }
                if (isNullable) raw else irImplicitCast(raw, pBase)
            }
            isLatticeEnum(pBase) -> {
                val enumCls = pBase.classOrNull!!.owner
                if (getEnumStoreAsOrdinal(pBase)) {
                    val valuesFn = enumCls.functions.find { it.name.asString() == "values" }!!
                    val arrGet = irBuiltIns.arrayClass.owner.declarations
                        .filterIsInstance<IrSimpleFunction>()
                        .find { it.name.asString() == "get" }!!.symbol
                    irCall(arrGet).apply {
                        dispatchReceiver = irCall(valuesFn.symbol)
                        putValueArgument(0, scalarCall("getUnionInt"))
                    }
                } else {
                    val valueOfFn = enumCls.functions.find {
                        it.name.asString() == "valueOf" && it.regularParameters.size == 1
                    }!!
                    val valuesFn = enumCls.functions.find { it.name.asString() == "values" }
                    val resolver = findLatticeNativeFunction(latticeNativeSymbol, "enumNameOrDefault")
                    val resolvedName = if (resolver != null && valuesFn != null) {
                        irCall(resolver).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irCall(valuesFn))
                            putValueArgument(1, scalarCall("getUnionString"))
                            putValueArgument(2, irInt(0))
                        }
                    } else scalarCall("getUnionString")
                    irCall(valueOfFn).apply { putValueArgument(0, resolvedName) }
                }
            }
            pBase.isInt() -> scalarCall("getUnionInt")
            pBase.isLong() -> scalarCall("getUnionLong")
            pBase.isDouble() -> scalarCall("getUnionDouble")
            pBase.isFloat() -> scalarCall("getUnionFloat")
            pBase.isBoolean() -> scalarCall("getUnionBoolean")
            isByteArray(pBase) -> scalarCall("getUnionBlob")
            isInstant(pBase) -> scalarCall("getUnionInstant")
            isUuid(pBase) -> scalarCall("getUnionUuid")
            else -> scalarCall("getUnionString")
        }

        if (isNullable && !param.isLink && hasFieldFn != null) {
            irIfThenElse(
                type = pType,
                condition = irCall(hasFieldFn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar))
                    putValueArgument(1, irString(param.uvKey))
                },
                thenPart = nonNullExpr,
                elsePart = irNull()
            )
        } else {
            nonNullExpr
        }
    }

    private fun transformUnionGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        unionMeta: UnionMeta,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getUnionHandleFn = findLatticeNativeFunction(latticeNativeSymbol, "getUnionHandle") ?: return
        val getUnionCaseFn = findLatticeNativeFunction(latticeNativeSymbol, "getUnionCase") ?: return
        val releaseUnionHandleFn = findLatticeNativeFunction(latticeNativeSymbol, "releaseUnionHandle") ?: return

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!
            val uvVar = irTemporary(
                irCall(getUnionHandleFn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                },
                nameHint = "uvHandle"
            )

            +irIfThen(
                type = irBuiltIns.unitType,
                condition = irEquals(irGet(uvVar), irLong(0L)),
                thenPart = irReturn(buildDefaultUnionCaseExpr(this, propertyType, unionMeta))
            )

            val caseVar = irTemporary(
                irCall(getUnionCaseFn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar))
                },
                nameHint = "caseName"
            )

            val branches = mutableListOf<IrBranch>()
            for (c in unionMeta.cases) {
                val cond = irEquals(irGet(caseVar), irString(c.caseName))
                val caseExpr: IrExpression = if (c.isObject) {
                    irGetObject(c.caseClass.symbol)
                } else {
                    val ctor = c.caseClass.primaryConstructor!!
                    irCallConstructor(ctor.symbol, emptyList()).apply {
                        c.params.forEachIndexed { idx, pInfo ->
                            putValueArgument(idx, buildReadUnionFieldExpr(this@irBlockBody, uvVar, pInfo, latticeNativeSymbol))
                        }
                    }
                }
                branches += irBranch(cond, caseExpr)
            }
            branches += irElseBranch(buildDefaultUnionCaseExpr(this, propertyType, unionMeta))

            val resVar = irTemporary(
                irWhen(propertyType, branches),
                nameHint = "unionResult"
            )

            +irCall(releaseUnionHandleFn).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGet(uvVar))
            }

            +irReturn(irGet(resVar))
        }
    }

    private fun emitSetUnionFieldStatement(
        blockBuilder: IrBlockBuilder,
        uvVar: IrVariable,
        caseVar: IrVariable,
        caseClass: IrClass,
        param: UnionParamInfo,
        latticeNativeSymbol: IrClassSymbol
    ) = with(blockBuilder) {
        val prop = caseClass.properties.find { it.name.asString() == param.kotlinPropName } ?: return
        val propGetter = prop.getter ?: return
        val rawFieldVal = irCall(propGetter).apply {
            dispatchReceiver = irGet(caseVar)
        }

        val pType = param.paramType
        val isNullable = pType.isNullable()
        val pBase = if (isNullable) pType.makeNotNull() else pType

        fun emitNonNullWrite(valExpr: IrExpression): IrExpression {
            return when {
                param.isLink -> {
                    val fn = findLatticeNativeFunction(latticeNativeSymbol, "setUnionLinkHandle")!!
                    irCall(fn).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irGet(uvVar))
                        putValueArgument(1, irString(param.uvKey))
                        putValueArgument(2, valExpr)
                    }
                }
                pBase.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true -> {
                    val fn = findLatticeNativeFunction(latticeNativeSymbol, "setUnionEmbedded")!!
                    val kClassType = irBuiltIns.kClassClass.typeWith(pBase)
                    val kClassRef = IrClassReferenceImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, pBase.classOrNull!!, pBase
                    )
                    irCall(fn).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        typeArguments[0] = pBase
                        putValueArgument(0, irGet(uvVar))
                        putValueArgument(1, irString(param.uvKey))
                        putValueArgument(2, valExpr)
                        putValueArgument(3, kClassRef)
                    }
                }
                isLatticeEnum(pBase) -> {
                    val enumCls = pBase.classOrNull!!.owner
                    if (getEnumStoreAsOrdinal(pBase)) {
                        val ordProp = enumCls.properties.find { it.name.asString() == "ordinal" }
                            ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))
                                ?.owner?.properties?.find { it.name.asString() == "ordinal" }!!
                        val fn = findLatticeNativeFunction(latticeNativeSymbol, "setUnionInt")!!
                        irCall(fn).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irGet(uvVar))
                            putValueArgument(1, irString(param.uvKey))
                            putValueArgument(2, irCall(ordProp.getter!!).apply { dispatchReceiver = valExpr })
                        }
                    } else {
                        val nameProp = enumCls.properties.find { it.name.asString() == "name" }
                            ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))
                                ?.owner?.properties?.find { it.name.asString() == "name" }!!
                        val fn = findLatticeNativeFunction(latticeNativeSymbol, "setUnionString")!!
                        irCall(fn).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irGet(uvVar))
                            putValueArgument(1, irString(param.uvKey))
                            putValueArgument(2, irCall(nameProp.getter!!).apply { dispatchReceiver = valExpr })
                        }
                    }
                }
                pBase.isInt() -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionInt")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                pBase.isLong() -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionLong")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                pBase.isDouble() -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionDouble")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                pBase.isFloat() -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionFloat")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                pBase.isBoolean() -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionBoolean")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                isByteArray(pBase) -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionBlob")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                isInstant(pBase) -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionInstant")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                isUuid(pBase) -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionUuid")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
                else -> irCall(findLatticeNativeFunction(latticeNativeSymbol, "setUnionString")!!).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(uvVar)); putValueArgument(1, irString(param.uvKey)); putValueArgument(2, valExpr)
                }
            }
        }

        if (isNullable) {
            val fVar = irTemporary(rawFieldVal, nameHint = "uField")
            +irIfThen(
                type = irBuiltIns.unitType,
                condition = irNotEquals(irGet(fVar), irNull()),
                thenPart = emitNonNullWrite(irImplicitCast(irGet(fVar), pBase))
            )
        } else {
            +emitNonNullWrite(rawFieldVal)
        }
    }

    private fun emitSetUnionStatements(
        blockBuilder: IrBlockBuilder,
        handleExpr: () -> IrExpression,
        propertyName: String,
        propertyType: IrType,
        valueExpr: IrExpression,
        unionMeta: UnionMeta,
        latticeNativeSymbol: IrClassSymbol
    ) = with(blockBuilder) {
        val createUnionFn = findLatticeNativeFunction(latticeNativeSymbol, "createUnionHandle") ?: return
        val setAndReleaseFn = findLatticeNativeFunction(latticeNativeSymbol, "setAndReleaseUnion") ?: return
        val clearUnionFn = findLatticeNativeFunction(latticeNativeSymbol, "clearUnion") ?: return

        val valVar = irTemporary(valueExpr, nameHint = "unionVal")

        val branches = mutableListOf<IrBranch>()
        if (propertyType.isNullable()) {
            branches += irBranch(
                irEqualsNull(irGet(valVar)),
                irCall(clearUnionFn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr())
                    putValueArgument(1, irString(propertyName))
                }
            )
        }

        for (c in unionMeta.cases) {
            val cond = irIs(irGet(valVar), c.caseClass.defaultType)
            val body = irBlock {
                val uvVar = irTemporary(
                    irCall(createUnionFn).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irString(c.caseName))
                    },
                    nameHint = "uvOut"
                )
                if (!c.isObject && c.params.isNotEmpty()) {
                    val typedCase = irTemporary(
                        irImplicitCast(irGet(valVar), c.caseClass.defaultType),
                        nameHint = "typedCase"
                    )
                    for (p in c.params) {
                        emitSetUnionFieldStatement(this, uvVar, typedCase, c.caseClass, p, latticeNativeSymbol)
                    }
                }
                +irCall(setAndReleaseFn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr())
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, irGet(uvVar))
                }
            }
            branches += irBranch(cond, body)
        }

        +irWhen(irBuiltIns.unitType, branches)
    }

    private fun transformUnionSetter(
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        unionMeta: UnionMeta,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val valueParam = setter.regularParameters.firstOrNull() ?: return

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            val thisReceiver = setter.dispatchReceiverParameter!!
            +irBlock {
                emitSetUnionStatements(
                    blockBuilder = this,
                    handleExpr = { irGetField(irGet(thisReceiver), handleField) },
                    propertyName = propertyName,
                    propertyType = propertyType,
                    valueExpr = irGet(valueParam),
                    unionMeta = unionMeta,
                    latticeNativeSymbol = latticeNativeSymbol
                )
            }
        }
    }

    // =========================================================================
    // @Embedded & @LatticeEnum & Collection Property Accessors
    // =========================================================================

    private fun transformEmbeddedGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getEmbedded = findLatticeNativeFunction(latticeNativeSymbol, "getEmbedded") ?: return
        val isNullable = propertyType.isNullable()
        val baseType = if (isNullable) propertyType.makeNotNull() else propertyType
        if (baseType.classOrNull == null) return

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!
            val kClassType = irBuiltIns.kClassClass.typeWith(baseType)
            val kClassRef = IrClassReferenceImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, baseType.classOrNull!!, baseType
            )
            +irReturn(
                irCall(getEmbedded).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    typeArguments[0] = baseType
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, kClassRef)
                    putValueArgument(3, irBoolean(!isNullable))
                }
            )
        }
    }

    private fun transformEmbeddedSetter(
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val setEmbedded = findLatticeNativeFunction(latticeNativeSymbol, "setEmbedded") ?: return
        val valueParam = setter.regularParameters.firstOrNull() ?: return
        val baseType = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType
        if (baseType.classOrNull == null) return

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            val thisReceiver = setter.dispatchReceiverParameter!!
            val kClassType = irBuiltIns.kClassClass.typeWith(baseType)
            val kClassRef = IrClassReferenceImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, baseType.classOrNull!!, baseType
            )
            +irCall(setEmbedded).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                typeArguments[0] = baseType
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
                putValueArgument(2, irGet(valueParam))
                putValueArgument(3, kClassRef)
            }
        }
    }

    private fun buildCollectionGetCall(
        builder: IrBuilderWithScope,
        handleExpr: IrExpression,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ): IrExpression? = with(builder) {
        val baseColl = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType
        val className = baseColl.classOrNull?.owner?.name?.asString() ?: return null
        val elemType = (baseColl as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
        val elemBase = elemType?.let { if (it.isNullable()) it.makeNotNull() else it }

        val isList = className in setOf("List", "MutableList", "ArrayList")
        val isSet = className in setOf("Set", "MutableSet", "HashSet", "LinkedHashSet")
        val isMap = className in setOf("Map", "MutableMap", "HashMap", "LinkedHashMap")

        when {
            isList && elemBase?.isString() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, if (propertyType.isNullable()) "getStringListOrNull" else "getStringList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName))
                }
            }
            isList && elemBase?.isInt() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getIntList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName))
                }
            }
            isList && elemBase?.isLong() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getLongList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName))
                }
            }
            isList && elemBase?.isDouble() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getDoubleList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName))
                }
            }
            isList && elemBase?.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getEmbeddedList") ?: return null
                val kClassType = irBuiltIns.kClassClass.typeWith(elemBase)
                val kClassRef = IrClassReferenceImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, elemBase.classOrNull!!, elemBase
                )
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    typeArguments[0] = elemBase
                    putValueArgument(0, handleExpr)
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, kClassRef)
                }
            }
            isList && elemBase != null && isLatticeEnum(elemBase) -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getEnumList") ?: return null
                val valuesFn = elemBase.classOrNull?.owner?.functions?.find { it.name.asString() == "values" } ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    typeArguments[0] = elemBase
                    putValueArgument(0, handleExpr)
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, irCall(valuesFn))
                }
            }
            isSet && elemBase?.isString() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getStringSet") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName))
                }
            }
            isSet && elemBase != null && isLatticeEnum(elemBase) -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getEnumSet") ?: return null
                val valuesFn = elemBase.classOrNull?.owner?.functions?.find { it.name.asString() == "values" } ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    typeArguments[0] = elemBase
                    putValueArgument(0, handleExpr)
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, irCall(valuesFn))
                }
            }
            isMap -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "getStringStringMap") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName))
                }
            }
            else -> null
        }
    }

    private fun buildCollectionSetCall(
        builder: IrBuilderWithScope,
        handleExpr: IrExpression,
        propertyName: String,
        propertyType: IrType,
        valueExpr: IrExpression,
        latticeNativeSymbol: IrClassSymbol
    ): IrExpression? = with(builder) {
        val baseColl = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType
        val className = baseColl.classOrNull?.owner?.name?.asString() ?: return null
        val elemType = (baseColl as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
        val elemBase = elemType?.let { if (it.isNullable()) it.makeNotNull() else it }

        val isList = className in setOf("List", "MutableList", "ArrayList")
        val isSet = className in setOf("Set", "MutableSet", "HashSet", "LinkedHashSet")
        val isMap = className in setOf("Map", "MutableMap", "HashMap", "LinkedHashMap")

        when {
            isList && elemBase?.isString() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setStringList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            isList && elemBase?.isInt() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setIntList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            isList && elemBase?.isLong() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setLongList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            isList && elemBase?.isDouble() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setDoubleList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            isList && elemBase?.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setEmbeddedList") ?: return null
                val kClassType = irBuiltIns.kClassClass.typeWith(elemBase)
                val kClassRef = IrClassReferenceImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET, kClassType, elemBase.classOrNull!!, elemBase
                )
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    typeArguments[0] = elemBase
                    putValueArgument(0, handleExpr)
                    putValueArgument(1, irString(propertyName))
                    putValueArgument(2, valueExpr)
                    putValueArgument(3, kClassRef)
                }
            }
            isList && elemBase != null && isLatticeEnum(elemBase) -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setEnumList") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            isSet && elemBase?.isString() == true -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setStringSet") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            isSet && elemBase != null && isLatticeEnum(elemBase) -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setEnumSet") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            isMap -> {
                val fn = findLatticeNativeFunction(latticeNativeSymbol, "setStringStringMap") ?: return null
                irCall(fn).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, handleExpr); putValueArgument(1, irString(propertyName)); putValueArgument(2, valueExpr)
                }
            }
            else -> null
        }
    }

    private fun transformCollectionGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
        val thisReceiver = getter.dispatchReceiverParameter ?: return
        val callExpr = buildCollectionGetCall(
            builder = builder,
            handleExpr = builder.irGetField(builder.irGet(thisReceiver), handleField),
            propertyName = propertyName,
            propertyType = propertyType,
            latticeNativeSymbol = latticeNativeSymbol
        ) ?: return

        getter.body = builder.irBlockBody {
            +irReturn(callExpr)
        }
    }

    private fun transformCollectionSetter(
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val valueParam = setter.regularParameters.firstOrNull() ?: return
        val builder = DeclarationIrBuilder(pluginContext, setter.symbol)
        val thisReceiver = setter.dispatchReceiverParameter ?: return
        val callExpr = buildCollectionSetCall(
            builder = builder,
            handleExpr = builder.irGetField(builder.irGet(thisReceiver), handleField),
            propertyName = propertyName,
            propertyType = propertyType,
            valueExpr = builder.irGet(valueParam),
            latticeNativeSymbol = latticeNativeSymbol
        ) ?: return

        setter.body = builder.irBlockBody {
            +callExpr
        }
    }

    private fun transformEnumGetter(
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val isNullable = propertyType.isNullable()
        val baseType = if (isNullable) propertyType.makeNotNull() else propertyType
        val storeAsOrdinal = getEnumStoreAsOrdinal(baseType)
        val enumClass = baseType.classOrNull?.owner ?: return

        val valueOfFn = enumClass.functions.find {
            it.name.asString() == "valueOf" && it.regularParameters.size == 1
        }

        if (storeAsOrdinal) {
            val nativeGetter = findLatticeNativeFunction(latticeNativeSymbol, "getInt") ?: return
            val hasValueFn = findLatticeNativeFunction(latticeNativeSymbol, "hasValue")
            val valuesFn = enumClass.functions.find { it.name.asString() == "values" } ?: return

            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                val thisReceiver = getter.dispatchReceiverParameter!!
                val handleExpr = irGetField(irGet(thisReceiver), handleField)

                if (isNullable && hasValueFn != null) {
                    val hasValue = irCall(hasValueFn).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, handleExpr)
                        putValueArgument(1, irString(propertyName))
                    }
                    +irIfThenElse(
                        type = propertyType,
                        condition = hasValue,
                        thenPart = irBlock {
                            val ordinal = irCall(nativeGetter).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                putValueArgument(1, irString(propertyName))
                            }
                            +irReturn(irCall(
                                irBuiltIns.arrayClass.owner.declarations
                                    .filterIsInstance<IrSimpleFunction>()
                                    .find { it.name.asString() == "get" }!!.symbol
                            ).apply {
                                dispatchReceiver = irCall(valuesFn.symbol)
                                putValueArgument(0, ordinal)
                            })
                        },
                        elsePart = irReturn(irNull())
                    )
                } else {
                    val ordinal = irCall(nativeGetter).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, handleExpr)
                        putValueArgument(1, irString(propertyName))
                    }
                    +irReturn(irCall(
                        irBuiltIns.arrayClass.owner.declarations
                            .filterIsInstance<IrSimpleFunction>()
                            .find { it.name.asString() == "get" }!!.symbol
                    ).apply {
                        dispatchReceiver = irCall(valuesFn.symbol)
                        putValueArgument(0, ordinal)
                    })
                }
            }
        } else {
            val nativeGetter = findLatticeNativeFunction(latticeNativeSymbol, if (isNullable) "getStringOrNull" else "getString")
            if (nativeGetter == null || valueOfFn == null) return

            val enumEntryNames = enumClass.declarations
                .filterIsInstance<IrEnumEntry>()
                .map { it.name.asString() }
            val declaredDefault = getEnumDefaultValue(propertyType)
            val defaultIndex = enumEntryNames.indexOf(declaredDefault).takeIf { it >= 0 } ?: 0
            val valuesFnForNames = enumClass.functions.find { it.name.asString() == "values" }
            val nameResolver = findLatticeNativeFunction(
                latticeNativeSymbol,
                if (isNullable) "enumNameOrNull" else "enumNameOrDefault"
            )

            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                val thisReceiver = getter.dispatchReceiverParameter!!
                val rawName = irCall(nativeGetter).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                }
                val nameValue = if (nameResolver != null && valuesFnForNames != null) {
                    irCall(nameResolver).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irCall(valuesFnForNames))
                        putValueArgument(1, rawName)
                        if (!isNullable) putValueArgument(2, irInt(defaultIndex))
                    }
                } else rawName

                if (isNullable) {
                    val nameVar = irTemporary(nameValue, nameHint = "enumName")
                    +irIfThenElse(
                        type = propertyType,
                        condition = irEqualsNull(irGet(nameVar)),
                        thenPart = irReturn(irNull()),
                        elsePart = irReturn(irCall(valueOfFn).apply {
                            putValueArgument(0, irGet(nameVar))
                        })
                    )
                } else {
                    +irReturn(irCall(valueOfFn).apply {
                        putValueArgument(0, nameValue)
                    })
                }
            }
        }
    }

    private fun transformEnumSetter(
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val isNullable = propertyType.isNullable()
        val baseType = if (isNullable) propertyType.makeNotNull() else propertyType
        val storeAsOrdinal = getEnumStoreAsOrdinal(baseType)
        val enumClass = baseType.classOrNull?.owner ?: return
        val valueParam = setter.regularParameters.firstOrNull() ?: return

        val nameProp = enumClass.properties.find { it.name.asString() == "name" }
            ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))
                ?.owner?.properties?.find { it.name.asString() == "name" }
        val ordinalProp = enumClass.properties.find { it.name.asString() == "ordinal" }
            ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))
                ?.owner?.properties?.find { it.name.asString() == "ordinal" }

        if (storeAsOrdinal) {
            val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setInt")
            if (nativeSetter == null || ordinalProp?.getter == null) return

            setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
                val thisReceiver = setter.dispatchReceiverParameter!!
                if (isNullable) {
                    val setNull = findLatticeNativeFunction(latticeNativeSymbol, "setNull")
                    +irIfThenElse(
                        type = irBuiltIns.unitType,
                        condition = irEqualsNull(irGet(valueParam)),
                        thenPart = if (setNull != null) {
                            irCall(setNull).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                putValueArgument(1, irString(propertyName))
                            }
                        } else {
                            irCall(nativeSetter).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                putValueArgument(1, irString(propertyName))
                                putValueArgument(2, irInt(0))
                            }
                        },
                        elsePart = irCall(nativeSetter).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                            putValueArgument(1, irString(propertyName))
                            putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                                dispatchReceiver = irGet(valueParam)
                            })
                        }
                    )
                } else {
                    +irCall(nativeSetter).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                        putValueArgument(1, irString(propertyName))
                        putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                            dispatchReceiver = irGet(valueParam)
                        })
                    }
                }
            }
        } else {
            val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setString")
            if (nativeSetter == null || nameProp?.getter == null) return

            setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
                val thisReceiver = setter.dispatchReceiverParameter!!
                if (isNullable) {
                    val setNull = findLatticeNativeFunction(latticeNativeSymbol, "setNull")
                    +irIfThenElse(
                        type = irBuiltIns.unitType,
                        condition = irEqualsNull(irGet(valueParam)),
                        thenPart = if (setNull != null) {
                            irCall(setNull).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                putValueArgument(1, irString(propertyName))
                            }
                        } else {
                            irCall(nativeSetter).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                putValueArgument(1, irString(propertyName))
                                putValueArgument(2, irString(""))
                            }
                        },
                        elsePart = irCall(nativeSetter).apply {
                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                            putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                            putValueArgument(1, irString(propertyName))
                            putValueArgument(2, irCall(nameProp.getter!!).apply {
                                dispatchReceiver = irGet(valueParam)
                            })
                        }
                    )
                } else {
                    +irCall(nativeSetter).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                        putValueArgument(1, irString(propertyName))
                        putValueArgument(2, irCall(nameProp.getter!!).apply {
                            dispatchReceiver = irGet(valueParam)
                        })
                    }
                }
            }
        }
    }

    // =========================================================================
    // Type Classification Helpers
    // =========================================================================

    private fun getNativeGetterName(type: IrType): String {
        val isNullable = type.isNullable()
        val baseType = if (isNullable) type.makeNotNull() else type
        return when {
            type.isString() || (isNullable && baseType.isString()) ->
                if (isNullable) "getStringOrNull" else "getString"
            type.isInt() || (isNullable && baseType.isInt()) ->
                if (isNullable) "getIntOrNull" else "getInt"
            type.isLong() || (isNullable && baseType.isLong()) ->
                if (isNullable) "getLongOrNull" else "getLong"
            type.isDouble() || (isNullable && baseType.isDouble()) ->
                if (isNullable) "getDoubleOrNull" else "getDouble"
            type.isFloat() || (isNullable && baseType.isFloat()) ->
                if (isNullable) "getFloatOrNull" else "getFloat"
            type.isBoolean() || (isNullable && baseType.isBoolean()) ->
                if (isNullable) "getBooleanOrNull" else "getBoolean"
            isByteArray(baseType) ->
                if (isNullable) "getBlobOrNull" else "getBlob"
            isFloatVector(baseType) ->
                if (isNullable) "getFloatVectorOrNull" else "getFloatVector"
            isDoubleVector(baseType) ->
                if (isNullable) "getDoubleVectorOrNull" else "getDoubleVector"
            isGeoBounds(baseType) ->
                if (isNullable) "getGeoBoundsOrNull" else "getGeoBounds"
            isInstant(baseType) ->
                if (isNullable) "getInstantOrNull" else "getInstant"
            isUuid(baseType) ->
                if (isNullable) "getUuidOrNull" else "getUuid"
            else -> "getString"
        }
    }

    private fun getNativeSetterName(type: IrType): String {
        val baseType = if (type.isNullable()) type.makeNotNull() else type
        return when {
            baseType.isString() -> "setString"
            baseType.isInt() -> "setInt"
            baseType.isLong() -> "setLong"
            baseType.isDouble() -> "setDouble"
            baseType.isFloat() -> "setFloat"
            baseType.isBoolean() -> "setBoolean"
            isByteArray(baseType) -> "setBlob"
            isFloatVector(baseType) -> "setFloatVector"
            isDoubleVector(baseType) -> "setDoubleVector"
            isGeoBounds(baseType) -> "setGeoBounds"
            isInstant(baseType) -> "setInstant"
            isUuid(baseType) -> "setUuid"
            else -> "setString"
        }
    }

    private fun isNativeCollection(type: IrType): Boolean {
        val base = if (type.isNullable()) type.makeNotNull() else type
        val className = base.classOrNull?.owner?.name?.asString() ?: return false
        return className in setOf(
            "List", "MutableList", "ArrayList",
            "Set", "MutableSet", "HashSet", "LinkedHashSet",
            "Map", "MutableMap", "HashMap", "LinkedHashMap"
        )
    }

    private fun isByteArray(type: IrType): Boolean =
        type.classOrNull?.owner?.classId?.asFqNameString() == "kotlin.ByteArray"

    private fun isFloatVector(type: IrType): Boolean =
        type.classOrNull?.owner?.classId?.asFqNameString() == "com.lattice.FloatVector"

    private fun isDoubleVector(type: IrType): Boolean =
        type.classOrNull?.owner?.classId?.asFqNameString() == "com.lattice.DoubleVector"

    private fun isGeoBounds(type: IrType): Boolean =
        type.classOrNull?.owner?.classId?.asFqNameString() == "com.lattice.GeoBounds"

    private fun isInstant(type: IrType): Boolean =
        type.classOrNull?.owner?.classId?.asFqNameString() == "kotlinx.datetime.Instant"

    private fun isUuid(type: IrType): Boolean =
        type.classOrNull?.owner?.classId?.asFqNameString() == "kotlin.uuid.Uuid"

    private fun isLatticeEnum(type: IrType): Boolean {
        val classOwner = type.classOrNull?.owner ?: return false
        if (classOwner.kind != ClassKind.ENUM_CLASS) return false
        return classOwner.hasAnnotation(latticeEnumAnnotationFqn)
    }

    private fun getEnumStoreAsOrdinal(type: IrType): Boolean {
        val classOwner = type.classOrNull?.owner ?: return false
        val annotation = classOwner.annotations.find { ann ->
            ann.type.classOrNull?.owner?.fqNameWhenAvailable == latticeEnumAnnotationFqn
        } ?: return false
        val arg = annotation.getValueArgument(Name.identifier("storeAsOrdinal"))
        return (arg as? IrConst)?.value as? Boolean ?: false
    }

    private fun getEnumDefaultValue(type: IrType): String {
        val base = if (type.isNullable()) type.makeNotNull() else type
        val classOwner = base.classOrNull?.owner ?: return ""
        val annotation = classOwner.annotations.find { ann ->
            ann.type.classOrNull?.owner?.fqNameWhenAvailable == latticeEnumAnnotationFqn
        } ?: return ""
        val arg = annotation.getValueArgument(Name.identifier("defaultValue"))
        return (arg as? IrConst)?.value as? String ?: ""
    }

    private fun findLatticeNativeFunction(latticeNativeSymbol: IrClassSymbol, name: String): IrSimpleFunctionSymbol? {
        return latticeNativeSymbol.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .find { it.name.asString() == name }
            ?.symbol
    }
}

object LatticeDeclarationOrigin : IrDeclarationOrigin {
    override val name: String = "LATTICE_GENERATED"
    override val isSynthetic: Boolean = true
    override fun toString(): String = name
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrClass.hasAnnotation(fqName: FqName): Boolean {
    return annotations.any { annotation ->
        annotation.type.classOrNull?.owner?.fqNameWhenAvailable == fqName
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrProperty.hasAnnotation(fqName: FqName): Boolean {
    return annotations.any { annotation ->
        annotation.type.classOrNull?.owner?.fqNameWhenAvailable == fqName
    }
}

private val IrFunction.regularParameters: List<IrValueParameter>
    get() = parameters.filter { it.kind == IrParameterKind.Regular }

private fun IrFunctionAccessExpression.putValueArgument(index: Int, valueArgument: IrExpression) {
    val regular = symbol.owner.parameters.filter { it.kind == IrParameterKind.Regular }
    require(index in regular.indices) {
        "putValueArgument($index) on ${symbol.owner.name}, which declares ${regular.size} value parameter(s)"
    }
    arguments[regular[index]] = valueArgument
}
