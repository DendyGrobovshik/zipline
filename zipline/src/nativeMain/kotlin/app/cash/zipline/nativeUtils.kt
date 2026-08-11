/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(ExperimentalForeignApi::class)

package app.cash.zipline

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValues
import kotlinx.cinterop.CVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.NativePlacement
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.CValue
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf8
import app.cash.zipline.quickjs.JSContext
import app.cash.zipline.quickjs.JSValue
import app.cash.zipline.quickjs.JS_Call
import app.cash.zipline.quickjs.JS_FreeValue
import app.cash.zipline.quickjs.JS_GetPropertyStr
import app.cash.zipline.quickjs.JS_IsBool
import app.cash.zipline.quickjs.JS_IsException
import app.cash.zipline.quickjs.JS_IsNull
import app.cash.zipline.quickjs.JS_IsNumber
import app.cash.zipline.quickjs.JS_IsString
import app.cash.zipline.quickjs.JS_IsUndefined
import app.cash.zipline.quickjs.JS_TAG_FLOAT64
import app.cash.zipline.quickjs.JS_TAG_INT
import app.cash.zipline.quickjs.JS_ToCString
import app.cash.zipline.quickjs.JS_FreeCString
import app.cash.zipline.quickjs.JsValueGetBool
import app.cash.zipline.quickjs.JsValueGetFloat64
import app.cash.zipline.quickjs.JsValueGetInt
import app.cash.zipline.quickjs.JsValueGetNormTag
import app.cash.zipline.quickjs.JsGetOwnPropertyNames
import app.cash.zipline.quickjs.JsGetPropertyName
import app.cash.zipline.quickjs.JsFreePropertyEnum
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.value
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped

/** Copy the data of [item] to the [index] of [this] as if it were an array of [T] structs. */
internal inline operator fun <reified T : CVariable> CPointer<T>.set(index: Int, item: CValues<T>) {
  val offset = index * sizeOf<T>()
  item.place(interpretCPointer(rawValue + offset)!!)
}

/** Copy the values of [items] into a new array. */
internal inline fun <reified T : CVariable> NativePlacement.allocArrayOf(
  vararg items: CValues<T>,
): CArrayPointer<T> {
  val array = allocArray<T>(items.size)
  items.forEachIndexed { index, item ->
    array[index] = item
  }
  return array
}

/**
 * Read a JS number as Double, handling both JS_TAG_INT and JS_TAG_FLOAT64.
 * JS numbers may be encoded as either tag — JsValueGetFloat64 on an int-tagged
 * value reads garbage.
 */
@OptIn(ExperimentalForeignApi::class)
fun JsNumberToDouble(jsVal: CValue<JSValue>): Double = when (JsValueGetNormTag(jsVal)) {
  JS_TAG_INT -> JsValueGetInt(jsVal).toDouble()
  else -> JsValueGetFloat64(jsVal)
}

/**
 * Read a JS number as Long, handling JS_TAG_INT, JS_TAG_FLOAT64,
 * and Kotlin/JS Long objects {low_1, high_1}.
 */
