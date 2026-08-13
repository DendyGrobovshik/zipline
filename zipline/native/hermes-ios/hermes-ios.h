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

// Executes bytecode and returns a bridge handle to the raw JS result (for
// bridge dispatch via HermesBridge_* functions). Returns 0 on error; the
// error message is available via HermesContext_getLastError.
int HermesContext_executeToHandle(void* context, const uint8_t* bytecode, int bytecodeSize,
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

// Per-change-type RDMA callbacks (called directly from JS host-function lambdas).
// Each callback writes into the Kotlin-side RdmaChangeSink accumulator.
void HermesContext_setRdmaCreateCallback(void* context, void* fn);
void HermesContext_setRdmaPropertyChangeCallback(void* context, void* fn);
void HermesContext_setRdmaModifierChangeCallback(void* context, void* fn);
void HermesContext_setRdmaAddCallback(void* context, void* fn);
void HermesContext_setRdmaRemoveCallback(void* context, void* fn);
void HermesContext_setRdmaMoveCallback(void* context, void* fn);
void HermesContext_setRdmaBridgeChangeCallback(void* context, void* fn);
void HermesContext_setRdmaSetRemoveDetachCallback(void* context, void* fn);
void HermesContext_setRdmaSendChangesCallback(void* context, void* fn);

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

// -- Bridge Handle Management (for Kotlin/Native generated bridge code) --
// Tag constants for HermesBridge_getValueTag. Must stay in sync with
// the NativeGenerator Kotlin code.
#define BRIDGE_TAG_NULL       0
#define BRIDGE_TAG_INT        1
#define BRIDGE_TAG_DOUBLE     2
#define BRIDGE_TAG_STRING     3
#define BRIDGE_TAG_BOOL       4
#define BRIDGE_TAG_OBJECT     5
#define BRIDGE_TAG_ARRAY      6
#define BRIDGE_TAG_UNDEFINED  7

/** Create a handle by reading a named property from a parent handle.
 *  Pass 0 as parentHandle to read from the global object.
 *  Returns handle ID (> 0) on success, 0 on failure. */
int HermesBridge_createHandle(void* context, int parentHandle, const char* name);

/** Create a handle for an array element by index.
 *  Returns handle ID (> 0) on success, 0 on failure. */
int HermesBridge_createArrayElementHandle(void* context, int arrayHandle, int index);

/** Get the length of an array stored in the given handle. Returns -1 on error. */
int HermesBridge_getArrayLength(void* context, int handle);

/** Get the value tag for a handle (see BRIDGE_TAG_* constants). */
int HermesBridge_getValueTag(void* context, int handle);

/** Get the numeric value from a handle. Safe to call on any handle. */
double HermesBridge_getValueDouble(void* context, int handle);

/** Get the boolean value from a handle (0 or 1). */
int HermesBridge_getValueBool(void* context, int handle);

/** Get the string value from a handle. Returns malloc'd string; caller frees with free(). */
char* HermesBridge_getValueString(void* context, int handle);

/** Release a handle. Does NOT free memory; just clears the slot. */
void HermesBridge_freeHandle(void* context, int handle);

/** Read the bridge_dispatch pointer from an object handle.
 *  Returns the intptr_t-encoded dispatch pointer, or 0 if not found. */
intptr_t HermesBridge_getBridgeDispatch(void* context, int handle);

/** Return a handle to a JS array of own property name strings for an object.
 *  Returns handle ID (> 0) on success, 0 on failure (not an object, or is an array). */
int HermesBridge_getObjectPropertyNames(void* context, int objectHandle);

/** Iterate a JS Map (Kotlin/JS Map) and return array handles of its keys and values.
 *  Returns 1 on success (keysHandleOut/valuesHandleOut receive the new handle IDs), 0 on failure. */
int HermesBridge_getMapEntries(void* context, int mapHandle, int* keysHandleOut, int* valuesHandleOut);

/** Clear all bridge handles. Called when context is about to be destroyed. */
void HermesBridge_clearHandles(void* context);

/** Register a bridge function pointer for the given FQN. 
 *  Called from Kotlin registerBridge(). */
void HermesBridge_addBridgeEntry(const char* fqn, void* fn);

/** Install the __bridgeRegister JS function on the global object.
 *  Called once after Hermes runtime creation. */
void HermesBridge_installBridgeRegister(void* jsiRuntime);

#ifdef __cplusplus
}
#endif

#endif // HERMES_IOS_H
