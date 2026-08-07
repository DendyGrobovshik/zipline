package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties

// -- Field extraction and class discovery (shared between C, Native, and JS generators) --

/** Collect all classes in the module annotated with @WithJS2HostBridge. */
internal fun findAnnotatedClasses(
  moduleFragment: IrModuleFragment,
): List<IrClass> {
  val result = mutableListOf<IrClass>()
  for (irFile in moduleFragment.files) {
    for (declaration in irFile.declarations) {
      collectAnnotatedClasses(declaration, result)
    }
  }
  return result
}

/** Walk [declaration] and its nested classes, collecting @WithJS2HostBridge-annotated ones. */
internal fun collectAnnotatedClasses(
  declaration: org.jetbrains.kotlin.ir.declarations.IrDeclaration,
  acc: MutableList<IrClass>,
) {
  if (declaration is IrClass) {
    if (hasWithJS2HostBridgeAnnotation(declaration)) {
      acc.add(declaration)
    }
    for (nested in declaration.declarations) {
      collectAnnotatedClasses(nested, acc)
    }
  }
}

internal fun importForType(fqName: String): String {
  val lastDot = fqName.lastIndexOf('.')
  if (lastDot < 0) return ""
  val pkg = fqName.substring(0, lastDot)
  val shortName = fqName.substring(lastDot + 1)
  return "import $pkg.$shortName"
}

/**
 * Generates a single file that retains all bridge functions in the module,
 * preventing the linker from dead-code eliminating them.
 */
/** Emit C code to extract an array field value. */
internal fun extractFields(annotatedClass: IrClass, includeValBodyFields: Boolean = false): List<FieldInfo> {
  val primaryConstructor = annotatedClass.declarations
    .filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary }
  val primaryConstructorParamNames = primaryConstructor
    ?.parameters
    ?.filter { it.kind == IrParameterKind.Regular }
    ?.map { it.name.asString() }
    ?.toSet() ?: emptySet()

  return annotatedClass.properties.mapNotNull {
    extractField(it, primaryConstructorParamNames, includeValBodyFields)
  }.toList()
}

private fun extractField(property: IrProperty, primaryConstructorParamNames: Set<String>, includeValBodyFields: Boolean): FieldInfo? {
  val propertyType = property.getter?.returnType
    ?: return null

  val irClass = (propertyType as? IrSimpleType)?.getClass()
  val ktType = propertyType.classFqName?.asString() ?: return null

  // Detect inline value classes and unwrap to underlying type
  var isInline = false
  var underlyingKtType: String? = null
  var effectiveKtType = ktType
  var wrapperKtType: String? = null
  if (irClass != null && isInlineClass(irClass)) {
    isInline = true
    wrapperKtType = ktType  // save original wrapper before unwrap
    val primaryCtor = irClass.declarations
      .filterIsInstance<IrConstructor>()
      .firstOrNull { it.isPrimary }
    val underlyingParam = primaryCtor?.parameters
      ?.firstOrNull { it.kind == IrParameterKind.Regular }
    val underlyingType = underlyingParam?.type
    underlyingKtType = underlyingType?.classFqName?.asString()
    // Only unwrap non-nullable inline classes: nullable ones keep their
    // inline class type in JVM bytecode descriptors.
    if (!propertyType.isMarkedNullable() && underlyingKtType != null && isKnownType(underlyingKtType)) {
      effectiveKtType = underlyingKtType
    }
  }

  val jsPropertyName = property.jsName()

  // JNI type detection uses effectiveKtType (after inline unwrapping) to get
  // the primitive underlying type for value classes (Id → int, Dp → double).
  val isKnown = isKnownType(effectiveKtType)
  val jniFieldType = if (isKnown) kotlinToJniFieldType[effectiveKtType]!!
  else if (irClass != null) jniTypeDescriptorForClass(irClass)
  else jniFieldDescriptor(effectiveKtType)
  val name = property.name.asString()
  val isConstructorParam = name in primaryConstructorParamNames
  // Skip computed getters: no backing field anywhere in the hierarchy.
  if (!isConstructorParam && !property.hasBackingField()) return null

  // Computed vals can't be reassigned in Native codegen; C codegen reads them via JS_GetPropertyStr.
  if (!isConstructorParam && !property.isVar && !includeValBodyFields) return null

  // Detect array fields
  val isArray = effectiveKtType in arrayKotlinTypes
  // Extract element type info for Array<T>
  val elementIrType = if (effectiveKtType == "kotlin.Array" || effectiveKtType == "kotlin.collections.List") {
    (propertyType as? IrSimpleType)?.arguments
      ?.firstOrNull()
      ?.let { (it as? IrTypeProjection)?.type ?: (it as? IrType) }
  } else { null }
  val arrayElementType = elementIrType?.getClass()?.classId?.asSingleFqName()?.asString()
    ?: elementIrType?.classFqName?.asString()
  val arrayElementNullable = (elementIrType as? IrSimpleType)?.isMarkedNullable() ?: false

  // Detect nullability
  val isNullable = (propertyType as? IrSimpleType)?.isMarkedNullable() ?: false

  // Nullable primitives use boxed JNI descriptors and are not JNI primitives
  val isNullablePrimitive = isNullable && isKnown && isJniPrimitive(effectiveKtType)
  val effectiveJniFieldType = if (isNullablePrimitive) boxedJniDescriptor[effectiveKtType] ?: jniFieldType else jniFieldType
  val effectiveIsPrimitive = isKnown && isJniPrimitive(effectiveKtType) && !isNullable
  val effectiveIsObjectType = !isKnown && !isArray

  return FieldInfo(
    name = name,
    ktType = effectiveKtType,
    jniTypeChar = effectiveJniFieldType,
    jniFieldType = effectiveJniFieldType,
    isPrimitive = effectiveIsPrimitive,
    isObjectType = effectiveIsObjectType,
    isConstructorParam = isConstructorParam,
    isArray = isArray,
    arrayElementType = arrayElementType,
    arrayElementNullable = arrayElementNullable,
    isNullable = isNullable,
    isInline = isInline,
    underlyingKtType = underlyingKtType,
    wrapperKtType = wrapperKtType,
    jsPropertyName = jsPropertyName,
  )
}

