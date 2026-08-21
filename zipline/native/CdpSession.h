#ifndef ZIPLINE_CDP_SESSION_H
#define ZIPLINE_CDP_SESSION_H

#include <string>

struct ContextBase;

// CDP (Chrome DevTools Protocol) debugging support, shared by all platform
// layers (JNI, Kotlin/Native). A session wraps a hermes CDPDebugAPI +
// CDPAgent pair bound to the context's runtime. CDP commands arrive from a
// transport (WebSocket) on arbitrary threads; outbound CDP messages are
// delivered through the Listener callbacks; tasks that need exclusive
// runtime access are queued and drained on the JS thread.
//
// Platform glue (CdpJni.cpp, hermes-ios.cpp) adapts its own listener
// representation (JNI object refs, Kotlin/Native staticCFunction pointers)
// to the function-pointer Listener below.
namespace zipline_cdp {

struct Session;

// Outbound CDP notifications delivered by the session to the platform
// transport. All callbacks may be invoked from arbitrary threads (including
// during detach, from ~CDPAgent).
struct Listener {
  // Opaque platform handle (e.g. a heap struct holding a JNI global ref).
  void* ref = nullptr;
  // A CDP response or event (UTF-8 JSON) ready to send to the frontend.
  void (*onMessage)(void* ref, const std::string& json) = nullptr;
  // One or more runtime tasks were enqueued and need drainTasks() to run on
  // the JS thread at the next opportunity.
  void (*onTasksEnqueued)(void* ref) = nullptr;
  // Releases ref. Called when the session is torn down, when attach fails,
  // or when attach is called while a session already exists.
  void (*dispose)(void* ref) = nullptr;
};

// Starts a CDP session on the context. May be called on any thread. Takes
// ownership of listener.ref in all outcomes (dispose is called when the
// listener is not or no longer needed). Returns false when the engine was
// built without debugger support (HERMES_ENABLE_DEBUGGER off); returns true
// when a session is active after the call (attaching twice is idempotent).
bool attach(ContextBase* ctx, Listener listener);

// Forwards a CDP command (UTF-8 JSON). Safe to call from arbitrary threads.
void handleCommand(ContextBase* ctx, const std::string& json);

// Re-creates the CDP agent (preserving its state, e.g. breakpoints) so the
// next debugger client starts with fresh domain state and re-receives
// scriptParsed notifications when it enables the Debugger domain. Called
// when the last debugger client disconnects.
void resetAgent(ContextBase* ctx);

// Runs queued runtime tasks. Must be called on the JS thread, between JS
// executions (the engine glue calls this at call/execute/evaluate
// boundaries; the transport may also schedule it when tasks are enqueued
// while JS is idle).
void drainTasks(ContextBase* ctx);

// Tears down the session (agent, debug API, listener). Called from the
// context teardown; may also be called explicitly from any thread.
void detach(ContextBase* ctx);

}  // namespace zipline_cdp

#endif  // ZIPLINE_CDP_SESSION_H
