#include "CdpJni.h"

#include "CdpSession.h"
#include "ContextJni.h"
#include "JniUtf8.h"

#if HERMES_ENABLE_DEBUGGER

// JNI adapter for the platform-neutral CDP session (CdpSession.cpp). All
// session logic (server, task queue, locking) lives in the shared core; this
// file only bridges the Java app.cash.zipline.CdpListener object to the
// core's function-pointer Listener.

namespace zipline_cdp {

namespace {

// The listener's platform handle: a global ref to the Java CdpListener plus
// the JVM it belongs to (for attaching transport threads).
struct JniListener {
  JavaVM* javaVm = nullptr;
  jobject globalRef = nullptr;
  jmethodID onMessageId = nullptr;
  jmethodID onTasksEnqueuedId = nullptr;
};

// Returns a JNIEnv for the current thread, attaching it to the JVM if needed.
// If the thread was attached by this call, *attachedOut is set to true and the
// caller must detach when done calling into Java.
JNIEnv* envForCurrentThread(JavaVM* vm, bool* attachedOut) {
  *attachedOut = false;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
    return env;
  }
  // The C++ JNI headers (Android NDK) take JNIEnv** here, the C headers
  // (Oracle/OpenJDK) take void**.
#ifdef __ANDROID__
  if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
#else
  if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
#endif
    return nullptr;
  }
  *attachedOut = true;
  return env;
}

void jniOnMessage(void* ref, const std::string& json) {
  JniListener* listener = static_cast<JniListener*>(ref);
  bool attached;
  JNIEnv* env = envForCurrentThread(listener->javaVm, &attached);
  if (!env) return;
  jstring j = zipline::utf8ToJniString(env, json);
  env->CallVoidMethod(listener->globalRef, listener->onMessageId, j);
  if (j) env->DeleteLocalRef(j);
  // Listener failures must not crash the engine thread.
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) listener->javaVm->DetachCurrentThread();
}

void jniOnTasksEnqueued(void* ref) {
  JniListener* listener = static_cast<JniListener*>(ref);
  bool attached;
  JNIEnv* env = envForCurrentThread(listener->javaVm, &attached);
  if (!env) return;
  env->CallVoidMethod(listener->globalRef, listener->onTasksEnqueuedId);
  // Listener failures must not crash the engine thread.
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) listener->javaVm->DetachCurrentThread();
}

void jniDispose(void* ref) {
  JniListener* listener = static_cast<JniListener*>(ref);
  if (!listener) return;
  bool attached;
  JNIEnv* env = envForCurrentThread(listener->javaVm, &attached);
  if (env) {
    env->DeleteGlobalRef(listener->globalRef);
    if (attached) listener->javaVm->DetachCurrentThread();
  }
  delete listener;
}

}  // namespace

bool attach(ContextJni* ctx, JNIEnv* env, jobject listener) {
  if (!ctx || !listener) return false;

  jclass listenerClass = env->GetObjectClass(listener);
  jmethodID onMessageId =
      env->GetMethodID(listenerClass, "onMessage", "(Ljava/lang/String;)V");
  jmethodID onTasksEnqueuedId =
      env->GetMethodID(listenerClass, "onTasksEnqueued", "()V");
  if (!onMessageId || !onTasksEnqueuedId) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return false;
  }

  JniListener* jniListener = new JniListener();
  jniListener->javaVm = ctx->javaVm;
  jniListener->onMessageId = onMessageId;
  jniListener->onTasksEnqueuedId = onTasksEnqueuedId;
  jniListener->globalRef = env->NewGlobalRef(listener);
  if (!jniListener->globalRef) {
    // Out of memory: the pending OutOfMemoryError propagates to Java.
    delete jniListener;
    return false;
  }

  Listener coreListener;
  coreListener.ref = jniListener;
  coreListener.onMessage = &jniOnMessage;
  coreListener.onTasksEnqueued = &jniOnTasksEnqueued;
  coreListener.dispose = &jniDispose;
  // The core disposes jniListener if the session is not created.
  return attach(ctx, coreListener);
}

void handleCommand(ContextJni* ctx, const std::string& json) {
  handleCommand(static_cast<ContextBase*>(ctx), json);
}

void resetAgent(ContextJni* ctx) {
  resetAgent(static_cast<ContextBase*>(ctx));
}

void drainTasks(ContextJni* ctx) {
  drainTasks(static_cast<ContextBase*>(ctx));
}

void detach(ContextJni* ctx) {
  detach(static_cast<ContextBase*>(ctx));
}

}  // namespace zipline_cdp

#else  // !HERMES_ENABLE_DEBUGGER

// Prod (lean) builds ship without the CDP agent: all entry points no-op.
namespace zipline_cdp {

bool attach(ContextJni*, JNIEnv*, jobject) { return false; }
void handleCommand(ContextJni*, const std::string&) {}
void resetAgent(ContextJni*) {}
void drainTasks(ContextJni*) {}
void detach(ContextJni*) {}

}  // namespace zipline_cdp

#endif  // HERMES_ENABLE_DEBUGGER
