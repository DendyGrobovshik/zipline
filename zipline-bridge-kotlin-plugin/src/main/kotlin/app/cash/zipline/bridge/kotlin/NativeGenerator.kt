package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

// -- Kotlin/Native bridge code generation (iOS) --


internal fun generateNativeBridgeFile(outputDir: String, clazz: IrClass) {
  val fqn = clazz.fqNameWhenAvailable?.asString() ?: return
  val functionName = "${clazz.name.asString()}_toKotlin"
  val fields = extractFields(clazz)

  // Skip classes with unsupported field types (Function*, FloatArray, List — generic type lost in IR)
  val hasUnsupported = fields.any {
    (it.isObjectType && it.ktType.startsWith("kotlin.Function")) ||
    it.ktType == "kotlin.FloatArray"
  }
  if (hasUnsupported) return

  // Collect wrapper names and full types for Dp-like erased value classes and generic params
  val wrapperByField = mutableMapOf<String, String>()
  for (constructor in clazz.declarations.filterIsInstance<IrConstructor>().filter { it.isPrimary }) {
    val params = constructor.parameters
      .filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
    for (param in params) {
      val paramType = param.type
      val paramClass = (paramType as? IrSimpleType)?.getClass()
      val paramClassFqn = paramClass?.classId?.asSingleFqName()?.asString()
      if (paramClassFqn != null && paramClassFqn != "kotlin.Double") {
        val fieldName = param.name.asString()
        wrapperByField[fieldName] = paramClassFqn
      }
    }
  }

  val source = buildString {
    appendLine("// GENERATED FILE. DO NOT MODIFY MANUALLY.")
    appendLine()
    appendLine("@file:Suppress(\"UNUSED_PARAMETER\", \"unused\", \"INVISIBLE_MEMBER\", \"INVISIBLE_REFERENCE\", \"UNCHECKED_CAST\")")
    appendLine("@file:OptIn(app.cash.redwood.RedwoodCodegenApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)")
    appendLine("package generated_bridges")
    appendLine()
    appendLine("import kotlinx.cinterop.*")
    appendLine("import app.cash.zipline.quickjs.*")
    appendLine("import app.cash.zipline.registerBridge")
    val needsBridgeForAny = fields.any { it.ktType == "kotlin.Any" || it.ktType == "kotlin.collections.List" }
    if (needsBridgeForAny) {
      appendLine("import app.cash.zipline.bridgeForAny")
    }
    val needsJsNumber = fields.any { it.ktType in setOf("kotlin.Double", "kotlin.Float") || (it.isInline && it.underlyingKtType == "kotlin.Float") }
    if (needsJsNumber) {
      appendLine("import app.cash.zipline.JsNumberToDouble")
    }
    val needsJsLong = fields.any { it.ktType == "kotlin.Long" || (it.isInline && it.underlyingKtType == "kotlin.Long") }
    if (needsJsLong) {
      appendLine("import app.cash.zipline.JsNumberToLong")
    }
    // Import the target class and any inline wrapper types + object types
    // Import parent class for nested classes (e.g., Modifier for Modifier.Companion)
    val parentFqn = (clazz.parent as? IrClass)?.fqNameWhenAvailable?.asString()
    val importFqn = parentFqn ?: fqn
    val imports = mutableSetOf(importForType(importFqn))
    for (field in fields) {
      if (field.wrapperKtType != null) {
        imports.add(importForType(field.wrapperKtType))
      }
      if (field.isObjectType && field.ktType != "kotlin.Any" && field.ktType != "kotlin.collections.List") {
        imports.add(importForType(field.ktType))
      }
      if (field.arrayElementType != null) {
        imports.add(importForType(field.arrayElementType))
      }
    }
    imports.filter { it.isNotEmpty() }.sorted().forEach { appendLine(it) }
    appendLine()
    appendLine("public fun $functionName(")
    appendLine("  ctx: CPointer<JSContext>,")
    appendLine("  jsVal: CValue<JSValue>,")
    appendLine("): Any {")
    // Generate field reads
    for (field in fields) {
      val propName = field.jsPropertyName

      when {
        field.isInline && field.underlyingKtType == "kotlin.Int" -> {
          val wrapperName = field.wrapperKtType?.substringAfterLast(".") ?: error("inline without wrapperKtType: ${field.name}")
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else $wrapperName(JsValueGetInt(${field.name}Raw))")
          } else {
            appendLine("    val ${field.name}Val = JsValueGetInt(${field.name}Raw)")
            appendLine("    val ${field.name} = $wrapperName(${field.name}Val)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.isInline && field.underlyingKtType == "kotlin.Float" -> {
          val wrapperName = field.wrapperKtType?.substringAfterLast(".") ?: error("inline without wrapperKtType: ${field.name}")
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else $wrapperName(JsValueGetFloat64(${field.name}Raw).toFloat())")
          } else {
            appendLine("    val ${field.name}Val = JsValueGetFloat64(${field.name}Raw).toFloat()")
            appendLine("    val ${field.name} = $wrapperName(${field.name}Val)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.isInline && field.underlyingKtType == "kotlin.Long" -> {
          val wrapperName = field.wrapperKtType?.substringAfterLast(".") ?: error("inline without wrapperKtType: ${field.name}")
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else $wrapperName(JsNumberToLong(ctx, ${field.name}Raw))")
          } else {
            appendLine("    val ${field.name}Val = JsNumberToLong(ctx, ${field.name}Raw)")
            appendLine("    val ${field.name} = $wrapperName(${field.name}Val)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        !field.isInline && field.wrapperKtType != null -> {
          val wrapperName = field.wrapperKtType.substringAfterLast(".")
          // Value class not detected as inline — treat as effectiveKtType + wrap
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          when (field.ktType) {
            "kotlin.Double" -> {
              appendLine("    val ${field.name}Val = JsValueGetFloat64(${field.name}Raw).toFloat()")
            }
            "kotlin.Float" -> {
              appendLine("    val ${field.name}Val = JsValueGetFloat64(${field.name}Raw).toFloat()")
            }
            else -> {
              appendLine("    // TODO: unsupported wrapper effective type ${field.ktType}")
              appendLine("    val ${field.name}Val = ${field.name}Raw  // stub")
            }
          }
          appendLine("    val ${field.name} = $wrapperName(${field.name}Val)")
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Int" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          appendLine("    val ${field.name} = JsValueGetInt(${field.name}Raw${if (field.isNullable) ".takeUnless { JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0 }" else ""})")
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Boolean" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsValueGetBool(${field.name}Raw) != 0")
          } else {
            appendLine("    val ${field.name} = JsValueGetBool(${field.name}Raw) != 0")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Double" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsNumberToDouble(${field.name}Raw)")
          } else {
            appendLine("    val ${field.name} = JsNumberToDouble(${field.name}Raw)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Float" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsNumberToDouble(${field.name}Raw).toFloat()")
          } else {
            appendLine("    val ${field.name} = JsNumberToDouble(${field.name}Raw).toFloat()")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Long" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsNumberToLong(ctx, ${field.name}Raw)")
          } else {
            appendLine("    val ${field.name} = JsNumberToLong(ctx, ${field.name}Raw)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.String" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          appendLine("    val ${field.name}Str = JS_ToCString(ctx, ${field.name}Raw)")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (${field.name}Str == null) null else ${field.name}Str.toKStringFromUtf8().also { JS_FreeCString(ctx, ${field.name}Str) }")
          } else {
            appendLine("    val ${field.name} = ${field.name}Str?.toKStringFromUtf8().also { JS_FreeCString(ctx, ${field.name}Str) } ?: \"\"")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.isObjectType && field.ktType != "kotlin.collections.List" -> {
          val typeName = field.ktType.substringAfterLast(".")
          val castName = if (typeName == "List") "Any" else typeName
          appendLine("    val ${field.name}Ref = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Ref) != 0 || JS_IsNull(${field.name}Ref) != 0) null else {")
            appendLine("        val dispatch = JS_GetPropertyStr(ctx, ${field.name}Ref, \"bridge_dispatch\")")
            appendLine("        val dispatchFn = JsValueGetFloat64(dispatch).toRawBits().toCPointer<UByteVar>()!!.asStableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>()")
            appendLine("        val result = dispatchFn.get()(ctx, ${field.name}Ref) as $castName")
            appendLine("        JS_FreeValue(ctx, dispatch)")
            appendLine("        result")
            appendLine("    }")
          } else {
            appendLine("    val ${field.name}Dispatch = JS_GetPropertyStr(ctx, ${field.name}Ref, \"bridge_dispatch\")")
            appendLine("    val ${field.name}DispatchFn = JsValueGetFloat64(${field.name}Dispatch).toRawBits().toCPointer<UByteVar>()!!.asStableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>()")
            appendLine("    val ${field.name} = ${field.name}DispatchFn.get()(ctx, ${field.name}Ref) as $castName")
            appendLine("    JS_FreeValue(ctx, ${field.name}Dispatch)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Ref)")
        }
        field.ktType == "kotlin.Any" || field.ktType == "kotlin.collections.List" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.ktType == "kotlin.collections.List") {
            // Kotlin/JS ArrayList wraps the JS array in a name-mangled 'array_1' property.
            appendLine("    var ${field.name}Arr = ${field.name}Raw")
            appendLine("    var _tmpArr_${field.name} = JS_GetPropertyStr(ctx, ${field.name}Raw, \"array_1\")")
            appendLine("    if (JS_IsUndefined(_tmpArr_${field.name}) == 0) {")
            appendLine("        JS_FreeValue(ctx, ${field.name}Raw)")
            appendLine("        ${field.name}Arr = _tmpArr_${field.name}")
            appendLine("    }")
            val elemType = field.arrayElementType?.substringAfterLast(".") ?: "Any"
            if (field.isNullable) {
              appendLine("    val ${field.name}: List<$elemType>? = if (JS_IsUndefined(${field.name}Arr) != 0 || JS_IsNull(${field.name}Arr) != 0) null else {")
            } else {
              appendLine("    val ${field.name}: List<$elemType> = run {")
            }
            appendLine("        val lenVal = JS_GetPropertyStr(ctx, ${field.name}Arr, \"length\")")
            appendLine("        val len = JsValueGetInt(lenVal)")
            appendLine("        JS_FreeValue(ctx, lenVal)")
            appendLine("        val list = mutableListOf<$elemType>()")
            appendLine("        var i = 0")
            appendLine("        while (i < len.toInt()) {")
            appendLine("            val elem = JS_GetPropertyUint32(ctx, ${field.name}Arr, i.toUInt())")
            val elemConv = when (elemType) {
              "Float" -> "(bridgeForAny(ctx, elem) as Double).toFloat()"
              "Double" -> "bridgeForAny(ctx, elem) as Double"
              "Int" -> "(bridgeForAny(ctx, elem) as Double).toInt()"
              "Long" -> "JsNumberToLong(ctx, elem)"
              else -> "bridgeForAny(ctx, elem) as $elemType"
            }
            appendLine("            list.add($elemConv)")
            appendLine("            JS_FreeValue(ctx, elem)")
            appendLine("            i++")
            appendLine("        }")
            appendLine("        JS_FreeValue(ctx, ${field.name}Arr)")
            appendLine("        list")
            if (field.isNullable) appendLine("    }")
            else appendLine("    }")
          } else {
            if (field.isNullable) {
              appendLine("    val ${field.name}: Any? = bridgeForAny(ctx, ${field.name}Raw)")
            } else {
              appendLine("    val ${field.name}: Any = bridgeForAny(ctx, ${field.name}Raw)!!")
            }
            appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
          }
        }
        else -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          appendLine("    // TODO: unsupported type ${field.ktType} (isObjectType=${field.isObjectType}, isInline=${field.isInline})")
          appendLine("    val ${field.name} = ${field.name}Raw  // stub")
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
      }
    }

    // Constructor call
    val ctorFields = fields.filter { it.isConstructorParam }
    val bodyFields = fields.filter { !it.isConstructorParam }
    val className = clazz.name.asString()
    val qualifier = ((clazz.parent as? IrClass)?.name?.asString()?.plus(".")) ?: ""
    if (clazz.kind == ClassKind.ENUM_CLASS) {
      // Enum — read ordinal, return entries[ordinal]
      appendLine("    val ordinalRaw = JS_GetPropertyStr(ctx, jsVal, \"ordinal_1\")")
      appendLine("    val ordinal = JsValueGetInt(ordinalRaw)")
      appendLine("    JS_FreeValue(ctx, ordinalRaw)")
      appendLine("    val _obj = $qualifier$className.entries[ordinal]")
    } else if (ctorFields.isNotEmpty()) {
      appendLine("    @Suppress(\"UNCHECKED_CAST\")")
      append("    val _obj = $qualifier$className(")
      append(ctorFields.joinToString(", ") {
        val wrapName = wrapperByField[it.name]
        if (wrapName != null && it.ktType == "kotlin.Double") {
          val shortName = wrapName.substringAfterLast(".")
          "${it.name} = $shortName(${it.name})"
        } else {
          "${it.name} = ${it.name}"
        }
      })
      appendLine(")")
    } else if (clazz.kind == ClassKind.OBJECT || clazz.isCompanion) {
      // Object/singleton — reference directly, not via constructor
      appendLine("    val _obj = $qualifier$className")
    } else {
      appendLine("    val _obj = $qualifier$className()")
    }
    // Body fields
    for (field in bodyFields) {
      appendLine("    _obj.${field.name} = ${field.name}")
    }
    appendLine("    return _obj")
    appendLine("}")
    appendLine()
    appendLine("@OptIn(kotlin.ExperimentalStdlibApi::class)")
    appendLine("@kotlin.native.EagerInitialization")
    appendLine("private val _bridgeInit_${functionName} = run {")
    appendLine("    registerBridge(\"$fqn\", ::$functionName)")
    appendLine("    Unit")
    appendLine("}")
  }

  val fileName = "${fqn.replace(".", "_")}_bridge_native.kt"
  val file = java.io.File(outputDir, fileName)
  file.parentFile.mkdirs()
  file.writeText(source)
}

/** Generate per-class native bridge files. Each file self-registers via @EagerInitialization. */
internal fun generateNativeBridges(outputDir: String, dispatchClasses: List<IrClass>) {
  for (clazz in dispatchClasses) {
    generateNativeBridgeFile(outputDir, clazz)
  }
}
