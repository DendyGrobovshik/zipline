package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.name.FqName
import java.io.File

// -- C file naming and JNI class name helpers --

internal fun cFunctionPrefix(fqName: FqName): String =
  fqName.asString().replace(".", "_")

internal fun cFileName(fqName: FqName): String =
  cFunctionPrefix(fqName) + ".cpp"

internal fun buildJniClassName(irClass: IrClass): String {
  val fqName = irClass.fqNameWhenAvailable?.asString() ?: return irClass.name.asString()
  val segments = fqName.split('.')

  var classDepth = 1
  var parent: IrDeclarationParent? = irClass.parent
  while (parent is IrClass) {
    classDepth++
    parent = parent.parent
  }

  val packageSegments = segments.dropLast(classDepth)
  val classSegments = segments.takeLast(classDepth)

  val packagePart = packageSegments.joinToString("/")
  val classPart = classSegments.joinToString("\$")

  return if (packagePart.isEmpty()) classPart else "$packagePart/$classPart"
}

// -- C/JNI bridge code generation (Android .so) --

/** Map types recognized by the C converter (mirrors the native generator). */
private val MAP_C_TYPES = setOf(
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
  "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
)

internal fun generateBridgeFile(outputDir: String, annotatedClass: IrClass) {
  val fqName = annotatedClass.fqNameWhenAvailable ?: return
  val functionPrefix = cFunctionPrefix(fqName)

  val fields = extractFields(annotatedClass, includeValBodyFields = true)
  val constructorFields = fields.filter { it.isConstructorParam }
  val bodyFields = fields.filter { !it.isConstructorParam }

  val targetFqn = resolveTargetFqn(annotatedClass)
  val jniClassName = targetFqn ?: buildJniClassName(annotatedClass)
  val jsClassName = fqName.asString()

  val nullablePrimitiveFields = fields.filter { it.isNullable && isKnownType(it.ktType) && isJniPrimitive(it.ktType) }
  val hasAnyField = fields.any { it.ktType == "kotlin.Any" }
  val hasCollectionField = fields.any { it.ktType in kotlinToJvmClass }
  val isObject = annotatedClass.kind == ClassKind.OBJECT
  val isCompanion = isObject && annotatedClass.isCompanion
  val isEnum = annotatedClass.kind == ClassKind.ENUM_CLASS
  val constructorSig = if (isObject || constructorFields.isEmpty()) "()V"
    else "(" + constructorFields.joinToString("") { it.jniTypeChar } + ")V"
  val instanceSig = if (isObject) "L${jniClassName.replace(".", "/")};" else ""

  val cSource = buildString {
    appendLine("#include <jni.h>")
    appendLine("#include <jsi/jsi.h>")
    appendLine("#include <cmath>")
    appendLine("#include <cstdlib>")
    appendLine("#include <string>")
    appendLine("#include <cstring>")
    appendLine("#include \"bridge_dispatch.h\"")
    appendLine("namespace jsi = facebook::jsi;")
    appendLine()
    appendLine("// -- Hermes JSI compatibility layer (emulates QuickJS C API) --")
    appendLine("// JS tag enum (ordered to match common checks)")
    appendLine("enum {")
    appendLine("  JTAG_INT = 0, JTAG_BOOL = 1, JTAG_NULL = 2, JTAG_UNDEFINED = 3,")
    appendLine("  JTAG_STRING = 4, JTAG_OBJECT = 5, JTAG_FLOAT64 = 7")
    appendLine("};")
    appendLine("#define JS_TAG_INT JTAG_INT")
    appendLine("#define JS_TAG_BOOL JTAG_BOOL")
    appendLine("#define JS_TAG_NULL JTAG_NULL")
    appendLine("#define JS_TAG_UNDEFINED JTAG_UNDEFINED")
    appendLine("#define JS_TAG_STRING JTAG_STRING")
    appendLine("#define JS_TAG_OBJECT JTAG_OBJECT")
    appendLine("#define JS_TAG_FLOAT64 JTAG_FLOAT64")
    appendLine()
    appendLine("// Tag emulation: maps jsi::Value to a numeric tag")
    appendLine("static inline int jsi_value_tag(jsi::Runtime &rt, const jsi::Value &v) {")
    appendLine("  if (v.isNumber()) {")
    appendLine("    double d = v.asNumber();")
    appendLine("    if (std::trunc(d) == d && d >= -2147483648.0 && d <= 2147483647.0)")
    appendLine("      return JTAG_INT;")
    appendLine("    return JTAG_FLOAT64;")
    appendLine("  }")
    appendLine("  if (v.isBool())   return JTAG_BOOL;")
    appendLine("  if (v.isNull())   return JTAG_NULL;")
    appendLine("  if (v.isUndefined()) return JTAG_UNDEFINED;")
    appendLine("  if (v.isString()) return JTAG_STRING;")
    appendLine("  if (v.isObject()) return JTAG_OBJECT;")
    appendLine("  return -1;")
    appendLine("}")
    appendLine()
    appendLine("// Thread-local string buffer (emulates JS_ToCString/JS_FreeCString)")
    appendLine("static inline const char* jsi_to_cstring(jsi::Runtime &rt, const jsi::Value &v) {")
    appendLine("  static thread_local std::string buf;")
    appendLine("  buf = v.asString(rt).utf8(rt);")
    appendLine("  return buf.c_str();")
    appendLine("}")
    appendLine()
    appendLine("// Reconstruct 64-bit pointer from two 32-bit halves stored as JS doubles.")
    appendLine("// ARM64 pointers need 64 bits; JS double mantissa is only 53 bits,")
    appendLine("// so we split into bridge_dispatch_low / bridge_dispatch_high.")
    appendLine("static inline intptr_t jsi_get_bridge_dispatch(jsi::Runtime &rt, const jsi::Value &objVal) {")
    appendLine("  if (!objVal.isObject()) return 0;")
    appendLine("  jsi::Object obj = objVal.asObject(rt);")
    appendLine("  jsi::Value lowVal = obj.getProperty(rt, \"bridge_dispatch_low\");")
    appendLine("  jsi::Value highVal = obj.getProperty(rt, \"bridge_dispatch_high\");")
    appendLine("  if (lowVal.isUndefined() || highVal.isUndefined()) return 0;")
    appendLine("  int32_t low  = (int32_t)lowVal.asNumber();")
    appendLine("  int32_t high = (int32_t)highVal.asNumber();")
    appendLine("  return ((intptr_t)high << 32) | ((intptr_t)(uint32_t)low);")
    appendLine("}")
    appendLine()
    appendLine("// Iterate a Kotlin/JS Map (ES6 Map) and return a flat JS array of")
    appendLine("// alternating [key0, value0, key1, value1, ...] entries.")
    appendLine("static inline jsi::Array jsi_map_entries(jsi::Runtime &rt, const jsi::Value &mapVal) {")
    appendLine("  jsi::Array result = jsi::Array(rt, 0);")
    appendLine("  if (!mapVal.isObject()) return result;")
    appendLine("  jsi::Object map = mapVal.asObject(rt);")
    appendLine("  jsi::Value entriesVal = map.getProperty(rt, \"entries\");")
    appendLine("  if (!entriesVal.isObject()) return result;")
    appendLine("  jsi::Function entriesFn = entriesVal.asObject(rt).asFunction(rt);")
    appendLine("  jsi::Value iteratorVal = entriesFn.callWithThis(rt, map);")
    appendLine("  if (!iteratorVal.isObject()) return result;")
    appendLine("  jsi::Object iterator = iteratorVal.asObject(rt);")
    appendLine("  jsi::Value nextVal = iterator.getProperty(rt, \"next\");")
    appendLine("  if (!nextVal.isObject()) return result;")
    appendLine("  jsi::Function nextFn = nextVal.asObject(rt).asFunction(rt);")
    appendLine("  size_t idx = 0;")
    appendLine("  while (true) {")
    appendLine("    jsi::Value r = nextFn.callWithThis(rt, iterator);")
    appendLine("    if (!r.isObject()) break;")
    appendLine("    jsi::Object rObj = r.asObject(rt);")
    appendLine("    jsi::Value done = rObj.getProperty(rt, \"done\");")
    appendLine("    if (done.isBool() && done.asBool()) break;")
    appendLine("    jsi::Value entry = rObj.getProperty(rt, \"value\");")
    appendLine("    if (!entry.isObject()) break;")
    appendLine("    jsi::Array pair = entry.asObject(rt).asArray(rt);")
    appendLine("    result.setValueAtIndex(rt, idx++, pair.getValueAtIndex(rt, 0));")
    appendLine("    result.setValueAtIndex(rt, idx++, pair.getValueAtIndex(rt, 1));")
    appendLine("  }")
    appendLine("  return result;")
    appendLine("}")
    appendLine()
    appendLine("// Box a JS value into a Java object (Boolean/Integer/Double/String), or")
    appendLine("// dispatch a bridged object. Returns NULL for null/undefined/unhandled.")
    appendLine("static inline jobject jsi_value_to_boxed(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v) {")
    appendLine("  if (v.isBool()) {")
    appendLine("    jclass c = env->FindClass(\"java/lang/Boolean\");")
    appendLine("    jmethodID m = env->GetMethodID(c, \"<init>\", \"(Z)V\");")
    appendLine("    return env->NewObject(c, m, (jboolean)v.asBool());")
    appendLine("  }")
    appendLine("  if (v.isNumber()) {")
    appendLine("    double d = v.asNumber();")
    appendLine("    if (d == (double)(int32_t)d) {")
    appendLine("      jclass c = env->FindClass(\"java/lang/Integer\");")
    appendLine("      jmethodID m = env->GetMethodID(c, \"<init>\", \"(I)V\");")
    appendLine("      return env->NewObject(c, m, (jint)d);")
    appendLine("    }")
    appendLine("    jclass c = env->FindClass(\"java/lang/Double\");")
    appendLine("    jmethodID m = env->GetMethodID(c, \"<init>\", \"(D)V\");")
    appendLine("    return env->NewObject(c, m, (jdouble)d);")
    appendLine("  }")
    appendLine("  if (v.isString()) {")
    appendLine("    return env->NewStringUTF(jsi_to_cstring(rt, v));")
    appendLine("  }")
    appendLine("  if (v.isObject()) {")
    appendLine("    intptr_t ptr = jsi_get_bridge_dispatch(rt, v);")
    appendLine("    if (ptr != 0) {")
    appendLine("      JniBridgeDispatch *disp = (JniBridgeDispatch*)ptr;")
    appendLine("      return disp->toJavaObject(env, rt, v);")
    appendLine("    }")
    appendLine("    // Kotlin/JS Long: {low_1, high_1}.")
    appendLine("    jsi::Object obj = v.asObject(rt);")
    appendLine("    jsi::Value lo = obj.getProperty(rt, \"low_1\");")
    appendLine("    if (!lo.isUndefined()) {")
    appendLine("      jsi::Value hi = obj.getProperty(rt, \"high_1\");")
    appendLine("      jlong lv = (static_cast<jlong>(static_cast<int32_t>(hi.asNumber())) << 32) |")
    appendLine("                 (static_cast<jlong>(static_cast<uint32_t>(static_cast<int32_t>(lo.asNumber()))));")
    appendLine("      jclass c = env->FindClass(\"java/lang/Long\");")
    appendLine("      jmethodID m = env->GetMethodID(c, \"<init>\", \"(J)V\");")
    appendLine("      return env->NewObject(c, m, lv);")
    appendLine("    }")
    appendLine("  }")
    appendLine("  return nullptr;")
    appendLine("}")
    appendLine()
    appendLine("typedef jsi::Value JSValue;")
    appendLine()
    appendLine("#define JS_VALUE_GET_NORM_TAG(v) jsi_value_tag(rt, (v))")
    appendLine("#define JS_VALUE_GET_INT(v)      (static_cast<jint>((v).asNumber()))")
    appendLine("#define JS_VALUE_GET_FLOAT64(v)  (static_cast<jdouble>((v).asNumber()))")
    appendLine("#define JS_VALUE_GET_BOOL(v)     (static_cast<jboolean>((v).asBool()))")
    appendLine()
    appendLine("#define JS_GetPropertyStr(c, obj, name)    ((obj).asObject(rt).getProperty(rt, name))")
    appendLine("#define JS_GetPropertyUint32(c, arr, i)    ((arr).asObject(rt).getProperty(rt, std::to_string(i).c_str()))")
    appendLine("#define JS_ToCString(c, v)                 jsi_to_cstring(rt, (v))")
    appendLine("#define JS_FreeCString(c, s)               ((void)0)")
    appendLine()
    appendLine("#define JS_Undefined()                     jsi::Value::undefined()")
    appendLine("#define JS_IsUndefined(v)                  ((v).isUndefined())")
    appendLine("#define JS_IsNull(v)                       ((v).isNull())")
    appendLine("#define JS_IsArray(c, v)                   ((v).isObject() && (v).asObject(rt).isArray(rt))")
    appendLine()
    appendLine("#define JS_FreeValue(c, v)                 ((void)0)")
    appendLine("#define JS_DupValue(c, v)                  jsi::Value(rt, (v))")
    appendLine("#define JS_NewFloat64(c, d)                jsi::Value((d))")
    appendLine("#define js_malloc(c, sz)                   std::malloc(sz)")
    appendLine()
    appendLine("#ifdef __ANDROID__")
    appendLine("#include <android/log.h>")
    appendLine("#endif")
    appendLine()

    // Extern declarations for nullable inline class field helpers.
    val nullableInlineFields = fields.filter { it.isInline && it.isNullable && !isKnownType(it.ktType) }
    if (nullableInlineFields.isNotEmpty()) {
      appendLine("// Inline class _fromValue helpers (used for nullable inline class fields)")
      for (f in nullableInlineFields.distinctBy { it.ktType }) {
        val inlinePrefix = cFunctionPrefix(FqName(f.ktType))
        appendLine("extern void ${inlinePrefix}_init(JNIEnv *env);")
        appendLine("extern jobject ${inlinePrefix}_fromValue(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal);")
      }
      appendLine()
    }

    // -- cached JNI references (initialized once by _init, used by _toJavaObject) --
    appendLine("static jclass _cls = NULL;")
    if (!isObject && !isEnum) appendLine("static jmethodID _ctor = NULL;")
    if (isEnum) appendLine("static jmethodID _valuesMethod = NULL;")
    if (isCompanion) {
      appendLine("static jclass _outerCls = NULL;")
      appendLine("static jfieldID _companionField = NULL;")
    } else if (isObject) {
      appendLine("static jfieldID _instField = NULL;")
    }
    for (f in nullablePrimitiveFields) {
      appendLine("static jclass _boxed_${f.name} = NULL;")
      appendLine("static jmethodID _boxedCtor_${f.name} = NULL;")
    }
    if (!isEnum) {
      for (f in bodyFields) {
        appendLine("static jfieldID _fld_${f.name} = NULL;")
      }
    }
    if (hasAnyField || hasCollectionField) {
      appendLine("// Boxed type refs for Any? value dispatch")
      appendLine("static jclass _any_boxed_Integer_cls = NULL;")
      appendLine("static jmethodID _any_boxed_Integer_ctor = NULL;")
      appendLine("static jclass _any_boxed_Double_cls = NULL;")
      appendLine("static jmethodID _any_boxed_Double_ctor = NULL;")
      appendLine("static jclass _any_boxed_Boolean_cls = NULL;")
      appendLine("static jmethodID _any_boxed_Boolean_ctor = NULL;")
      appendLine("static jclass _any_boxed_Float_cls = NULL;")
      appendLine("static jmethodID _any_boxed_Float_ctor = NULL;")
    }
    appendLine()

    // -- _init: cache JNI references (called once from main thread via init_all) --
    appendLine("void ${functionPrefix}_init(JNIEnv *env) {")
    appendLine("    if (_cls != NULL) return;")
    appendLine("    jclass local = env->FindClass(\"$jniClassName\");")
    appendLine("    if (env->ExceptionCheck()) {")
    appendLine("#ifdef __ANDROID__")
    appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_init FAILED: FindClass for $jniClassName\");")
    appendLine("#endif")
    appendLine("        // Leave the pending exception pending: it propagates to the JVM and crashes.")
    appendLine("        return;")
    appendLine("    }")
    appendLine("    _cls = (jclass)env->NewGlobalRef(local);")
    if (!isObject && !isEnum) {
      appendLine("    _ctor = env->GetMethodID(_cls, \"<init>\", \"$constructorSig\");")
      appendLine("    if (env->ExceptionCheck()) {")
      appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
      appendLine("        _ctor = NULL;")
      appendLine("    }")
    }
    if (isEnum) {
      appendLine("    _valuesMethod = env->GetStaticMethodID(_cls, \"values\", \"()[L$jniClassName;\");")
      appendLine("    if (env->ExceptionCheck()) {")
      appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
      appendLine("        _valuesMethod = NULL;")
      appendLine("    }")
    }
    if (isCompanion) {
      val outerJni = buildJniClassName(annotatedClass.parent as IrClass)
      appendLine("    {")
      appendLine("        jclass outerLocal = env->FindClass(\"$outerJni\");")
      appendLine("        if (env->ExceptionCheck()) { /* pending exception propagates */ return; }")
      appendLine("        _outerCls = (jclass)env->NewGlobalRef(outerLocal);")
      appendLine("        _companionField = env->GetStaticFieldID(_outerCls, \"Companion\", \"$instanceSig\");")
      appendLine("    }")
    } else if (isObject) {
      appendLine("    _instField = env->GetStaticFieldID(_cls, \"INSTANCE\", \"$instanceSig\");")
    }
    for (f in nullablePrimitiveFields) {
      val info = boxedPrimitiveInfo[f.ktType]!!
      appendLine("    {")
      appendLine("        jclass boxed = env->FindClass(\"${info.wrapperClass}\");")
      appendLine("        _boxed_${f.name} = (jclass)env->NewGlobalRef(boxed);")
      appendLine("        _boxedCtor_${f.name} = env->GetMethodID(_boxed_${f.name}, \"<init>\", \"${info.ctorSig}\");")
      appendLine("    }")
    }
    if (hasAnyField || hasCollectionField) {
      appendLine("    // Init boxed type refs for Any? value dispatch")
      appendLine("    if (_any_boxed_Integer_cls == NULL) {")
      appendLine("        jclass intLocal = env->FindClass(\"java/lang/Integer\");")
      appendLine("        _any_boxed_Integer_cls = (jclass)env->NewGlobalRef(intLocal);")
      appendLine("        _any_boxed_Integer_ctor = env->GetMethodID(_any_boxed_Integer_cls, \"<init>\", \"(I)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Double_cls == NULL) {")
      appendLine("        jclass dblLocal = env->FindClass(\"java/lang/Double\");")
      appendLine("        _any_boxed_Double_cls = (jclass)env->NewGlobalRef(dblLocal);")
      appendLine("        _any_boxed_Double_ctor = env->GetMethodID(_any_boxed_Double_cls, \"<init>\", \"(D)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Boolean_cls == NULL) {")
      appendLine("        jclass boolLocal = env->FindClass(\"java/lang/Boolean\");")
      appendLine("        _any_boxed_Boolean_cls = (jclass)env->NewGlobalRef(boolLocal);")
      appendLine("        _any_boxed_Boolean_ctor = env->GetMethodID(_any_boxed_Boolean_cls, \"<init>\", \"(Z)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Float_cls == NULL) {")
      appendLine("        jclass fltLocal = env->FindClass(\"java/lang/Float\");")
      appendLine("        _any_boxed_Float_cls = (jclass)env->NewGlobalRef(fltLocal);")
      appendLine("        _any_boxed_Float_ctor = env->GetMethodID(_any_boxed_Float_cls, \"<init>\", \"(F)V\");")
      appendLine("    }")
    }
    if (!isEnum) {
      for (f in bodyFields) {
        appendLine("    _fld_${f.name} = env->GetFieldID(_cls, \"${f.name}\", \"${f.jniFieldType}\");")
        appendLine("    if (env->ExceptionCheck()) {")
        appendLine("        // Let the pending NoSuchFieldError propagate instead of clearing it.")
        appendLine("        _fld_${f.name} = NULL;")
        appendLine("    }")
      }
    }
    appendLine("}")
    appendLine()

    // -- toJavaObject function --
    // Recursive collection/array/map converters are emitted before the converter that calls them.
    val helpers = StringBuilder()
    for (field in fields) {
      val kt = field.effectiveKtType
      if (kt == "kotlin.collections.List" || kt == "kotlin.collections.MutableList" || kt in MAP_C_TYPES || kt == "kotlin.Array" || kt in PRIMITIVE_ARRAY_ELEMENT_TYPE) {
        emitCValueConverter(helpers, "conv_${field.name}", kt, field.type)
      }
    }
    if (helpers.isNotEmpty()) {
      val withFindMethod = StringBuilder()
      if (fields.any { it.effectiveKtType in MAP_C_TYPES }) {
        emitCBridgeFindMethod(withFindMethod)
      }
      withFindMethod.append(helpers)
      append(withFindMethod)
      appendLine()
    }

    appendLine("static jobject ${functionPrefix}_toJavaObject(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsObj) {")
    if (isEnum) {
      appendLine("    if (_cls == NULL || _valuesMethod == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    } else if (!isObject) {
      appendLine("    if (_cls == NULL || _ctor == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    } else if (isCompanion) {
      appendLine("    if (_cls == NULL || _outerCls == NULL || _companionField == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    } else {
      appendLine("    if (_cls == NULL || _instField == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    }
    appendLine()

    if (isEnum) {
      // Enum — read the JS ordinal and return values()[ordinal].
      appendLine("    jsi::Value ordinalRaw = jsObj.asObject(rt).getProperty(rt, \"ordinal_1\");")
      appendLine("    jint ordinal = (jint)ordinalRaw.asNumber();")
      appendLine("    jobjectArray values = (jobjectArray)env->CallStaticObjectMethod(_cls, _valuesMethod);")
      appendLine("    if (env->ExceptionCheck()) return NULL;")
      appendLine("    jobject result = env->GetObjectArrayElement(values, ordinal);")
      appendLine("    if (env->ExceptionCheck()) return NULL;")
      appendLine("    return result;")
      appendLine("}")
    } else {
    if (fields.isNotEmpty()) {
      appendLine("    // Extract field values from JS object")
    }

    // Extract each field from the JS object
    for (field in fields) {
      val nullablePrimitive = field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType)
      // Non-nullable inline value classes are erased to their underlying JNI primitive on JVM,
      // so dispatch on effectiveKtType (unwrapped) for the primitive branches.
      val isCollectionField = field.isArray ||
        field.effectiveKtType == "kotlin.collections.List" ||
        field.effectiveKtType == "kotlin.collections.MutableList" ||
        field.effectiveKtType in MAP_C_TYPES
      val cType = if (nullablePrimitive || isCollectionField) "jobject"
        else kotlinToCType[field.effectiveKtType] ?: "jobject"
      val javaVar = "java_${field.name}"

      appendLine("    jsi::Value js_${field.name} = jsObj.asObject(rt).getProperty(rt, \"${field.jsPropertyName}\");")
      // For Long-backed inline classes (e.g. Color), the JS object may be unboxed —
      // *jsObj IS the Long {low_1, high_1} with no .value wrapper. If .value is
      // undefined, fall back to using the object directly as the Long representation.
      if (field.ktType == "kotlin.Long" && isInlineClass(annotatedClass)) {
        appendLine("    if (js_${field.name}.isUndefined()) {")
        appendLine("        js_${field.name} = jsi::Value(rt, jsObj);")
        appendLine("    }")
      }
      // Inline value classes (e.g. @JvmInline value class Id(val value: Int))
      // may be boxed ({value: ...}) or unboxed (plain int) in Kotlin/JS depending
      // on context. We check: if it's an object, unwrap .value; otherwise use as-is.
      // EXCEPTION: Long-backed inline classes (e.g. Color) — Long is already a JS
      // object {low_1, high_1} in Kotlin/JS, so the object IS the value, not a wrapper.
      if (field.isInline && field.underlyingKtType != "kotlin.Long") {
        appendLine("    if (js_${field.name}.isObject()) {")
        appendLine("        js_${field.name} = js_${field.name}.asObject(rt).getProperty(rt, \"value\");")
        appendLine("    }")
      }
      appendLine("    $cType $javaVar;")
      appendLine("    {")

      // Open null check for nullable fields
      if (field.isNullable) {
        appendLine("        if (!js_${field.name}.isUndefined() && !js_${field.name}.isNull()) {")
      }

      when {
        field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> {
          emitNullablePrimitiveExtraction(this, field)
        }
        field.effectiveKtType == "kotlin.Boolean" -> {
          appendLine("        $javaVar = (jboolean)js_${field.name}.asBool();")
        }
        field.effectiveKtType == "kotlin.Byte" -> {
          appendLine("        $javaVar = (jbyte)JS_VALUE_GET_INT(js_${field.name});")
        }
        field.effectiveKtType == "kotlin.Short" -> {
          appendLine("        $javaVar = (jshort)JS_VALUE_GET_INT(js_${field.name});")
        }
        field.effectiveKtType == "kotlin.Int" -> {
          appendLine("        $javaVar = (jint)JS_VALUE_GET_INT(js_${field.name});")
        }
        field.effectiveKtType == "kotlin.Long" -> {
          appendLine("        int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        if (tag_${field.name} == JS_TAG_FLOAT64) {")
          appendLine("            $javaVar = (jlong)JS_VALUE_GET_FLOAT64(js_${field.name});")
          appendLine("        } else if (tag_${field.name} == JS_TAG_INT) {")
          appendLine("            $javaVar = (jlong)JS_VALUE_GET_INT(js_${field.name});")
          appendLine("        } else if (tag_${field.name} == JS_TAG_OBJECT) {")
          appendLine("            /* Kotlin/JS Long: {low_1, high_1} packed representation */")
          appendLine("            JSValue lowVal = JS_GetPropertyStr(ctx, js_${field.name}, \"low_1\");")
          appendLine("            JSValue highVal = JS_GetPropertyStr(ctx, js_${field.name}, \"high_1\");")
          appendLine("            jint low = JS_VALUE_GET_INT(lowVal);")
          appendLine("            jint high = JS_VALUE_GET_INT(highVal);")
          appendLine("            $javaVar = ((jlong)high << 32) | ((jlong)low & 0xFFFFFFFF);")
          appendLine("            JS_FreeValue(ctx, lowVal);")
          appendLine("            JS_FreeValue(ctx, highVal);")
          appendLine("        } else {")
          appendLine("#ifdef __ANDROID__")
          appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Long field ${field.name}: unexpected JS tag %d\", tag_${field.name});")
          appendLine("#endif")
          appendLine("            $javaVar = 0;")
          appendLine("        }")
        }
        field.effectiveKtType == "kotlin.Float" || field.effectiveKtType == "kotlin.Double" -> {
          val cast = if (field.effectiveKtType == "kotlin.Float") "(jfloat)" else "(jdouble)"
          appendLine("        int tag_${field.name}_d = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        if (tag_${field.name}_d == JS_TAG_FLOAT64) {")
          appendLine("            $javaVar = ${cast}JS_VALUE_GET_FLOAT64(js_${field.name});")
          appendLine("        } else if (tag_${field.name}_d == JS_TAG_INT) {")
          appendLine("            $javaVar = ${cast}JS_VALUE_GET_INT(js_${field.name});")
          appendLine("        } else {")
          appendLine("#ifdef __ANDROID__")
          appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Float/Double field ${field.name}: unexpected JS tag %d\", tag_${field.name}_d);")
          appendLine("#endif")
          appendLine("            /* JS_TAG_UNDEFINED=3, JS_TAG_NULL=2 */")
          appendLine("            $javaVar = 0;")
          appendLine("        }")
        }
        field.effectiveKtType == "kotlin.Char" -> {
          appendLine("        $javaVar = (jchar)JS_VALUE_GET_INT(js_${field.name});")
        }
        field.effectiveKtType == "kotlin.String" -> {
          appendLine("        const char *str_${field.name} = JS_ToCString(ctx, js_${field.name});")
          appendLine("        $javaVar = env->NewStringUTF(str_${field.name});")
          appendLine("        JS_FreeCString(ctx, str_${field.name});")
        }
        field.effectiveKtType in MAP_C_TYPES -> {
          appendLine("        $javaVar = conv_${field.name}(env, rt, js_${field.name});")
        }
        field.effectiveKtType == "kotlin.collections.List" || field.effectiveKtType == "kotlin.collections.MutableList" -> {
          appendLine("        $javaVar = conv_${field.name}(env, rt, js_${field.name});")
        }
        field.effectiveKtType == "kotlin.Any" -> {
          emitAnyFieldExtraction(this, field)
        }
        field.isArray -> {
          appendLine("        $javaVar = conv_${field.name}(env, rt, js_${field.name});")
        }
        field.isInline && field.isNullable -> {
          val inlineCPrefix = cFunctionPrefix(FqName(field.ktType))
          appendLine("        $javaVar = ${inlineCPrefix}_fromValue(env, rt, js_${field.name});")
        }
        field.isObjectType -> {
          appendLine("        // Look up bridge_dispatch on the sub-object to convert it.")
          appendLine("        intptr_t _bridge_ptr_${field.name} = jsi_get_bridge_dispatch(rt, js_${field.name});")
          appendLine("        if (_bridge_ptr_${field.name} == 0) {")
          appendLine("#ifdef __ANDROID__")
          appendLine("            const char *dbgCtorNm_${field.name} = \"(unknown)\";")
          appendLine("            JSValue dbgCtor_${field.name} = JS_GetPropertyStr(ctx, js_${field.name}, \"constructor\");")
          appendLine("            if (!JS_IsUndefined(dbgCtor_${field.name}) && !JS_IsNull(dbgCtor_${field.name})) {")
          appendLine("                JSValue dbgCtorNmVal_${field.name} = JS_GetPropertyStr(ctx, dbgCtor_${field.name}, \"name\");")
          appendLine("                dbgCtorNm_${field.name} = JS_ToCString(ctx, dbgCtorNmVal_${field.name});")
          appendLine("                JS_FreeValue(ctx, dbgCtorNmVal_${field.name});")
          appendLine("            }")
          appendLine("            __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"bridge_dispatch not found for field ${field.name} (ctor=%s)\", dbgCtorNm_${field.name});")
          appendLine("            if (strcmp(dbgCtorNm_${field.name}, \"(unknown)\") != 0) JS_FreeCString(ctx, dbgCtorNm_${field.name});")
          appendLine("            JS_FreeValue(ctx, dbgCtor_${field.name});")
          appendLine("#endif")
          appendLine("            JS_FreeValue(ctx, js_${field.name});")
          appendLine("            return NULL;")
          appendLine("        }")
          appendLine("        JniBridgeDispatch *disp_${field.name} = (JniBridgeDispatch *)_bridge_ptr_${field.name};")
          appendLine("        $javaVar = disp_${field.name}->toJavaObject(env, rt, js_${field.name});")
        }
      }

      // Close null check for nullable fields
      if (field.isNullable) {
        appendLine("        } else {")
        appendLine("            $javaVar = NULL;")
        appendLine("        }")
      }

      appendLine("        JS_FreeValue(ctx, js_${field.name});")
      appendLine("    }")
      appendLine()
    }

    // Create instance using cached class/constructor/field refs.
    appendLine("    // Create instance using cached JNI references")
    if (isCompanion) {
      appendLine("    jobject result = env->GetStaticObjectField(_outerCls, _companionField);")
    } else if (isObject) {
      appendLine("    jobject result = env->GetStaticObjectField(_cls, _instField);")
    } else {
      if (constructorFields.isEmpty()) {
        appendLine("    jobject result = env->NewObject(_cls, _ctor);")
      } else {
        val args = constructorFields.joinToString(", ") { "java_${it.name}" }
        appendLine("    jobject result = env->NewObject(_cls, _ctor, $args);")
      }
    }
    appendLine("    if (env->ExceptionCheck()) return NULL;")
    appendLine()

    // Set body fields using cached field IDs
    if (!isEnum && bodyFields.isNotEmpty()) {
      appendLine("    // Set non-constructor fields")
      for (field in bodyFields) {
        val javaVar = "java_${field.name}"
        val setFn = when {
          field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> "SetObjectField"
          else -> kotlinToSetFieldFunction[field.effectiveKtType] ?: "SetObjectField"
        }
        appendLine("    if (_fld_${field.name} != NULL) {")
        appendLine("        env->$setFn(result, _fld_${field.name}, $javaVar);")
        appendLine("    }")
      }
      appendLine()
    }

    appendLine("    return result;")
    appendLine("}")
    } // end else (non-enum)

    if (isInlineClass(annotatedClass) && !isObject && constructorFields.size == 1) {
      val underlyingField = constructorFields[0]
      val underlyingCType = kotlinToCType[underlyingField.effectiveKtType] ?: "jobject"
      appendLine()
      appendLine("jobject ${functionPrefix}_fromValue(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {")
      appendLine("    ${functionPrefix}_init(env);")
      appendLine("    if (_cls == NULL || _ctor == NULL) return NULL;")
      appendLine("    $underlyingCType java_v;")
      appendLine("    {")
      appendLine("        int tag_v = JS_VALUE_GET_NORM_TAG(jsVal);")
      appendLine("        if (tag_v == JS_TAG_FLOAT64) {")
      appendLine("            java_v = ($underlyingCType)JS_VALUE_GET_FLOAT64(jsVal);")
      appendLine("        } else if (tag_v == JS_TAG_INT) {")
      appendLine("            java_v = ($underlyingCType)JS_VALUE_GET_INT(jsVal);")
      if (underlyingField.effectiveKtType == "kotlin.Long") {
        appendLine("        } else if (tag_v == JS_TAG_OBJECT) {")
        appendLine("            /* Kotlin/JS Long: {low_1, high_1} packed representation */")
        appendLine("            JSValue lv = JS_GetPropertyStr(ctx, jsVal, \"low_1\");")
        appendLine("            JSValue hv = JS_GetPropertyStr(ctx, jsVal, \"high_1\");")
        appendLine("            java_v = (($underlyingCType)JS_VALUE_GET_INT(hv) << 32) | (($underlyingCType)JS_VALUE_GET_INT(lv) & 0xFFFFFFFF);")
        appendLine("            JS_FreeValue(ctx, lv);")
        appendLine("            JS_FreeValue(ctx, hv);")
      }
      appendLine("        } else {")
      appendLine("#ifdef __ANDROID__")
      appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"_fromValue: unexpected JS tag %d\", tag_v);")
      appendLine("#endif")
      appendLine("            java_v = 0;")
      appendLine("        }")
      appendLine("    }")
      appendLine("    jobject result = env->NewObject(_cls, _ctor, java_v);")
      appendLine("    if (env->ExceptionCheck()) return NULL;")
      appendLine("    return result;")
      appendLine("}")
    }

    appendLine()
    appendLine("// Register this class in the shared bridge dispatch table (Context.cpp).")
    appendLine("__attribute__((used, constructor))")
    appendLine("void ${functionPrefix}_bridge_register(void) {")
    appendLine("    addBridgeInit(${functionPrefix}_init);")
    appendLine("    addBridgeEntry(\"$jsClassName\", ${functionPrefix}_toJavaObject);")
    appendLine("}")
  }

  val outputFile = File(outputDir, cFileName(fqName))
  outputFile.parentFile.mkdirs()
  outputFile.writeText(cSource)
}