@OptIn(ExperimentalForeignApi::class)
fun JsNumberToLong(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Long = when (JsValueGetNormTag(jsVal)) {
  JS_TAG_INT -> JsValueGetInt(jsVal).toLong()
  JS_TAG_FLOAT64 -> JsValueGetFloat64(jsVal).toLong()
  else -> {
    val lowVal = JS_GetPropertyStr(ctx, jsVal, "low_1")
    val highVal = JS_GetPropertyStr(ctx, jsVal, "high_1")
    val low = JsValueGetInt(lowVal)
    val high = JsValueGetInt(highVal)
    val result = (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFF)
    JS_FreeValue(ctx, lowVal)
    JS_FreeValue(ctx, highVal)
    result
  }
}

/**
 * Dispatch a single JS value to its Kotlin equivalent using bridge_dispatch for objects.
 * Used by generated bridge code for elements of unknown type (Any? fields, List elements).
 */
@OptIn(ExperimentalForeignApi::class)
fun bridgeForAny(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Any? = when {
  JS_IsNumber(jsVal) != 0 -> JsNumberToDouble(jsVal)
  JS_IsBool(jsVal) != 0 -> (JsValueGetBool(jsVal) != 0)
  JS_IsString(jsVal) != 0 -> {
    val cstr = JS_ToCString(ctx, jsVal)
    if (cstr != null) {
      val str = cstr.toKStringFromUtf8()
      JS_FreeCString(ctx, cstr)
      str
    } else ""
  }
  JS_IsUndefined(jsVal) != 0 || JS_IsNull(jsVal) != 0 -> null
  else -> {
    val dispatch = JS_GetPropertyStr(ctx, jsVal, "bridge_dispatch")
    if (JS_IsUndefined(dispatch) == 0) {
      val fn = JsValueGetFloat64(dispatch).toRawBits().toCPointer<UByteVar>()!!.asStableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>()
      val r = fn.get()(ctx, jsVal)
      JS_FreeValue(ctx, dispatch)
      r
    } else {
      JS_FreeValue(ctx, dispatch)
      // Check for Kotlin/JS Long: {low_1, high_1} with constructor.name === "Long"
      val lo = JS_GetPropertyStr(ctx, jsVal, "low_1")
      if (JS_IsUndefined(lo) == 0) {
        val ctor = JS_GetPropertyStr(ctx, jsVal, "constructor")
        var isLong = false
        if (JS_IsUndefined(ctor) == 0) {
          val ctorName = JS_GetPropertyStr(ctx, ctor, "name")
          val ctorNameStr = JS_ToCString(ctx, ctorName)
          isLong = ctorNameStr?.toKStringFromUtf8() == "Long"
          JS_FreeCString(ctx, ctorNameStr)
          JS_FreeValue(ctx, ctorName)
        }
        JS_FreeValue(ctx, ctor)
        if (isLong) {
          val hi = JS_GetPropertyStr(ctx, jsVal, "high_1")
          val loVal = JsValueGetInt(lo).toLong() and 0xFFFFFFFFL
          val hiVal = JsValueGetInt(hi).toLong() shl 32
          val lv = hiVal or loVal
          JS_FreeValue(ctx, hi)
          JS_FreeValue(ctx, lo)
          return lv
        }
        JS_FreeValue(ctx, lo)
      }
      null
    }
  }
}

/**
 * Converts a Kotlin/JS Map instance into a Kotlin Map by walking its Kotlin iterator protocol
 * (entries() → iterator() → hasNext()/next()) and converting each key/value pair with the
 * supplied converters. Used by generated bridge code for Map fields.
 */
@OptIn(ExperimentalForeignApi::class)
public fun jsMapToKotlin(
  ctx: CPointer<JSContext>,
  jsVal: CValue<JSValue>,
  keyConverter: (CValue<JSValue>) -> Any,
  valueConverter: (CValue<JSValue>) -> Any,
): Map<Any, Any> {
  val result = mutableMapOf<Any, Any>()
  // Kotlin/JS exposes Map iteration only through mangled methods (get_entries_*, iterator_*,
  // hasNext_*, next_*, get_key_*, get_value_*) with stable prefixes; discover the exact names
  // at runtime and call them via JS_Call.
  fun protoNames(obj: CValue<JSValue>): List<String> {
    // Kotlin/JS methods live on the prototype chain (constructor.prototype + __proto__ parents);
    // own properties are only fields. Walk the chain collecting method names.
    val result = mutableListOf<String>()
    val constructor = JS_GetPropertyStr(ctx, obj, "constructor")
    var proto = JS_GetPropertyStr(ctx, constructor, "prototype")
    JS_FreeValue(ctx, constructor)
    while (JS_IsUndefined(proto) == 0 && JS_IsNull(proto) == 0) {
      val names = memScoped {
        val count = alloc<IntVar>()
        val ptab = JsGetOwnPropertyNames(ctx, proto, count.ptr)
        if (ptab == null) {
          emptyList()
        } else {
          val list = (0 until count.value).mapNotNull { i ->
            JsGetPropertyName(ctx, ptab, i)?.toKStringFromUtf8()
          }
          JsFreePropertyEnum(ctx, ptab)
          list
        }
      }
      result.addAll(names)
      val parent = JS_GetPropertyStr(ctx, proto, "__proto__")
      JS_FreeValue(ctx, proto)
      proto = parent
    }
    JS_FreeValue(ctx, proto)
    return result
  }
  fun findMethod(protoNames: List<String>, prefix: String): String? =
    protoNames.firstOrNull { it.startsWith(prefix) }

  val mapNames = protoNames(jsVal)
  val entriesName = findMethod(mapNames, "get_entries_") ?: return result
  val entriesFn = JS_GetPropertyStr(ctx, jsVal, entriesName)
  val entries = JS_Call(ctx, entriesFn, jsVal, 0, null)
  JS_FreeValue(ctx, entriesFn)
  if (JS_IsException(entries) != 0) {
    JS_FreeValue(ctx, entries)
    return result
  }
  val entriesNames = protoNames(entries)
  val iteratorName = findMethod(entriesNames, "iterator_") ?: run {
    JS_FreeValue(ctx, entries)
    return result
  }
  val iteratorFn = JS_GetPropertyStr(ctx, entries, iteratorName)
  val iterator = JS_Call(ctx, iteratorFn, entries, 0, null)
  JS_FreeValue(ctx, iteratorFn)
  JS_FreeValue(ctx, entries)
  if (JS_IsException(iterator) != 0) {
    JS_FreeValue(ctx, iterator)
    return result
  }
  val iteratorNames = protoNames(iterator)
  val hasNextName = findMethod(iteratorNames, "hasNext_")
  val nextName = findMethod(iteratorNames, "next_")
  if (hasNextName == null || nextName == null) {
    JS_FreeValue(ctx, iterator)
    return result
  }
  val hasNextFn = JS_GetPropertyStr(ctx, iterator, hasNextName)
  val nextFn = JS_GetPropertyStr(ctx, iterator, nextName)
  while (true) {
    val hasNext = JS_Call(ctx, hasNextFn, iterator, 0, null)
    val hasNextValue = JsValueGetBool(hasNext) != 0
    JS_FreeValue(ctx, hasNext)
    if (!hasNextValue) break
    val entry = JS_Call(ctx, nextFn, iterator, 0, null)
    if (JS_IsException(entry) != 0) {
      JS_FreeValue(ctx, entry)
      break
    }
    val entryNames = protoNames(entry)
    val keyName = findMethod(entryNames, "get_key_")
    val valueName = findMethod(entryNames, "get_value_")
    if (keyName == null || valueName == null) {
      JS_FreeValue(ctx, entry)
      break
    }
    val keyFn = JS_GetPropertyStr(ctx, entry, keyName)
    val valueFn = JS_GetPropertyStr(ctx, entry, valueName)
    val keyRaw = JS_Call(ctx, keyFn, entry, 0, null)
    val valueRaw = JS_Call(ctx, valueFn, entry, 0, null)
    JS_FreeValue(ctx, keyFn)
    JS_FreeValue(ctx, valueFn)
    result[keyConverter(keyRaw)] = valueConverter(valueRaw)
    JS_FreeValue(ctx, valueRaw)
    JS_FreeValue(ctx, keyRaw)
    JS_FreeValue(ctx, entry)
  }
  JS_FreeValue(ctx, nextFn)
  JS_FreeValue(ctx, hasNextFn)
  JS_FreeValue(ctx, iterator)
  return result
}

