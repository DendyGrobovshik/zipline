#include "hermes-ios.h"
#include "hermes-core.h"
#include "../RdmaChange.h"
#include "../ContextNative.h"
#include "../CdpSession.h"
#include "../InboundCallChannel.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <string>
#include <vector>

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

namespace {

void* createRuntime(bool forceEagerCompilation) {
    ContextNative* ctx = new ContextNative();
    if (!HermesCore_initContext(ctx, forceEagerCompilation)) {
        delete ctx;
        snprintf(g_lastError, sizeof(g_lastError), "Failed to create Hermes runtime");
        return NULL;
    }
    return ctx;
}

} // namespace

void* HermesRuntime_create(void) {
    return createRuntime(false);
}

void* HermesRuntime_createForDebugging(void) {
    return createRuntime(true);
}

void HermesRuntime_destroy(void* runtime) {
    if (runtime) {
        ContextNative* ctx = asNativeContext(runtime);
        // Tear down any CDP session before the runtime goes away; the
        // session's agent and debug API reference the runtime.
        zipline_cdp::detach(ctx);
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

    // Run any pending CDP runtime tasks (e.g. breakpoint installation)
    // before evaluating more JavaScript. We are on the JS thread here.
    zipline_cdp::drainTasks(ctx);

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

    // Run any pending CDP runtime tasks (e.g. breakpoint installation)
    // before executing more bytecode. We are on the JS thread here.
    zipline_cdp::drainTasks(ctx);

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

    jsi::Function appendCreateFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendCreate"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isNumber()) {
                throw jsi::JSError(runtime, "appendCreate expects (id, tag)");
            }
            ContextNative* ctx = asNativeContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Create;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendCreate", appendCreateFn);

    jsi::Function appendPropertyChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendPropertyChange"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendPropertyChange expects (id, widgetTag, propertyTag, value)");
            }
            ContextNative* ctx = asNativeContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::PropertyChange;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.jsValue = std::make_shared<jsi::Value>(runtime, args[3]);
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendPropertyChange", appendPropertyChangeFn);

    jsi::Function appendModifierChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendModifierChange"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isObject()) {
                throw jsi::JSError(runtime, "appendModifierChange expects (id, elements)");
            }
            ContextNative* ctx = asNativeContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::ModifierChange;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.jsValue = std::make_shared<jsi::Value>(runtime, args[1]);
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendModifierChange", appendModifierChangeFn);

    jsi::Function appendAddFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendAdd"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber()) {
                throw jsi::JSError(runtime, "appendAdd expects (id, childrenTag, childId, index)");
            }
            ContextNative* ctx = asNativeContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Add;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.field3 = static_cast<int>(args[3].asNumber());
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendAdd", appendAddFn);

    jsi::Function appendRemoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendRemove"), 3,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 3 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendRemove expects (id, childrenTag, index)");
            }
            ContextNative* ctx = asNativeContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Remove;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.detach = false;
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            // The counter tracks removes only (matching the original Kotlin
            // protocol): each remove gets an ordinal that the JS side passes
            // back to setRemoveDetach().
            int removeOrdinal = ctx->removeCounter++;
            return jsi::Value(removeOrdinal);
        });
    rdmaObj.setProperty(rt, "appendRemove", appendRemoveFn);

    jsi::Function setRemoveDetachFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "setRemoveDetach"), 1,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isNumber()) {
                throw jsi::JSError(runtime, "setRemoveDetach expects (idx)");
            }
            ContextNative* ctx = asNativeContext(context);
            // idx is the remove ordinal returned by appendRemove(), not an
            // index into pendingChanges: find the idx-th Remove change.
            int idx = static_cast<int>(args[0].asNumber());
            int removeOrdinal = 0;
            for (RdmaChange& ch : ctx->pendingChanges) {
                if (ch.type != RdmaChangeType::Remove) continue;
                if (removeOrdinal == idx) {
                    ch.detach = true;
                    break;
                }
                removeOrdinal++;
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "setRemoveDetach", setRemoveDetachFn);

    jsi::Function appendMoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendMove"), 5,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 5 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber() || !args[4].isNumber()) {
                throw jsi::JSError(runtime, "appendMove expects (id, childrenTag, fromIndex, toIndex, count)");
            }
            ContextNative* ctx = asNativeContext(context);
            RdmaChange ch;
            ch.type = RdmaChangeType::Move;
            ch.id = static_cast<int>(args[0].asNumber());
            ch.field1 = static_cast<int>(args[1].asNumber());
            ch.field2 = static_cast<int>(args[2].asNumber());
            ch.field3 = static_cast<int>(args[3].asNumber());
            ch.count = static_cast<int>(args[4].asNumber());
            ch.jsValue = nullptr;
            ctx->pendingChanges.push_back(std::move(ch));
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendMove", appendMoveFn);

    jsi::Function finishChangesFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "finishChanges"), 0,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            // Flush all pending changes to the Kotlin RDMA sink via the
            // rdmaChangeSink function pointer. The C++ side manages the
            // pendingChanges list and removeCounter per context; only the
            // final sendChanges() call delegates to Kotlin.
            RdmaChangeSinkFn sinkFn = NULL;
            ContextNative* ctx = asNativeContext(context);
            ctx->removeCounter = 0;
            sinkFn = ctx->rdmaSinkFn;
            if (sinkFn) {
                sinkFn(context);
            }
            ctx->pendingChanges.clear();
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "finishChanges", finishChangesFn);

    jsi::Function changesLengthFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "changesLength"), 0,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
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

    // Run any pending CDP runtime tasks (e.g. breakpoint installation)
    // before calling into JavaScript. We are on the JS thread here.
    zipline_cdp::drainTasks(ctx);

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

