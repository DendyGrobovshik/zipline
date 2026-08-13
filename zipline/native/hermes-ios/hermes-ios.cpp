#include "hermes-ios.h"
#include "hermes-core.h"
#include "../RdmaChange.h"
#include "../ContextNative.h"
#include "../InboundCallChannel.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <cmath>

#include <string>
#include <vector>
#include <mutex>

#include <jsi/jsi.h>

namespace jsi = facebook::jsi;

// Process-global error buffer. NOT thread-safe: two engines reporting errors
// from different threads race on this buffer. The bridge is designed for
// single-threaded-per-runtime use, and errors are consumed immediately after
// each failing call on the same thread, so this is acceptable for now. If
// multi-threaded error reporting is ever needed, move this into
// ContextNative (per-runtime) or use thread_local storage.
static char g_lastError[1024];

// Returns the jsi runtime for a context, recording "Invalid runtime" in
// g_lastError and returning nullptr on failure. Callers return their own
// error value (0/NULL) when this returns nullptr.
static jsi::Runtime* getJsiRuntimeOrNull(void* context) {
    void* runtime = HermesCore_getRuntime(asNativeContext(context));
    if (!runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return nullptr;
    }
    return static_cast<jsi::Runtime*>(runtime);
}

namespace {

char* copyToMalloc(const std::string& str) {
    char* copy = (char*)malloc(str.size() + 1);
    if (copy) {
        memcpy(copy, str.c_str(), str.size() + 1);
    }
    return copy;
}

} // namespace

void HermesContext_setOutboundChannelCallbacks(void* context,
                                               OutboundCallChannelCallFn callFn,
                                               OutboundCallChannelDisconnectFn disconnectFn) {
    if (!context) return;
    ContextNative* ctx = asNativeContext(context);
    ctx->outboundCallFn = callFn;
    ctx->outboundDisconnectFn = disconnectFn;
}

void HermesContext_setRdmaChangeSink(void* context, RdmaChangeSinkFn sinkFn) {
    if (!context) return;
    asNativeContext(context)->rdmaSinkFn = sinkFn;
}

void HermesContext_setRdmaCreateCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->createCb = reinterpret_cast<RdmaCreateFn>(fn);
}
void HermesContext_setRdmaPropertyChangeCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->propertyChangeCb = reinterpret_cast<RdmaPropertyChangeFn>(fn);
}
void HermesContext_setRdmaModifierChangeCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->modifierChangeCb = reinterpret_cast<RdmaModifierChangeFn>(fn);
}
void HermesContext_setRdmaAddCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->addCb = reinterpret_cast<RdmaAddFn>(fn);
}
void HermesContext_setRdmaRemoveCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->removeCb = reinterpret_cast<RdmaRemoveFn>(fn);
}
void HermesContext_setRdmaMoveCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->moveCb = reinterpret_cast<RdmaMoveFn>(fn);
}
void HermesContext_setRdmaBridgeChangeCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->bridgeChangeCb = reinterpret_cast<RdmaBridgeChangeFn>(fn);
}
void HermesContext_setRdmaSetRemoveDetachCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->setRemoveDetachCb = reinterpret_cast<RdmaSetRemoveDetachFn>(fn);
}
void HermesContext_setRdmaSendChangesCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->sendChangesCb = reinterpret_cast<RdmaSendChangesFn>(fn);
}

int HermesFramework_init(void** runtimeOut) {
    if (!runtimeOut) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime output pointer");
        return 0;
    }
    ContextNative* ctx = new ContextNative();
    if (!HermesCore_initContext(ctx)) {
        delete ctx;
        snprintf(g_lastError, sizeof(g_lastError), "Failed to create Hermes runtime");
        return 0;
    }
    *runtimeOut = ctx;
    return 1;
}

void* HermesRuntime_create(void) {
    void* runtime = NULL;
    if (!HermesFramework_init(&runtime)) {
        return NULL;
    }
    return runtime;
}

void HermesRuntime_destroy(void* runtime) {
    if (runtime) {
        ContextNative* ctx = asNativeContext(runtime);
        HermesCore_releaseContext(ctx);
        delete ctx;
    }
}

