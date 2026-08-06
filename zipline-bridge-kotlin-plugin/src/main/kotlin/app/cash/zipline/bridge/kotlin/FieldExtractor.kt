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

// -- Kotlin/Native bridge source generation (text-based, compiled by native target) --

/**
 * Generates a single file that retains all bridge functions in the module,
 * preventing the linker from dead-code eliminating them.
 */
/** Emit C code to extract an array field value. */
internal fun extractFields(annotatedClass: IrClass, includeValBodyFields: Boolean = false): List<FieldInfo> {
  val allProperties = mutableListOf<IrProperty>()
  collectProperties(annotatedClass, allProperties, mutableSetOf())
  // Compute own (non-inherited) property names to distinguish true overrides
  // from synthetic inherited members. Only properties with backing fields
  // (i.e. declared in this class) count as "own".
  val ownPropertyNames = annotatedClass.declarations
    .filterIsInstance<IrProperty>()
    .filter { it.backingField != null }
    .map { it.name.asString() }
    .toSet()
  // Detect overridden names: if a name appears in both the class's own declarations
  // and a superclass, Kotlin/JS IR mangles it with a _1 (or higher) suffix.
  val nameCounts = mutableMapOf<String, Int>()
  for (prop in allProperties) {
    nameCounts[prop.name.asString()] = nameCounts.getOrDefault(prop.name.asString(), 0) + 1
  }
  // Also check supertypes without @WithJS2HostBridge — Kotlin/JS IR mangles
  // override val properties to name_1 even when the supertype isn't annotated.
  // collectProperties only recurses into annotated supertypes, so interface
  // properties (like Clip.shape) are missed. We scan all supertype properties
  // here to detect overrides that would trigger name mangling.
  val overriddenNames = mutableSetOf<String>()
  val visitedForOverride = mutableSetOf<IrClass>()
  fun collectOverrideNames(tp: IrType) {
    val cls = tp.getClass() ?: return
    if (!visitedForOverride.add(cls)) return
    for (prop in cls.properties) {
      val name = prop.name.asString()
      if (name in ownPropertyNames) {
        overriddenNames.add(name)
      }
    }
    cls.superTypes.forEach(::collectOverrideNames)
  }
  annotatedClass.superTypes.forEach(::collectOverrideNames)

  // Track which property names have a backing field anywhere in the hierarchy.
  // Used later to distinguish inherited-backed from inherited-computed.
  val namesWithBackingField = allProperties.filter { it.backingField != null }.map { it.name.asString() }.toSet()

  // Deduplicate by name, keeping first occurrence (subclass overrides superclass)
  val seenNames = mutableSetOf<String>()
  allProperties.removeAll { !seenNames.add(it.name.asString()) }

  val primaryConstructor = annotatedClass.declarations
    .filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary }
  val primaryConstructorParamNames = primaryConstructor
    ?.parameters
    ?.filter { it.kind == IrParameterKind.Regular }
    ?.map { it.name.asString() }
    ?.toSet() ?: emptySet()

  return allProperties.mapNotNull { property ->
    val kotlinName = property.name.asString()
    val propertyType = property.backingField?.type
      ?: property.getter?.returnType
      ?: return@mapNotNull null

    val irClass = (propertyType as? IrSimpleType)?.getClass()
    val ktType = irClass?.classId?.asSingleFqName()?.asString()
      ?: propertyType.classFqName?.asString()
      ?: return@mapNotNull null

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
      underlyingKtType = underlyingType?.getClass()?.classId?.asSingleFqName()?.asString()
        ?: underlyingType?.classFqName?.asString()
      // Only unwrap non-nullable inline classes: nullable ones keep their
      // inline class type in JVM bytecode descriptors.
      if (!propertyType.isMarkedNullable() && underlyingKtType != null && isKnownType(underlyingKtType)) {
        effectiveKtType = underlyingKtType
      }
    }

    // Kotlin/JS IR always mangles override val backing fields to name_1.
    // This applies to ALL types: primitives (Double, Int), inline value classes
    // (Id, Dp), and reference types (Shape, Modifier). The backing field is
    // always name_1; the getter may or may not exist on the prototype.
    // Private properties also get _1 (e.g., _id → _id_1).
    val isPrivate = property.visibility == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PRIVATE
    val isOverridden = kotlinName in overriddenNames
    val jsPropertyName = if (isOverridden || isPrivate || isInlineClass(annotatedClass)) "${kotlinName}_1" else kotlinName

    // JNI type detection uses effectiveKtType (after inline unwrapping) to get
    // the primitive underlying type for value classes (Id → int, Dp → double).
    val isKnown = isKnownType(effectiveKtType)
    val jniFieldType = if (isKnown) kotlinToJniFieldType[effectiveKtType]!!
      else if (irClass != null) jniTypeDescriptorForClass(irClass)
      else jniFieldDescriptor(effectiveKtType)
    val name = property.name.asString()
    val isConstructorParam = name in primaryConstructorParamNames
    // Skip computed getters: no backing field anywhere in the hierarchy.
    if (!isConstructorParam && property.backingField == null && property.name.asString() !in namesWithBackingField) return@mapNotNull null

    // Computed vals can't be reassigned in Native codegen; C codegen reads them via JS_GetPropertyStr.
    if (!isConstructorParam && !property.isVar && !includeValBodyFields) return@mapNotNull null

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
    val nullablePrimitive = isNullable && isKnown && isJniPrimitive(effectiveKtType)
    val effectiveJniFieldType = if (nullablePrimitive) boxedJniDescriptor[effectiveKtType] ?: jniFieldType else jniFieldType
    val effectiveIsPrimitive = isKnown && isJniPrimitive(effectiveKtType) && !isNullable
    val effectiveIsObjectType = !isKnown && !isArray

    FieldInfo(
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
  }.toList()
}

/** Collect properties from [irClass] and any superclasses annotated with @WithJS2HostBridge. */
internal fun collectProperties(
  irClass: IrClass,
  acc: MutableList<IrProperty>,
  visited: MutableSet<IrClass>,
) {
  if (!visited.add(irClass)) return
  acc.addAll(irClass.properties.toList())
  for (superType in irClass.superTypes) {
    val superClass = superType.getClass() ?: continue
    if (hasWithJS2HostBridgeAnnotation(superClass)) {
      collectProperties(superClass, acc, visited)
    }
  }
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


