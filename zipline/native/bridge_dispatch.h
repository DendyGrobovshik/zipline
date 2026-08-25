/*
 * Copyright (C) 2026
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
#ifndef ZIPLINE_BRIDGE_DISPATCH_H
#define ZIPLINE_BRIDGE_DISPATCH_H

#include <jsi/jsi.h>
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace jsi = facebook::jsi;

#ifdef __cplusplus
extern "C" {
#endif

typedef struct JniBridgeDispatch {
    jobject (*toJavaObject)(JNIEnv *env, facebook::jsi::Runtime& rt, const facebook::jsi::Value& jsObj);
} JniBridgeDispatch;

/** Register a JNI init function (caches class/method refs on JVM thread). */
void addBridgeInit(void (*fn)(JNIEnv* env));

/** Register a bridge FQN → converter mapping. */
void addBridgeEntry(const char* fq, jobject (*fn)(JNIEnv *env, facebook::jsi::Runtime& rt, const facebook::jsi::Value& jsObj));

/** Run all registered JNI init functions. */
void init_all(JNIEnv* env);

/** Install __bridgeRegister on global. */
void register_all(facebook::jsi::Runtime& rt);

#ifdef __cplusplus
}
#endif

// -- Hermes JSI compatibility layer (emulates QuickJS C API) --
// JS tag enum (ordered to match common checks)
enum {
  JTAG_INT = 0, JTAG_BOOL = 1, JTAG_NULL = 2, JTAG_UNDEFINED = 3,
  JTAG_STRING = 4, JTAG_OBJECT = 5, JTAG_FLOAT64 = 7
};
#define JS_TAG_INT JTAG_INT
#define JS_TAG_BOOL JTAG_BOOL
#define JS_TAG_NULL JTAG_NULL
#define JS_TAG_UNDEFINED JTAG_UNDEFINED
#define JS_TAG_STRING JTAG_STRING
#define JS_TAG_OBJECT JTAG_OBJECT
#define JS_TAG_FLOAT64 JTAG_FLOAT64

// Tag emulation: maps jsi::Value to a numeric tag
static inline int jsi_value_tag(jsi::Runtime &rt, const jsi::Value &v) {
  if (v.isNumber()) {
    double d = v.asNumber();
    if (std::trunc(d) == d && d >= -2147483648.0 && d <= 2147483647.0)
      return JTAG_INT;
    return JTAG_FLOAT64;
  }
  if (v.isBool())   return JTAG_BOOL;
  if (v.isNull())   return JTAG_NULL;
  if (v.isUndefined()) return JTAG_UNDEFINED;
  if (v.isString()) return JTAG_STRING;
  if (v.isObject()) return JTAG_OBJECT;
  return -1;
}

// Thread-local string buffer (emulates JS_ToCString/JS_FreeCString)
static inline const char* jsi_to_cstring(jsi::Runtime &rt, const jsi::Value &v) {
  static thread_local std::string buf;
  buf = v.asString(rt).utf8(rt);
  return buf.c_str();
}

// Reconstruct 64-bit pointer from two 32-bit halves stored as JS doubles.
// ARM64 pointers need 64 bits; JS double mantissa is only 53 bits,
// so we split into bridge_dispatch_low / bridge_dispatch_high.
static inline intptr_t jsi_get_bridge_dispatch(jsi::Runtime &rt, const jsi::Value &objVal) {
  if (!objVal.isObject()) return 0;
  jsi::Object obj = objVal.asObject(rt);
  jsi::Value lowVal = obj.getProperty(rt, "bridge_dispatch_low");
  jsi::Value highVal = obj.getProperty(rt, "bridge_dispatch_high");
  if (lowVal.isUndefined() || highVal.isUndefined()) return 0;
  int32_t low  = (int32_t)lowVal.asNumber();
  int32_t high = (int32_t)highVal.asNumber();
  return ((intptr_t)high << 32) | ((intptr_t)(uint32_t)low);
}

// Box a JS value into a Java object (Boolean/Integer/Double/String), or
// dispatch a bridged object. Returns NULL for null/undefined/unhandled.
static inline jobject jsi_value_to_boxed(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v) {
  if (v.isBool()) {
    jclass c = env->FindClass("java/lang/Boolean");
    jmethodID m = env->GetMethodID(c, "<init>", "(Z)V");
    return env->NewObject(c, m, (jboolean)v.asBool());
  }
  if (v.isNumber()) {
    double d = v.asNumber();
    if (d == (double)(int32_t)d) {
      jclass c = env->FindClass("java/lang/Integer");
      jmethodID m = env->GetMethodID(c, "<init>", "(I)V");
      return env->NewObject(c, m, (jint)d);
    }
    jclass c = env->FindClass("java/lang/Double");
    jmethodID m = env->GetMethodID(c, "<init>", "(D)V");
    return env->NewObject(c, m, (jdouble)d);
  }
  if (v.isString()) {
    return env->NewStringUTF(jsi_to_cstring(rt, v));
  }
  if (v.isObject()) {
    intptr_t ptr = jsi_get_bridge_dispatch(rt, v);
    if (ptr != 0) {
      JniBridgeDispatch *disp = (JniBridgeDispatch*)ptr;
      return disp->toJavaObject(env, rt, v);
    }
    // Kotlin/JS Long: {low_1, high_1}.
    jsi::Object obj = v.asObject(rt);
    jsi::Value lo = obj.getProperty(rt, "low_1");
    if (!lo.isUndefined()) {
      jsi::Value hi = obj.getProperty(rt, "high_1");
      jlong lv = (static_cast<jlong>(static_cast<int32_t>(hi.asNumber())) << 32) |
                 (static_cast<jlong>(static_cast<uint32_t>(static_cast<int32_t>(lo.asNumber()))));
      jclass c = env->FindClass("java/lang/Long");
      jmethodID m = env->GetMethodID(c, "<init>", "(J)V");
      return env->NewObject(c, m, lv);
    }
  }
  return nullptr;
}

typedef jsi::Value JSValue;

#define JS_VALUE_GET_NORM_TAG(v) jsi_value_tag(rt, (v))
#define JS_VALUE_GET_INT(v)      (static_cast<jint>((v).asNumber()))
#define JS_VALUE_GET_FLOAT64(v)  (static_cast<jdouble>((v).asNumber()))
#define JS_VALUE_GET_BOOL(v)     (static_cast<jboolean>((v).asBool()))

#define JS_GetPropertyStr(c, obj, name)    ((obj).asObject(rt).getProperty(rt, name))
#define JS_GetPropertyUint32(c, arr, i)    ((arr).asObject(rt).getProperty(rt, std::to_string(i).c_str()))
#define JS_ToCString(c, v)                 jsi_to_cstring(rt, (v))
#define JS_FreeCString(c, s)               ((void)0)

#define JS_Undefined()                     jsi::Value::undefined()
#define JS_IsUndefined(v)                  ((v).isUndefined())
#define JS_IsNull(v)                       ((v).isNull())
#define JS_IsArray(c, v)                   ((v).isObject() && (v).asObject(rt).isArray(rt))

#define JS_FreeValue(c, v)                 ((void)0)
#define JS_DupValue(c, v)                  jsi::Value(rt, (v))
#define JS_NewFloat64(c, d)                jsi::Value((d))
#define js_malloc(c, sz)                   std::malloc(sz)

#endif