void* HermesRuntime_getJsiRuntime(void* runtime) {
    if (!runtime) return NULL;
    return HermesCore_getRuntime(asNativeContext(runtime));
}

namespace {

HermesTaggedValue toTaggedValue(jsi::Runtime& rt, const jsi::Value& v) {
    HermesTaggedValue out;
    out.tag = HERMES_TAG_NULL;
    out.number = 0;
    out.string = nullptr;
    if (v.isBool()) {
        out.tag = HERMES_TAG_BOOL;
        out.number = v.asBool() ? 1 : 0;
    } else if (v.isNumber()) {
        // Always encode numbers as double; the platform side re-narrows
        // integral values to Int.
        out.tag = HERMES_TAG_DOUBLE;
        out.number = v.asNumber();
    } else if (v.isString()) {
        out.tag = HERMES_TAG_STRING;
        out.string = strdup(v.asString(rt).utf8(rt).c_str());
    }
    return out;
}

} // namespace

HermesTaggedValue HermesContext_evaluate(void* context, const char* code, const char* sourceURL) {
    if (!context || !code) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    ContextBase* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    try {
        std::string source(code, strlen(code));
        jsi::Value result = ctx->runtime->evaluateJavaScript(
            std::make_unique<jsi::StringBuffer>(std::move(source)),
            sourceURL ? sourceURL : "<eval>");
        return toTaggedValue(*ctx->runtime, result);
    } catch (const jsi::JSError& e) {
        // Full text goes to ctx->lastError (unbounded; preferred by
        // HermesContext_getLastError), not the fixed g_lastError buffer.
        ctx->lastError = e.getMessage() + std::string("\n") + e.getStack();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }
}

int HermesContext_hasGlobalObject(void* context, const char* name) {
    if (!context || !name) {
        return 0;
    }
    return HermesCore_hasGlobalObject(asNativeContext(context), name);
}

void HermesContext_freeValue(void* context, char* value) {
    if (value) {
        free(value);
    }
}

int HermesContext_compile(void* context, const char* code, const char* sourceURL,
                          const char* sourceMap, char** bytecodeOut, int* bytecodeSizeOut) {
#ifdef HERMESVM_LEAN
    snprintf(g_lastError, sizeof(g_lastError), "compile() is not available in lean Hermes build");
    return 0;
#else
    if (!context || !code) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    uint8_t* bytecode = NULL;
    size_t bytecodeSize = 0;
    char* errorOut = NULL;

    int success = HermesCore_compile(asNativeContext(context), code, sourceURL, sourceMap, &bytecode, &bytecodeSize, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Compilation failed");
        if (errorOut) free(errorOut);
        return 0;
    }

    char* bytes = static_cast<char*>(malloc(bytecodeSize));
    if (!bytes) {
        delete[] bytecode; // Allocated with new[] by HermesCore_compile.
        snprintf(g_lastError, sizeof(g_lastError), "Out of memory");
        return 0;
    }
    memcpy(bytes, bytecode, bytecodeSize);
    delete[] bytecode; // Allocated with new[] by HermesCore_compile.

    *bytecodeOut = bytes;
    *bytecodeSizeOut = (int)bytecodeSize;
    return 1;
#endif
}

HermesTaggedValue HermesContext_execute(void* context, const uint8_t* bytecode, int bytecodeSize,
                                        const char* sourceURL) {
    if (!context || !bytecode) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    ContextBase* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    try {
        jsi::Value result = HermesCore_evaluateBytecode(
            ctx, bytecode, bytecodeSize, sourceURL ? sourceURL : "zipline-module.js");
        return toTaggedValue(*ctx->runtime, result);
    } catch (const jsi::JSError& e) {
        ctx->lastError = e.getMessage() + std::string("\n") + e.getStack();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }
}

int HermesContext_executeToHandle(void* context, const uint8_t* bytecode, int bytecodeSize,
                                  const char* sourceURL) {
    if (!context || !bytecode) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return -1;
    }

    ContextNative* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return -1;
    }

    try {
        jsi::Value result = HermesCore_evaluateBytecode(
            ctx, bytecode, bytecodeSize, sourceURL ? sourceURL : "zipline-module.js");
        // Handle 0 is reserved as "global object" in HermesBridge_createHandle, so
        // the first real handle must start at 1.
        if (ctx->bridgeHandles.empty()) {
            ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(jsi::Value::undefined()));
        }
        int handle = static_cast<int>(ctx->bridgeHandles.size());
        ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(*ctx->runtime, result));
        return handle;
    } catch (const jsi::JSError& e) {
        ctx->lastError = e.getMessage() + std::string("\n") + e.getStack();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.getMessage().c_str());
        return -1;
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.what());
        return -1;
    }
}

