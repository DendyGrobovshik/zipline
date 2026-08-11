package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.name.FqName
import java.io.File

// -- C file naming and JNI class name helpers --

internal fun cFunctionPrefix(fqName: FqName): String =
  fqName.asString().replace(".", "_")

internal fun cFileName(fqName: FqName): String =
  cFunctionPrefix(fqName) + ".c"

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
  val classPart = classSegments.joinToString("$")

  return if (packagePart.isEmpty()) classPart else "$packagePart/$classPart"
}

// -- C/JNI bridge code generation (Android .so) --

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
  val constructorSig = if (isObject || constructorFields.isEmpty()) "()V"
    else "(" + constructorFields.joinToString("") { it.jniTypeChar } + ")V"
  val instanceSig = if (isObject) "L${jniClassName.replace(".", "/")};" else ""

  val cSource = buildString {
    appendLine("// GENERATED FILE. DO NOT MODIFY MANUALLY.")
    appendLine()
    appendLine("#include <jni.h>")
    appendLine("#include \"quickjs/quickjs.h\"")
    appendLine("#include \"bridge_dispatch.h\"")
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
        appendLine("extern jobject ${inlinePrefix}_fromValue(JNIEnv *env, JSContext *ctx, JSValue jsVal);")
      }
      appendLine()
    }

    // -- cached JNI references (initialized once by _init, used by _toJavaObject) --
    appendLine("static jclass _cls = NULL;")
    if (!isObject) appendLine("static jmethodID _ctor = NULL;")
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
    for (f in bodyFields) {
      appendLine("static jfieldID _fld_${f.name} = NULL;")
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
    appendLine("    jclass local = (*env)->FindClass(env, \"$jniClassName\");")
    appendLine("    if ((*env)->ExceptionCheck(env)) {")
    appendLine("#ifdef __ANDROID__")
    appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_init FAILED: FindClass for $jniClassName\");")
    appendLine("#endif")
    appendLine("        (*env)->ExceptionClear(env);")
    appendLine("        return;")
    appendLine("    }")
    appendLine("    _cls = (*env)->NewGlobalRef(env, local);")
    if (!isObject) {
      appendLine("    _ctor = (*env)->GetMethodID(env, _cls, \"<init>\", \"$constructorSig\");")
      appendLine("    if ((*env)->ExceptionCheck(env)) {")
      appendLine("        (*env)->ExceptionClear(env);")
      appendLine("        _ctor = NULL;")
      appendLine("    }")
    }
    if (isCompanion) {
      val outerJni = buildJniClassName(annotatedClass.parent as IrClass)
      appendLine("    {")
      appendLine("        jclass outerLocal = (*env)->FindClass(env, \"$outerJni\");")
      appendLine("        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }")
      appendLine("        _outerCls = (*env)->NewGlobalRef(env, outerLocal);")
      appendLine("        _companionField = (*env)->GetStaticFieldID(env, _outerCls, \"Companion\", \"$instanceSig\");")
      appendLine("    }")
    } else if (isObject) {
      appendLine("    _instField = (*env)->GetStaticFieldID(env, _cls, \"INSTANCE\", \"$instanceSig\");")
    }
    for (f in nullablePrimitiveFields) {
      val info = boxedPrimitiveInfo[f.ktType]!!
      appendLine("    {")
      appendLine("        jclass boxed = (*env)->FindClass(env, \"${info.wrapperClass}\");")
      appendLine("        _boxed_${f.name} = (*env)->NewGlobalRef(env, boxed);")
      appendLine("        _boxedCtor_${f.name} = (*env)->GetMethodID(env, _boxed_${f.name}, \"<init>\", \"${info.ctorSig}\");")
      appendLine("    }")
    }
    if (hasAnyField || hasCollectionField) {
      appendLine("    // Init boxed type refs for Any? value dispatch")
      appendLine("    if (_any_boxed_Integer_cls == NULL) {")
      appendLine("        jclass intLocal = (*env)->FindClass(env, \"java/lang/Integer\");")
      appendLine("        _any_boxed_Integer_cls = (*env)->NewGlobalRef(env, intLocal);")
      appendLine("        _any_boxed_Integer_ctor = (*env)->GetMethodID(env, _any_boxed_Integer_cls, \"<init>\", \"(I)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Double_cls == NULL) {")
      appendLine("        jclass dblLocal = (*env)->FindClass(env, \"java/lang/Double\");")
      appendLine("        _any_boxed_Double_cls = (*env)->NewGlobalRef(env, dblLocal);")
      appendLine("        _any_boxed_Double_ctor = (*env)->GetMethodID(env, _any_boxed_Double_cls, \"<init>\", \"(D)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Boolean_cls == NULL) {")
      appendLine("        jclass boolLocal = (*env)->FindClass(env, \"java/lang/Boolean\");")
      appendLine("        _any_boxed_Boolean_cls = (*env)->NewGlobalRef(env, boolLocal);")
      appendLine("        _any_boxed_Boolean_ctor = (*env)->GetMethodID(env, _any_boxed_Boolean_cls, \"<init>\", \"(Z)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Float_cls == NULL) {")
      appendLine("        jclass fltLocal = (*env)->FindClass(env, \"java/lang/Float\");")
      appendLine("        _any_boxed_Float_cls = (*env)->NewGlobalRef(env, fltLocal);")
      appendLine("        _any_boxed_Float_ctor = (*env)->GetMethodID(env, _any_boxed_Float_cls, \"<init>\", \"(F)V\");")
      appendLine("    }")
    }
    for (f in bodyFields) {
      appendLine("    _fld_${f.name} = (*env)->GetFieldID(env, _cls, \"${f.name}\", \"${f.jniFieldType}\");")
      appendLine("    if ((*env)->ExceptionCheck(env)) {")
      appendLine("        (*env)->ExceptionClear(env);")
      appendLine("        _fld_${f.name} = NULL;")
      appendLine("    }")
    }
    appendLine("}")
    appendLine()

    // -- toJavaObject function --
    // Recursive collection/array/map converters are emitted before the converter that calls them.
    val helpers = StringBuilder()
    for (field in fields) {
      val kt = field.effectiveKtType
      if (kt == "kotlin.collections.List" || kt in MAP_C_TYPES || kt == "kotlin.Array" || kt in PRIMITIVE_ARRAY_ELEMENT_TYPE) {
        emitCValueConverter(helpers, "conv_${field.name}", kt, field.type)
      }
    }
    if (helpers.isNotEmpty()) {
      val withFindMethod = StringBuilder()
      emitCBridgeFindMethod(withFindMethod)
      withFindMethod.append(helpers)
      append(withFindMethod)
      appendLine()
    }

    appendLine("static jobject ${functionPrefix}_toJavaObject(JNIEnv *env, JSContext *ctx, JSValue jsObj) {")
    if (!isObject) {
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

    if (fields.isNotEmpty()) {
      appendLine("    // Extract field values from JS object")
    }

    // Extract each field from the JS object
    for (field in fields) {
      val nullablePrimitive = field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType)
      // Non-nullable inline value classes are erased to their underlying JNI primitive on JVM,
      // so dispatch on effectiveKtType (unwrapped) for the primitive branches.
      val cType = if (nullablePrimitive) "jobject"
        else kotlinToCType[field.effectiveKtType] ?: "jobject"
      // JVM value for the field
      val javaVar = "java_${field.name}"

      appendLine("    JSValue js_${field.name} = JS_GetPropertyStr(ctx, jsObj, \"${field.jsPropertyName}\");")
      // For Long-backed inline classes (e.g. Color), the JS object may be unboxed —
      // jsObj IS the Long {low_1, high_1} with no .value wrapper. If .value is
      // undefined, fall back to using jsObj directly as the Long representation.
      if (field.ktType == "kotlin.Long" && isInlineClass(annotatedClass)) {
        appendLine("    if (JS_IsUndefined(js_${field.name})) {")
        appendLine("        JS_FreeValue(ctx, js_${field.name});")
        appendLine("        js_${field.name} = JS_DupValue(ctx, jsObj);")
        appendLine("    }")
      }
      // Inline value classes (e.g. @JvmInline value class Id(val value: Int))
      // may be boxed ({value: ...}) or unboxed (plain int) in Kotlin/JS depending
      // on context. We check the tag: if it's an object, unwrap .value; otherwise use as-is.
      // EXCEPTION: Long-backed inline classes (e.g. Color) — Long is already a JS
      // object {low_1, high_1} in Kotlin/JS, so the object IS the value, not a wrapper.
      if (field.isInline && field.underlyingKtType != "kotlin.Long") {
        appendLine("    if (JS_VALUE_GET_NORM_TAG(js_${field.name}) == JS_TAG_OBJECT) {")
        appendLine("        JSValue _inner = JS_GetPropertyStr(ctx, js_${field.name}, \"value\");")
        appendLine("        JS_FreeValue(ctx, js_${field.name});")
        appendLine("        js_${field.name} = _inner;")
        appendLine("    }")
      }
      appendLine("    $cType $javaVar;")
      appendLine("    {")

      // Open null check for nullable fields
      if (field.isNullable) {
        appendLine("        if (!JS_IsUndefined(js_${field.name}) && !JS_IsNull(js_${field.name})) {")
      }

      when {
        field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> {
          emitNullablePrimitiveExtraction(this, field)
        }
        field.effectiveKtType == "kotlin.Boolean" -> {
          appendLine("        $javaVar = (jboolean)JS_VALUE_GET_BOOL(js_${field.name});")
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
          appendLine("        $javaVar = (*env)->NewStringUTF(env, str_${field.name});")
          appendLine("        JS_FreeCString(ctx, str_${field.name});")
        }
        field.effectiveKtType == "kotlin.collections.List" -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.effectiveKtType in MAP_C_TYPES -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.effectiveKtType == "kotlin.Any" -> {
          emitAnyFieldExtraction(this, field)
        }
        field.effectiveKtType == "kotlin.Array" -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.isArray -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.isInline && field.isNullable -> {
          val inlineCPrefix = cFunctionPrefix(FqName(field.ktType))
          appendLine("        $javaVar = ${inlineCPrefix}_fromValue(env, ctx, js_${field.name});")
        }
        field.isObjectType -> {
          appendLine("        // Look up bridge_dispatch on the sub-object to convert it.")
          appendLine("        JSValue disp_val_${field.name} = JS_GetPropertyStr(ctx, js_${field.name}, \"bridge_dispatch\");")
          appendLine("        if (JS_IsUndefined(disp_val_${field.name})) {")
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
          appendLine("            JS_FreeValue(ctx, disp_val_${field.name});")
          appendLine("            JS_FreeValue(ctx, js_${field.name});")
          appendLine("            return NULL;")
          appendLine("        }")
          appendLine("        // TODO: when pointer compression lands, use JS_VALUE_GET_INT(disp_val_...) to read arena offset")
          appendLine("        BridgeConverterFn disp_${field.name} = bridgeConverterFromJSValue(disp_val_${field.name});")
          appendLine("        $javaVar = disp_${field.name}(env, ctx, js_${field.name});")
          appendLine("        JS_FreeValue(ctx, disp_val_${field.name});")
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
      appendLine("    jobject result = (*env)->GetStaticObjectField(env, _outerCls, _companionField);")
    } else if (isObject) {
      appendLine("    jobject result = (*env)->GetStaticObjectField(env, _cls, _instField);")
    } else {
      if (constructorFields.isEmpty()) {
        appendLine("    jobject result = (*env)->NewObject(env, _cls, _ctor);")
      } else {
        // `java_${it.name}` is what used to be called `javaVar` in the field-extracting loop.
        val args = constructorFields.joinToString(", ") { "java_${it.name}" }
        appendLine("    jobject result = (*env)->NewObject(env, _cls, _ctor, $args);")
      }
    }
    appendLine("    if ((*env)->ExceptionCheck(env)) return NULL;")
    appendLine()

    // Set body fields using cached field IDs
    if (bodyFields.isNotEmpty()) {
      appendLine("    // Set non-constructor fields")
      for (field in bodyFields) {
        val javaVar = "java_${field.name}"  // Same var name as in the field-extracting loop
        val setFn = when {
          field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> "SetObjectField"
          else -> kotlinToSetFieldFunction[field.ktType] ?: "SetObjectField"
        }
        appendLine("    if (_fld_${field.name} != NULL) {")
        appendLine("        (*env)->$setFn(env, result, _fld_${field.name}, $javaVar);")
        appendLine("    }")
      }
      appendLine()
    }

    appendLine("    return result;")
    appendLine("}")

    if (isInlineClass(annotatedClass) && !isObject && constructorFields.size == 1) {
      val underlyingField = constructorFields[0]
      // The underlying type may itself be an inline class (e.g. SpaceArrangement(val spacing: Dp));
      // use effectiveKtType to reach the primitive C type.
      val underlyingCType = kotlinToCType[underlyingField.effectiveKtType] ?: "jobject"
      appendLine()
      appendLine("jobject ${functionPrefix}_fromValue(JNIEnv *env, JSContext *ctx, JSValue jsVal) {")
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
      appendLine("    jobject result = (*env)->NewObject(env, _cls, _ctor, java_v);")
      appendLine("    if ((*env)->ExceptionCheck(env)) return NULL;")
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

/** Emit the common array preamble: length check (JS_IsArray rejects typed arrays like Int32Array). */
internal fun emitArrayPreamble(sb: StringBuilder, jsVar: String) {
  sb.appendLine("        JSValue _arr_len_check = JS_GetPropertyStr(ctx, $jsVar, \"length\");")
  sb.appendLine("        if (JS_IsUndefined(_arr_len_check)) {")
  sb.appendLine("            jclass iae = (*env)->FindClass(env, \"java/lang/IllegalArgumentException\");")
  sb.appendLine("            if (iae != NULL) (*env)->ThrowNew(env, iae, \"Expected an array\");")
  sb.appendLine("            JS_FreeValue(ctx, _arr_len_check);")
  sb.appendLine("            JS_FreeValue(ctx, $jsVar);")
  sb.appendLine("            return NULL;")
  sb.appendLine("        }")
  sb.appendLine("        JS_FreeValue(ctx, _arr_len_check);")
  sb.appendLine("        JSValue js_len_${jsVar} = JS_GetPropertyStr(ctx, $jsVar, \"length\");")
  sb.appendLine("        jint len_${jsVar} = (jint)JS_VALUE_GET_INT(js_len_${jsVar});")
  sb.appendLine("        JS_FreeValue(ctx, js_len_${jsVar});")
}

/** Emit loop for a primitive specialized array (IntArray, FloatArray, etc.). */
internal fun emitPrimitiveArrayLoop(
  sb: StringBuilder,
  field: FieldInfo,
  javaVar: String,
  jsVar: String,
) {
  val info = primitiveArrayJniInfo[field.ktType]!!

  sb.appendLine("        ${field.ktType.let { kotlinToCType[it] }} arr_${field.name} = (*env)->${info.newArrayFn}(env, len_${jsVar});")
  sb.appendLine("        ${info.jniElementType} *elems_${field.name} = (*env)->${info.getElementsFn}(env, arr_${field.name}, NULL);")
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
  sb.appendLine("        (*env)->${info.releaseElementsFn}(env, arr_${field.name}, elems_${field.name}, 0);")
  sb.appendLine("        $javaVar = arr_${field.name};")
}

/** Emit loop for Array<String> (or Array<String?>). */
internal fun emitStringArrayLoop(
  sb: StringBuilder,
  field: FieldInfo,
  javaVar: String,
  jsVar: String,
) {
  sb.appendLine("        jclass strClass_${field.name} = (*env)->FindClass(env, \"java/lang/String\");")
  sb.appendLine("        jobjectArray arr_${field.name} = (*env)->NewObjectArray(env, len_${jsVar}, strClass_${field.name}, NULL);")
  sb.appendLine("        for (jint i = 0; i < len_${jsVar}; i++) {")
  sb.appendLine("            JSValue elem = JS_GetPropertyUint32(ctx, $jsVar, i);")
  sb.appendLine("            jobject java_elem = NULL;")
  if (field.arrayElementNullable) {
    sb.appendLine("            if (!JS_IsUndefined(elem) && !JS_IsNull(elem)) {")
  }
  sb.appendLine("            const char *str = JS_ToCString(ctx, elem);")
  sb.appendLine("            java_elem = (*env)->NewStringUTF(env, str);")
  sb.appendLine("            JS_FreeCString(ctx, str);")
  if (field.arrayElementNullable) {
    sb.appendLine("            }")
  }
  sb.appendLine("            (*env)->SetObjectArrayElement(env, arr_${field.name}, i, java_elem);")
  sb.appendLine("            if (java_elem != NULL) (*env)->DeleteLocalRef(env, java_elem);")
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
  sb.appendLine("        jclass objClass_${field.name} = (*env)->FindClass(env, \"java/lang/Object\");")
  for ((ktType, info) in boxedPrimitiveInfo) {
    val shortName = ktType.substringAfterLast('.')
    sb.appendLine("        jclass cls_${field.name}_${shortName} = (*env)->FindClass(env, \"${info.wrapperClass}\");")
    sb.appendLine("        jmethodID ctor_${field.name}_${shortName} = (*env)->GetMethodID(env, cls_${field.name}_${shortName}, \"<init>\", \"${info.ctorSig}\");")
  }

  sb.appendLine()
  sb.appendLine("        jobjectArray arr_${field.name} = (*env)->NewObjectArray(env, len_${jsVar}, objClass_${field.name}, NULL);")
  sb.appendLine("        for (jint i = 0; i < len_${jsVar}; i++) {")
  sb.appendLine("            JSValue elem = JS_GetPropertyUint32(ctx, $jsVar, i);")
  sb.appendLine("            int tag = JS_VALUE_GET_NORM_TAG(elem);")
  sb.appendLine("            jobject java_elem = NULL;")
  sb.appendLine()
  sb.appendLine("            switch (tag) {")

  // JS_TAG_INT → Integer
  sb.appendLine("                case JS_TAG_INT: {")
  sb.appendLine("                    java_elem = (*env)->NewObject(env, cls_${field.name}_Int, ctor_${field.name}_Int, (jint)JS_VALUE_GET_INT(elem));")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_FLOAT64 → Double
  sb.appendLine("                case JS_TAG_FLOAT64: {")
  sb.appendLine("                    java_elem = (*env)->NewObject(env, cls_${field.name}_Double, ctor_${field.name}_Double, JS_VALUE_GET_FLOAT64(elem));")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_BOOL → Boolean
  sb.appendLine("                case JS_TAG_BOOL: {")
  sb.appendLine("                    java_elem = (*env)->NewObject(env, cls_${field.name}_Boolean, ctor_${field.name}_Boolean, (jboolean)JS_VALUE_GET_BOOL(elem));")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_STRING → java.lang.String
  sb.appendLine("                case JS_TAG_STRING: {")
  sb.appendLine("                    const char *str_${field.name} = JS_ToCString(ctx, elem);")
  sb.appendLine("                    java_elem = (*env)->NewStringUTF(env, str_${field.name});")
  sb.appendLine("                    JS_FreeCString(ctx, str_${field.name});")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // JS_TAG_OBJECT → try bridge_dispatch
  sb.appendLine("                case JS_TAG_OBJECT: {")
  sb.appendLine("                    JSValue disp_val = JS_GetPropertyStr(ctx, elem, \"bridge_dispatch\");")
  sb.appendLine("                    if (!JS_IsUndefined(disp_val)) {")
  sb.appendLine("                        // TODO: when pointer compression lands, use JS_VALUE_GET_INT(disp_val) to read arena offset")
  sb.appendLine("                        BridgeConverterFn disp = bridgeConverterFromJSValue(disp_val);")
  sb.appendLine("                        java_elem = disp(env, ctx, elem);")
  sb.appendLine("                    }")
  sb.appendLine("                    JS_FreeValue(ctx, disp_val);")
  sb.appendLine("                    break;")
  sb.appendLine("                }")

  // Default: leave as NULL (null, undefined, etc.)
  sb.appendLine("                default:")
  sb.appendLine("                    break;")
  sb.appendLine("            }")
  sb.appendLine()
  sb.appendLine("            (*env)->SetObjectArrayElement(env, arr_${field.name}, i, java_elem);")
  sb.appendLine("            if (java_elem != NULL) (*env)->DeleteLocalRef(env, java_elem);")
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
    sb.appendLine("            $javaVar = (*env)->NewObject(env, _boxed_${field.name}, _boxedCtor_${field.name}, longVal);")
  } else {
    sb.appendLine(
      "            $javaVar = (*env)->NewObject(env, _boxed_${field.name}, _boxedCtor_${field.name}, ${info.jsCast}${info.jsGetter}(js_${field.name}));"
    )
  }
}

// -- Any? field extraction: dispatch on JS tag --

internal fun emitAnyFieldExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val javaVar = "java_${field.name}"
  sb.appendLine("        // Any? field — delegate to bridgeForAny")
  sb.appendLine("        $javaVar = bridgeForAny(env, ctx, js_${field.name});")
}

// -- Collection field extraction (List, Set, Map from Kotlin stdlib) --

/** Map types recognized by the recursive C converters. */
private val MAP_C_TYPES = setOf(
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
  "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
)

/** Emits the shared prototype-method discovery helper (once per file with collection fields). */
private fun emitCBridgeFindMethod(helpers: StringBuilder) {
  helpers.appendLine(
    """
    static JSValue bridgeFindMethod(JSContext* ctx, JSValue obj, const char* prefix) {
      JSValue ctor = JS_GetPropertyStr(ctx, obj, "constructor");
      JSValue proto = JS_GetPropertyStr(ctx, ctor, "prototype");
      JS_FreeValue(ctx, ctor);
      while (JS_IsObject(proto)) {
        JSPropertyEnum* ptab; uint32_t plen;
        if (JS_GetOwnPropertyNames(ctx, &ptab, &plen, proto, JS_GPN_STRING_MASK) == 0) {
          for (uint32_t i = 0; i < plen; i++) {
            const char* nm = JS_AtomToCString(ctx, ptab[i].atom);
            if (nm != NULL && strncmp(nm, prefix, strlen(prefix)) == 0) {
              JSValue fn = JS_GetPropertyStr(ctx, obj, nm);
              JS_FreeCString(ctx, nm);
              JS_FreePropertyEnum(ctx, ptab, plen);
              JS_FreeValue(ctx, proto);
              return fn;
            }
            JS_FreeCString(ctx, nm);
          }
          JS_FreePropertyEnum(ctx, ptab, plen);
        }
        JSValue parent = JS_GetPropertyStr(ctx, proto, "__proto__");
        JS_FreeValue(ctx, proto);
        proto = parent;
      }
      JS_FreeValue(ctx, proto);
      return JS_DupValue(ctx, JS_UNDEFINED);
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

private fun emitCBoxedConverter(
  helpers: StringBuilder,
  name: String,
  boxedClass: String,
  ctorSig: String,
  valueExpr: String,
) {
  helpers.appendLine(
    """
    static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
      jclass c = (*env)->FindClass(env, "$boxedClass");
      jmethodID m = (*env)->GetMethodID(env, c, "<init>", "$ctorSig");
      return (*env)->NewObject(env, c, m, $valueExpr);
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

/**
 * Emits a C static function converting a JS value of [ktType] into a jobject (borrowed input;
 * the caller owns and frees the JSValue). Recurses for nested collections. Returns the function name.
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
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          const char* s = JS_ToCString(ctx, jsVal);
          jobject r = (*env)->NewStringUTF(env, s);
          JS_FreeCString(ctx, s);
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
    "kotlin.Long" -> emitCBoxedConverter(helpers, name, "java/lang/Long", "(J)V", "JS_VALUE_GET_INT(jsVal)")
    "kotlin.Byte" -> emitCBoxedConverter(helpers, name, "java/lang/Byte", "(B)V", "(jbyte)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Short" -> emitCBoxedConverter(helpers, name, "java/lang/Short", "(S)V", "(jshort)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Char" -> emitCBoxedConverter(helpers, name, "java/lang/Character", "(C)V", "(jchar)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Any" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          int tag = JS_VALUE_GET_NORM_TAG(jsVal);
          switch (tag) {
            case JS_TAG_INT: {
              jclass c = (*env)->FindClass(env, "java/lang/Integer");
              jmethodID m = (*env)->GetMethodID(env, c, "<init>", "(I)V");
              return (*env)->NewObject(env, c, m, JS_VALUE_GET_INT(jsVal));
            }
            case JS_TAG_FLOAT64: {
              jclass c = (*env)->FindClass(env, "java/lang/Double");
              jmethodID m = (*env)->GetMethodID(env, c, "<init>", "(D)V");
              return (*env)->NewObject(env, c, m, JS_VALUE_GET_FLOAT64(jsVal));
            }
            case JS_TAG_BOOL: {
              jclass c = (*env)->FindClass(env, "java/lang/Boolean");
              jmethodID m = (*env)->GetMethodID(env, c, "<init>", "(Z)V");
              return (*env)->NewObject(env, c, m, JS_VALUE_GET_BOOL(jsVal));
            }
            case JS_TAG_STRING: {
              const char* s = JS_ToCString(ctx, jsVal);
              jobject r = (*env)->NewStringUTF(env, s);
              JS_FreeCString(ctx, s);
              return r;
            }
            default: {
              JSValue disp = JS_GetPropertyStr(ctx, jsVal, "bridge_dispatch");
              if (!JS_IsUndefined(disp)) {
                BridgeConverterFn fn = bridgeConverterFromJSValue(disp);
                JS_FreeValue(ctx, disp);
                return fn(env, ctx, jsVal);
              }
              JS_FreeValue(ctx, disp);
              return NULL;
            }
          }
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
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          jclass alc = (*env)->FindClass(env, "java/util/ArrayList");
          jmethodID alc_init = (*env)->GetMethodID(env, alc, "<init>", "()V");
          jmethodID alc_add = (*env)->GetMethodID(env, alc, "add", "(Ljava/lang/Object;)Z");
          jobject result = (*env)->NewObject(env, alc, alc_init);
          JSValue arr = jsVal;
          JSValue wrap = JS_GetPropertyStr(ctx, jsVal, "array_1");
          if (!JS_IsUndefined(wrap)) { arr = wrap; }
          JSValue lenVal = JS_GetPropertyStr(ctx, arr, "length");
          jint len = JS_VALUE_GET_INT(lenVal);
          JS_FreeValue(ctx, lenVal);
          for (jint i = 0; i < len; i++) {
            JSValue elem = JS_GetPropertyUint32(ctx, arr, i);
            jobject je = $elementName(env, ctx, elem);
            (*env)->CallBooleanMethod(env, result, alc_add, je);
            (*env)->DeleteLocalRef(env, je);
            JS_FreeValue(ctx, elem);
          }
          JS_FreeValue(ctx, wrap);
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
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          jclass oc = (*env)->FindClass(env, "java/lang/Object");
          JSValue lenVal = JS_GetPropertyStr(ctx, jsVal, "length");
          jint len = JS_VALUE_GET_INT(lenVal);
          JS_FreeValue(ctx, lenVal);
          jobjectArray result = (*env)->NewObjectArray(env, len, oc, NULL);
          for (jint i = 0; i < len; i++) {
            JSValue elem = JS_GetPropertyUint32(ctx, jsVal, i);
            jobject je = $elementName(env, ctx, elem);
            (*env)->SetObjectArrayElement(env, result, i, je);
            (*env)->DeleteLocalRef(env, je);
            JS_FreeValue(ctx, elem);
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
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          jclass hc = (*env)->FindClass(env, "java/util/HashMap");
          jmethodID hc_init = (*env)->GetMethodID(env, hc, "<init>", "()V");
          jmethodID hc_put = (*env)->GetMethodID(env, hc, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
          jobject result = (*env)->NewObject(env, hc, hc_init);
          JSValue entriesFn = bridgeFindMethod(ctx, jsVal, "get_entries_");
          if (JS_IsUndefined(entriesFn)) { JS_FreeValue(ctx, entriesFn); return result; }
          JSValue entries = JS_Call(ctx, entriesFn, jsVal, 0, NULL);
          JS_FreeValue(ctx, entriesFn);
          JSValue iterFn = bridgeFindMethod(ctx, entries, "iterator_");
          if (JS_IsUndefined(iterFn)) { JS_FreeValue(ctx, iterFn); JS_FreeValue(ctx, entries); return result; }
          JSValue iterator = JS_Call(ctx, iterFn, entries, 0, NULL);
          JS_FreeValue(ctx, iterFn);
          JS_FreeValue(ctx, entries);
          JSValue hasNextFn = bridgeFindMethod(ctx, iterator, "hasNext_");
          JSValue nextFn = bridgeFindMethod(ctx, iterator, "next_");
          if (JS_IsUndefined(hasNextFn) || JS_IsUndefined(nextFn)) {
            JS_FreeValue(ctx, nextFn); JS_FreeValue(ctx, hasNextFn); JS_FreeValue(ctx, iterator);
            return result;
          }
          while (1) {
            JSValue hn = JS_Call(ctx, hasNextFn, iterator, 0, NULL);
            int h = JS_VALUE_GET_BOOL(hn);
            JS_FreeValue(ctx, hn);
            if (!h) break;
            JSValue entry = JS_Call(ctx, nextFn, iterator, 0, NULL);
            JSValue keyFn = bridgeFindMethod(ctx, entry, "get_key_");
            JSValue valueFn = bridgeFindMethod(ctx, entry, "get_value_");
            JSValue keyVal = JS_Call(ctx, keyFn, entry, 0, NULL);
            JSValue valueVal = JS_Call(ctx, valueFn, entry, 0, NULL);
            JS_FreeValue(ctx, keyFn);
            JS_FreeValue(ctx, valueFn);
            jobject jk = $keyName(env, ctx, keyVal);
            jobject jv = $valueName(env, ctx, valueVal);
            (*env)->CallObjectMethod(env, result, hc_put, jk, jv);
            (*env)->DeleteLocalRef(env, jk);
            (*env)->DeleteLocalRef(env, jv);
            JS_FreeValue(ctx, valueVal);
            JS_FreeValue(ctx, keyVal);
            JS_FreeValue(ctx, entry);
          }
          JS_FreeValue(ctx, nextFn);
          JS_FreeValue(ctx, hasNextFn);
          JS_FreeValue(ctx, iterator);
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
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          JSValue lenVal = JS_GetPropertyStr(ctx, jsVal, "length");
          jint len = JS_VALUE_GET_INT(lenVal);
          JS_FreeValue(ctx, lenVal);
          ${arrayType} arr = (*env)->${info.newArrayFn}(env, len);
          ${info.jniElementType}* elems = (*env)->${info.getElementsFn}(env, arr, NULL);
          for (jint i = 0; i < len; i++) {
            JSValue elem = JS_GetPropertyUint32(ctx, jsVal, i);
            elems[i] = ${info.jsGetterCast}${info.jsGetterTemplate}(elem);
            JS_FreeValue(ctx, elem);
          }
          (*env)->${info.releaseElementsFn}(env, arr, elems, 0);
          return arr;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    else -> {
      // Object: bridge_dispatch lookup
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          JSValue disp = JS_GetPropertyStr(ctx, jsVal, "bridge_dispatch");
          if (!JS_IsUndefined(disp)) {
            BridgeConverterFn fn = bridgeConverterFromJSValue(disp);
            JS_FreeValue(ctx, disp);
            return fn(env, ctx, jsVal);
          }
          JS_FreeValue(ctx, disp);
          return NULL;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
  }
  return name
}
internal fun emitCollectionExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val javaVar = "java_${field.name}"
  val elemNullable = field.arrayElementNullable
  sb.appendLine("        // Collection field — unwrap Kotlin/JS ArrayList wrapper, then copy elements.")
  sb.appendLine("        JSValue colArr_${field.name} = js_${field.name};")
  sb.appendLine("        {")
  sb.appendLine("            JSValue _wrapCheck = JS_GetPropertyStr(ctx, js_${field.name}, \"array_1\");")
  sb.appendLine("            if (!JS_IsUndefined(_wrapCheck)) {")
  sb.appendLine("                JS_FreeValue(ctx, js_${field.name});")
  sb.appendLine("                colArr_${field.name} = _wrapCheck;")
  sb.appendLine("            }")
  sb.appendLine("        }")
  sb.appendLine("        if (JS_IsArray(ctx, colArr_${field.name})) {")
  sb.appendLine("            // Look up boxed primitive classes for per-element dispatch")
  for ((ktType, info) in boxedPrimitiveInfo) {
    val shortName = ktType.substringAfterLast('.')
    sb.appendLine("            jclass cls_col_${field.name}_${shortName} = (*env)->FindClass(env, \"${info.wrapperClass}\");")
    sb.appendLine("            jmethodID ctor_col_${field.name}_${shortName} = (*env)->GetMethodID(env, cls_col_${field.name}_${shortName}, \"<init>\", \"${info.ctorSig}\");")
  }
  sb.appendLine()
  sb.appendLine("            jclass alc = (*env)->FindClass(env, \"java/util/ArrayList\");")
  sb.appendLine("            jmethodID alc_init = (*env)->GetMethodID(env, alc, \"<init>\", \"()V\");")
  sb.appendLine("            $javaVar = (*env)->NewObject(env, alc, alc_init);")
  sb.appendLine("            jmethodID alc_add = (*env)->GetMethodID(env, alc, \"add\", \"(Ljava/lang/Object;)Z\");")
  sb.appendLine("            jint _coll_len = 0;")
  sb.appendLine("            {")
  sb.appendLine("                JSValue _coll_len_val = JS_GetPropertyStr(ctx, colArr_${field.name}, \"length\");")
  sb.appendLine("                _coll_len = JS_VALUE_GET_INT(_coll_len_val);")
  sb.appendLine("                JS_FreeValue(ctx, _coll_len_val);")
  sb.appendLine("            }")
  sb.appendLine("            for (jint _ci = 0; _ci < _coll_len; _ci++) {")
  sb.appendLine("                JSValue _celem = JS_GetPropertyUint32(ctx, colArr_${field.name}, _ci);")
  if (elemNullable) {
    sb.appendLine("                if (JS_IsUndefined(_celem) || JS_IsNull(_celem)) {")
    sb.appendLine("                    (*env)->CallBooleanMethod(env, $javaVar, alc_add, NULL);")
    sb.appendLine("                    JS_FreeValue(ctx, _celem);")
    sb.appendLine("                    continue;")
    sb.appendLine("                }")
  }
  sb.appendLine("                int _celem_tag = JS_VALUE_GET_NORM_TAG(_celem);")
  sb.appendLine("                jobject _celem_obj = NULL;")
  sb.appendLine("                switch (_celem_tag) {")
  sb.appendLine("                    case JS_TAG_INT: {")
  sb.appendLine("                        _celem_obj = (*env)->NewObject(env, cls_col_${field.name}_Int, ctor_col_${field.name}_Int, (jint)JS_VALUE_GET_INT(_celem));")
  sb.appendLine("                        break;")
  sb.appendLine("                    }")
  sb.appendLine("                    case JS_TAG_FLOAT64: {")
  sb.appendLine("                        _celem_obj = (*env)->NewObject(env, cls_col_${field.name}_Double, ctor_col_${field.name}_Double, JS_VALUE_GET_FLOAT64(_celem));")
  sb.appendLine("                        break;")
  sb.appendLine("                    }")
  sb.appendLine("                    case JS_TAG_BOOL: {")
  sb.appendLine("                        _celem_obj = (*env)->NewObject(env, cls_col_${field.name}_Boolean, ctor_col_${field.name}_Boolean, (jboolean)JS_VALUE_GET_BOOL(_celem));")
  sb.appendLine("                        break;")
  sb.appendLine("                    }")
  sb.appendLine("                    case JS_TAG_STRING: {")
  sb.appendLine("                        const char *_cstr = JS_ToCString(ctx, _celem);")
  sb.appendLine("                        if (_cstr != NULL) {")
  sb.appendLine("                            _celem_obj = (*env)->NewStringUTF(env, _cstr);")
  sb.appendLine("                            JS_FreeCString(ctx, _cstr);")
  sb.appendLine("                        }")
  sb.appendLine("                        break;")
  sb.appendLine("                    }")
  sb.appendLine("                    case JS_TAG_OBJECT: {")
  sb.appendLine("                        JSValue _cedisp = JS_GetPropertyStr(ctx, _celem, \"bridge_dispatch\");")
  sb.appendLine("                        if (!JS_IsUndefined(_cedisp)) {")
  sb.appendLine("                            BridgeConverterFn _cedp = bridgeConverterFromJSValue(_cedisp);")
  sb.appendLine("                            _celem_obj = _cedp(env, ctx, _celem);")
  sb.appendLine("                            JS_FreeValue(ctx, _cedisp);")
  sb.appendLine("                        }")
  sb.appendLine("                        break;")
  sb.appendLine("                    }")
  sb.appendLine("                    default:")
  sb.appendLine("                        break;")
  sb.appendLine("                }")
  sb.appendLine("                (*env)->CallBooleanMethod(env, $javaVar, alc_add, _celem_obj);")
  sb.appendLine("                if (_celem_obj != NULL) (*env)->DeleteLocalRef(env, _celem_obj);")
  sb.appendLine("                JS_FreeValue(ctx, _celem);")
  sb.appendLine("            }")
  sb.appendLine("        } else {")
  sb.appendLine("            // Try bridge_dispatch as fallback")
  sb.appendLine("            JSValue coldisp_${field.name} = JS_GetPropertyStr(ctx, colArr_${field.name}, \"bridge_dispatch\");")
  sb.appendLine("            if (!JS_IsUndefined(coldisp_${field.name})) {")
  sb.appendLine("                BridgeConverterFn coldisp_p_${field.name} = bridgeConverterFromJSValue(coldisp_${field.name});")
  sb.appendLine("                $javaVar = coldisp_p_${field.name}(env, ctx, colArr_${field.name});")
  sb.appendLine("                JS_FreeValue(ctx, coldisp_${field.name});")
  sb.appendLine("            } else {")
  sb.appendLine("                JS_FreeValue(ctx, coldisp_${field.name});")
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
    generateBridgeFile(outputDir, clazz)
  }
}

// -- field extraction --

