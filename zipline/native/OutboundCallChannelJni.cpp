#include "OutboundCallChannelJni.h"
#include "JniUtf8.h"

#include <jni.h>
#include <jsi/jsi.h>

#include <stdexcept>

#include "hermes-core.h"
#include "ContextJni.h"

namespace jsi = facebook::jsi;

OutboundCallChannelJni::OutboundCallChannelJni(ContextBase* context, JNIEnv* env, std::string name,
                                               jobject callChannel, const jsi::Object& jsObject)
    : OutboundCallChannel(context, std::move(name)),
      contextJni_(static_cast<ContextJni*>(context)),
      javaThis_(env->NewGlobalRef(callChannel)),
      callChannelClass_(static_cast<jclass>(env->NewGlobalRef(
          env->FindClass("app/cash/zipline/internal/bridge/CallChannel")))),
      callMethod_(env->GetMethodID(callChannelClass_, "call", "(Ljava/lang/String;)Ljava/lang/String;")),
      disconnectMethod_(env->GetMethodID(callChannelClass_, "disconnect", "(Ljava/lang/String;)Z")) {
  jsi::Runtime& rt = context->getRuntime();

  jsi::Object callFn = jsi::Function::createFromHostFunction(
      rt,
      jsi::PropNameID::forUtf8(rt, "call"),
      1,
      [this](jsi::Runtime& rt2, const jsi::Value& thisVal,
             const jsi::Value* args, size_t argc) -> jsi::Value {
        return outboundCallImpl(rt2, thisVal, args, argc, this);
      });
  jsObject.setProperty(rt, "call", callFn);

  jsi::Object disconnectFn = jsi::Function::createFromHostFunction(
      rt,
      jsi::PropNameID::forUtf8(rt, "disconnect"),
      1,
      [this](jsi::Runtime& rt2, const jsi::Value& thisVal,
             const jsi::Value* args, size_t argc) -> jsi::Value {
        return outboundDisconnectImpl(rt2, thisVal, args, argc, this);
      });
  jsObject.setProperty(rt, "disconnect", disconnectFn);
}

OutboundCallChannelJni::~OutboundCallChannelJni() {
  // JNIEnv is thread-local, so we must fetch one for the current thread
  // rather than reuse the (possibly foreign-thread) construction-time env.
  JNIEnv* env = contextJni_->getEnv();
  if (!env) return;
  env->DeleteGlobalRef(javaThis_);
  env->DeleteGlobalRef(callChannelClass_);
}

std::string OutboundCallChannelJni::call(const std::string& callJson) {
  JNIEnv* env = contextJni_->getEnv();
  if (!env) {
    throw std::runtime_error("OutboundCallChannel.call: no JNIEnv for current thread");
  }
  jstring javaArg = zipline::utf8ToJniString(env, callJson);
  jstring javaResult = static_cast<jstring>(
      env->CallObjectMethod(javaThis_, callMethod_, javaArg));
  env->DeleteLocalRef(javaArg);

  if (env->ExceptionCheck()) {
    // The Kotlin endpoint threw: stash the throwable and raise it as a JS
    // error so it propagates through JS and is re-thrown verbatim on the
    // outer JNI boundary (instead of feeding JS an empty result string).
    contextJni_->throwJavaExceptionFromJs(env);
  }

  std::string result = zipline::jniStringToUtf8(env, javaResult);
  env->DeleteLocalRef(javaResult);
  return result;
}

bool OutboundCallChannelJni::disconnect(const std::string& instanceName) {
  JNIEnv* env = contextJni_->getEnv();
  if (!env) {
    throw std::runtime_error("OutboundCallChannel.disconnect: no JNIEnv for current thread");
  }
  jstring javaArg = zipline::utf8ToJniString(env, instanceName);
  jboolean javaResult = env->CallBooleanMethod(javaThis_, disconnectMethod_, javaArg);
  env->DeleteLocalRef(javaArg);

  if (env->ExceptionCheck()) {
    contextJni_->throwJavaExceptionFromJs(env);
  }
  return javaResult != JNI_FALSE;
}

facebook::jsi::Value OutboundCallChannelJni::outboundCallImpl(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& /*thisVal*/,
    const facebook::jsi::Value* args,
    size_t argc,
    OutboundCallChannel* channel) {
  if (argc != 1 || !args[0].isString()) {
    throw jsi::JSError(rt, "OutboundCallChannel.call expects a single string arg");
  }
  auto* channelJni = static_cast<OutboundCallChannelJni*>(channel);
  std::string cppArg = args[0].asString(rt).utf8(rt);
  std::string result = channelJni->call(cppArg);
  return jsi::String::createFromUtf8(rt, result);
}

facebook::jsi::Value OutboundCallChannelJni::outboundDisconnectImpl(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value& /*thisVal*/,
    const facebook::jsi::Value* args,
    size_t argc,
    OutboundCallChannel* channel) {
  if (argc != 1 || !args[0].isString()) {
    throw jsi::JSError(rt, "OutboundCallChannel.disconnect expects a single string arg");
  }
  auto* channelJni = static_cast<OutboundCallChannelJni*>(channel);
  std::string cppArg = args[0].asString(rt).utf8(rt);
  bool result = channelJni->disconnect(cppArg);
  return jsi::Value(rt, result);
}