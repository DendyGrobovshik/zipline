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

// -- JSI value helpers (explicit runtime; no macros, no hidden context) --

// JS tag enum (ordered to match common checks)
enum {
  JS_TAG_INT = 0, JS_TAG_BOOL = 1, JS_TAG_NULL = 2, JS_TAG_UNDEFINED = 3,
  JS_TAG_STRING = 4, JS_TAG_OBJECT = 5, JS_TAG_FLOAT64 = 7
};

// Tag emulation: maps jsi::Value to a numeric tag
static inline int jsi_value_tag(jsi::Runtime &rt, const jsi::Value &v) {
  if (v.isNumber()) {
    double d = v.asNumber();
    if (std::trunc(d) == d && d >= -2147483648.0 && d <= 2147483647.0)
      return JS_TAG_INT;
    return JS_TAG_FLOAT64;
  }
  if (v.isBool())   return JS_TAG_BOOL;
  if (v.isNull())   return JS_TAG_NULL;
  if (v.isUndefined()) return JS_TAG_UNDEFINED;
  if (v.isString()) return JS_TAG_STRING;
  if (v.isObject()) return JS_TAG_OBJECT;
  return -1;
}

// Primitive value accessors (no runtime required).
static inline jint jsi_value_get_int(const jsi::Value &v) {
  return static_cast<jint>(v.asNumber());
}

static inline jdouble jsi_value_get_float64(const jsi::Value &v) {
  return static_cast<jdouble>(v.asNumber());
}

static inline jboolean jsi_value_get_bool(const jsi::Value &v) {
  return static_cast<jboolean>(v.asBool());
}

// Property access (explicit runtime).
static inline jsi::Value jsi_get_property(jsi::Runtime &rt, const jsi::Value &obj, const char *name) {
  return obj.asObject(rt).getProperty(rt, name);
}

// Find the first property on the object (walking its prototype chain) whose
// name starts with [prefix] and that is a callable function. Used to discover
// Kotlin/JS mangled accessors. Non-function properties that match the prefix
// (e.g. backing fields) are skipped, so callers can safely call asFunction()
// on the result.
static inline jsi::Value jsi_find_method(jsi::Runtime &rt, const jsi::Value &obj, const char *prefix) {
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
        jsi::Value candidate = o.getProperty(rt, name.c_str());
        if (candidate.isObject() && candidate.asObject(rt).isFunction(rt)) {
          return candidate;
        }
      }
    }
    proto = proto.asObject(rt).getProperty(rt, "__proto__");
  }
  return jsi::Value::undefined();
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
    return env->NewStringUTF(v.asString(rt).utf8(rt).c_str());
  }
  if (v.isObject()) {
    jsi::Object obj = v.asObject(rt);
    // JS array → java.util.ArrayList, converting each element recursively.
    if (obj.isArray(rt)) {
      jclass alc = env->FindClass("java/util/ArrayList");
      jmethodID alc_init = env->GetMethodID(alc, "<init>", "()V");
      jmethodID alc_add = env->GetMethodID(alc, "add", "(Ljava/lang/Object;)Z");
      jobject list = env->NewObject(alc, alc_init);
      if (env->ExceptionCheck()) return nullptr;
      jsi::Array arr = obj.asArray(rt);
      size_t n = arr.length(rt);
      for (size_t i = 0; i < n && !env->ExceptionCheck(); i++) {
        jsi::Value elem = arr.getValueAtIndex(rt, i);
        jobject je = jsi_value_to_boxed(env, rt, elem);
        env->CallBooleanMethod(list, alc_add, je);
        if (je) env->DeleteLocalRef(je);
      }
      if (env->ExceptionCheck()) {
        env->DeleteLocalRef(list);
        return nullptr;
      }
      return list;
    }
    intptr_t ptr = jsi_get_bridge_dispatch(rt, v);
    if (ptr != 0) {
      JniBridgeDispatch *disp = (JniBridgeDispatch*)ptr;
      return disp->toJavaObject(env, rt, v);
    }
    // Kotlin/JS Long: {low_1, high_1}.
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

#endif