int HermesContext_getGlobalProperty(void* context, const char* name, char** valueOut) {
    if (!context || !name) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_getGlobalProperty(asNativeContext(context), name, valueOut, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to get global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_setGlobalProperty(void* context, const char* name, const char* value) {
    if (!context || !name || !value) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_setGlobalProperty(asNativeContext(context), name, value, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to set global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_deleteGlobalProperty(void* context, const char* name) {
    if (!context || !name) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_deleteGlobalProperty(asNativeContext(context), name, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to delete global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callGlobalMethod(void* context, const char* objectName, const char* methodName) {
    if (!context || !objectName || !methodName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callGlobalMethod(asNativeContext(context), objectName, methodName, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call global method");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callGlobalFunctionWithStringArg(void* context, const char* functionName,
                                                const char* arg, char** resultOut) {
    if (!context || !functionName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callGlobalFunctionWithStringArg(asNativeContext(context), functionName, arg, resultOut, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call global function");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_installModuleLoader(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_installModuleLoader(asNativeContext(context), &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to install module loader");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callRequireMethod(void* context, const char* moduleId, const char* methodName) {
    if (!context || !moduleId || !methodName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callRequireMethod(asNativeContext(context), moduleId, methodName, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call require method");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_initRdmaChangesChannel(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    jsi::Runtime* rtPtr = getJsiRuntimeOrNull(context);
    if (!rtPtr) {
        return 0;
    }
    jsi::Runtime& rt = *rtPtr;

    jsi::Object rdmaObj(rt);

    auto appendCreateFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendCreate"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isNumber()) {
                throw jsi::JSError(runtime, "appendCreate expects (id, tag)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->createCb) {
                ctx->createCb(context, static_cast<int>(args[0].asNumber()),
                              static_cast<int>(args[1].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendCreate", appendCreateFn);

    auto appendPropertyChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendPropertyChange"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendPropertyChange expects (id, widgetTag, propertyTag, value)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (!ctx->propertyChangeCb) return jsi::Value::undefined();
            int handle = static_cast<int>(ctx->bridgeHandles.size());
            ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(runtime, args[3]));
            ctx->propertyChangeCb(context,
                static_cast<int>(args[0].asNumber()),
                static_cast<int>(args[1].asNumber()),
                static_cast<int>(args[2].asNumber()),
                 handle);
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendPropertyChange", appendPropertyChangeFn);

    auto appendModifierChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendModifierChange"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isObject()) {
                throw jsi::JSError(runtime, "appendModifierChange expects (id, elements)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (!ctx->modifierChangeCb) return jsi::Value::undefined();
            int handle = static_cast<int>(ctx->bridgeHandles.size());
            ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(runtime, args[1]));
            ctx->modifierChangeCb(context, static_cast<int>(args[0].asNumber()), handle);
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendModifierChange", appendModifierChangeFn);

    auto appendAddFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendAdd"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber()) {
                throw jsi::JSError(runtime, "appendAdd expects (id, childrenTag, childId, index)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->addCb) {
                ctx->addCb(context,
                    static_cast<int>(args[0].asNumber()),
                    static_cast<int>(args[1].asNumber()),
                    static_cast<int>(args[2].asNumber()),
                     static_cast<int>(args[3].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendAdd", appendAddFn);

    auto appendRemoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendRemove"), 3,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 3 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendRemove expects (id, childrenTag, index)");
            }
            ContextNative* ctx = asNativeContext(context);
            int id = static_cast<int>(args[0].asNumber());
            int ct = static_cast<int>(args[1].asNumber());
            int idx = static_cast<int>(args[2].asNumber());
            if (ctx->removeCb) {
                ctx->removeCb(context, id, ct, idx, 0);
            }
            return jsi::Value(ctx->removeCounter++);
        });
    rdmaObj.setProperty(rt, "appendRemove", appendRemoveFn);

    auto setRemoveDetachFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "setRemoveDetach"), 1,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isNumber()) {
                throw jsi::JSError(runtime, "setRemoveDetach expects (idx)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->setRemoveDetachCb) {
                ctx->setRemoveDetachCb(context, static_cast<int>(args[0].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "setRemoveDetach", setRemoveDetachFn);

    auto appendMoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendMove"), 5,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 5 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber() || !args[4].isNumber()) {
                throw jsi::JSError(runtime, "appendMove expects (id, childrenTag, fromIndex, toIndex, count)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->moveCb) {
                ctx->moveCb(context,
                    static_cast<int>(args[0].asNumber()),
                    static_cast<int>(args[1].asNumber()),
                    static_cast<int>(args[2].asNumber()),
                    static_cast<int>(args[3].asNumber()),
                     static_cast<int>(args[4].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendMove", appendMoveFn);

    auto appendBridgeChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendBridgeChange"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isObject()) {
                return jsi::Value::undefined();
            }
            ContextNative* ctx = asNativeContext(context);
            if (!ctx->bridgeChangeCb) return jsi::Value::undefined();
            int handle = static_cast<int>(ctx->bridgeHandles.size());
            ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(runtime, args[1]));
            ctx->bridgeChangeCb(context, static_cast<int>(args[0].asNumber()), handle);
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendBridgeChange", appendBridgeChangeFn);

    auto finishChangesFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "finishChanges"), 0,
        [context](jsi::Runtime&, const jsi::Value&,
           const jsi::Value*, size_t) -> jsi::Value {
            ContextNative* ctx = asNativeContext(context);
            ctx->removeCounter = 0;
            if (ctx->sendChangesCb) {
                ctx->sendChangesCb(context);
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "finishChanges", finishChangesFn);

    auto changesLengthFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "changesLength"), 0,
        [context](jsi::Runtime&, const jsi::Value&,
           const jsi::Value*, size_t) -> jsi::Value {
            return jsi::Value(asNativeContext(context)->removeCounter);
        });
    rdmaObj.setProperty(rt, "changesLength", changesLengthFn);

    rt.global().setProperty(rt, "app_cash_redwood_rdmaSendChanges", rdmaObj);
    return 1;
}

void HermesContext_setMemoryLimit(void* context, int64_t limitBytes) {
    if (context) {
        HermesCore_setMemoryLimit(asNativeContext(context), limitBytes);
    }
}

void HermesContext_setGcThreshold(void* context, int64_t thresholdBytes) {
    if (context) {
        HermesCore_setGcThreshold(asNativeContext(context), thresholdBytes);
    }
}

void HermesContext_setMaxStackSize(void* context, int64_t maxStackSizeBytes) {
    if (context) {
        HermesCore_setMaxStackSize(asNativeContext(context), maxStackSizeBytes);
    }
}

void HermesContext_gc(void* context) {
    if (context) {
        HermesCore_gc(asNativeContext(context));
    }
}

int HermesContext_getMemoryUsage(void* context, HermesCoreMemoryUsage* usageOut) {
    if (!context || !usageOut) {
        return 0;
    }
    return HermesCore_getMemoryUsage(asNativeContext(context), usageOut);
}

const char* Hermes_getVersion(void) {
    return HermesCore_getVersion();
}

const char* HermesContext_getLastError(void* context) {
    if (context) {
        const char* err = HermesCore_getLastError(asNativeContext(context));
        if (err && strlen(err) > 0) {
            return err;
        }
    }
    return g_lastError;
}

int HermesContext_setupOutboundCallChannel(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    jsi::Runtime* rtPtr = getJsiRuntimeOrNull(context);
    if (!rtPtr) {
        return 0;
    }
    jsi::Runtime& rt = *rtPtr;

    jsi::Object outboundChannel(rt);

    jsi::Function callFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "call"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "outboundChannel.call expects (callJson)");
            }
            std::string callJson = args[0].asString(runtime).utf8(runtime);

            ContextNative* ctx = asNativeContext(context);
            if (ctx->outboundCallFn) {
                char* result = (char*)ctx->outboundCallFn(context, callJson.c_str());
                if (result) {
                    jsi::Value jsResult = jsi::String::createFromUtf8(runtime, std::string(result));
                    free(result);
                    return jsResult;
                }
            }
            return jsi::Value::undefined();
        });
    outboundChannel.setProperty(rt, "call", callFn);

    jsi::Function disconnectFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "disconnect"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "outboundChannel.disconnect expects (instanceName)");
            }
            std::string instanceName = args[0].asString(runtime).utf8(runtime);

            ContextNative* ctx = asNativeContext(context);
            if (ctx->outboundDisconnectFn) {
                int result = ctx->outboundDisconnectFn(context, instanceName.c_str());
                return jsi::Value(result != 0);
            }
            return jsi::Value(false);
        });
    outboundChannel.setProperty(rt, "disconnect", disconnectFn);

    rt.global().setProperty(rt, "app_cash_zipline_outboundChannel", outboundChannel);
    return 1;
}

char* HermesContext_callInbound(void* context, const char* channelName, const char* callJson) {
    if (!context || !channelName || !callJson) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return NULL;
    }

    ContextNative* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return NULL;
    }

    // InboundCallChannel reports errors via throwJsException, captured in
    // the context's lastError and mapped to the C API error channel.
    InboundCallChannel channel(channelName);
    std::string result = channel.call(ctx, callJson);
    if (!ctx->lastError.empty()) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", ctx->lastError.c_str());
        return NULL;
    }
    return copyToMalloc(result);
}

char* HermesContext_callInboundDisconnect(void* context, const char* channelName, const char* instanceName) {
    if (!context || !channelName || !instanceName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return NULL;
    }

    ContextNative* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return NULL;
    }

    InboundCallChannel channel(channelName);
    bool result = channel.disconnect(ctx, instanceName);
    if (!ctx->lastError.empty()) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", ctx->lastError.c_str());
        return NULL;
    }
    return copyToMalloc(result ? "true" : "false");
}

