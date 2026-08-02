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
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKStringFromUtf8
import app.cash.zipline.quickjs.JSContext
import app.cash.zipline.quickjs.JSValue
import app.cash.zipline.quickjs.JS_FreeValue
import app.cash.zipline.quickjs.JS_GetPropertyStr
import app.cash.zipline.quickjs.JS_IsBool
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
public inline fun JsNumberToDouble(jsVal: CValue<JSValue>): Double = when (JsValueGetNormTag(jsVal)) {
  JS_TAG_INT -> JsValueGetInt(jsVal).toDouble()
  else -> JsValueGetFloat64(jsVal)
}

/**
 * Read a JS number as Long, handling JS_TAG_INT, JS_TAG_FLOAT64,
 * and Kotlin/JS Long objects {low_1, high_1}.
 */
@OptIn(ExperimentalForeignApi::class)
public inline fun JsNumberToLong(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Long = when (JsValueGetNormTag(jsVal)) {
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
public fun bridgeForAny(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Any? = when {
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

