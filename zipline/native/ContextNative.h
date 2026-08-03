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
};

// Upcast a HermesContext* (void*) to the native context it was created as.
inline ContextNative* asNativeContext(void* context) {
  return static_cast<ContextNative*>(context);
}

#endif  // ZIPLINE_CONTEXT_NATIVE_H