// -- Bridge Handle Management (for Kotlin/Native generated bridge code) --
// Tag constants are defined as #defines in hermes-ios.h (BRIDGE_TAG_*).

int HermesBridge_createHandle(void* context, int parentHandle, const char* name) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    jsi::Runtime& rt = *ctx->runtime;

    // Allocate new handle
    int handle = static_cast<int>(ctx->bridgeHandles.size());

    if (parentHandle == 0) {
        // 0 = no parent, create from global object
        auto val = std::make_shared<jsi::Value>(rt.global().getProperty(rt, name));
        ctx->bridgeHandles.push_back(val);
    } else if (parentHandle >= 0 && static_cast<size_t>(parentHandle) < ctx->bridgeHandles.size()) {
        auto& parent = ctx->bridgeHandles[parentHandle];
        if (parent->isObject()) {
            auto val = std::make_shared<jsi::Value>(
                parent->asObject(rt).getProperty(rt, name));
            ctx->bridgeHandles.push_back(val);
        } else {
            return 0;
        }
    } else {
        return 0;
    }
    return handle;
}

int HermesBridge_createArrayElementHandle(void* context, int arrayHandle, int index) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    if (arrayHandle < 0 || static_cast<size_t>(arrayHandle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[arrayHandle];
    if (!val->isObject()) return 0;
    jsi::Object obj = val->asObject(rt);

    // Use getProperty by index so both plain JS arrays and Kotlin/JS typed
    // arrays (Int32Array, Float64Array, ...) are supported.
    jsi::Value elem = obj.getProperty(rt, std::to_string(index).c_str());
    int handle = static_cast<int>(ctx->bridgeHandles.size());
    ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(rt, elem));
    return handle;
}

