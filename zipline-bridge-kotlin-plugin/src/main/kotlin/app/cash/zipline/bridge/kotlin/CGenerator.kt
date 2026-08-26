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
      append(helpers)
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
            appendLine("        $javaVar = (jbyte)jsi_value_get_int(js_${field.name});")
          }
          field.effectiveKtType == "kotlin.Short" -> {
            appendLine("        $javaVar = (jshort)jsi_value_get_int(js_${field.name});")
          }
          field.effectiveKtType == "kotlin.Int" -> {
            appendLine("        $javaVar = (jint)jsi_value_get_int(js_${field.name});")
          }
          field.effectiveKtType == "kotlin.Long" -> {
            appendLine("        int tag_${field.name} = jsi_value_tag(rt, js_${field.name});")
            appendLine("        if (tag_${field.name} == JS_TAG_FLOAT64) {")
            appendLine("            $javaVar = (jlong)jsi_value_get_float64(js_${field.name});")
            appendLine("        } else if (tag_${field.name} == JS_TAG_INT) {")
            appendLine("            $javaVar = (jlong)jsi_value_get_int(js_${field.name});")
            appendLine("        } else if (tag_${field.name} == JS_TAG_OBJECT) {")
            appendLine("            /* Kotlin/JS Long: {low_1, high_1} packed representation */")
            appendLine("            jsi::Value lowVal = jsi_get_property(rt, js_${field.name}, \"low_1\");")
            appendLine("            jsi::Value highVal = jsi_get_property(rt, js_${field.name}, \"high_1\");")
            appendLine("            jint low = jsi_value_get_int(lowVal);")
            appendLine("            jint high = jsi_value_get_int(highVal);")
            appendLine("            $javaVar = ((jlong)high << 32) | ((jlong)low & 0xFFFFFFFF);")
            appendLine("        } else {")
            appendLine("#ifdef __ANDROID__")
            appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Long field ${field.name}: unexpected JS tag %d\", tag_${field.name});")
            appendLine("#endif")
            appendLine("            $javaVar = 0;")
            appendLine("        }")
          }
          field.effectiveKtType == "kotlin.Float" || field.effectiveKtType == "kotlin.Double" -> {
            val cast = if (field.effectiveKtType == "kotlin.Float") "(jfloat)" else "(jdouble)"
            appendLine("        int tag_${field.name}_d = jsi_value_tag(rt, js_${field.name});")
            appendLine("        if (tag_${field.name}_d == JS_TAG_FLOAT64) {")
            appendLine("            $javaVar = ${cast}jsi_value_get_float64(js_${field.name});")
            appendLine("        } else if (tag_${field.name}_d == JS_TAG_INT) {")
            appendLine("            $javaVar = ${cast}jsi_value_get_int(js_${field.name});")
            appendLine("        } else {")
            appendLine("#ifdef __ANDROID__")
            appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Float/Double field ${field.name}: unexpected JS tag %d\", tag_${field.name}_d);")
            appendLine("#endif")
            appendLine("            /* JS_TAG_UNDEFINED=3, JS_TAG_NULL=2 */")
            appendLine("            $javaVar = 0;")
            appendLine("        }")
          }
          field.effectiveKtType == "kotlin.Char" -> {
            appendLine("        $javaVar = (jchar)jsi_value_get_int(js_${field.name});")
          }
          field.effectiveKtType == "kotlin.String" -> {
            appendLine("        std::string str_${field.name} = js_${field.name}.asString(rt).utf8(rt);")
            appendLine("        $javaVar = env->NewStringUTF(str_${field.name}.c_str());")
          }
          field.effectiveKtType in MAP_C_TYPES ||
            field.effectiveKtType == "kotlin.collections.List" ||
            field.effectiveKtType == "kotlin.collections.MutableList" ||
            field.isArray -> {
            appendLine("        $javaVar = conv_${field.name}(env, rt, js_${field.name});")
          }
          field.effectiveKtType == "kotlin.Any" -> {
            emitAnyFieldExtraction(this, field)
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
            appendLine("            std::string dbgCtorNm_${field.name} = \"(unknown)\";")
            appendLine("            jsi::Value dbgCtor_${field.name} = jsi_get_property(rt, js_${field.name}, \"constructor\");")
            appendLine("            if (!dbgCtor_${field.name}.isUndefined() && !dbgCtor_${field.name}.isNull()) {")
            appendLine("                jsi::Value dbgCtorNmVal_${field.name} = jsi_get_property(rt, dbgCtor_${field.name}, \"name\");")
            appendLine("                dbgCtorNm_${field.name} = dbgCtorNmVal_${field.name}.asString(rt).utf8(rt);")
            appendLine("            }")
            appendLine("            __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"bridge_dispatch not found for field ${field.name} (ctor=%s)\", dbgCtorNm_${field.name}.c_str());")
            appendLine("#endif")
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
      appendLine("        int tag_v = jsi_value_tag(rt, jsVal);")
      appendLine("        if (tag_v == JS_TAG_FLOAT64) {")
      appendLine("            java_v = ($underlyingCType)jsi_value_get_float64(jsVal);")
      appendLine("        } else if (tag_v == JS_TAG_INT) {")
      appendLine("            java_v = ($underlyingCType)jsi_value_get_int(jsVal);")
      if (underlyingField.effectiveKtType == "kotlin.Long") {
        appendLine("        } else if (tag_v == JS_TAG_OBJECT) {")
        appendLine("            /* Kotlin/JS Long: {low_1, high_1} packed representation */")
        appendLine("            jsi::Value lv = jsi_get_property(rt, jsVal, \"low_1\");")
        appendLine("            jsi::Value hv = jsi_get_property(rt, jsVal, \"high_1\");")
        appendLine("            java_v = (($underlyingCType)jsi_value_get_int(hv) << 32) | (($underlyingCType)jsi_value_get_int(lv) & 0xFFFFFFFF);")
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
          std::string s = jsVal.asString(rt).utf8(rt);
          jobject r = env->NewStringUTF(s.c_str());
          return r;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Int" -> emitCBoxedConverter(helpers, name, "java/lang/Integer", "(I)V", "jsi_value_get_int(jsVal)")
    "kotlin.Float" -> emitCBoxedConverter(helpers, name, "java/lang/Float", "(F)V", "jsi_value_get_float64(jsVal)")
    "kotlin.Double" -> emitCBoxedConverter(helpers, name, "java/lang/Double", "(D)V", "jsi_value_get_float64(jsVal)")
    "kotlin.Boolean" -> emitCBoxedConverter(helpers, name, "java/lang/Boolean", "(Z)V", "jsi_value_get_bool(jsVal)")
    "kotlin.Byte" -> emitCBoxedConverter(helpers, name, "java/lang/Byte", "(B)V", "(jbyte)jsi_value_get_int(jsVal)")
    "kotlin.Short" -> emitCBoxedConverter(helpers, name, "java/lang/Short", "(S)V", "(jshort)jsi_value_get_int(jsVal)")
    "kotlin.Char" -> emitCBoxedConverter(helpers, name, "java/lang/Character", "(C)V", "(jchar)jsi_value_get_int(jsVal)")
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
          jsi::Value entriesFn = jsi_find_method(rt, jsVal, "get_entries_");
          if (entriesFn.isUndefined() || !entriesFn.isObject()) return result;
          jsi::Value entries = entriesFn.asObject(rt).asFunction(rt).callWithThis(rt, jsVal.asObject(rt));
          if (!entries.isObject()) return result;
          jsi::Value iterFn = jsi_find_method(rt, entries, "iterator_");
          if (iterFn.isUndefined() || !iterFn.isObject()) return result;
          jsi::Value iterator = iterFn.asObject(rt).asFunction(rt).callWithThis(rt, entries.asObject(rt));
          if (!iterator.isObject()) return result;
          jsi::Value hasNextFn = jsi_find_method(rt, iterator, "hasNext_");
          jsi::Value nextFn = jsi_find_method(rt, iterator, "next_");
          if (hasNextFn.isUndefined() || nextFn.isUndefined()) return result;
          while (true) {
            jsi::Value hn = hasNextFn.asObject(rt).asFunction(rt).callWithThis(rt, iterator.asObject(rt));
            if (!hn.isBool() || !hn.asBool()) break;
            jsi::Value entry = nextFn.asObject(rt).asFunction(rt).callWithThis(rt, iterator.asObject(rt));
            if (!entry.isObject()) break;
            jsi::Value keyFn = jsi_find_method(rt, entry, "get_key_");
            jsi::Value valueFn = jsi_find_method(rt, entry, "get_value_");
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

/** Emit boxing extraction for a nullable primitive field (e.g. Int? → Integer). */
internal fun emitNullablePrimitiveExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val info = boxedPrimitiveInfo[field.ktType]!!
  val javaVar = "java_${field.name}"

  if (field.ktType == "kotlin.Long") {
    // Long needs tag-based extraction
    sb.appendLine("            int tag_${field.name} = jsi_value_tag(rt, js_${field.name});")
    sb.appendLine("            jlong longVal;")
    sb.appendLine("            if (tag_${field.name} == JS_TAG_FLOAT64) {")
    sb.appendLine("                longVal = (jlong)jsi_value_get_float64(js_${field.name});")
    sb.appendLine("            } else {")
    sb.appendLine("                longVal = (jlong)jsi_value_get_int(js_${field.name});")
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

// -- C bridge orchestration --

/** Generate all per-class C bridge files (each self-registers via constructor). */
internal fun generateCBridges(outputDir: String, annotatedClasses: List<IrClass>) {
  for (clazz in annotatedClasses) {
    // Interfaces have no JS constructor to export; skip them.
    if (clazz.kind == ClassKind.INTERFACE) continue
    generateBridgeFile(outputDir, clazz)
  }
}

