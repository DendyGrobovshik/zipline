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

#endif
