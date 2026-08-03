#ifndef HERMES_IOS_H
#define HERMES_IOS_H

#include <stdbool.h>
#include <stdint.h>

#include "../hermes-core.h"
#include "../common/intset-builtins.h"

#ifdef __cplusplus
extern "C" {
#endif

// Initialize the Hermes framework (must be called before any other function)
// runtimeOut: output pointer to receive the newly created Hermes runtime (opaque void**).
// Returns 1 on success, 0 on failure.
int HermesFramework_init(void** runtimeOut);

// Runtime lifecycle (opaque void* pointers). The returned pointer is an
// ios-layer context that extends the core Hermes context with the
// per-runtime bridge state (channel callbacks, RDMA state).
void* HermesRuntime_create(void);
void HermesRuntime_destroy(void* runtime);

// Get the jsi::Runtime from a runtime returned by HermesRuntime_create
// (used to register JS intrinsics right after creation).
void* HermesRuntime_getJsiRuntime(void* runtime);

// Tagged scalar result of evaluate/execute. Only bool, int, double and
// string are supported: all other kinds (objects, arrays, functions) map
// to HERMES_TAG_NULL.
#define HERMES_TAG_ERROR -1
#define HERMES_TAG_NULL 0
#define HERMES_TAG_INT 1
#define HERMES_TAG_DOUBLE 2
#define HERMES_TAG_STRING 3
#define HERMES_TAG_BOOL 4
typedef struct {
  int tag;
  double number;  // HERMES_TAG_INT (integral) / HERMES_TAG_DOUBLE / HERMES_TAG_BOOL (0/1)
  char* string;   // HERMES_TAG_STRING: malloc'd; caller frees with free()
} HermesTaggedValue;

// Evaluation - executes the script and returns the result as a tagged
// scalar. On error the returned tag is HERMES_TAG_ERROR and the message is
// available via HermesContext_getLastError. String payloads are freed
// with HermesContext_freeValue.
HermesTaggedValue HermesContext_evaluate(void* context, const char* code, const char* sourceURL);
void HermesContext_freeValue(void* context, char* value);
int HermesContext_hasGlobalObject(void* context, const char* name);

// Bytecode compilation and execution (same tagged-scalar result contract
// as HermesContext_evaluate)
int HermesContext_compile(void* context, const char* code, const char* sourceURL,
                          const char* sourceMap, char** bytecodeOut, int* bytecodeSizeOut);
HermesTaggedValue HermesContext_execute(void* context, const uint8_t* bytecode, int bytecodeSize,
                         const char* sourceURL);

// Global properties
int HermesContext_getGlobalProperty(void* context, const char* name, char** valueOut);
int HermesContext_setGlobalProperty(void* context, const char* name, const char* value);
int HermesContext_deleteGlobalProperty(void* context, const char* name);

// Call functions
int HermesContext_callGlobalMethod(void* context, const char* objectName, const char* methodName);
int HermesContext_callGlobalFunctionWithStringArg(void* context, const char* functionName,
                                                 const char* arg, char** resultOut);

// Call channel callbacks (for outbound channel - JS calling into native/Kotlin).
// Callbacks are registered per-context so that multiple concurrent runtimes
// (e.g. an old and a new Zipline during a screen transition) each route their
// JS calls to their own Kotlin endpoint. The context pointer is passed back
// as the first argument of every callback invocation.
typedef void* (*OutboundCallChannelCallFn)(void* context, const char* callJson);
typedef int (*OutboundCallChannelDisconnectFn)(void* context, const char* instanceName);
void HermesContext_setOutboundChannelCallbacks(void* context,
                                                OutboundCallChannelCallFn callFn,
                                                OutboundCallChannelDisconnectFn disconnectFn);

// RDMA Changes support. The sink is per-context; the context pointer is
// passed back on invocation.
typedef void (*RdmaChangeSinkFn)(void* context);
void HermesContext_setRdmaChangeSink(void* context, RdmaChangeSinkFn sinkFn);

// Outbound call channel (JS calling into Kotlin)
// Sets up global "outboundChannel" object with call/disconnect functions
// that delegate to the callbacks set via HermesContext_setOutboundChannelCallbacks
int HermesContext_setupOutboundCallChannel(void* context);

// Call an inbound channel's "call" method from Kotlin
// Returns a newly allocated string (caller must free), or NULL on error
char* HermesContext_callInbound(void* context, const char* channelName, const char* callJson);

// Call an inbound channel's "disconnect" method from Kotlin
// Returns a newly allocated string "true" or "false" (caller must free), or NULL on error
char* HermesContext_callInboundDisconnect(void* context, const char* channelName, const char* instanceName);

// Module loading (not implemented - returns 0)
int HermesContext_installModuleLoader(void* context);
int HermesContext_callRequireMethod(void* context, const char* moduleId, const char* methodName);

// RDMA Changes support
int HermesContext_initRdmaChangesChannel(void* context);

// Memory management
void HermesContext_setMemoryLimit(void* context, int64_t limitBytes);
void HermesContext_setGcThreshold(void* context, int64_t thresholdBytes);
void HermesContext_setMaxStackSize(void* context, int64_t maxStackSizeBytes);
void HermesContext_gc(void* context);

// Memory usage (see HermesCoreMemoryUsage in hermes-core.h)
int HermesContext_getMemoryUsage(void* context, HermesCoreMemoryUsage* usageOut);

// Version
const char* Hermes_getVersion(void);

// Error handling
const char* HermesContext_getLastError(void* context);

#ifdef __cplusplus
}
#endif

#endif // HERMES_IOS_H