int HermesBridge_getArrayLength(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return -1;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return -1;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[handle];
    if (!val->isObject()) return -1;
    jsi::Object obj = val->asObject(rt);

    jsi::Value lenVal = obj.getProperty(rt, "length");
    if (!lenVal.isNumber()) return -1;
    return static_cast<int>(lenVal.asNumber());
}

int HermesBridge_getValueTag(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return BRIDGE_TAG_UNDEFINED;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return BRIDGE_TAG_UNDEFINED;

    auto& val = ctx->bridgeHandles[handle];
    jsi::Runtime& rt = *ctx->runtime;

    if (val->isNull())    return BRIDGE_TAG_NULL;
    if (val->isUndefined()) return BRIDGE_TAG_UNDEFINED;
    if (val->isBool())   return BRIDGE_TAG_BOOL;

    if (val->isNumber()) {
        double d = val->asNumber();
        if (std::trunc(d) == d && d >= -2147483648.0 && d <= 2147483647.0)
            return BRIDGE_TAG_INT;
        return BRIDGE_TAG_DOUBLE;
    }

    if (val->isString()) return BRIDGE_TAG_STRING;

    if (val->isObject()) {
        jsi::Object obj = val->asObject(rt);
        if (obj.isArray(rt)) return BRIDGE_TAG_ARRAY;
        return BRIDGE_TAG_OBJECT;
    }

    return BRIDGE_TAG_UNDEFINED;
}

