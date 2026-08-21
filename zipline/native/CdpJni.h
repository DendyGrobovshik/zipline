#ifndef ZIPLINE_CDP_JNI_H
#define ZIPLINE_CDP_JNI_H

#include <jni.h>
#include <memory>
#include <string>

class ContextJni;

// CDP (Chrome DevTools Protocol) debugging support for the JNI platforms.
// A session wraps a hermes CDPDebugAPI + CDPAgent pair bound to the context's
// runtime. CDP commands arrive from a transport (WebSocket) on arbitrary
// threads; outbound CDP messages are delivered to a Java listener; tasks that
// need exclusive runtime access are queued and drained on the JS thread.
namespace zipline_cdp {

struct Session;

// Starts a CDP session on the context. listener is an app.cash.zipline
// CdpListener instance; may be called on any thread. Returns false when a
// session is already attached or the engine was built without debugger
// support (HERMES_ENABLE_DEBUGGER off).
bool attach(ContextJni* ctx, JNIEnv* env, jobject listener);

// Forwards a CDP command (UTF-8 JSON). Safe to call from arbitrary threads.
void handleCommand(ContextJni* ctx, const std::string& json);

// Re-creates the CDP agent (preserving its state, e.g. breakpoints) so the next
// debugger client starts with fresh domain state and re-receives scriptParsed
// notifications when it enables the Debugger domain. Called when the last
// debugger client disconnects.
void resetAgent(ContextJni* ctx);

// Runs queued runtime tasks. Must be called on the JS thread, between JS
// executions (the engine calls this at call/execute boundaries; the transport
// may also schedule it when tasks are enqueued while JS is idle).
void drainTasks(ContextJni* ctx);

// Tears down the session (agent, debug API, Java listener). Called from
// ~ContextJni; may also be called explicitly from any thread.
void detach(ContextJni* ctx);

}  // namespace zipline_cdp

#endif  // ZIPLINE_CDP_JNI_H
