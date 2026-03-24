package com.lattice.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.declarations.impl.IrAnonymousInitializerImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR Transformer for @Model classes.
 *
 * Follows Swift's approach:
 * - C++ object is created at init time with schema (using createObjectWithSchema)
 * - All property storage is in C++ - no local Kotlin storage
 * - Property access always goes through C++ API
 *
 * Transforms:
 * ```kotlin
 * @Model
 * class Trip {
 *     var name: String = ""
 *     var days: Int = 0
 * }
 * ```
 *
 * Into:
 * ```kotlin
 * class Trip : LatticeObject {
 *     override var _latticeHandle: Long = LatticeNative.createObjectWithSchema("Trip", _latticeSchema)
 *     override val _latticeTableName: String get() = "Trip"
 *
 *     var name: String
 *         get() = LatticeNative.getString(_latticeHandle, "name")
 *         set(value) = LatticeNative.setString(_latticeHandle, "name", value)
 *
 *     // similar for days...
 * }
 *
 * init {
 *     // Copy initial values from backing fields to C++
 *     LatticeNative.setString(_latticeHandle, "name", backingField_name)
 *     LatticeNative.setInt(_latticeHandle, "days", backingField_days)
 * }
 * ```
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class LatticeIrTransformer(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector
) : IrElementTransformerVoidWithContext() {

    private val modelAnnotationFqn = FqName("com.lattice.Model")
    private val ignoreAnnotationFqn = FqName("com.lattice.Ignore")
    private val linkAnnotationFqn = FqName("com.lattice.Link")
    private val linkListAnnotationFqn = FqName("com.lattice.LinkList")
    private val embeddedAnnotationFqn = FqName("com.lattice.Embedded")
    private val latticeEnumAnnotationFqn = FqName("com.lattice.LatticeEnum")
    private val latticeObjectFqn = FqName("com.lattice.LatticeObject")
    private val latticeNativeFqn = FqName("com.lattice.LatticeNative")
    private val latticePropertyDescriptorFqn = FqName("com.lattice.LatticePropertyDescriptor")
    private val latticeTypeFqn = FqName("com.lattice.LatticeType")
    private val latticePropertyKindFqn = FqName("com.lattice.LatticePropertyKind")

    private val irFactory get() = pluginContext.irFactory
    private val irBuiltIns get() = pluginContext.irBuiltIns

    // Lazy references to runtime classes
    private val latticeObjectClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticeObjectFqn))
    }

    private val latticeNativeClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticeNativeFqn))
    }

    private val latticePropertyDescriptorClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticePropertyDescriptorFqn))
    }

    private val latticeTypeClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticeTypeFqn))
    }

    private val latticePropertyKindClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(latticePropertyKindFqn))
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

        messageCollector.report(
            CompilerMessageSeverity.WARNING,
            "Lattice: Transforming @Model class '${declaration.name}'"
        )

        // FIR has already added:
        // - LatticeObject interface
        // - _latticeHandle property declaration
        // - _latticeTableName property declaration
        // We need to:
        // 1. Create a backing field for _latticeHandle
        // 2. Implement the getters/setters for the FIR-generated properties

        // Find FIR-generated _latticeHandle property
        val handleProperty = declaration.properties.find { it.name.asString() == "_latticeHandle" }

        // Find or create backing field for _latticeHandle
        val handleField = findOrCreateHandleField(declaration, latticeNative)
        // Only add to declarations if not already present
        if (handleField !in declaration.declarations) {
            declaration.declarations.add(handleField)
        }

        if (handleProperty != null) {
            // Implement the FIR-generated _latticeHandle property
            implementHandleProperty(declaration, handleProperty, handleField)
        } else {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: _latticeHandle property not found (should be generated by FIR)"
            )
        }

        // Find and implement FIR-generated _latticeTableName property
        val tableNameProperty = declaration.properties.find { it.name.asString() == "_latticeTableName" }
        if (tableNameProperty != null) {
            implementTableNameProperty(declaration, tableNameProperty)
        } else {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: _latticeTableName property not found (should be generated by FIR)"
            )
        }

        // Find user properties first (we need this for schema)
        val userProperties = declaration.properties.filter { prop ->
            val name = prop.name.asString()
            name !in setOf("_latticeHandle", "_latticeTableName", "_latticeSchema", "isManaged") &&
                !prop.hasAnnotation(ignoreAnnotationFqn) &&
                prop.backingField != null
        }.toList()

        // Find and implement FIR-generated _latticeSchema property
        val schemaProperty = declaration.properties.find { it.name.asString() == "_latticeSchema" }
        if (schemaProperty != null) {
            implementSchemaProperty(declaration, schemaProperty, userProperties)
        } else {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: _latticeSchema property not found (should be generated by FIR)"
            )
        }

        // Find and implement FIR-generated _copyPropertiesToNative function
        val copyFunction = declaration.functions.find { it.name.asString() == "_copyPropertiesToNative" }
        if (copyFunction != null) {
            implementCopyToNativeFunction(declaration, copyFunction, userProperties, latticeNative)
        } else {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: _copyPropertiesToNative function not found (should be generated by FIR)"
            )
        }

        // Transform user properties
        val propertiesToTransform = declaration.properties.filter { prop ->
            val name = prop.name.asString()
            name !in setOf("_latticeHandle", "_latticeTableName", "_latticeSchema", "isManaged") &&
                !prop.hasAnnotation(ignoreAnnotationFqn) &&
                prop.backingField != null
        }.toList()

        // Add init block that creates C++ object with schema and copies initial values
        addInitBlock(declaration, handleField, propertiesToTransform, latticeNative)

        propertiesToTransform.forEach { property ->
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice:   Transforming property '${property.name}'"
            )
            transformProperty(declaration, property, handleField, latticeNative)
        }

        // Register factory so Lattice and VirtualModelRegistry can create instances
        addFactoryRegistration(declaration)

        return super.visitClassNew(declaration)
    }

    /**
     * Add factory registration to companion object.
     * Generates code that registers the factory when the companion object is initialized:
     *
     *   companion object {
     *       init {
     *           Lattice.registerFactory(MyClass::class) { MyClass() }
     *       }
     *   }
     */
    private fun addFactoryRegistration(irClass: IrClass) {
        // Find VirtualModelRegistry for table-based factory registration
        val virtualModelRegistryClass = pluginContext.referenceClass(
            ClassId.topLevel(FqName("com.lattice.VirtualModelRegistry"))
        )
        val registerTableFactoryFn = virtualModelRegistryClass?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.find { it.name.asString() == "registerTableFactory" }

        // Find Lattice class
        val latticeClass = pluginContext.referenceClass(ClassId.topLevel(FqName("com.lattice.Lattice")))
        if (latticeClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find Lattice class for factory registration"
            )
            return
        }

        // Find registerFactory function
        val registerFactoryFn = latticeClass.owner.declarations
            .filterIsInstance<IrClass>()
            .find { it.isCompanion }
            ?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.find { it.name.asString() == "registerFactory" }

        if (registerFactoryFn == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find Lattice.registerFactory function"
            )
            return
        }

        // Find or create companion object
        var companion = irClass.declarations
            .filterIsInstance<IrClass>()
            .find { it.isCompanion }

        if (companion == null) {
            // Skip companion creation — factory registration will be handled
            // via reflection on JVM/Android (createFactoryViaReflection).
            // Creating a companion in the IR transformer causes ObjectClassLowering
            // assertions because the companion needs proper thisReceiver/superTypes setup.
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Skipping factory registration for '${irClass.name}' (no companion object, will use reflection)"
            )
            return
        }

        // NOTE: Companion object creation removed — it caused ObjectClassLowering assertion
        // failures because the IR companion lacked proper thisReceiver/superTypes setup.

        // Find primary constructor of the model class
        val modelConstructor = irClass.primaryConstructor
        if (modelConstructor == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Model class '${irClass.name}' has no primary constructor for factory"
            )
            return
        }

        // Create init block for companion
        val initBlock = irFactory.createAnonymousInitializer(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = LatticeDeclarationOrigin,
            symbol = IrAnonymousInitializerSymbolImpl()
        ).apply {
            parent = companion

            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                // Create KClass reference: MyClass::class
                val kClassType = pluginContext.irBuiltIns.kClassClass.typeWith(irClass.defaultType)
                val kClassRef = IrClassReferenceImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = kClassType,
                    symbol = irClass.symbol,
                    classType = irClass.defaultType
                )

                // Create lambda: { MyClass() }
                val lambdaType = pluginContext.irBuiltIns.functionN(0).typeWith(irClass.defaultType)

                // Create the lambda function
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

                // Create function reference expression
                val lambdaExpr = IrFunctionExpressionImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = lambdaType,
                    function = lambdaFn,
                    origin = IrStatementOrigin.LAMBDA
                )

                // Call Lattice.registerFactory(MyClass::class) { MyClass() }
                +irCall(registerFactoryFn).apply {
                    // Get the Lattice companion object
                    val latticeCompanion = latticeClass.owner.declarations
                        .filterIsInstance<IrClass>()
                        .find { it.isCompanion }
                    if (latticeCompanion != null) {
                        dispatchReceiver = irGetObject(latticeCompanion.symbol)
                    }
                    putValueArgument(0, kClassRef)
                    putValueArgument(1, lambdaExpr)
                }

                // Also register with VirtualModelRegistry for VirtualList type resolution
                // VirtualModelRegistry.registerTableFactory("ClassName") { ClassName() }
                if (registerTableFactoryFn != null && virtualModelRegistryClass != null) {
                    // Create a separate lambda for the VirtualModelRegistry registration
                    // The return type needs to be LatticeObject for registerTableFactory
                    val latticeObjectClass = pluginContext.referenceClass(
                        ClassId.topLevel(FqName("com.lattice.LatticeObject"))
                    )
                    if (latticeObjectClass != null) {
                        val vmrLambdaType = pluginContext.irBuiltIns.functionN(0).typeWith(latticeObjectClass.defaultType)

                        val vmrLambdaFn = irFactory.createSimpleFunction(
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            origin = LatticeDeclarationOrigin,
                            name = Name.special("<anonymous>"),
                            visibility = DescriptorVisibilities.LOCAL,
                            isInline = false,
                            isExpect = false,
                            returnType = latticeObjectClass.defaultType,
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

                        val vmrLambdaExpr = IrFunctionExpressionImpl(
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            type = vmrLambdaType,
                            function = vmrLambdaFn,
                            origin = IrStatementOrigin.LAMBDA
                        )

                        +irCall(registerTableFactoryFn).apply {
                            dispatchReceiver = irGetObject(virtualModelRegistryClass)
                            putValueArgument(0, irString(irClass.name.asString()))
                            putValueArgument(1, vmrLambdaExpr)
                        }
                    }
                }
            }
        }

        companion.declarations.add(initBlock)

        messageCollector.report(
            CompilerMessageSeverity.WARNING,
            "Lattice:   Added factory registration to companion object for '${irClass.name}'"
        )
    }

    private fun findOrCreateHandleField(irClass: IrClass, latticeNativeSymbol: IrClassSymbol): IrField {
        // Check if field already exists (may have been created by a link from another class)
        val fieldName = "_lattice_handle_field_${irClass.name.asString()}"
        val existingField = irClass.declarations
            .filterIsInstance<IrField>()
            .find { it.name.asString() == fieldName }

        if (existingField != null) {
            return existingField
        }

        // Create the field
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
            // Initialize to 0L - C++ object is created in init block
            initializer = irFactory.createExpressionBody(
                IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.longType, 0L)
            )
        }
    }

    private fun implementHandleProperty(irClass: IrClass, property: IrProperty, field: IrField) {
        // Implement getter body for FIR-generated property
        property.getter?.let { getter ->
            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                +irReturn(irGetField(irGet(getter.dispatchReceiverParameter!!), field))
            }
        }

        // Implement setter body for FIR-generated property
        property.setter?.let { setter ->
            val valueParam = setter.valueParameters.firstOrNull()
            if (valueParam != null) {
                setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
                    +irSetField(irGet(setter.dispatchReceiverParameter!!), field, irGet(valueParam))
                }
            }
        }
    }

    private fun implementTableNameProperty(irClass: IrClass, property: IrProperty) {
        val tableName = irClass.name.asString()

        // Implement getter body for FIR-generated property
        property.getter?.let { getter ->
            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                +irReturn(irString(tableName))
            }
        }
    }

    /**
     * Add an init block that:
     * 1. Creates C++ object with schema using createObjectWithSchema
     * 2. Copies initial property values from backing fields to C++
     */
    private fun addInitBlock(
        irClass: IrClass,
        handleField: IrField,
        userProperties: List<IrProperty>,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val tableName = irClass.name.asString()

        // Find createObjectWithSchema(tableName, schema) to create C++ object with property types
        val createObjectWithSchemaFn = findLatticeNativeFunction(latticeNativeSymbol, "createObjectWithSchema")
        // Fallback to createObject if createObjectWithSchema not found
        val createObjectFn = createObjectWithSchemaFn
            ?: findLatticeNativeFunction(latticeNativeSymbol, "createObject")
        if (createObjectFn == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative.createObject, skipping init block"
            )
            return
        }

        // Find the _latticeSchema property to pass to createObjectWithSchema
        val schemaProperty = irClass.properties.find { it.name.asString() == "_latticeSchema" }

        // Create anonymous initializer
        val initBlock = irFactory.createAnonymousInitializer(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = LatticeDeclarationOrigin,
            symbol = IrAnonymousInitializerSymbolImpl()
        ).apply {
            parent = irClass

            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                val thisReceiver = irClass.thisReceiver!!

                // _latticeHandle = LatticeNative.createObjectWithSchema("Trip", _latticeSchema)
                // Passing schema ensures C++ object has property type info for INSERT
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

                // Copy initial property values from backing fields to C++
                // Skip link properties and embedded properties - they have special handling
                userProperties.forEach { prop ->
                    // Skip link properties
                    if (prop.hasAnnotation(linkAnnotationFqn) || prop.hasAnnotation(linkListAnnotationFqn)) {
                        return@forEach
                    }

                    val backingField = prop.backingField ?: return@forEach
                    val propName = prop.name.asString()
                    val propType = backingField.type

                    // Skip LatticeList properties (detected by type)
                    if (propType.classOrNull?.owner?.name?.asString() == "LatticeList") {
                        return@forEach
                    }

                    // Skip VirtualList properties (detected by type)
                    if (propType.classOrNull?.owner?.name?.asString() == "VirtualList") {
                        return@forEach
                    }

                    // Handle native collection properties (List, Set, Map — stored as JSON TEXT)
                    if (isNativeCollection(propType)) {
                        val setterName = getCollectionSetterName(propType)
                        if (setterName != null) {
                            val collectionSetter = findLatticeNativeFunction(latticeNativeSymbol, setterName)
                            if (collectionSetter != null) {
                                val initialValue = irGetField(irGet(thisReceiver), backingField)
                                +irCall(collectionSetter).apply {
                                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                    putValueArgument(1, irString(propName))
                                    putValueArgument(2, initialValue)
                                }
                            }
                        }
                        return@forEach
                    }

                    // Skip embedded properties (detected by @Embedded annotation on the type)
                    val baseType = if (propType.isNullable()) propType.makeNotNull() else propType
                    val isEmbedded = baseType.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true
                    if (isEmbedded) {
                        return@forEach
                    }

                    // Handle enum properties specially (need to convert to string/int)
                    val isEnum = isLatticeEnum(baseType)
                    if (isEnum) {
                        val isNullable = propType.isNullable()
                        val storeAsOrdinal = getEnumStoreAsOrdinal(baseType)
                        val enumClass = baseType.classOrNull?.owner

                        if (enumClass != null) {
                            val initialValue = irGetField(irGet(thisReceiver), backingField)

                            if (isNullable) {
                                // For nullable enums, only set if not null
                                val setNull = findLatticeNativeFunction(latticeNativeSymbol, "setNull")

                                if (storeAsOrdinal) {
                                    val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setInt")
                                        ?: return@forEach
                                    val ordinalProp = enumClass.properties.find { it.name.asString() == "ordinal" }
                                        ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "ordinal" }

                                    if (ordinalProp?.getter != null) {
                                        val initialVar = irTemporary(initialValue, nameHint = "enumInit")
                                        +irIfThenElse(
                                            type = irBuiltIns.unitType,
                                            condition = irEqualsNull(irGet(initialVar)),
                                            thenPart = if (setNull != null) {
                                                irCall(setNull).apply {
                                                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                                    putValueArgument(1, irString(propName))
                                                }
                                            } else {
                                                irCall(nativeSetter).apply {
                                                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                                    putValueArgument(1, irString(propName))
                                                    putValueArgument(2, irInt(0))
                                                }
                                            },
                                            elsePart = irCall(nativeSetter).apply {
                                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                                putValueArgument(1, irString(propName))
                                                putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                                                    dispatchReceiver = irGet(initialVar)
                                                })
                                            }
                                        )
                                    }
                                } else {
                                    val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setString")
                                        ?: return@forEach
                                    val nameProp = enumClass.properties.find { it.name.asString() == "name" }
                                        ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "name" }

                                    if (nameProp?.getter != null) {
                                        val initialVar = irTemporary(initialValue, nameHint = "enumInit")
                                        +irIfThenElse(
                                            type = irBuiltIns.unitType,
                                            condition = irEqualsNull(irGet(initialVar)),
                                            thenPart = if (setNull != null) {
                                                irCall(setNull).apply {
                                                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                                    putValueArgument(1, irString(propName))
                                                }
                                            } else {
                                                irCall(nativeSetter).apply {
                                                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                                    putValueArgument(1, irString(propName))
                                                    putValueArgument(2, irString("")
                                                    )
                                                }
                                            },
                                            elsePart = irCall(nativeSetter).apply {
                                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                                putValueArgument(1, irString(propName))
                                                putValueArgument(2, irCall(nameProp.getter!!).apply {
                                                    dispatchReceiver = irGet(initialVar)
                                                })
                                            }
                                        )
                                    }
                                }
                            } else {
                                // Non-nullable enum - just set the value
                                if (storeAsOrdinal) {
                                    val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setInt")
                                        ?: return@forEach
                                    val ordinalProp = enumClass.properties.find { it.name.asString() == "ordinal" }
                                        ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "ordinal" }

                                    if (ordinalProp?.getter != null) {
                                        +irCall(nativeSetter).apply {
                                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                                            putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                            putValueArgument(1, irString(propName))
                                            putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                                                dispatchReceiver = initialValue
                                            })
                                        }
                                    }
                                } else {
                                    val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setString")
                                        ?: return@forEach
                                    val nameProp = enumClass.properties.find { it.name.asString() == "name" }
                                        ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "name" }

                                    if (nameProp?.getter != null) {
                                        +irCall(nativeSetter).apply {
                                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                                            putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                                            putValueArgument(1, irString(propName))
                                            putValueArgument(2, irCall(nameProp.getter!!).apply {
                                                dispatchReceiver = initialValue
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        return@forEach
                    }

                    val nativeSetterName = getNativeSetterName(propType)
                    val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, nativeSetterName)
                        ?: return@forEach

                    // Read initial value from backing field
                    val initialValue = irGetField(irGet(thisReceiver), backingField)

                    // LatticeNative.setXxx(_latticeHandle, "propName", initialValue)
                    +irCall(nativeSetter).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                        putValueArgument(1, irString(propName))
                        putValueArgument(2, initialValue)
                    }
                }
            }
        }

        // Add to class declarations
        irClass.declarations.add(initBlock)

        messageCollector.report(
            CompilerMessageSeverity.WARNING,
            "Lattice:   Added init block to create C++ object with schema for '${irClass.name}'"
        )
    }

    private fun implementCopyToNativeFunction(
        irClass: IrClass,
        function: IrSimpleFunction,
        userProperties: List<IrProperty>,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val nativeHandleParam = function.valueParameters.firstOrNull() ?: return

        function.body = DeclarationIrBuilder(pluginContext, function.symbol).irBlockBody {
            val thisReceiver = function.dispatchReceiverParameter!!

            // For each property, read from backing field and call LatticeNative setter
            // Skip links, link lists, and embedded properties
            userProperties.forEach { prop ->
                // Skip link properties
                if (prop.hasAnnotation(linkAnnotationFqn) || prop.hasAnnotation(linkListAnnotationFqn)) {
                    return@forEach
                }

                val backingField = prop.backingField ?: return@forEach
                val propName = prop.name.asString()
                val propType = backingField.type

                // Skip LatticeList properties
                if (propType.classOrNull?.owner?.name?.asString() == "LatticeList") {
                    return@forEach
                }

                // Skip VirtualList properties
                if (propType.classOrNull?.owner?.name?.asString() == "VirtualList") {
                    return@forEach
                }

                // Handle native collection properties (List, Set, Map — stored as JSON TEXT)
                if (isNativeCollection(propType)) {
                    val setterName = getCollectionSetterName(propType)
                    if (setterName != null) {
                        val collectionSetter = findLatticeNativeFunction(latticeNativeSymbol, setterName)
                        if (collectionSetter != null) {
                            val fieldValue = irGetField(irGet(thisReceiver), backingField)
                            +irCall(collectionSetter).apply {
                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                putValueArgument(0, irGet(nativeHandleParam))
                                putValueArgument(1, irString(propName))
                                putValueArgument(2, fieldValue)
                            }
                        }
                    }
                    return@forEach
                }

                // Skip embedded properties
                val baseType = if (propType.isNullable()) propType.makeNotNull() else propType
                val isEmbedded = baseType.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true
                if (isEmbedded) {
                    return@forEach
                }

                // Handle enum properties specially
                val isEnum = isLatticeEnum(baseType)
                if (isEnum) {
                    val isNullable = propType.isNullable()
                    val storeAsOrdinal = getEnumStoreAsOrdinal(baseType)
                    val enumClass = baseType.classOrNull?.owner

                    if (enumClass != null) {
                        val fieldValue = irGetField(irGet(thisReceiver), backingField)

                        if (storeAsOrdinal) {
                            val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setInt")
                                ?: return@forEach
                            val ordinalProp = enumClass.properties.find { it.name.asString() == "ordinal" }
                                ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "ordinal" }

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
                                                putValueArgument(0, irGet(nativeHandleParam))
                                                putValueArgument(1, irString(propName))
                                            }
                                        } else {
                                            irCall(nativeSetter).apply {
                                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                putValueArgument(0, irGet(nativeHandleParam))
                                                putValueArgument(1, irString(propName))
                                                putValueArgument(2, irInt(0))
                                            }
                                        },
                                        elsePart = irCall(nativeSetter).apply {
                                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                                            putValueArgument(0, irGet(nativeHandleParam))
                                            putValueArgument(1, irString(propName))
                                            putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                                                dispatchReceiver = irGet(valueVar)
                                            })
                                        }
                                    )
                                } else {
                                    +irCall(nativeSetter).apply {
                                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                                        putValueArgument(0, irGet(nativeHandleParam))
                                        putValueArgument(1, irString(propName))
                                        putValueArgument(2, irCall(ordinalProp.getter!!).apply {
                                            dispatchReceiver = fieldValue
                                        })
                                    }
                                }
                            }
                        } else {
                            val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setString")
                                ?: return@forEach
                            val nameProp = enumClass.properties.find { it.name.asString() == "name" }
                                ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "name" }

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
                                                putValueArgument(0, irGet(nativeHandleParam))
                                                putValueArgument(1, irString(propName))
                                            }
                                        } else {
                                            irCall(nativeSetter).apply {
                                                dispatchReceiver = irGetObject(latticeNativeSymbol)
                                                putValueArgument(0, irGet(nativeHandleParam))
                                                putValueArgument(1, irString(propName))
                                                putValueArgument(2, irString("")
                                                )
                                            }
                                        },
                                        elsePart = irCall(nativeSetter).apply {
                                            dispatchReceiver = irGetObject(latticeNativeSymbol)
                                            putValueArgument(0, irGet(nativeHandleParam))
                                            putValueArgument(1, irString(propName))
                                            putValueArgument(2, irCall(nameProp.getter!!).apply {
                                                dispatchReceiver = irGet(valueVar)
                                            })
                                        }
                                    )
                                } else {
                                    +irCall(nativeSetter).apply {
                                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                                        putValueArgument(0, irGet(nativeHandleParam))
                                        putValueArgument(1, irString(propName))
                                        putValueArgument(2, irCall(nameProp.getter!!).apply {
                                            dispatchReceiver = fieldValue
                                        })
                                    }
                                }
                            }
                        }
                    }
                    return@forEach
                }

                val nativeSetterName = getNativeSetterName(propType)
                val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, nativeSetterName)
                    ?: return@forEach

                // Read from backing field (unmanaged value)
                val fieldValue = irGetField(irGet(thisReceiver), backingField)

                // Call LatticeNative.setXxx(nativeHandle, propertyName, value)
                +irCall(nativeSetter).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGet(nativeHandleParam))
                    putValueArgument(1, irString(propName))
                    putValueArgument(2, fieldValue)
                }
            }
        }
    }

    private fun implementSchemaProperty(
        irClass: IrClass,
        property: IrProperty,
        userProperties: List<IrProperty>
    ) {
        val descriptorClass = latticePropertyDescriptorClass
        if (descriptorClass == null) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "Lattice: Cannot find LatticePropertyDescriptor class — _latticeSchema will be empty")
            return
        }
        val typeClass = latticeTypeClass
        if (typeClass == null) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "Lattice: Cannot find LatticeType enum — _latticeSchema will be empty")
            return
        }
        val kindClass = latticePropertyKindClass
        if (kindClass == null) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "Lattice: Cannot find LatticePropertyKind enum — _latticeSchema will be empty")
            return
        }

        // Find the LatticePropertyDescriptor primary constructor
        val descriptorConstructor = descriptorClass.owner.constructors.find { it.isPrimary }
            ?: descriptorClass.owner.constructors.firstOrNull()
        if (descriptorConstructor == null) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "Lattice: Cannot find LatticePropertyDescriptor constructor — _latticeSchema will be empty")
            return
        }

        // Find enum entries for LatticeType
        fun getTypeEnumEntry(name: String): IrEnumEntry? =
            typeClass.owner.declarations.filterIsInstance<IrEnumEntry>()
                .find { it.name.asString() == name }

        // Find enum entries for LatticePropertyKind
        fun getKindEnumEntry(name: String): IrEnumEntry? =
            kindClass.owner.declarations.filterIsInstance<IrEnumEntry>()
                .find { it.name.asString() == name }

        property.getter?.let { getter ->
            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                // Build list of LatticePropertyDescriptor
                val descriptors = userProperties.mapNotNull { prop ->
                    val backingField = prop.backingField ?: return@mapNotNull null
                    val propName = prop.name.asString()
                    val propType = backingField.type
                    val isNullable = propType.isNullable()

                    // Check if this is a @Link property, LatticeList type, or VirtualList type
                    val isLink = prop.hasAnnotation(linkAnnotationFqn)
                    val isVirtualList = propType.classOrNull?.owner?.name?.asString() == "VirtualList"
                    val isLinkList = prop.hasAnnotation(linkListAnnotationFqn) ||
                        propType.classOrNull?.owner?.name?.asString() == "LatticeList"

                    // Determine the property kind and target table
                    val kindName: String
                    var targetTableName: String? = null

                    if (isLink) {
                        kindName = "LINK"
                        // Extract target table name from property type
                        val baseType = if (isNullable) propType.makeNotNull() else propType
                        val targetClass = baseType.classOrNull?.owner
                        targetTableName = targetClass?.name?.asString()
                    } else if (isVirtualList) {
                        kindName = "VIRTUAL_LIST"
                        // VirtualList has no single target table — empty target, parent class as link table
                        targetTableName = null
                    } else if (isLinkList) {
                        kindName = "LINK_LIST"
                        // Extract element type from LatticeList<T>
                        val listType = propType
                        if (listType is org.jetbrains.kotlin.ir.types.IrSimpleType) {
                            val elementType = listType.arguments.firstOrNull()?.typeOrNull
                            val elementClass = elementType?.classOrNull?.owner
                            targetTableName = elementClass?.name?.asString()
                        }
                    } else {
                        kindName = "PRIMITIVE"
                    }

                    // Determine LatticeType based on property type
                    val baseForType = if (isNullable) propType.makeNotNull() else propType
                    val latticeTypeName = when {
                        isLink || isLinkList || isVirtualList -> "INTEGER" // Links are stored as foreign key IDs
                        propType.isString() || (isNullable && baseForType.isString()) -> "TEXT"
                        propType.isInt() || (isNullable && baseForType.isInt()) -> "INTEGER"
                        propType.isLong() || (isNullable && baseForType.isLong()) -> "INTEGER"
                        propType.isBoolean() || (isNullable && baseForType.isBoolean()) -> "INTEGER"
                        propType.isFloat() || (isNullable && baseForType.isFloat()) -> "REAL"
                        propType.isDouble() || (isNullable && baseForType.isDouble()) -> "REAL"
                        isByteArray(baseForType) -> "BLOB"
                        isFloatVector(baseForType) -> "BLOB"
                        isDoubleVector(baseForType) -> "BLOB"
                        isInstant(baseForType) -> "REAL"
                        isUuid(baseForType) -> "TEXT"
                        isLatticeEnum(baseForType) -> if (getEnumStoreAsOrdinal(baseForType)) "INTEGER" else "TEXT"
                        else -> "TEXT"
                    }

                    val typeEntry = getTypeEnumEntry(latticeTypeName) ?: return@mapNotNull null
                    val kindEntry = getKindEnumEntry(kindName) ?: return@mapNotNull null

                    // Create LatticePropertyDescriptor(name, type, kind, nullable, targetTable, linkTable)
                    irCall(descriptorConstructor).apply {
                        putValueArgument(0, irString(propName))  // name
                        putValueArgument(1, IrGetEnumValueImpl(
                            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                            typeClass.defaultType,
                            typeEntry.symbol
                        ))  // type
                        putValueArgument(2, IrGetEnumValueImpl(
                            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                            kindClass.defaultType,
                            kindEntry.symbol
                        ))  // kind
                        putValueArgument(3, irBoolean(isNullable))  // nullable
                        putValueArgument(4, if (targetTableName != null) irString(targetTableName) else irNull())  // targetTable
                        putValueArgument(5, irNull())  // linkTable
                    }
                }

                // Build a listOf(...) call
                val listOf = pluginContext.referenceFunctions(
                    CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))
                ).find {
                    it.owner.valueParameters.size == 1 &&
                        it.owner.valueParameters[0].isVararg
                }

                if (listOf != null && descriptors.isNotEmpty()) {
                    +irReturn(
                        irCall(listOf).apply {
                            putValueArgument(0, irVararg(
                                descriptorClass.defaultType,
                                descriptors
                            ))
                        }
                    )
                } else {
                    // Return empty list
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

    private fun transformProperty(
        irClass: IrClass,
        property: IrProperty,
        handleField: IrField,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val backingField = property.backingField ?: return
        val propertyName = property.name.asString()
        val propertyType = backingField.type

        // Keep backing field for initial values (copied to C++ in init block)
        // Don't rename - it's only used during initialization

        // Check if this is a @Link property, VirtualList type, or LatticeList type
        val isLink = property.hasAnnotation(linkAnnotationFqn)
        val isVirtualList = propertyType.classOrNull?.owner?.name?.asString() == "VirtualList"
        val isLinkList = property.hasAnnotation(linkListAnnotationFqn) ||
            propertyType.classOrNull?.owner?.name?.asString() == "LatticeList"

        // Check if native collection type (List, Set, Map — skip transformation, use backing field)
        val isNativeCollection = isNativeCollection(propertyType)

        // Check if the property type is an @Embedded type
        val baseType = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType
        val isEmbedded = baseType.classOrNull?.owner?.hasAnnotation(embeddedAnnotationFqn) == true

        // Check if the property type is a @LatticeEnum type
        val isEnum = isLatticeEnum(baseType)

        if (isNativeCollection) {
            // Native collections (List, Set, Map) stored as JSON TEXT via KSerializer.
            // Mirrors Swift PrimitiveProperty conformance for Array/Set/Dictionary.
            property.getter?.let { getter ->
                transformCollectionGetter(irClass, getter, handleField, backingField, propertyName, propertyType, latticeNativeSymbol)
            }
            property.setter?.let { setter ->
                transformCollectionSetter(irClass, setter, handleField, backingField, propertyName, propertyType, latticeNativeSymbol)
            }
        } else if (isLink) {
            // Transform as a link property
            property.getter?.let { getter ->
                transformLinkGetter(irClass, getter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
            property.setter?.let { setter ->
                transformLinkSetter(irClass, setter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
        } else if (isVirtualList) {
            // Transform as a virtual list property (polymorphic link list)
            property.getter?.let { getter ->
                transformVirtualListGetter(irClass, getter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
            property.setter?.let { setter ->
                transformVirtualListSetter(irClass, setter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
        } else if (isLinkList) {
            // Transform as a link list property
            property.getter?.let { getter ->
                transformLinkListGetter(irClass, getter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
            property.setter?.let { setter ->
                transformLinkListSetter(irClass, setter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
        } else if (isEmbedded) {
            // Transform as an embedded model (stored as JSON)
            property.getter?.let { getter ->
                transformEmbeddedGetter(irClass, getter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
            property.setter?.let { setter ->
                transformEmbeddedSetter(irClass, setter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
        } else if (isEnum) {
            // Transform as a @LatticeEnum property (stored as TEXT name or INTEGER ordinal)
            property.getter?.let { getter ->
                transformEnumGetter(irClass, getter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
            property.setter?.let { setter ->
                transformEnumSetter(irClass, setter, handleField, propertyName, propertyType, latticeNativeSymbol)
            }
        } else {
            // Transform getter to always delegate to C++
            property.getter?.let { getter ->
                transformGetter(irClass, getter, handleField, backingField, propertyName, propertyType, latticeNativeSymbol)
            }

            // Transform setter to always delegate to C++
            property.setter?.let { setter ->
                transformSetter(irClass, setter, handleField, backingField, propertyName, propertyType, latticeNativeSymbol)
            }
        }
    }

    private fun transformGetter(
        irClass: IrClass,
        getter: IrSimpleFunction,
        handleField: IrField,
        backingField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val nativeGetterName = getNativeGetterName(propertyType)
        val nativeGetter = findLatticeNativeFunction(latticeNativeSymbol, nativeGetterName)

        if (nativeGetter == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative.$nativeGetterName for property '$propertyName', skipping transformation"
            )
            return
        }

        // Always delegate to C++ - all storage is in C++ object
        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!

            // return LatticeNative.getXxx(_latticeHandle, "propertyName")
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
        irClass: IrClass,
        setter: IrSimpleFunction,
        handleField: IrField,
        backingField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val nativeSetterName = getNativeSetterName(propertyType)
        val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, nativeSetterName)

        if (nativeSetter == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative.$nativeSetterName for property '$propertyName', skipping transformation"
            )
            return
        }

        val valueParam = setter.valueParameters.firstOrNull() ?: return

        // Always delegate to C++ - all storage is in C++ object
        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            val thisReceiver = setter.dispatchReceiverParameter!!

            // LatticeNative.setXxx(_latticeHandle, "propertyName", value)
            +irCall(nativeSetter).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
                putValueArgument(2, irGet(valueParam))
            }
        }
    }

    /**
     * Transform a @Link property getter.
     * Gets the linked object handle from C++ and wraps it in the target type.
     *
     * Like Swift's approach:
     *   let model = Self()
     *   model._dynamicObject = object.getObject(named: name)
     *   return model
     */
    private fun transformLinkGetter(
        irClass: IrClass,
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getObjectHandle = findLatticeNativeFunction(latticeNativeSymbol, "getObjectHandle")
        val setHandle = findLatticeNativeFunction(latticeNativeSymbol, "setHandle")
        if (getObjectHandle == null || setHandle == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative functions for link '$propertyName', skipping"
            )
            return
        }

        // Get the target class (unwrap nullable if needed)
        val targetType = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType
        val targetClass = targetType.classOrNull?.owner
        if (targetClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot determine target class for link '$propertyName', skipping"
            )
            return
        }

        // Find the primary constructor of the target class
        val targetConstructor = targetClass.primaryConstructor
        if (targetConstructor == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Target class '${targetClass.name}' has no primary constructor for link '$propertyName'"
            )
            return
        }

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!

            // val linkedHandle = LatticeNative.getObjectHandle(_latticeHandle, "propertyName")
            val handleVar = irTemporary(
                irCall(getObjectHandle).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                },
                nameHint = "linkedHandle"
            )

            // if (linkedHandle == 0L) return null
            +irIfThen(
                type = irBuiltIns.unitType,
                condition = irEquals(irGet(handleVar), irLong(0L)),
                thenPart = irReturn(irNull())
            )

            // val obj = TargetClass()
            val instance = irTemporary(
                irCallConstructor(targetConstructor.symbol, emptyList()),
                nameHint = "linkedObj"
            )

            // LatticeNative.setHandle(obj, linkedHandle)
            +irCall(setHandle).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGet(instance))
                putValueArgument(1, irGet(handleVar))
            }

            // return obj
            +irReturn(irGet(instance))
        }
    }

    /**
     * Transform a @Link property setter.
     * Extracts the handle from the linked object and sets it in C++.
     */
    private fun transformLinkSetter(
        irClass: IrClass,
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val setObjectHandle = findLatticeNativeFunction(latticeNativeSymbol, "setObjectHandle")
        val getHandle = findLatticeNativeFunction(latticeNativeSymbol, "getHandle")
        if (setObjectHandle == null || getHandle == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative functions for link setter '$propertyName', skipping"
            )
            return
        }

        val valueParam = setter.valueParameters.firstOrNull() ?: return

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            val thisReceiver = setter.dispatchReceiverParameter!!

            if (propertyType.isNullable()) {
                // val linkedHandle = if (value != null) LatticeNative.getHandle(value) else 0L
                // LatticeNative.setObjectHandle(_latticeHandle, "propertyName", linkedHandle)
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
                // val linkedHandle = LatticeNative.getHandle(value)
                // LatticeNative.setObjectHandle(_latticeHandle, "propertyName", linkedHandle)
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

    /**
     * Transform a @LinkList property getter.
     * Gets the link list from C++ and configures a LatticeList wrapper.
     *
     * Generated code:
     *   val list = LatticeList<T>()
     *   LatticeNative.configureLinkList(list, _latticeHandle, "propertyName") { T() }
     *   return list
     */
    private fun transformLinkListGetter(
        irClass: IrClass,
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getLinkListHandle = findLatticeNativeFunction(latticeNativeSymbol, "getLinkListHandle")
        val setHandle = findLatticeNativeFunction(latticeNativeSymbol, "setHandle")
        if (getLinkListHandle == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative.getLinkListHandle for '$propertyName', skipping"
            )
            return
        }

        // Find LatticeList class
        val latticeListClass = pluginContext.referenceClass(
            ClassId.topLevel(FqName("com.lattice.LatticeList"))
        )
        if (latticeListClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeList class for '$propertyName', skipping"
            )
            return
        }

        // Get the element type from LatticeList<T>
        val listTypeArg = propertyType.classOrNull?.typeWith()?.arguments?.firstOrNull()?.typeOrNull
            ?: run {
                // Try to extract from the property type directly
                val classifier = propertyType.classOrNull
                classifier?.owner?.typeParameters?.firstOrNull()?.let { typeParam ->
                    // This is a type parameter, need to get actual type argument
                    propertyType.let { pt ->
                        if (pt is org.jetbrains.kotlin.ir.types.IrSimpleType) {
                            pt.arguments.firstOrNull()?.typeOrNull
                        } else null
                    }
                }
            }

        if (listTypeArg == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot determine element type for link list '$propertyName', skipping"
            )
            return
        }

        val elementClass = listTypeArg.classOrNull?.owner
        if (elementClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find element class for link list '$propertyName', skipping"
            )
            return
        }

        val elementConstructor = elementClass.primaryConstructor
        if (elementConstructor == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Element class '${elementClass.name}' has no primary constructor for '$propertyName'"
            )
            return
        }

        // Find LatticeList constructor
        val latticeListConstructor = latticeListClass.owner.constructors.firstOrNull()
        if (latticeListConstructor == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeList constructor for '$propertyName'"
            )
            return
        }

        // Find _nativeHandle property on LatticeList
        val nativeHandleProp = latticeListClass.owner.properties.find { it.name.asString() == "_nativeHandle" }

        if (nativeHandleProp == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeList._nativeHandle for '$propertyName'"
            )
            return
        }

        // Find setElementClass function on LatticeList
        val setElementClassFun = latticeListClass.owner.functions.find { it.name.asString() == "setElementClass" }

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!

            // val list = LatticeList<T>()
            val listInstance = irTemporary(
                irCallConstructor(latticeListConstructor.symbol, listOf(listTypeArg)),
                nameHint = "linkList"
            )

            // list._nativeHandle = LatticeNative.getLinkListHandle(_latticeHandle, "propertyName")
            val handleValue = irCall(getLinkListHandle).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
            }

            val handleSetter = nativeHandleProp.setter
            if (handleSetter != null) {
                +irCall(handleSetter).apply {
                    dispatchReceiver = irGet(listInstance)
                    putValueArgument(0, handleValue)
                }
            }

            // list.setElementClass(ElementClass::class)
            // This allows the list to look up the factory for creating elements
            if (setElementClassFun != null) {
                val kClassType = pluginContext.irBuiltIns.kClassClass.typeWith(listTypeArg)
                +irCall(setElementClassFun).apply {
                    dispatchReceiver = irGet(listInstance)
                    putValueArgument(0, IrClassReferenceImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        type = kClassType,
                        symbol = listTypeArg.classOrNull!!,
                        classType = listTypeArg
                    ))
                }
            }

            // return list
            +irReturn(irGet(listInstance))
        }
    }

    /**
     * Transform a @LinkList property setter.
     * For now, this is a no-op since list modifications happen through the list object itself.
     */
    private fun transformLinkListSetter(
        irClass: IrClass,
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        // For link lists, setting is handled by modifying the list directly
        // The setter is a no-op or could copy elements from the new list
        val valueParam = setter.valueParameters.firstOrNull() ?: return

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            // No-op for now - list modifications happen through the list object
            // Could potentially copy elements: clear existing, add all from new
        }
    }

    /**
     * Transform a VirtualList property getter.
     * Gets the link list handle from C++ and configures a VirtualList wrapper.
     * Unlike LatticeList, VirtualList does NOT call setElementClass — it resolves
     * types at runtime via table name using VirtualModelRegistry.
     *
     * Generated code:
     *   val list = VirtualList<T>()
     *   list._nativeHandle = LatticeNative.getLinkListHandle(_latticeHandle, "propertyName")
     *   return list
     */
    private fun transformVirtualListGetter(
        irClass: IrClass,
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getLinkListHandle = findLatticeNativeFunction(latticeNativeSymbol, "getLinkListHandle")
        if (getLinkListHandle == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative.getLinkListHandle for virtual list '$propertyName', skipping"
            )
            return
        }

        // Find VirtualList class
        val virtualListClass = pluginContext.referenceClass(
            ClassId.topLevel(FqName("com.lattice.VirtualList"))
        )
        if (virtualListClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find VirtualList class for '$propertyName', skipping"
            )
            return
        }

        // Get the element type from VirtualList<T>
        val listTypeArg = propertyType.let { pt ->
            if (pt is org.jetbrains.kotlin.ir.types.IrSimpleType) {
                pt.arguments.firstOrNull()?.typeOrNull
            } else null
        }

        if (listTypeArg == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot determine element type for virtual list '$propertyName', skipping"
            )
            return
        }

        // Find VirtualList constructor
        val virtualListConstructor = virtualListClass.owner.constructors.firstOrNull()
        if (virtualListConstructor == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find VirtualList constructor for '$propertyName'"
            )
            return
        }

        // Find _nativeHandle property on VirtualList
        val nativeHandleProp = virtualListClass.owner.properties.find { it.name.asString() == "_nativeHandle" }
        if (nativeHandleProp == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find VirtualList._nativeHandle for '$propertyName'"
            )
            return
        }

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!

            // val list = VirtualList<T>()
            val listInstance = irTemporary(
                irCallConstructor(virtualListConstructor.symbol, listOf(listTypeArg)),
                nameHint = "virtualList"
            )

            // list._nativeHandle = LatticeNative.getLinkListHandle(_latticeHandle, "propertyName")
            val handleValue = irCall(getLinkListHandle).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
            }

            val handleSetter = nativeHandleProp.setter
            if (handleSetter != null) {
                +irCall(handleSetter).apply {
                    dispatchReceiver = irGet(listInstance)
                    putValueArgument(0, handleValue)
                }
            }

            // Do NOT call setElementClass — VirtualList resolves types at runtime via table name

            // return list
            +irReturn(irGet(listInstance))
        }
    }

    /**
     * Transform a VirtualList property setter.
     * This is a no-op since list modifications happen through the list object itself.
     */
    private fun transformVirtualListSetter(
        irClass: IrClass,
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        // For virtual lists, setting is handled by modifying the list directly
        // The setter is a no-op
        val valueParam = setter.valueParameters.firstOrNull() ?: return

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            // No-op — list modifications happen through the list object
        }
    }

    /**
     * Transform an embedded model property getter.
     * Reads JSON from C++ and decodes it using the registered serializer.
     *
     * Generated code:
     *   return LatticeNative.getEmbedded(_latticeHandle, "propertyName", EmbeddedType::class)
     */
    private fun transformEmbeddedGetter(
        irClass: IrClass,
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getEmbedded = findLatticeNativeFunction(latticeNativeSymbol, "getEmbedded")
        if (getEmbedded == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative.getEmbedded for '$propertyName', skipping"
            )
            return
        }

        val isNullable = propertyType.isNullable()
        val baseType = if (isNullable) propertyType.makeNotNull() else propertyType
        val embeddedClass = baseType.classOrNull?.owner
        if (embeddedClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot determine embedded class for '$propertyName', skipping"
            )
            return
        }

        getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
            val thisReceiver = getter.dispatchReceiverParameter!!

            // Create KClass reference for the embedded type
            val kClassType = pluginContext.irBuiltIns.kClassClass.typeWith(baseType)
            val kClassRef = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = baseType.classOrNull!!,
                classType = baseType
            )

            // Call LatticeNative.getEmbedded(_latticeHandle, "propertyName", EmbeddedType::class, useDefaultIfMissing)
            // For non-nullable properties, use default if missing
            // For nullable properties, return null if missing
            val result = irCall(getEmbedded).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
                putValueArgument(2, kClassRef)
                putValueArgument(3, irBoolean(!isNullable)) // useDefaultIfMissing = true for non-nullable
            }

            +irReturn(result)
        }
    }

    /**
     * Transform an embedded model property setter.
     * Encodes the value to JSON and stores it in C++.
     *
     * Generated code:
     *   LatticeNative.setEmbedded(_latticeHandle, "propertyName", value, EmbeddedType::class)
     */
    private fun transformEmbeddedSetter(
        irClass: IrClass,
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val setEmbedded = findLatticeNativeFunction(latticeNativeSymbol, "setEmbedded")
        if (setEmbedded == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot find LatticeNative.setEmbedded for '$propertyName', skipping"
            )
            return
        }

        val valueParam = setter.valueParameters.firstOrNull() ?: return
        val baseType = if (propertyType.isNullable()) propertyType.makeNotNull() else propertyType

        setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
            val thisReceiver = setter.dispatchReceiverParameter!!

            // Create KClass reference for the embedded type
            val kClassType = pluginContext.irBuiltIns.kClassClass.typeWith(baseType)
            val kClassRef = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = baseType.classOrNull!!,
                classType = baseType
            )

            // Call LatticeNative.setEmbedded(_latticeHandle, "propertyName", value, EmbeddedType::class)
            +irCall(setEmbedded).apply {
                dispatchReceiver = irGetObject(latticeNativeSymbol)
                putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                putValueArgument(1, irString(propertyName))
                putValueArgument(2, irGet(valueParam))
                putValueArgument(3, kClassRef)
            }
        }
    }

    /**
     * Determine the LatticeNative getter method name for a collection type.
     * Returns null if the collection type is not supported.
     */
    private fun getCollectionGetterName(type: IrType): String? {
        val className = type.classOrNull?.owner?.name?.asString() ?: return null
        val elementType = (type as? org.jetbrains.kotlin.ir.types.IrSimpleType)
            ?.arguments?.firstOrNull()?.typeOrNull

        return when {
            className in setOf("List", "MutableList", "ArrayList") && elementType?.isString() == true -> "getStringList"
            className in setOf("Set", "MutableSet", "HashSet", "LinkedHashSet") && elementType?.isString() == true -> "getStringSet"
            className in setOf("Map", "MutableMap", "HashMap", "LinkedHashMap") -> "getStringStringMap"
            else -> null // Unsupported collection type — stays in backing field
        }
    }

    private fun getCollectionSetterName(type: IrType): String? {
        val className = type.classOrNull?.owner?.name?.asString() ?: return null
        val elementType = (type as? org.jetbrains.kotlin.ir.types.IrSimpleType)
            ?.arguments?.firstOrNull()?.typeOrNull

        return when {
            className in setOf("List", "MutableList", "ArrayList") && elementType?.isString() == true -> "setStringList"
            className in setOf("Set", "MutableSet", "HashSet", "LinkedHashSet") && elementType?.isString() == true -> "setStringSet"
            className in setOf("Map", "MutableMap", "HashMap", "LinkedHashMap") -> "setStringStringMap"
            else -> null
        }
    }

    /**
     * Transform a native collection property getter.
     * Calls LatticeNative.getStringList/getStringSet/getStringStringMap.
     */
    private fun transformCollectionGetter(
        irClass: IrClass,
        getter: IrSimpleFunction,
        handleField: IrField,
        backingField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val getterName = getCollectionGetterName(propertyType)
        if (getterName == null) {
            // Unsupported collection type — leave getter as-is (uses backing field)
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Unsupported collection type for '$propertyName', using backing field"
            )
            return
        }

        val nativeGetter = findLatticeNativeFunction(latticeNativeSymbol, getterName) ?: return

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

    /**
     * Transform a native collection property setter.
     * Calls LatticeNative.setStringList/setStringSet/setStringStringMap.
     */
    private fun transformCollectionSetter(
        irClass: IrClass,
        setter: IrSimpleFunction,
        handleField: IrField,
        backingField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val setterName = getCollectionSetterName(propertyType)
        if (setterName == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Unsupported collection type for setter '$propertyName', using backing field"
            )
            return
        }

        val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, setterName) ?: return
        val valueParam = setter.valueParameters.firstOrNull() ?: return

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

    /**
     * Transform a @LatticeEnum property getter.
     * Reads string name (or ordinal) from C++ and converts to enum value.
     *
     * Generated code (storeAsOrdinal=false):
     *   val name = LatticeNative.getString(_latticeHandle, "propertyName")
     *   return EnumClass.valueOf(name)
     *
     * Generated code (storeAsOrdinal=true):
     *   val ordinal = LatticeNative.getInt(_latticeHandle, "propertyName")
     *   return EnumClass.entries[ordinal]
     */
    private fun transformEnumGetter(
        irClass: IrClass,
        getter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val isNullable = propertyType.isNullable()
        val baseType = if (isNullable) propertyType.makeNotNull() else propertyType
        val storeAsOrdinal = getEnumStoreAsOrdinal(baseType)

        val enumClass = baseType.classOrNull?.owner
        if (enumClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot determine enum class for '$propertyName', skipping"
            )
            return
        }

        // Find the valueOf function or entries property
        val valueOfFn = enumClass.functions.find {
            it.name.asString() == "valueOf" && it.valueParameters.size == 1
        }

        // Find entries property (Kotlin 1.9+)
        val entriesProp = enumClass.properties.find { it.name.asString() == "entries" }

        if (storeAsOrdinal) {
            // Use ordinal storage: read int, use values()[ordinal]
            val nativeGetter = findLatticeNativeFunction(latticeNativeSymbol, "getInt")
            val hasValueFn = findLatticeNativeFunction(latticeNativeSymbol, "hasValue")
            if (nativeGetter == null) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "Lattice: Cannot find LatticeNative.getInt for enum '$propertyName', skipping"
                )
                return
            }

            val valuesFn = enumClass.functions.find { it.name.asString() == "values" }
            if (valuesFn == null) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "Lattice: Cannot find values() for enum '$propertyName', skipping"
                )
                return
            }

            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                val thisReceiver = getter.dispatchReceiverParameter!!
                val handleExpr = irGetField(irGet(thisReceiver), handleField)

                if (isNullable && hasValueFn != null) {
                    // For nullable: if (hasValue) return values()[getInt()] else return null
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
                            val valuesCall = irCall(valuesFn.symbol)
                            +irReturn(irCall(
                                irBuiltIns.arrayClass.owner.declarations
                                    .filterIsInstance<IrSimpleFunction>()
                                    .find { it.name.asString() == "get" }!!.symbol
                            ).apply {
                                dispatchReceiver = valuesCall
                                putValueArgument(0, ordinal)
                            })
                        },
                        elsePart = irReturn(irNull())
                    )
                } else {
                    // For non-nullable: just return values()[getInt()]
                    val ordinal = irCall(nativeGetter).apply {
                        dispatchReceiver = irGetObject(latticeNativeSymbol)
                        putValueArgument(0, handleExpr)
                        putValueArgument(1, irString(propertyName))
                    }
                    val valuesCall = irCall(valuesFn.symbol)
                    +irReturn(irCall(
                        irBuiltIns.arrayClass.owner.declarations
                            .filterIsInstance<IrSimpleFunction>()
                            .find { it.name.asString() == "get" }!!.symbol
                    ).apply {
                        dispatchReceiver = valuesCall
                        putValueArgument(0, ordinal)
                    })
                }
            }
        } else {
            // Use name storage: read string, use valueOf(name)
            val nativeGetter = findLatticeNativeFunction(latticeNativeSymbol, if (isNullable) "getStringOrNull" else "getString")
            if (nativeGetter == null || valueOfFn == null) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "Lattice: Cannot find LatticeNative.getString or valueOf for enum '$propertyName', skipping"
                )
                return
            }

            getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
                val thisReceiver = getter.dispatchReceiverParameter!!

                // val name = LatticeNative.getString(_latticeHandle, "propertyName")
                val nameValue = irCall(nativeGetter).apply {
                    dispatchReceiver = irGetObject(latticeNativeSymbol)
                    putValueArgument(0, irGetField(irGet(thisReceiver), handleField))
                    putValueArgument(1, irString(propertyName))
                }

                if (isNullable) {
                    // if (name == null || name.isEmpty()) return null else valueOf(name)
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
                    // return EnumClass.valueOf(name)
                    +irReturn(irCall(valueOfFn).apply {
                        putValueArgument(0, nameValue)
                    })
                }
            }
        }
    }

    /**
     * Transform a @LatticeEnum property setter.
     * Writes enum name (or ordinal) to C++.
     *
     * Generated code (storeAsOrdinal=false):
     *   LatticeNative.setString(_latticeHandle, "propertyName", value.name)
     *
     * Generated code (storeAsOrdinal=true):
     *   LatticeNative.setInt(_latticeHandle, "propertyName", value.ordinal)
     */
    private fun transformEnumSetter(
        irClass: IrClass,
        setter: IrSimpleFunction,
        handleField: IrField,
        propertyName: String,
        propertyType: IrType,
        latticeNativeSymbol: IrClassSymbol
    ) {
        val isNullable = propertyType.isNullable()
        val baseType = if (isNullable) propertyType.makeNotNull() else propertyType
        val storeAsOrdinal = getEnumStoreAsOrdinal(baseType)

        val enumClass = baseType.classOrNull?.owner
        if (enumClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Lattice: Cannot determine enum class for setter '$propertyName', skipping"
            )
            return
        }

        val valueParam = setter.valueParameters.firstOrNull() ?: return

        // Find name and ordinal properties on the enum
        val nameProp = enumClass.properties.find { it.name.asString() == "name" }
            ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "name" }
        val ordinalProp = enumClass.properties.find { it.name.asString() == "ordinal" }
            ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Enum")))?.owner?.properties?.find { it.name.asString() == "ordinal" }

        if (storeAsOrdinal) {
            // Store as ordinal: value.ordinal
            val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setInt")
            if (nativeSetter == null || ordinalProp?.getter == null) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "Lattice: Cannot find LatticeNative.setInt or ordinal property for enum '$propertyName', skipping"
                )
                return
            }

            setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
                val thisReceiver = setter.dispatchReceiverParameter!!

                if (isNullable) {
                    // if (value != null) setInt(handle, name, value.ordinal) else setNull(handle, name)
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
                    // LatticeNative.setInt(_latticeHandle, "propertyName", value.ordinal)
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
            // Store as name: value.name
            val nativeSetter = findLatticeNativeFunction(latticeNativeSymbol, "setString")
            if (nativeSetter == null || nameProp?.getter == null) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "Lattice: Cannot find LatticeNative.setString or name property for enum '$propertyName', skipping"
                )
                return
            }

            setter.body = DeclarationIrBuilder(pluginContext, setter.symbol).irBlockBody {
                val thisReceiver = setter.dispatchReceiverParameter!!

                if (isNullable) {
                    // if (value != null) setString(handle, name, value.name) else setNull(handle, name)
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
                    // LatticeNative.setString(_latticeHandle, "propertyName", value.name)
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
            isInstant(baseType) ->
                if (isNullable) "getInstantOrNull" else "getInstant"
            isUuid(baseType) ->
                if (isNullable) "getUuidOrNull" else "getUuid"
            else -> "getString" // Fallback
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
            isInstant(baseType) -> "setInstant"
            isUuid(baseType) -> "setUuid"
            else -> "setString" // Fallback
        }
    }

    /**
     * Check if the type is a native collection (List, MutableList, Set, Map, etc.)
     * that should be serialized as JSON TEXT in Lattice.
     */
    private fun isNativeCollection(type: IrType): Boolean {
        val className = type.classOrNull?.owner?.name?.asString() ?: return false
        return className in setOf(
            "List", "MutableList", "ArrayList",
            "Set", "MutableSet", "HashSet", "LinkedHashSet",
            "Map", "MutableMap", "HashMap", "LinkedHashMap"
        )
    }

    private fun isByteArray(type: IrType): Boolean {
        val classId = type.classOrNull?.owner?.classId ?: return false
        return classId.asFqNameString() == "kotlin.ByteArray"
    }

    private fun isFloatVector(type: IrType): Boolean {
        val classId = type.classOrNull?.owner?.classId ?: return false
        return classId.asFqNameString() == "com.lattice.FloatVector"
    }

    private fun isDoubleVector(type: IrType): Boolean {
        val classId = type.classOrNull?.owner?.classId ?: return false
        return classId.asFqNameString() == "com.lattice.DoubleVector"
    }

    private fun isInstant(type: IrType): Boolean {
        val classId = type.classOrNull?.owner?.classId ?: return false
        return classId.asFqNameString() == "kotlinx.datetime.Instant"
    }

    private fun isUuid(type: IrType): Boolean {
        val classId = type.classOrNull?.owner?.classId ?: return false
        return classId.asFqNameString() == "kotlin.uuid.Uuid"
    }

    /**
     * Check if the type is an enum class annotated with @LatticeEnum.
     */
    private fun isLatticeEnum(type: IrType): Boolean {
        val classOwner = type.classOrNull?.owner ?: return false
        // Check if it's an enum class
        if (classOwner.kind != org.jetbrains.kotlin.descriptors.ClassKind.ENUM_CLASS) {
            return false
        }
        // Check if it has @LatticeEnum annotation
        return classOwner.hasAnnotation(latticeEnumAnnotationFqn)
    }

    /**
     * Get the storeAsOrdinal parameter from @LatticeEnum annotation.
     * Returns false if not found.
     */
    private fun getEnumStoreAsOrdinal(type: IrType): Boolean {
        val classOwner = type.classOrNull?.owner ?: return false
        val annotation = classOwner.annotations.find { ann ->
            ann.type.classOrNull?.owner?.fqNameWhenAvailable == latticeEnumAnnotationFqn
        } ?: return false

        // Get the storeAsOrdinal parameter (first parameter)
        val arg = annotation.getValueArgument(0)
        return (arg as? IrConst<*>)?.value as? Boolean ?: false
    }

    private fun findLatticeNativeFunction(latticeNativeSymbol: IrClassSymbol, name: String): IrSimpleFunctionSymbol? {
        return latticeNativeSymbol.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .find { it.name.asString() == name }
            ?.symbol
    }
}

// Custom origin for generated declarations
object LatticeDeclarationOrigin : IrDeclarationOrigin {
    override val name: String = "LATTICE_GENERATED"
    override val isSynthetic: Boolean = true
    override fun toString(): String = name
}

// Extension functions
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