double HermesBridge_getValueDouble(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return 0.0;
    auto& val = ctx->bridgeHandles[handle];
    return val->isNumber() ? val->asNumber() : 0.0;
}

int HermesBridge_getValueBool(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return 0;
    auto& val = ctx->bridgeHandles[handle];
    return val->isBool() ? (val->asBool() ? 1 : 0) : 0;
}

char* HermesBridge_getValueString(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return NULL;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return NULL;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[handle];
    if (!val->isString()) return NULL;

    std::string s = val->asString(rt).utf8(rt);
    return strdup(s.c_str());  // caller frees with free()
}

void HermesBridge_freeHandle(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx) return;
    if (handle >= 0 && static_cast<size_t>(handle) < ctx->bridgeHandles.size()) {
        ctx->bridgeHandles[handle].reset();
    }
}

int HermesBridge_getObjectPropertyNames(void* context, int objectHandle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    if (objectHandle < 0 || static_cast<size_t>(objectHandle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[objectHandle];
    if (!val->isObject()) return 0;
    jsi::Object obj = val->asObject(rt);
    if (obj.isArray(rt)) return 0;

    jsi::Array names = obj.getPropertyNames(rt);
    int handle = static_cast<int>(ctx->bridgeHandles.size());
    ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(rt, names));
    return handle;
}

static jsi::Value bridgeFindMethod(jsi::Runtime& rt, const jsi::Value& obj, const char* prefix) {
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
                jsi::Value fn = o.getProperty(rt, name.c_str());
                if (fn.isObject() && fn.asObject(rt).isFunction(rt)) {
                    return fn;
                }
            }
        }
        proto = proto.asObject(rt).getProperty(rt, "__proto__");
    }
    return jsi::Value::undefined();
}

int HermesBridge_getMapEntries(void* context, int mapHandle, int* keysHandleOut, int* valuesHandleOut) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || !keysHandleOut || !valuesHandleOut) return 0;
    if (mapHandle < 0 || static_cast<size_t>(mapHandle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[mapHandle];
    if (!val || !val->isObject()) return 0;

    jsi::Object map = val->asObject(rt);

    try {
        // Kotlin/JS HashMap exposes its backing hash table via `internalMap`
        // (mangled get_internalMap_*): keysArray_1, valuesArray_1, presenceArray_1,
        // length_1. Iterate occupied slots (presence == 1).
        jsi::Value internalMapFn = bridgeFindMethod(rt, *val, "get_internalMap");
        if (internalMapFn.isUndefined()) return 0;
        jsi::Value internalMap = internalMapFn.asObject(rt).asFunction(rt).callWithThis(rt, map);
        if (!internalMap.isObject()) return 0;
        jsi::Object table = internalMap.asObject(rt);

        jsi::Value keysArr = table.getProperty(rt, "keysArray_1");
        jsi::Value valuesArr = table.getProperty(rt, "valuesArray_1");
        jsi::Value presenceArr = table.getProperty(rt, "presenceArray_1");
        jsi::Value lengthVal = table.getProperty(rt, "length_1");
        if (!keysArr.isObject() || !valuesArr.isObject() || !presenceArr.isObject() || !lengthVal.isNumber()) {
            return 0;
        }
        int length = static_cast<int>(lengthVal.asNumber());

        std::vector<jsi::Value> keyVec;
        std::vector<jsi::Value> valueVec;
        keyVec.reserve(length);
        valueVec.reserve(length);
        for (int i = 0; i < length; i++) {
            jsi::Value presence = presenceArr.asObject(rt).getProperty(rt, std::to_string(i).c_str());
            if (!presence.isNumber() || presence.asNumber() != 1.0) continue;
            keyVec.emplace_back(keysArr.asObject(rt).getProperty(rt, std::to_string(i).c_str()));
            valueVec.emplace_back(valuesArr.asObject(rt).getProperty(rt, std::to_string(i).c_str()));
        }

        jsi::Array keys = jsi::Array(rt, keyVec.size());
        jsi::Array values = jsi::Array(rt, valueVec.size());
        for (size_t i = 0; i < keyVec.size(); i++) {
            keys.setValueAtIndex(rt, i, keyVec[i]);
            values.setValueAtIndex(rt, i, valueVec[i]);
        }

        int keysHandle = static_cast<int>(ctx->bridgeHandles.size());
        ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(rt, keys));
        int valuesHandle = static_cast<int>(ctx->bridgeHandles.size());
        ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(rt, values));
        *keysHandleOut = keysHandle;
        *valuesHandleOut = valuesHandle;
        return 1;
    } catch (const jsi::JSError& e) {
        return 0;
    }
}

