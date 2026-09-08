#ifndef ZIPLINE_CONTEXT_NATIVE_H
#define ZIPLINE_CONTEXT_NATIVE_H

#include "RdmaChange.h"
#include "hermes-core.h"

#include <jsi/jsi.h>

#include <string>
#include <vector>

// Function-pointer types for the per-context bridge callbacks. Kotlin/Native
// has no JNI, so it registers staticCFunction pointers instead of jobject
// method refs (see nativeMain/JsEngine.kt).
typedef void* (*OutboundCallChannelCallFn)(void* context, const char* callJson);
typedef int (*OutboundCallChannelDisconnectFn)(void* context, const char* instanceName);
typedef void (*RdmaChangeSinkFn)(void* context);

// Per-change-type callbacks for the RDMA host functions. Each is called
// directly from the JS host-function lambdas so changes flow straight into
// the Kotlin RdmaChangeSink accumulator (matching the gogabr/jni-bridges design).
typedef void (*RdmaCreateFn)(void* context, int id, int tag);
typedef void (*RdmaPropertyChangeFn)(void* context, int id, int widgetTag, int propertyTag, int valueHandle);
typedef void (*RdmaModifierChangeFn)(void* context, int id, int elementsHandle);
typedef void (*RdmaAddFn)(void* context, int id, int childrenTag, int childId, int index);
typedef void (*RdmaRemoveFn)(void* context, int id, int childrenTag, int index, int detach);
typedef void (*RdmaMoveFn)(void* context, int id, int childrenTag, int fromIndex, int toIndex, int count);
typedef void (*RdmaBridgeChangeFn)(void* context, int id, int jsValueHandle);
typedef void (*RdmaSetRemoveDetachFn)(void* context, int index);
typedef void (*RdmaSendChangesFn)(void* context);

// The Kotlin/Native engine context. Extends the core Hermes context with
// per-runtime bridge state (channel callbacks, RDMA state), so multiple
// concurrent runtimes (e.g. an old and a new Zipline during a screen
// transition) never route calls into each other. HermesRuntime_create()
// returns this struct; HermesContext_* functions pass it to HermesCore_*
// functions via an implicit upcast (see asNativeContext).
struct ContextNative : ContextBase {
  OutboundCallChannelCallFn outboundCallFn = nullptr;
  OutboundCallChannelDisconnectFn outboundDisconnectFn = nullptr;
  RdmaChangeSinkFn rdmaSinkFn = nullptr;
  std::vector<RdmaChange> pendingChanges;
  int removeCounter = 0;

  // Per-change-type RDMA callbacks (called from JS host-function lambdas).
  RdmaCreateFn createCb = nullptr;
  RdmaPropertyChangeFn propertyChangeCb = nullptr;
  RdmaModifierChangeFn modifierChangeCb = nullptr;
  RdmaAddFn addCb = nullptr;
  RdmaRemoveFn removeCb = nullptr;
  RdmaMoveFn moveCb = nullptr;
  RdmaBridgeChangeFn bridgeChangeCb = nullptr;
  RdmaSetRemoveDetachFn setRemoveDetachCb = nullptr;
  RdmaSendChangesFn sendChangesCb = nullptr;

  // Bridge-value handle table: stores jsi::Value objects that are referenced
  // by integer handles from Kotlin/Native (generated bridge code). Handle 0
  // is reserved (invalid). Handles are never reused; the table grows
  // monotonically and is cleared when the context is destroyed.
  std::vector<std::shared_ptr<facebook::jsi::Value>> bridgeHandles;
};

// Upcast a HermesContext* (void*) to the native context it was created as.
inline ContextNative* asNativeContext(void* context) {
  return static_cast<ContextNative*>(context);
}

#endif  // ZIPLINE_CONTEXT_NATIVE_H
