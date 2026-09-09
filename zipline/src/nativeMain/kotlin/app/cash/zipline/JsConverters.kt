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

import app.cash.zipline.hermes.*
import kotlinx.cinterop.*

// Tag constants matching HermesBridge_getValueTag in hermes-ios.h.
private const val TAG_NULL = 0
private const val TAG_INT = 1
private const val TAG_DOUBLE = 2
private const val TAG_STRING = 3
private const val TAG_BOOL = 4
private const val TAG_OBJECT = 5
private const val TAG_ARRAY = 6
private const val TAG_UNDEFINED = 7

/**
 * Read a JS number as Long, handling Hermes int/double tags and Kotlin/JS Long objects
 * ({low_1, high_1}).
 */
fun JsNumberToLong(context: COpaquePointer?, handle: Int): Long {
  if (context == null) return 0L
  return when (HermesBridge_getValueTag(context, handle)) {
    TAG_INT -> HermesBridge_getValueDouble(context, handle).toInt().toLong()
    TAG_DOUBLE -> HermesBridge_getValueDouble(context, handle).toLong()
    else -> {
      val lowRef = HermesBridge_createHandle(context, handle, "low_1")
      val highRef = HermesBridge_createHandle(context, handle, "high_1")
      val low = HermesBridge_getValueDouble(context, lowRef).toInt()
      val high = HermesBridge_getValueDouble(context, highRef).toInt()
      val result = (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
      HermesBridge_freeHandle(context, lowRef)
      HermesBridge_freeHandle(context, highRef)
      result
    }
  }
}

/**
 * Reads the numeric payload of a BOXED Kotlin value class instance as Double.
 * Kotlin/JS boxes value-class fields with custom members into plain JS objects whose
 * single backing field is hash-mangled and exposes no accessor. Scan the instance's OWN
 * enumerable properties and return the first numeric value. Returns null when the value
 * is not an object or has no numeric payload.
 */
fun JsBoxedNumberToDouble(context: COpaquePointer?, handle: Int): Double? {
  if (context == null) return null
  if (HermesBridge_getValueTag(context, handle) != TAG_OBJECT) return null
  val namesHandle = HermesBridge_getObjectPropertyNames(context, handle)
  if (namesHandle == 0) return null
  try {
    val count = HermesBridge_getArrayLength(context, namesHandle)
    var i = 0
    while (i < count) {
      val nameRef = HermesBridge_createArrayElementHandle(context, namesHandle, i)
      val namePtr = HermesBridge_getValueString(context, nameRef)
      val name = namePtr?.toKStringFromUtf8()?.also { platform.posix.free(namePtr) }
      HermesBridge_freeHandle(context, nameRef)
      if (name != null) {
        val propRef = HermesBridge_createHandle(context, handle, name)
        try {
          when (HermesBridge_getValueTag(context, propRef)) {
            TAG_INT, TAG_DOUBLE -> return HermesBridge_getValueDouble(context, propRef)
          }
        } finally {
          HermesBridge_freeHandle(context, propRef)
        }
      }
      i++
    }
    return null
  } finally {
    HermesBridge_freeHandle(context, namesHandle)
  }
}

/**
 * Reads the numeric payload of a BOXED Kotlin value class instance as Long (e.g. Color over
 * Long). Same scanning strategy as [JsBoxedNumberToDouble], additionally handling a Kotlin/JS
 * Long ({low_1, high_1}) backing payload. Returns null when there is no numeric payload.
 */
fun JsBoxedNumberToLong(context: COpaquePointer?, handle: Int): Long? {
  if (context == null) return null
  if (HermesBridge_getValueTag(context, handle) != TAG_OBJECT) return null
  // An UNBOXED Kotlin/JS Long is itself a {low_1, high_1} object, not a boxed value
  // class. Return null for it so the caller falls back to JsNumberToLong (which packs
  // low/high correctly) instead of scanning and reading just the low 32 bits.
  val lowRef = HermesBridge_createHandle(context, handle, "low_1")
  val highRef = HermesBridge_createHandle(context, handle, "high_1")
  val isDirectLong = HermesBridge_getValueTag(context, lowRef) != TAG_UNDEFINED &&
    HermesBridge_getValueTag(context, highRef) != TAG_UNDEFINED
  HermesBridge_freeHandle(context, highRef)
  HermesBridge_freeHandle(context, lowRef)
  if (isDirectLong) return null
  val namesHandle = HermesBridge_getObjectPropertyNames(context, handle)
  if (namesHandle == 0) return null
  try {
    val count = HermesBridge_getArrayLength(context, namesHandle)
    var i = 0
    while (i < count) {
      val nameRef = HermesBridge_createArrayElementHandle(context, namesHandle, i)
      val namePtr = HermesBridge_getValueString(context, nameRef)
      val name = namePtr?.toKStringFromUtf8()?.also { platform.posix.free(namePtr) }
      HermesBridge_freeHandle(context, nameRef)
      if (name != null) {
        val propRef = HermesBridge_createHandle(context, handle, name)
        try {
          when (HermesBridge_getValueTag(context, propRef)) {
            TAG_INT, TAG_DOUBLE -> return HermesBridge_getValueDouble(context, propRef).toLong()
            TAG_OBJECT -> {
              val lowRef = HermesBridge_createHandle(context, propRef, "low_1")
              val highRef = HermesBridge_createHandle(context, propRef, "high_1")
              if (HermesBridge_getValueTag(context, lowRef) != TAG_UNDEFINED &&
                HermesBridge_getValueTag(context, highRef) != TAG_UNDEFINED) {
                val low = HermesBridge_getValueDouble(context, lowRef).toInt()
                val high = HermesBridge_getValueDouble(context, highRef).toInt()
                HermesBridge_freeHandle(context, highRef)
                HermesBridge_freeHandle(context, lowRef)
                return (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
              }
              HermesBridge_freeHandle(context, highRef)
              HermesBridge_freeHandle(context, lowRef)
            }
          }
        } finally {
          HermesBridge_freeHandle(context, propRef)
        }
      }
      i++
    }
    return null
  } finally {
    HermesBridge_freeHandle(context, namesHandle)
  }
}

/**
 * Dispatch a single JS value to its Kotlin equivalent using bridge dispatch for objects.
 * Used by generated bridge code for elements of unknown type (Any? fields, List elements).
 */
fun bridgeForAny(context: COpaquePointer?, handle: Int): Any? {
  if (context == null) return null
  return when (HermesBridge_getValueTag(context, handle)) {
    TAG_INT, TAG_DOUBLE -> HermesBridge_getValueDouble(context, handle)
    TAG_BOOL -> HermesBridge_getValueBool(context, handle) != 0
    TAG_STRING -> {
      val str = HermesBridge_getValueString(context, handle)
      str?.toKStringFromUtf8()?.also { platform.posix.free(str) }
    }
    TAG_NULL, TAG_UNDEFINED -> null
    TAG_ARRAY -> {
      val length = HermesBridge_getArrayLength(context, handle)
      (0 until length).map { index ->
        val elementRef = HermesBridge_createArrayElementHandle(context, handle, index)
        try {
          bridgeForAny(context, elementRef)
        } finally {
          HermesBridge_freeHandle(context, elementRef)
        }
      }
    }
    else -> {
      val dispPtr = HermesBridge_getBridgeDispatch(context, handle)
      if (dispPtr != 0L) {
        val fn = dispPtr.toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!
        fn(context, handle)?.asStableRef<Any>()?.get()
      } else {
        // Kotlin/JS Long: {low_1, high_1}.
        val lowRef = HermesBridge_createHandle(context, handle, "low_1")
        if (HermesBridge_getValueTag(context, lowRef) != TAG_UNDEFINED) {
          val highRef = HermesBridge_createHandle(context, handle, "high_1")
          val low = HermesBridge_getValueDouble(context, lowRef).toInt()
          val high = HermesBridge_getValueDouble(context, highRef).toInt()
          val result = (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
          HermesBridge_freeHandle(context, highRef)
          HermesBridge_freeHandle(context, lowRef)
          result
        } else {
          HermesBridge_freeHandle(context, lowRef)
          null
        }
      }
    }
  }
}