private fun IrProperty.hasBackingField(): Boolean =
    backingField != null ||
      //  TODO(gogabr): should I also check for `isFakeOverride`?
    overriddenSymbols.singleOrNull { !it.owner.parentAsClass.isInterface }?.owner?.hasBackingField() == true

// Kotlin/JS IR always mangles override val backing fields to name_1.
// This applies to ALL types: primitives (Double, Int), inline value classes
// (Id, Dp), and reference types (Shape, Modifier). The backing field is
// always name_1; the getter may or may not exist on the prototype.
// Private properties also get _1 (e.g., _id → _id_1).
// Does the property need a JS '_1' suffix
// TODO(gogabr): find out whether there is further suffixing ('_2' etc) in deeper hierarchies
private fun IrProperty.jsName(): String {
  val kotlinName = name.asString()
  val isPrivate =
    visibility == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PRIVATE
  val isOverridden =
    (overriddenSymbols.isNotEmpty() && !isFakeOverride) // TODO(gogabr): look at the overridden prop!
  val jsPropertyName =
    if (isOverridden || isPrivate || isInlineClass(parentAsClass)) "${kotlinName}_1" else kotlinName
  return jsPropertyName
}

internal fun hasWithJS2HostBridgeAnnotation(irClass: IrClass): Boolean {
  return irClass.annotations.any {
    it.symbol.owner.returnType.getClass()?.classId == WITH_JS2HOST_BRIDGE_CLASS_ID
  }
}

/** Check if an [IrClass] is an inline value class. */
internal fun isInlineClass(irClass: IrClass): Boolean {
  // value classes have isValue=true in Kotlin 2.x IR; fallback to @JvmInline annotation
  return irClass.isValue || irClass.hasAnnotation(JVM_INLINE_CLASS_ID)
}

// -- JNI type helpers (used by field extraction) --

internal fun jniTypeDescriptorForClass(irClass: IrClass): String {
  val fqName = irClass.fqNameWhenAvailable?.asString() ?: irClass.name.asString()
  val remapped = kotlinToJvmClass[fqName]
  if (remapped != null) return "L${remapped.replace('.', '/')};"
  return "L${buildJniClassName(irClass).replace('.', '/')};"
}

internal fun jniFieldDescriptor(ktType: String): String {
  val remapped = kotlinToJvmClass[ktType]
  val jvmFqName = remapped ?: ktType
  return "L${jvmFqName.replace('.', '/')};"
}

internal fun isJniPrimitive(ktType: String): Boolean =
  kotlinToJniFieldType[ktType]?.let { it.length == 1 } ?: false

internal fun isKnownType(ktType: String): Boolean =
  ktType in kotlinToJniFieldType

internal fun isPrimitiveArray(ktType: String): Boolean =
  ktType in primitiveArrayJniInfo

internal fun isStringElement(elementType: String?): Boolean =
  elementType == "kotlin.String"


