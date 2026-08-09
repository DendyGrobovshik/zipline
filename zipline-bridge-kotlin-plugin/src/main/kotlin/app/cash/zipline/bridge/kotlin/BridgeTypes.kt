package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isMarkedNullable

/** All data types and type mapping tables extracted from ZiplineBridgeIrGenerationExtension.
 * These are now linked to the IR where possible for type safety.
 */
data class FieldInfo(
  val name: String,
  val type: IrType,
  val isConstructorParam: Boolean,
  val jsPropertyName: String,
) {
  val ktType: String get() = type.classFqName?.asString() ?: ""
  val isNullable: Boolean get() = (type as? IrSimpleType)?.isMarkedNullable() ?: false

  val irClass: IrClass? get() = (type as? IrSimpleType)?.getClass()

  val isInline: Boolean get() = irClass?.let { isInlineClass(it) } ?: false

  val underlyingKtType: String? get() = if (isInline) {
    irClass?.declarations
      ?.filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrConstructor>()
      ?.firstOrNull { it.isPrimary }
      ?.parameters
      ?.firstOrNull { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
      ?.type?.classFqName?.asString()
  } else null

  val wrapperKtType: String? get() = if (isInline) ktType else null

  val effectiveKtType: String get() {
    val underlying = underlyingKtType
    return if (isInline && !isNullable && underlying != null && isKnownType(underlying)) underlying else ktType
  }

  val jniFieldType: String get() {
    val typeStr = effectiveKtType
    return if (isKnownType(typeStr)) kotlinToJniFieldType[typeStr]!!
    else irClass?.let { jniTypeDescriptorForClass(it) }
    ?: jniFieldDescriptor(typeStr)
  }

  val jniTypeChar: String get() = jniFieldType.firstOrNull()?.toString() ?: ""

  val isPrimitive: Boolean get() = isKnownType(effectiveKtType) && isJniPrimitive(effectiveKtType) && !isNullable

  val isObjectType: Boolean get() = !isKnownType(effectiveKtType) && !isArray

  val isArray: Boolean get() = effectiveKtType in arrayKotlinTypes

  val arrayElementType: String? get() = if (isArray) {
    (type as? IrSimpleType)?.arguments
      ?.firstOrNull()
      ?.let { (it as? org.jetbrains.kotlin.ir.types.IrTypeProjection)?.type ?: (it as? IrType) }
      ?.let { it.classFqName?.asString() }
  } else null

  val arrayElementNullable: Boolean get() = if (isArray) {
    (type as? IrSimpleType)?.arguments
      ?.firstOrNull()
      ?.let { (it as? org.jetbrains.kotlin.ir.types.IrTypeProjection)?.type ?: (it as? IrType) }
      ?.let { (it as? IrSimpleType)?.isMarkedNullable() ?: false }
      ?: false
  } else false
}

// -- JNI info data classes --

data class PrimitiveArrayJniInfo(
  val jniElementType: String,
  val newArrayFn: String,
  val getElementsFn: String,
  val releaseElementsFn: String,
  val jsGetterTemplate: String,
  val jsGetterCast: String,
)

data class BoxedPrimitiveInfo(
  val wrapperClass: String,
  val ctorSig: String,
  val cType: String,
  val jsGetter: String,
  val jsCast: String,
)

// -- type mapping tables --

val kotlinToJniFieldType = mapOf(
  "kotlin.Boolean" to "Z", "kotlin.Byte" to "B", "kotlin.Char" to "C",
  "kotlin.Short" to "S", "kotlin.Int" to "I", "kotlin.Long" to "J",
  "kotlin.Float" to "F", "kotlin.Double" to "D",
  "kotlin.String" to "Ljava/lang/String;", "kotlin.Any" to "Ljava/lang/Object;",
  "kotlin.BooleanArray" to "[Z", "kotlin.ByteArray" to "[B",
  "kotlin.CharArray" to "[C", "kotlin.ShortArray" to "[S",
  "kotlin.IntArray" to "[I", "kotlin.LongArray" to "[J",
  "kotlin.FloatArray" to "[F", "kotlin.DoubleArray" to "[D",
  "kotlin.Array" to "[Ljava/lang/Object;",
)

val kotlinToCType = mapOf(
  "kotlin.Boolean" to "jboolean", "kotlin.Byte" to "jbyte",
  "kotlin.Char" to "jchar", "kotlin.Short" to "jshort",
  "kotlin.Int" to "jint", "kotlin.Long" to "jlong",
  "kotlin.Float" to "jfloat", "kotlin.Double" to "jdouble",
  "kotlin.String" to "jstring",
  "kotlin.BooleanArray" to "jbooleanArray", "kotlin.ByteArray" to "jbyteArray",
  "kotlin.CharArray" to "jcharArray", "kotlin.ShortArray" to "jshortArray",
  "kotlin.IntArray" to "jintArray", "kotlin.LongArray" to "jlongArray",
  "kotlin.FloatArray" to "jfloatArray", "kotlin.DoubleArray" to "jdoubleArray",
  "kotlin.Array" to "jobjectArray",
)

val kotlinToSetFieldFunction = mapOf(
  "kotlin.Boolean" to "SetBooleanField", "kotlin.Byte" to "SetByteField",
  "kotlin.Char" to "SetCharField", "kotlin.Short" to "SetShortField",
  "kotlin.Int" to "SetIntField", "kotlin.Long" to "SetLongField",
  "kotlin.Float" to "SetFloatField", "kotlin.Double" to "SetDoubleField",
  "kotlin.String" to "SetObjectField",
  "kotlin.BooleanArray" to "SetObjectField", "kotlin.ByteArray" to "SetObjectField",
  "kotlin.CharArray" to "SetObjectField", "kotlin.ShortArray" to "SetObjectField",
  "kotlin.IntArray" to "SetObjectField", "kotlin.LongArray" to "SetObjectField",
  "kotlin.FloatArray" to "SetObjectField", "kotlin.DoubleArray" to "SetObjectField",
  "kotlin.Array" to "SetObjectField",
)

val kotlinToJvmClass = mapOf(
  "kotlin.collections.List" to "java/util/List",
  "kotlin.collections.MutableList" to "java/util/List",
  "kotlin.collections.Map" to "java/util/Map",
  "kotlin.collections.MutableMap" to "java/util/Map",
  "kotlin.collections.Set" to "java/util/Set",
  "kotlin.collections.MutableSet" to "java/util/Set",
  "kotlin.collections.Collection" to "java/util/Collection",
  "kotlin.collections.MutableCollection" to "java/util/Collection",
  "kotlin.collections.Iterable" to "java/lang/Iterable",
  "kotlin.Function0" to "kotlin/jvm/functions/Function0",
  "kotlin.Function1" to "kotlin/jvm/functions/Function1",
  "kotlin.Function2" to "kotlin/jvm/functions/Function2",
  "kotlin.Function3" to "kotlin/jvm/functions/Function3",
  "kotlin.Function4" to "kotlin/jvm/functions/Function4",
  "kotlin.Function5" to "kotlin/jvm/functions/Function5",
  "kotlin.Function6" to "kotlin/jvm/functions/Function6",
)

val arrayKotlinTypes = setOf(
  "kotlin.BooleanArray", "kotlin.ByteArray", "kotlin.CharArray",
  "kotlin.ShortArray", "kotlin.IntArray", "kotlin.LongArray",
  "kotlin.FloatArray", "kotlin.DoubleArray", "kotlin.Array",
)

val primitiveArrayJniInfo = mapOf(
  "kotlin.BooleanArray" to PrimitiveArrayJniInfo("jboolean", "NewBooleanArray", "GetBooleanArrayElements", "ReleaseBooleanArrayElements", "JS_VALUE_GET_BOOL", "(jboolean)"),
  "kotlin.ByteArray" to PrimitiveArrayJniInfo("jbyte", "NewByteArray", "GetByteArrayElements", "ReleaseByteArrayElements", "JS_VALUE_GET_INT", "(jbyte)"),
  "kotlin.CharArray" to PrimitiveArrayJniInfo("jchar", "NewCharArray", "GetCharArrayElements", "ReleaseCharArrayElements", "JS_VALUE_GET_INT", "(jchar)"),
  "kotlin.ShortArray" to PrimitiveArrayJniInfo("jshort", "NewShortArray", "GetShortArrayElements", "ReleaseShortArrayElements", "JS_VALUE_GET_INT", "(jshort)"),
  "kotlin.IntArray" to PrimitiveArrayJniInfo("jint", "NewIntArray", "GetIntArrayElements", "ReleaseIntArrayElements", "JS_VALUE_GET_INT", "(jint)"),
  "kotlin.LongArray" to PrimitiveArrayJniInfo("jlong", "NewLongArray", "GetLongArrayElements", "ReleaseLongArrayElements", "JS_VALUE_GET_INT", "(jlong)"),
  "kotlin.FloatArray" to PrimitiveArrayJniInfo("jfloat", "NewFloatArray", "GetFloatArrayElements", "ReleaseFloatArrayElements", "JS_VALUE_GET_FLOAT64", "(jfloat)"),
  "kotlin.DoubleArray" to PrimitiveArrayJniInfo("jdouble", "NewDoubleArray", "GetDoubleArrayElements", "ReleaseDoubleArrayElements", "JS_VALUE_GET_FLOAT64", "(jdouble)"),
)

val boxedPrimitiveInfo = mapOf(
  "kotlin.Int" to BoxedPrimitiveInfo("java/lang/Integer", "(I)V", "jint", "JS_VALUE_GET_INT", "(jint)"),
  "kotlin.Float" to BoxedPrimitiveInfo("java/lang/Float", "(F)V", "jfloat", "JS_VALUE_GET_FLOAT64", "(jfloat)"),
  "kotlin.Double" to BoxedPrimitiveInfo("java/lang/Double", "(D)V", "jdouble", "JS_VALUE_GET_FLOAT64", "(jdouble)"),
  "kotlin.Long" to BoxedPrimitiveInfo("java/lang/Long", "(J)V", "jlong", "JS_VALUE_GET_INT", "(jlong)"),
  "kotlin.Short" to BoxedPrimitiveInfo("java/lang/Short", "(S)V", "jshort", "JS_VALUE_GET_INT", "(jshort)"),
  "kotlin.Byte" to BoxedPrimitiveInfo("java/lang/Byte", "(B)V", "jbyte", "JS_VALUE_GET_INT", "(jbyte)"),
  "kotlin.Boolean" to BoxedPrimitiveInfo("java/lang/Boolean", "(Z)V", "jboolean", "JS_VALUE_GET_BOOL", "(jboolean)"),
  "kotlin.Char" to BoxedPrimitiveInfo("java/lang/Character", "(C)V", "jchar", "JS_VALUE_GET_INT", "(jchar)"),
)

val boxedJniDescriptor = mapOf(
  "kotlin.Boolean" to "Ljava/lang/Boolean;", "kotlin.Byte" to "Ljava/lang/Byte;",
  "kotlin.Char" to "Ljava/lang/Character;", "kotlin.Short" to "Ljava/lang/Short;",
  "kotlin.Int" to "Ljava/lang/Integer;", "kotlin.Long" to "Ljava/lang/Long;",
  "kotlin.Float" to "Ljava/lang/Float;", "kotlin.Double" to "Ljava/lang/Double;",
)