// -- FQN-toJavaObject registration via shared bridgeTable (Context.cpp) --

private fun emitCBoxedConverter(
  helpers: StringBuilder,
  name: String,
  boxedClass: String,
  ctorSig: String,
  valueExpr: String,
) {
  helpers.appendLine(
    """
    static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
      jclass c = env->FindClass("$boxedClass");
      jmethodID m = env->GetMethodID(c, "<init>", "$ctorSig");
      return env->NewObject(c, m, $valueExpr);
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

private fun emitCBridgeFindMethod(helpers: StringBuilder) {
  helpers.appendLine(
    """
    static jsi::Value bridgeFindMethod(jsi::Runtime &rt, const jsi::Value &obj, const char* prefix) {
      if (!obj.isObject()) return jsi::Value::undefined();
      jsi::Object o = obj.asObject(rt);
      jsi::Value ctor = o.getProperty(rt, "constructor");
      if (!ctor.isObject()) return jsi::Value::undefined();
      jsi::Value proto = ctor.asObject(rt).getProperty(rt, "prototype");
      while (proto.isObject()) {
        jsi::Array names = proto.asObject(rt).getPropertyNames(rt);
        size_t n = names.length(rt);
        for (size_t i = 0; i < n; i++) {
          jsi::Value nm = names.getValueAtIndex(rt, i);
          if (!nm.isString()) continue;
          std::string name = nm.asString(rt).utf8(rt);
          if (strncmp(name.c_str(), prefix, strlen(prefix)) == 0) {
            return o.getProperty(rt, name.c_str());
          }
        }
        proto = proto.asObject(rt).getProperty(rt, "__proto__");
      }
      return jsi::Value::undefined();
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

/**
 * Emits a C static function converting a JS value of [ktType] into a jobject. Recurses for
 * nested collections. Returns the function name.
 */
private fun emitCValueConverter(
  helpers: StringBuilder,
  name: String,
  ktType: String,
  irType: IrType?,
): String {
  when (ktType) {
    "kotlin.String" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          const char* s = jsi_to_cstring(rt, jsVal);
          jobject r = env->NewStringUTF(s);
          return r;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Int" -> emitCBoxedConverter(helpers, name, "java/lang/Integer", "(I)V", "JS_VALUE_GET_INT(jsVal)")
    "kotlin.Float" -> emitCBoxedConverter(helpers, name, "java/lang/Float", "(F)V", "JS_VALUE_GET_FLOAT64(jsVal)")
    "kotlin.Double" -> emitCBoxedConverter(helpers, name, "java/lang/Double", "(D)V", "JS_VALUE_GET_FLOAT64(jsVal)")
    "kotlin.Boolean" -> emitCBoxedConverter(helpers, name, "java/lang/Boolean", "(Z)V", "JS_VALUE_GET_BOOL(jsVal)")
    "kotlin.Byte" -> emitCBoxedConverter(helpers, name, "java/lang/Byte", "(B)V", "(jbyte)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Short" -> emitCBoxedConverter(helpers, name, "java/lang/Short", "(S)V", "(jshort)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Char" -> emitCBoxedConverter(helpers, name, "java/lang/Character", "(C)V", "(jchar)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Long" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          return jsi_value_to_boxed(env, rt, jsVal);
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Any" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          return jsi_value_to_boxed(env, rt, jsVal);
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.collections.List" -> {
      val elementType = typeArgument(irType, 0)
      val elementKtType = effectiveClassFqn(elementType)
      val elementName = "${name}_element"
      emitCValueConverter(helpers, elementName, elementKtType, elementType)
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          jclass alc = env->FindClass("java/util/ArrayList");
          jmethodID alc_init = env->GetMethodID(alc, "<init>", "()V");
          jmethodID alc_add = env->GetMethodID(alc, "add", "(Ljava/lang/Object;)Z");
          jobject result = env->NewObject(alc, alc_init);
          jsi::Value arr = jsi::Value(rt, jsVal);
          if (arr.isObject()) {
            jsi::Value wrap = arr.asObject(rt).getProperty(rt, "array_1");
            if (!wrap.isUndefined()) arr = jsi::Value(rt, wrap);
          }
          if (!arr.isObject()) return result;
          jsi::Value lenVal = arr.asObject(rt).getProperty(rt, "length");
          if (!lenVal.isNumber()) return result;
          jint len = (jint)lenVal.asNumber();
          for (jint i = 0; i < len; i++) {
            jsi::Value elem = arr.asObject(rt).getProperty(rt, std::to_string(i).c_str());
            jobject je = $elementName(env, rt, elem);
            if (je) env->CallBooleanMethod(result, alc_add, je);
            if (je) env->DeleteLocalRef(je);
          }
          return result;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Array" -> {
      val elementType = typeArgument(irType, 0)
      val elementKtType = effectiveClassFqn(elementType)
      val elementName = "${name}_element"
      emitCValueConverter(helpers, elementName, elementKtType, elementType)
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          jclass oc = env->FindClass("java/lang/Object");
          if (!jsVal.isObject()) return env->NewObjectArray(0, oc, NULL);
          jsi::Value lenVal = jsVal.asObject(rt).getProperty(rt, "length");
          if (!lenVal.isNumber()) return env->NewObjectArray(0, oc, NULL);
          jint len = (jint)lenVal.asNumber();
          jobjectArray result = env->NewObjectArray(len, oc, NULL);
          for (jint i = 0; i < len; i++) {
            jsi::Value elem = jsVal.asObject(rt).getProperty(rt, std::to_string(i).c_str());
            jobject je = $elementName(env, rt, elem);
            env->SetObjectArrayElement(result, i, je);
            if (je) env->DeleteLocalRef(je);
          }
          return result;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    in MAP_C_TYPES -> {
      val keyType = typeArgument(irType, 0)
      val valueType = typeArgument(irType, 1)
      val keyName = "${name}_key"
      val valueName = "${name}_value"
      emitCValueConverter(helpers, keyName, effectiveClassFqn(keyType), keyType)
      emitCValueConverter(helpers, valueName, effectiveClassFqn(valueType), valueType)
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          jclass hc = env->FindClass("java/util/HashMap");
          jmethodID hc_init = env->GetMethodID(hc, "<init>", "()V");
          jmethodID hc_put = env->GetMethodID(hc, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
          jobject result = env->NewObject(hc, hc_init);
          if (!jsVal.isObject()) return result;
          jsi::Value entriesFn = bridgeFindMethod(rt, jsVal, "get_entries_");
          if (entriesFn.isUndefined() || !entriesFn.isObject()) return result;
          jsi::Value entries = entriesFn.asObject(rt).asFunction(rt).callWithThis(rt, jsVal.asObject(rt));
          if (!entries.isObject()) return result;
          jsi::Value iterFn = bridgeFindMethod(rt, entries, "iterator_");
          if (iterFn.isUndefined() || !iterFn.isObject()) return result;
          jsi::Value iterator = iterFn.asObject(rt).asFunction(rt).callWithThis(rt, entries.asObject(rt));
          if (!iterator.isObject()) return result;
          jsi::Value hasNextFn = bridgeFindMethod(rt, iterator, "hasNext_");
          jsi::Value nextFn = bridgeFindMethod(rt, iterator, "next_");
          if (hasNextFn.isUndefined() || nextFn.isUndefined()) return result;
          while (true) {
            jsi::Value hn = hasNextFn.asObject(rt).asFunction(rt).callWithThis(rt, iterator.asObject(rt));
            if (!hn.isBool() || !hn.asBool()) break;
            jsi::Value entry = nextFn.asObject(rt).asFunction(rt).callWithThis(rt, iterator.asObject(rt));
            if (!entry.isObject()) break;
            jsi::Value keyFn = bridgeFindMethod(rt, entry, "get_key_");
            jsi::Value valueFn = bridgeFindMethod(rt, entry, "get_value_");
            if (keyFn.isUndefined() || valueFn.isUndefined()) break;
            jsi::Value keyVal = keyFn.asObject(rt).asFunction(rt).callWithThis(rt, entry.asObject(rt));
            jsi::Value valueVal = valueFn.asObject(rt).asFunction(rt).callWithThis(rt, entry.asObject(rt));
            jobject jk = keyVal.isUndefined() ? nullptr : $keyName(env, rt, keyVal);
            jobject jv = valueVal.isUndefined() ? nullptr : $valueName(env, rt, valueVal);
            env->CallObjectMethod(result, hc_put, jk, jv);
            if (jk) env->DeleteLocalRef(jk);
            if (jv) env->DeleteLocalRef(jv);
          }
          return result;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    in PRIMITIVE_ARRAY_ELEMENT_TYPE -> {
      val arrayType = kotlinToCType[ktType]!!
      val info = primitiveArrayJniInfo[ktType]!!
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          if (!jsVal.isObject()) return env->${info.newArrayFn}(0);
          jsi::Value lenVal = jsVal.asObject(rt).getProperty(rt, "length");
          if (!lenVal.isNumber()) return env->${info.newArrayFn}(0);
          jint len = (jint)lenVal.asNumber();
          $arrayType arr = env->${info.newArrayFn}(len);
          ${info.jniElementType}* elems = env->${info.getElementsFn}(arr, NULL);
          for (jint i = 0; i < len; i++) {
            jsi::Value elem = jsVal.asObject(rt).getProperty(rt, std::to_string(i).c_str());
            elems[i] = ${info.jsGetterCast}${info.jsGetterTemplate}(elem);
          }
          env->${info.releaseElementsFn}(arr, elems, 0);
          return arr;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    else -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          intptr_t ptr = jsi_get_bridge_dispatch(rt, jsVal);
          if (ptr != 0) {
            JniBridgeDispatch *disp = (JniBridgeDispatch*)ptr;
            return disp->toJavaObject(env, rt, jsVal);
          }
          return nullptr;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
  }
  return name
}

// -- Array extraction helpers --

internal fun emitArrayExtraction(sb: StringBuilder, field: FieldInfo) {
  val javaVar = "java_${field.name}"
  val jsVar = "js_${field.name}"

  // Array preamble: check JS_IsArray, get length
  emitArrayPreamble(sb, jsVar)

  when {
    isPrimitiveArray(field.ktType) -> {
      emitPrimitiveArrayLoop(sb, field, javaVar, jsVar)
    }
    isStringElement(field.arrayElementType) -> {
      emitStringArrayLoop(sb, field, javaVar, jsVar)
    }
    field.arrayElementType != null && !isStringElement(field.arrayElementType) -> {
      // Non-string element type - could be a bridge object or general
      emitGeneralArrayLoop(sb, field, javaVar, jsVar)
    }
    else -> {
      // No element type info — general runtime dispatch
      emitGeneralArrayLoop(sb, field, javaVar, jsVar)
    }
  }
}

/** Emit the common array preamble: JS_IsArray check + get length. */
internal fun emitArrayPreamble(sb: StringBuilder, jsVar: String) {
  sb.appendLine("        if (!${jsVar}.isObject()) {")
  sb.appendLine("            jclass iae = env->FindClass(\"java/lang/IllegalArgumentException\");")
  sb.appendLine("            if (iae != NULL) env->ThrowNew(iae, \"Expected an array\");")
  sb.appendLine("            JS_FreeValue(ctx, $jsVar);")
  sb.appendLine("            return NULL;")
  sb.appendLine("        }")
  sb.appendLine("        JSValue js_len_${jsVar} = JS_GetPropertyStr(ctx, $jsVar, \"length\");")
  sb.appendLine("        jint len_${jsVar} = (jint)JS_VALUE_GET_INT(js_len_${jsVar});")
  sb.appendLine("        JS_FreeValue(ctx, js_len_${jsVar});")
}

/** Emit loop for a primitive specialised array (IntArray, FloatArray, etc.). */
internal fun emitPrimitiveArrayLoop(
  sb: StringBuilder,
  field: FieldInfo,
  javaVar: String,
  jsVar: String,
) {
  val info = primitiveArrayJniInfo[field.ktType]!!

  sb.appendLine("        ${field.ktType.let { kotlinToCType[it] }} arr_${field.name} = env->${info.newArrayFn}(len_${jsVar});")
  sb.appendLine("        ${info.jniElementType} *elems_${field.name} = env->${info.getElementsFn}(arr_${field.name}, NULL);")
  sb.appendLine("        for (jint i = 0; i < len_${jsVar}; i++) {")
  sb.appendLine("            JSValue elem = JS_GetPropertyUint32(ctx, $jsVar, i);")
  if (field.ktType == "kotlin.LongArray") {
    // Long needs tag-based extraction
    sb.appendLine("            int tag = JS_VALUE_GET_NORM_TAG(elem);")
    sb.appendLine("            if (tag == JS_TAG_FLOAT64) {")
    sb.appendLine("                elems_${field.name}[i] = (jlong)JS_VALUE_GET_FLOAT64(elem);")
    sb.appendLine("            } else {")
    sb.appendLine("                elems_${field.name}[i] = (jlong)JS_VALUE_GET_INT(elem);")
    sb.appendLine("            }")
  } else {
    sb.appendLine("            elems_${field.name}[i] = ${info.jsGetterCast}${info.jsGetterTemplate}(elem);")
  }
  sb.appendLine("            JS_FreeValue(ctx, elem);")
  sb.appendLine("        }")
  sb.appendLine("        env->${info.releaseElementsFn}(arr_${field.name}, elems_${field.name}, 0);")
  sb.appendLine("        $javaVar = arr_${field.name};")
}

/** Emit loop for Array<String> (or Array<String?>). */
internal fun emitStringArrayLoop(
  sb: StringBuilder,
  field: FieldInfo,
  javaVar: String,
  jsVar: String,
) {
  sb.appendLine("        jclass strClass_${field.name} = env->FindClass(\"java/lang/String\");")
  sb.appendLine("        jobjectArray arr_${field.name} = env->NewObjectArray(len_${jsVar}, strClass_${field.name}, NULL);")
  sb.appendLine("        for (jint i = 0; i < len_${jsVar}; i++) {")
  sb.appendLine("            JSValue elem = JS_GetPropertyUint32(ctx, $jsVar, i);")
  sb.appendLine("            jobject java_elem = NULL;")
  if (field.arrayElementNullable) {
    sb.appendLine("            if (!JS_IsUndefined(elem) && !JS_IsNull(elem)) {")
  }
  sb.appendLine("            const char *str = JS_ToCString(ctx, elem);")
  sb.appendLine("            java_elem = env->NewStringUTF(str);")
  sb.appendLine("            JS_FreeCString(ctx, str);")
  if (field.arrayElementNullable) {
    sb.appendLine("            }")
  }
  sb.appendLine("            env->SetObjectArrayElement(arr_${field.name}, i, java_elem);")
  sb.appendLine("            if (java_elem != NULL) env->DeleteLocalRef(java_elem);")
  sb.appendLine("            JS_FreeValue(ctx, elem);")
  sb.appendLine("        }")
  sb.appendLine("        $javaVar = arr_${field.name};")
}

/** Emit loop for a general Array<T> with per-element runtime tag dispatch.
 *  This covers Array<Any>, Array<Comparable<Int>>, Array<Int>, Array<SomeObject>, etc. */
internal fun emitGeneralArrayLoop(
  sb: StringBuilder,
  field: FieldInfo,
  javaVar: String,
  jsVar: String,
) {
  // Pre-lookup all boxed primitive classes and constructors
  sb.appendLine("        // Pre-lookup boxed primitive classes and constructors")
  sb.appendLine("        jclass objClass_${field.name} = env->FindClass(\"java/lang/Object\");")
  for ((ktType, info) in boxedPrimitiveInfo) {
    val shortName = ktType.substringAfterLast('.')
    sb.appendLine("        jclass cls_${field.name}_${shortName} = env->FindClass(\"${info.wrapperClass}\");")
    sb.appendLine("        jmethodID ctor_${field.name}_${shortName} = env->GetMethodID(cls_${field.name}_${shortName}, \"<init>\", \"${info.ctorSig}\");")
  }

  sb.appendLine()
  sb.appendLine("        jobjectArray arr_${field.name} = env->NewObjectArray(len_${jsVar}, objClass_${field.name}, NULL);")
  sb.appendLine("        for (jint i = 0; i < len_${jsVar}; i++) {")
  sb.appendLine("            JSValue elem = JS_GetPropertyUint32(ctx, $jsVar, i);")
  sb.appendLine("            int tag = JS_VALUE_GET_NORM_TAG(elem);")
  sb.appendLine("            jobject java_elem = NULL;")
  sb.appendLine()
  sb.appendLine("            switch (tag) {")

  // JS_TAG_INT → Integer
  sb.appendLine("                case JS_TAG_INT: {")
  sb.appendLine("                    java_elem = env->NewObject(cls_${field.name}_Int, ctor_${field.name}_Int, (jint)JS_VALUE_GET_INT(elem));")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_FLOAT64 → Double
  sb.appendLine("                case JS_TAG_FLOAT64: {")
  sb.appendLine("                    java_elem = env->NewObject(cls_${field.name}_Double, ctor_${field.name}_Double, JS_VALUE_GET_FLOAT64(elem));")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_BOOL → Boolean
  sb.appendLine("                case JS_TAG_BOOL: {")
  sb.appendLine("                    java_elem = env->NewObject(cls_${field.name}_Boolean, ctor_${field.name}_Boolean, (jboolean)JS_VALUE_GET_BOOL(elem));")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_STRING → java.lang.String
  sb.appendLine("                case JS_TAG_STRING: {")
  sb.appendLine("                    const char *str_${field.name} = JS_ToCString(ctx, elem);")
  sb.appendLine("                    java_elem = env->NewStringUTF(str_${field.name});")
  sb.appendLine("                    JS_FreeCString(ctx, str_${field.name});")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_OBJECT → try bridge_dispatch
  sb.appendLine("                case JS_TAG_OBJECT: {")
  sb.appendLine("                    intptr_t _bridge_ptr = jsi_get_bridge_dispatch(rt, elem);")
  sb.appendLine("                    if (_bridge_ptr != 0) {")
  sb.appendLine("                        JniBridgeDispatch *disp = (JniBridgeDispatch *)_bridge_ptr;")
  sb.appendLine("                        java_elem = disp->toJavaObject(env, rt, elem);")
  sb.appendLine("                    }")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // Default: leave as NULL (null, undefined, etc.)
  sb.appendLine("                default:")
  sb.appendLine("                    break;")
  sb.appendLine("            }")
  sb.appendLine()
  sb.appendLine("            env->SetObjectArrayElement(arr_${field.name}, i, java_elem);")
  sb.appendLine("            if (java_elem != NULL) env->DeleteLocalRef(java_elem);")
  sb.appendLine("            JS_FreeValue(ctx, elem);")
  sb.appendLine("        }")
  sb.appendLine("        $javaVar = arr_${field.name};")
}

/** Emit boxing extraction for a nullable primitive field (e.g. Int? → Integer). */
internal fun emitNullablePrimitiveExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val info = boxedPrimitiveInfo[field.ktType]!!
  val javaVar = "java_${field.name}"

  if (field.ktType == "kotlin.Long") {
    // Long needs tag-based extraction
    sb.appendLine("            int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
    sb.appendLine("            jlong longVal;")
    sb.appendLine("            if (tag_${field.name} == JS_TAG_FLOAT64) {")
    sb.appendLine("                longVal = (jlong)JS_VALUE_GET_FLOAT64(js_${field.name});")
    sb.appendLine("            } else {")
    sb.appendLine("                longVal = (jlong)JS_VALUE_GET_INT(js_${field.name});")
    sb.appendLine("            }")
    sb.appendLine("            $javaVar = env->NewObject(_boxed_${field.name}, _boxedCtor_${field.name}, longVal);")
  } else {
    sb.appendLine(
      "            $javaVar = env->NewObject(_boxed_${field.name}, _boxedCtor_${field.name}, ${info.jsCast}${info.jsGetter}(js_${field.name}));"
    )
  }
}

// -- Any? field extraction: delegate to jsi_value_to_boxed --

internal fun emitAnyFieldExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val javaVar = "java_${field.name}"
  sb.appendLine("        // Any? field — delegate to jsi_value_to_boxed")
  sb.appendLine("        $javaVar = jsi_value_to_boxed(env, rt, js_${field.name});")
}

// -- Collection field extraction (List, Set, Map from Kotlin stdlib) --

/** Emits extraction for a Kotlin/JS Map field (ES6 Map) into a java.util.HashMap. */
internal fun emitMapExtraction(sb: StringBuilder, field: FieldInfo) {
  val javaVar = "java_${field.name}"
  sb.appendLine("        // Map field — iterate entries via jsi_map_entries")
  sb.appendLine("        jsi::Array _entries_${field.name} = jsi_map_entries(rt, js_${field.name});")
  sb.appendLine("        jclass _hmc_${field.name} = env->FindClass(\"java/util/HashMap\");")
  sb.appendLine("        jmethodID _hmi_${field.name} = env->GetMethodID(_hmc_${field.name}, \"<init>\", \"()V\");")
  sb.appendLine("        jmethodID _hmp_${field.name} = env->GetMethodID(_hmc_${field.name}, \"put\", \"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;\");")
  sb.appendLine("        $javaVar = env->NewObject(_hmc_${field.name}, _hmi_${field.name});")
  sb.appendLine("        size_t _mlen_${field.name} = _entries_${field.name}.length(rt);")
  sb.appendLine("        for (size_t _mi_${field.name} = 0; _mi_${field.name} < _mlen_${field.name}; _mi_${field.name} += 2) {")
  sb.appendLine("            jsi::Value _mk_${field.name} = _entries_${field.name}.getValueAtIndex(rt, _mi_${field.name});")
  sb.appendLine("            jsi::Value _mv_${field.name} = _entries_${field.name}.getValueAtIndex(rt, _mi_${field.name} + 1);")
  // Generic key conversion.
  sb.appendLine("            jobject jk_${field.name} = jsi_value_to_boxed(env, rt, _mk_${field.name});")
  // Generic value conversion.
  sb.appendLine("            jobject jv_${field.name} = jsi_value_to_boxed(env, rt, _mv_${field.name});")
  sb.appendLine("            if (jk_${field.name} && jv_${field.name}) env->CallObjectMethod($javaVar, _hmp_${field.name}, jk_${field.name}, jv_${field.name});")
  sb.appendLine("            if (jk_${field.name}) env->DeleteLocalRef(jk_${field.name});")
  sb.appendLine("            if (jv_${field.name}) env->DeleteLocalRef(jv_${field.name});")
  sb.appendLine("        }")
}

internal fun emitCollectionExtraction(
  sb: StringBuilder,
  field: FieldInfo,
  jsClassName: String,
) {
  val javaVar = "java_${field.name}"
  // For now, create an empty ArrayList for JS arrays.
  // Full element conversion requires per-element dispatch which is tracked separately.
  sb.appendLine("        // Collection field — check if JS array, create empty ArrayList")
  sb.appendLine("        // Kotlin/JS ArrayList stores its backing JS array in a name-mangled 'array_1' property.")
  sb.appendLine("        if (!JS_IsArray(ctx, js_${field.name})) {")
  sb.appendLine("            JSValue _arr_${field.name} = JS_GetPropertyStr(ctx, js_${field.name}, \"array_1\");")
  sb.appendLine("            if (!JS_IsUndefined(_arr_${field.name})) {")
  sb.appendLine("                JS_FreeValue(ctx, js_${field.name});")
  sb.appendLine("                js_${field.name} = JS_DupValue(ctx, _arr_${field.name});")
  sb.appendLine("            } else {")
  sb.appendLine("                JS_FreeValue(ctx, _arr_${field.name});")
  sb.appendLine("            }")
  sb.appendLine("        }")
  sb.appendLine("        if (JS_IsArray(ctx, js_${field.name})) {")
  sb.appendLine("            jclass alc = env->FindClass(\"java/util/ArrayList\");")
  sb.appendLine("            jmethodID alc_init = env->GetMethodID(alc, \"<init>\", \"()V\");")
  sb.appendLine("            jmethodID alc_add = env->GetMethodID(alc, \"add\", \"(Ljava/lang/Object;)Z\");")
  sb.appendLine("            $javaVar = env->NewObject(alc, alc_init);")
  // Iterate array elements and convert via bridge_dispatch
  sb.appendLine("            JSValue _len_${field.name} = JS_GetPropertyStr(ctx, js_${field.name}, \"length\");")
  sb.appendLine("            if (!JS_IsUndefined(_len_${field.name})) {")
  sb.appendLine("                int _n_${field.name} = JS_VALUE_GET_INT(_len_${field.name});")
  sb.appendLine("                JS_FreeValue(ctx, _len_${field.name});")
  sb.appendLine("                for (int _ei_${field.name} = 0; _ei_${field.name} < _n_${field.name}; _ei_${field.name}++) {")
  sb.appendLine("                    JSValue _elem_${field.name} = JS_GetPropertyUint32(ctx, js_${field.name}, _ei_${field.name});")
  sb.appendLine("                    if (JS_IsUndefined(_elem_${field.name})) {")
  sb.appendLine("                        JS_FreeValue(ctx, _elem_${field.name});")
  sb.appendLine("                        continue;")
  sb.appendLine("                    }")
  sb.appendLine("                    intptr_t _edp_raw_${field.name} = jsi_get_bridge_dispatch(rt, _elem_${field.name});")
  sb.appendLine("                    if (_edp_raw_${field.name} != 0) {")
  sb.appendLine("                        JniBridgeDispatch *_edp_${field.name} = (JniBridgeDispatch *)_edp_raw_${field.name};")
  sb.appendLine("                        jobject _ejava_${field.name} = _edp_${field.name}->toJavaObject(env, rt, _elem_${field.name});")
  sb.appendLine("                        env->CallBooleanMethod($javaVar, alc_add, _ejava_${field.name});")
  sb.appendLine("                        if (_ejava_${field.name}) env->DeleteLocalRef(_ejava_${field.name});")
  sb.appendLine("                    } else {")
  // Try primitive boxing
  sb.appendLine("                        int _etag_${field.name} = JS_VALUE_GET_NORM_TAG(_elem_${field.name});")
  if (field.arrayElementType != null) {
    val elemType = field.arrayElementType!!
    when {
      elemType == "kotlin.Float" || elemType == "kotlin.Double" -> {
        sb.appendLine("                        if (_etag_${field.name} == JS_TAG_FLOAT64 || _etag_${field.name} == JS_TAG_INT) {")
        sb.appendLine("                            jclass _ecls_${field.name} = env->FindClass(\"java/lang/${if (elemType == "kotlin.Float") "Float" else "Double"}\");")
        sb.appendLine("                            jmethodID _ector_${field.name} = env->GetMethodID(_ecls_${field.name}, \"<init>\", \"(${if (elemType == "kotlin.Float") "F" else "D"})V\");")
        sb.appendLine("                            ${if (elemType == "kotlin.Float") "jfloat" else "jdouble"} _eval_${field.name} = _etag_${field.name} == JS_TAG_FLOAT64 ? (${if (elemType == "kotlin.Float") "jfloat" else "jdouble"})JS_VALUE_GET_FLOAT64(_elem_${field.name}) : (${if (elemType == "kotlin.Float") "jfloat" else "jdouble"})JS_VALUE_GET_INT(_elem_${field.name});")
        sb.appendLine("                            jobject _ejava_${field.name} = env->NewObject(_ecls_${field.name}, _ector_${field.name}, _eval_${field.name});")
        sb.appendLine("                            env->CallBooleanMethod($javaVar, alc_add, _ejava_${field.name});")
        sb.appendLine("                            env->DeleteLocalRef(_ejava_${field.name});")
        sb.appendLine("                        }")
      }
      elemType == "kotlin.Int" || elemType == "kotlin.Long" -> {
        sb.appendLine("                        if (_etag_${field.name} == JS_TAG_INT || _etag_${field.name} == JS_TAG_FLOAT64) {")
        sb.appendLine("                            jclass _ecls_${field.name} = env->FindClass(\"java/lang/${if (elemType == "kotlin.Int") "Integer" else "Long"}\");")
        sb.appendLine("                            jmethodID _ector_${field.name} = env->GetMethodID(_ecls_${field.name}, \"<init>\", \"(${if (elemType == "kotlin.Int") "I" else "J"})V\");")
        sb.appendLine("                            ${if (elemType == "kotlin.Int") "jint" else "jlong"} _eval_${field.name} = _etag_${field.name} == JS_TAG_INT ? (${if (elemType == "kotlin.Int") "jint" else "jlong"})JS_VALUE_GET_INT(_elem_${field.name}) : (${if (elemType == "kotlin.Int") "jint" else "jlong"})JS_VALUE_GET_FLOAT64(_elem_${field.name});")
        sb.appendLine("                            jobject _ejava_${field.name} = env->NewObject(_ecls_${field.name}, _ector_${field.name}, _eval_${field.name});")
        sb.appendLine("                            env->CallBooleanMethod($javaVar, alc_add, _ejava_${field.name});")
        sb.appendLine("                            env->DeleteLocalRef(_ejava_${field.name});")
        sb.appendLine("                        }")
      }
      elemType == "kotlin.Boolean" -> {
        sb.appendLine("                        if (_etag_${field.name} == JS_TAG_BOOL) {")
        sb.appendLine("                            jclass _ecls_${field.name} = env->FindClass(\"java/lang/Boolean\");")
        sb.appendLine("                            jmethodID _ector_${field.name} = env->GetMethodID(_ecls_${field.name}, \"<init>\", \"(Z)V\");")
        sb.appendLine("                            jboolean _eval_${field.name} = JS_VALUE_GET_BOOL(_elem_${field.name});")
        sb.appendLine("                            jobject _ejava_${field.name} = env->NewObject(_ecls_${field.name}, _ector_${field.name}, _eval_${field.name});")
        sb.appendLine("                            env->CallBooleanMethod($javaVar, alc_add, _ejava_${field.name});")
        sb.appendLine("                            env->DeleteLocalRef(_ejava_${field.name});")
        sb.appendLine("                        }")
      }
      elemType == "kotlin.String" -> {
        sb.appendLine("                        if (_etag_${field.name} == JS_TAG_STRING) {")
        sb.appendLine("                            const char *_estr_${field.name} = JS_ToCString(ctx, _elem_${field.name});")
        sb.appendLine("                            jobject _ejava_${field.name} = env->NewStringUTF(_estr_${field.name});")
        sb.appendLine("                            JS_FreeCString(ctx, _estr_${field.name});")
        sb.appendLine("                            env->CallBooleanMethod($javaVar, alc_add, _ejava_${field.name});")
        sb.appendLine("                            env->DeleteLocalRef(_ejava_${field.name});")
        sb.appendLine("                        }")
      }
    }
  } else {
      sb.appendLine("                        // Generic element (erased to Any): box via jsi_value_to_boxed.")
      sb.appendLine("                        jobject _ejava_${field.name} = jsi_value_to_boxed(env, rt, _elem_${field.name});")
      sb.appendLine("                        if (_ejava_${field.name}) {")
      sb.appendLine("                            env->CallBooleanMethod($javaVar, alc_add, _ejava_${field.name});")
      sb.appendLine("                            env->DeleteLocalRef(_ejava_${field.name});")
      sb.appendLine("                        }")
  }
  sb.appendLine("                    }")
  sb.appendLine("                    JS_FreeValue(ctx, _elem_${field.name});")
  sb.appendLine("                }")
  sb.appendLine("            } else {")
  sb.appendLine("                JS_FreeValue(ctx, _len_${field.name});")
  sb.appendLine("            }")
  sb.appendLine("        } else {")
  sb.appendLine("            // Unexpected: try bridge_dispatch as fallback")
  sb.appendLine("            intptr_t _cold_bridge_ptr_${field.name} = jsi_get_bridge_dispatch(rt, js_${field.name});")
  sb.appendLine("            if (_cold_bridge_ptr_${field.name} != 0) {")
  sb.appendLine("                JniBridgeDispatch *coldisp_p_${field.name} = (JniBridgeDispatch *)_cold_bridge_ptr_${field.name};")
  sb.appendLine("                $javaVar = coldisp_p_${field.name}->toJavaObject(env, rt, js_${field.name});")
  sb.appendLine("            } else {")
  sb.appendLine("#ifdef __ANDROID__")
  sb.appendLine("                __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"Collection field ${field.name}: not an array and no bridge_dispatch, returning NULL\");")
  sb.appendLine("#endif")
  sb.appendLine("                $javaVar = NULL;")
  sb.appendLine("            }")
  sb.appendLine("        }")
}

// -- C bridge orchestration --

/** Generate all per-class C bridge files (each self-registers via constructor). */
internal fun generateCBridges(outputDir: String, annotatedClasses: List<IrClass>) {
  for (clazz in annotatedClasses) {
    // Interfaces have no JS constructor to export; skip them.
    if (clazz.kind == ClassKind.INTERFACE) continue
    generateBridgeFile(outputDir, clazz)
  }
}

// -- field extraction --