intptr_t HermesBridge_getBridgeDispatch(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[handle];
    if (!val->isObject()) return 0;

    jsi::Object obj = val->asObject(rt);
    jsi::Value lowVal = obj.getProperty(rt, "bridge_dispatch_low");
    jsi::Value highVal = obj.getProperty(rt, "bridge_dispatch_high");
    if (lowVal.isUndefined() || highVal.isUndefined()) {
        return 0;
    }

    int32_t low = static_cast<int32_t>(lowVal.asNumber());
    int32_t high = static_cast<int32_t>(highVal.asNumber());
    intptr_t result = (static_cast<intptr_t>(high) << 32) |
           static_cast<intptr_t>(static_cast<uint32_t>(low));
    return result;
}

void HermesBridge_clearHandles(void* context) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx) return;
    ctx->bridgeHandles.clear();
}

// -- Bridge dispatch registration (__bridgeRegister) --

static std::vector<std::pair<std::string, void*>> g_iosBridgeTable;
static std::mutex g_iosBridgeMutex;

void HermesBridge_addBridgeEntry(const char* fqn, void* fn) {
    std::lock_guard<std::mutex> lock(g_iosBridgeMutex);
    g_iosBridgeTable.push_back({fqn, fn});
}

void HermesBridge_installBridgeRegister(void* jsiRuntime) {
    if (!jsiRuntime) return;
    jsi::Runtime& rt = *static_cast<jsi::Runtime*>(jsiRuntime);

    auto bridgeRegisterFn = jsi::Function::createFromHostFunction(
        rt,
        jsi::PropNameID::forUtf8(rt, "__bridgeRegister"),
        2,
        [](jsi::Runtime& rt, const jsi::Value&, const jsi::Value* args, size_t argc) -> jsi::Value {
            if (argc < 2) return jsi::Value::undefined();
            auto fq = args[0].asString(rt).utf8(rt);
            if (!args[1].isObject()) return jsi::Value::undefined();
            jsi::Object ctor = args[1].asObject(rt);

            std::lock_guard<std::mutex> lock(g_iosBridgeMutex);
            for (auto& entry : g_iosBridgeTable) {
                if (entry.first == fq) {
                    jsi::Object proto = ctor.getPropertyAsObject(rt, "prototype");
                    intptr_t ptr = reinterpret_cast<intptr_t>(entry.second);
                    int32_t low  = static_cast<int32_t>(ptr & 0xFFFFFFFF);
                    int32_t high = static_cast<int32_t>((ptr >> 32) & 0xFFFFFFFF);
                    proto.setProperty(rt, "bridge_dispatch_low",  jsi::Value(static_cast<double>(low)));
                    proto.setProperty(rt, "bridge_dispatch_high", jsi::Value(static_cast<double>(high)));
                    return jsi::Value::undefined();
                }
            }
            return jsi::Value::undefined();
        });

    rt.global().setProperty(rt, "__bridgeRegister", std::move(bridgeRegisterFn));
}
