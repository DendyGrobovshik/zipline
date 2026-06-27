/*
 * Copyright (C) 2019 Square, Inc.
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
#ifndef QUICKJS_ANDROID_CONTEXT_H
#define QUICKJS_ANDROID_CONTEXT_H

#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include "quickjs/quickjs.h"

class JSRuntime;
class JSContext;
class InboundCallChannel;

enum class RdmaChangeType {
  Create,
  PropertyChange,
  ModifierChange,
  Add,
  Remove,
  Move,
  BridgeChange,
};

constexpr int BATCH_SIZE = 2048;

struct RdmaChange {
  RdmaChangeType type;
  int id;
  int field1;    // tag (Create/Remove/Move), widgetTag (PropertyChange), childrenTag (Add)
  int field2;    // propertyTag (PropertyChange), childId (Add), index (Remove), fromIndex (Move)
  int field3;    // index (Add), toIndex (Move)
  int count;     // count (Move only)
  bool detach;   // detach flag (Remove only)
  JSValueConst jsValue; // JS payload for PropertyChange, ModifierChange; JS_NULL otherwise
};

class Context {
public:
  Context(JNIEnv *env);
  ~Context();

  InboundCallChannel* getInboundCallChannel(JNIEnv*, jstring name);
  void setOutboundCallChannel(JNIEnv*, jstring name, jobject callChannel);
  jobject execute(JNIEnv*, jbyteArray byteCode);
  jbyteArray compile(JNIEnv*, jstring source, jstring file);
  void setInterruptHandler(JNIEnv* env, jobject interruptHandler);
  jobject memoryUsage(JNIEnv*);
  void setMemoryLimit(JNIEnv* env, jlong limit);
  void setGcThreshold(JNIEnv* env, jlong gcThreshold);
  void gc(JNIEnv* env);
  void setMaxStackSize(JNIEnv* env, jlong stackSize);

  jobject toJavaObject(JNIEnv*, const JSValue& value, bool throwOnUnsupportedType = true);
  void throwJsException(JNIEnv*, const JSValue& value) const;
  JSValue throwJavaExceptionFromJs(JNIEnv*) const;

  JNIEnv* getEnv() const;

  std::string toCppString(JNIEnv* env, jstring string) const;
  JSValue toJsString(JNIEnv* env, jstring string) const;
  jstring toJavaString(JNIEnv* env, const JSValueConst& value) const;

  JavaVM* javaVm;
  const jint jniVersion;
  JSRuntime *jsRuntime;
  JSContext *jsContext;
  JSContext *jsContextForCompiling;
  JSClassID outboundCallChannelClassId;
  JSAtom lengthAtom;
  JSAtom callAtom;
  JSAtom disconnectAtom;
  jclass booleanClass;
  jclass integerClass;
  jclass doubleClass;
  jclass objectClass;
  jclass stringClass;
  jclass memoryUsageClass;
  jstring stringUtf8;
  jclass quickJsExceptionClass;
  jmethodID booleanValueOf;
  jmethodID integerValueOf;
  jmethodID doubleValueOf;
  jmethodID stringGetBytes;
  jmethodID stringConstructor;
  jmethodID memoryUsageConstructor;
  jmethodID quickJsExceptionConstructor;
  jclass interruptHandlerClass;
  jmethodID interruptHandlerPoll;
  jobject interruptHandler;
  std::vector<InboundCallChannel*> callChannels;
  std::unordered_map<std::string, jclass> globalReferences;

  // JNI cache for RdmaBridge (static factories)
  jclass rdmaBridgeClass = nullptr;
  jmethodID rdmaBridgeCreateCreate;
  jmethodID rdmaBridgeCreateAdd;
  jmethodID rdmaBridgeCreateRemove;
  jmethodID rdmaBridgeCreateMove;
  jmethodID rdmaBridgeCreatePropertyChange;
  jmethodID rdmaBridgeCreateModifierChange;
  jmethodID rdmaBridgeCreateModifierElement;
  jmethodID rdmaBridgeCreateBridgeChange;
  jmethodID rdmaBridgeJsonPrimitiveString;
  jmethodID rdmaBridgeJsonPrimitiveInt;
  jmethodID rdmaBridgeJsonPrimitiveLong;
  jmethodID rdmaBridgeJsonPrimitiveDouble;
  jmethodID rdmaBridgeJsonPrimitiveBoolean;
  jmethodID rdmaBridgeJsonNull;
  jmethodID rdmaBridgeCreateJsonArray;
  jmethodID rdmaBridgeCreateJsonObject;

  // JNI cache for ArrayList
  jclass arrayListClass;
  jmethodID arrayListInit;
  jmethodID arrayListInitWithCapacity;
  jmethodID arrayListAdd;

  jobject rdmaBridgeInstance;
  jmethodID rdmaBridgeSendChanges;
  jmethodID rdmaBridgeSendBatch;

  std::vector<RdmaChange> pendingChanges;
  jobject jsValueToJsonElement(JNIEnv* env, JSValueConst val);
  jobject jsArrayToJsonElement(JNIEnv* env, JSValueConst val);
  jobject jsObjectToJsonElement(JNIEnv* env, JSValueConst val);
  void flushPendingBatch(JNIEnv* env, int count);
  void finishFlushPending(JNIEnv* env);
  void cacheRdmaBridgeMethods(JNIEnv* env);
  void deleteBridgeRefs(JNIEnv* env);
  void initRdmaChangesChannel(JNIEnv* env);
};

#endif //QUICKJS_ANDROID_CONTEXT_H