// ---------------------------------------------------------------------------
// CDP debugging. Bridges the C function-pointer API to the platform-neutral
// CDP session core (CdpSession.cpp).

namespace {

// The listener handle handed to the session core: the platform's opaque
// listener pointer plus the C callbacks to invoke for it.
struct IosCdpListener {
    void* listener;
    CdpMessageFn messageFn;
    CdpTasksEnqueuedFn tasksEnqueuedFn;
    CdpListenerDisposedFn disposedFn;
};

void iosCdpOnMessage(void* ref, const std::string& json) {
    IosCdpListener* listener = static_cast<IosCdpListener*>(ref);
    listener->messageFn(listener->listener, json.c_str());
}

void iosCdpOnTasksEnqueued(void* ref) {
    IosCdpListener* listener = static_cast<IosCdpListener*>(ref);
    listener->tasksEnqueuedFn(listener->listener);
}

void iosCdpDispose(void* ref) {
    IosCdpListener* listener = static_cast<IosCdpListener*>(ref);
    if (!listener) return;
    if (listener->disposedFn) {
        listener->disposedFn(listener->listener);
    }
    delete listener;
}

} // namespace

int HermesContext_cdpAttach(void* context, void* listener,
                            CdpMessageFn messageFn,
                            CdpTasksEnqueuedFn tasksEnqueuedFn,
                            CdpListenerDisposedFn disposedFn) {
    if (!context || !listener || !messageFn || !tasksEnqueuedFn) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    IosCdpListener* iosListener = new IosCdpListener();
    iosListener->listener = listener;
    iosListener->messageFn = messageFn;
    iosListener->tasksEnqueuedFn = tasksEnqueuedFn;
    iosListener->disposedFn = disposedFn;

    zipline_cdp::Listener coreListener;
    coreListener.ref = iosListener;
    coreListener.onMessage = &iosCdpOnMessage;
    coreListener.onTasksEnqueued = &iosCdpOnTasksEnqueued;
    coreListener.dispose = &iosCdpDispose;
    // The core disposes iosListener if the session is not created (e.g.
    // builds without HERMES_ENABLE_DEBUGGER, or a duplicate attach).
    if (!zipline_cdp::attach(asNativeContext(context), coreListener)) {
        snprintf(g_lastError, sizeof(g_lastError),
                 "Engine does not support debugging (HERMES_ENABLE_DEBUGGER off?)");
        return 0;
    }
    return 1;
}

void HermesContext_cdpHandleCommand(void* context, const char* json) {
    if (!context || !json) return;
    zipline_cdp::handleCommand(asNativeContext(context), std::string(json));
}

void HermesContext_cdpDrainTasks(void* context) {
    if (!context) return;
    zipline_cdp::drainTasks(asNativeContext(context));
}

void HermesContext_cdpResetAgent(void* context) {
    if (!context) return;
    zipline_cdp::resetAgent(asNativeContext(context));
}
