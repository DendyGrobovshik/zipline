#include <jni.h>
#include <cstdlib>
#include <new>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "ContextJni.h"
#include "CdpJni.h"
#include "JniUtf8.h"
#include "ExceptionThrowers.h"
#include "InboundCallChannel.h"
#include "bridge_dispatch.h"

#include <jsi/jsi.h>

// Android log macros - available to all functions in this file
#ifdef __ANDROID__
#define JSI_LOG_DEBUG(tag, ...) __android_log_print(ANDROID_LOG_DEBUG, tag, __VA_ARGS__)
#define JSI_LOG_INFO(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#define JSI_LOG_WARN(tag, ...) __android_log_print(ANDROID_LOG_WARN, tag, __VA_ARGS__)
#define JSI_LOG_ERROR(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
#else
#define JSI_LOG_DEBUG(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#define JSI_LOG_INFO(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#define JSI_LOG_WARN(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#define JSI_LOG_ERROR(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#endif

namespace {

inline ContextJni* toContext(jlong p) {
  return reinterpret_cast<ContextJni*>(p);
}

inline std::string jstringToCppString(JNIEnv* env, jstring javaString) {
  return zipline::jniStringToUtf8(env, javaString);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_cash_zipline_JsEngine_createContext(JNIEnv* env, jclass /*clazz*/,
                                             jboolean forceEagerCompilation) {
  ContextJni* c = new (std::nothrow) ContextJni(env, forceEagerCompilation == JNI_TRUE);
  if (!c) {
    throwJavaException(env, "java/lang/OutOfMemoryError",
                       "Cannot allocate Hermes Context");
    return 0L;
  }
  return reinterpret_cast<jlong>(c);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_destroyContext(JNIEnv* /*env*/, jobject /*thiz*/,
                                            jlong context) {
  delete toContext(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_cash_zipline_JsEngine_getInboundCallChannel(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring name) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return 0L;
  }
  return reinterpret_cast<jlong>(ctx->getInboundCallChannel(env, name));
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setOutboundCallChannel(JNIEnv* env, jobject /*thiz*/,
                                                    jlong _context, jstring name,
                                                    jobject callChannel) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->setOutboundCallChannel(env, name, callChannel);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_cash_zipline_JsEngine_getJsContext(JNIEnv*, jclass, jlong context_) {
  return context_;
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_bridgeInitAllNative(JNIEnv* env, jclass, jlong jsContext) {
  ContextJni* ctx = toContext(jsContext);
  if (!ctx) return;
  jsi::Runtime& rt = ctx->getRuntime();
  init_all(env);
  register_all(rt);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_initRdmaChangesChannel(JNIEnv* env, jobject /*thiz*/,
                                                     jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->initRdmaChangesChannel(env);
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_cash_zipline_JsEngine_execute(JNIEnv* env, jobject /*thiz*/,
                                    jlong _context, jbyteArray bytecode,
                                    jstring fileName) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  return ctx->execute(env, bytecode, fileName);
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_cash_zipline_JsEngine_evaluate(JNIEnv* env, jobject /*thiz*/,
                                    jlong _context, jstring source,
                                    jstring fileName) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  return ctx->evaluate(env, source, fileName);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_cash_zipline_JsEngine_compile(JNIEnv* env, jobject /*thiz*/,
                                     jlong _context, jstring source,
                                     jstring filename, jstring sourceMap) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  return ctx->compile(env, source, filename, sourceMap);
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_cash_zipline_JsEngine_memoryUsage(JNIEnv* env, jobject /*thiz*/,
                                        jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  return ctx->memoryUsage(env);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_gc(JNIEnv* env, jobject /*thiz*/, jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->gc(env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_cash_zipline_JniCallChannel_call(JNIEnv* env, jobject /*thiz*/,
                                          jlong _context, jlong instance,
                                          jstring callJson) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  auto* channel = reinterpret_cast<const InboundCallChannel*>(instance);
  if (!channel) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "Invalid JavaScript object");
    return nullptr;
  }
  // Run any pending CDP runtime tasks before calling into JavaScript. We are
  // on the JS thread here.
  zipline_cdp::drainTasks(ctx);
  std::string result = channel->call(ctx, jstringToCppString(env, callJson));
  return zipline::utf8ToJniString(env, result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_cash_zipline_JniCallChannel_disconnect(JNIEnv* env, jobject /*thiz*/,
                                                jlong _context, jlong instance,
                                                jstring instanceName) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                        "JsEngine instance was closed");
    return JNI_FALSE;
  }
  auto* channel = reinterpret_cast<const InboundCallChannel*>(instance);
  if (!channel) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "Invalid JavaScript object");
    return JNI_FALSE;
  }
  return channel->disconnect(ctx, jstringToCppString(env, instanceName)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_cash_zipline_JsEngine_getGlobalProperty(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring name) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  std::string propName = jstringToCppString(env, name);
  char* value = nullptr;
  char* error = nullptr;
  if (!HermesCore_getGlobalProperty(ctx, propName.c_str(), &value, &error)) {
    // Not present or not a string (previous behavior), or an engine error.
    if (error) {
      throwJavaException(env, "java/lang/IllegalStateException", "%s", error);
      free(error);
    }
    return nullptr;
  }
  jstring result = value ? zipline::utf8ToJniString(env, value) : nullptr;
  free(value);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setGlobalProperty(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring name,
                                                  jstring value) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  std::string propName = jstringToCppString(env, name);
  std::string propValue = jstringToCppString(env, value);
  char* error = nullptr;
  if (!HermesCore_setGlobalProperty(ctx, propName.c_str(), propValue.c_str(), &error)) {
    throwJavaException(env, "java/lang/IllegalStateException", "%s",
                       error ? error : "setGlobalProperty failed");
    free(error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_deleteGlobalProperty(JNIEnv* env, jobject /*thiz*/,
                                                     jlong _context, jstring name) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  std::string propName = jstringToCppString(env, name);
  char* error = nullptr;
  if (!HermesCore_deleteGlobalProperty(ctx, propName.c_str(), &error)) {
    throwJavaException(env, "java/lang/IllegalStateException", "%s",
                       error ? error : "deleteGlobalProperty failed");
    free(error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_callGlobalMethod(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring objectName,
                                                  jstring methodName) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  std::string objName = jstringToCppString(env, objectName);
  std::string mtdName = jstringToCppString(env, methodName);
  char* error = nullptr;
  if (!HermesCore_callGlobalMethod(ctx, objName.c_str(), mtdName.c_str(), &error)) {
    throwJavaException(env, "java/lang/IllegalStateException", "%s",
                       error ? error : "callGlobalMethod failed");
    free(error);
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_cash_zipline_JsEngine_callGlobalFunctionWithStringArg(JNIEnv* env, jobject /*thiz*/,
                                                                  jlong _context, jstring functionName,
                                                                  jstring arg) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  std::string fnName = jstringToCppString(env, functionName);
  std::string argStr = jstringToCppString(env, arg);
  char* resultOut = nullptr;
  char* error = nullptr;
  if (!HermesCore_callGlobalFunctionWithStringArg(
          ctx, fnName.c_str(), argStr.c_str(), &resultOut, &error)) {
    throwJavaException(env, "java/lang/IllegalStateException", "%s",
                       error ? error : "callGlobalFunctionWithStringArg failed");
    free(error);
    return nullptr;
  }
  jstring result = resultOut ? zipline::utf8ToJniString(env, resultOut) : nullptr;
  free(resultOut);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_callRequireMethod(JNIEnv* env, jobject /*thiz*/,
                                                   jlong _context, jstring moduleId,
                                                   jstring methodName) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  std::string modId = jstringToCppString(env, moduleId);
  std::string mtdName = jstringToCppString(env, methodName);
  char* error = nullptr;
  if (!HermesCore_callRequireMethod(ctx, modId.c_str(), mtdName.c_str(), &error)) {
    throwJavaException(env, "java/lang/IllegalStateException", "%s",
                       error ? error : "callRequireMethod failed");
    free(error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_installModuleLoader(JNIEnv* env, jobject /*thiz*/,
                                                   jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  char* error = nullptr;
  if (!HermesCore_installModuleLoader(ctx, &error)) {
    throwJavaException(env, "java/lang/IllegalStateException", "%s",
                       error ? error : "installModuleLoader failed");
    free(error);
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_cash_zipline_JsEngine_cdpAttach(JNIEnv* env, jobject /*thiz*/,
                                         jlong _context, jobject listener) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return JNI_FALSE;
  }
  return zipline_cdp::attach(ctx, env, listener) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_cdpHandleCommand(JNIEnv* env, jobject /*thiz*/,
                                                jlong _context, jstring json) {
  ContextJni* ctx = toContext(_context);
  if (!ctx || !json) {
    return;
  }
  zipline_cdp::handleCommand(ctx, jstringToCppString(env, json));
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_cdpDrainTasks(JNIEnv* /*env*/, jobject /*thiz*/,
                                             jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    return;
  }
  zipline_cdp::drainTasks(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_cdpResetAgent(JNIEnv* /*env*/, jobject /*thiz*/,
                                             jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    return;
  }
  zipline_cdp::resetAgent(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_cdpDetach(JNIEnv* /*env*/, jobject /*thiz*/,
                                         jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    return;
  }
  zipline_cdp::detach(ctx);
}
