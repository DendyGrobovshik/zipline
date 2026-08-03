#ifndef ZIPLINE_CONTEXT_BASE_H
#define ZIPLINE_CONTEXT_BASE_H

#include <hermes/hermes.h>

#include <memory>
#include <string>
#include <vector>

class InboundCallChannel;
class OutboundCallChannel;

// The engine context, shared by all platform layers (JNI, Kotlin/Native).
// It owns the Hermes runtime and the last-error string used by the C API,
// and provides the virtual surface the shared call channels
// (InboundCallChannel/OutboundCallChannel) work against. Platform layers
// inherit from it to attach their own per-runtime state and to customize
// error reporting (see ContextJni, ContextNative); such layers must use
// HermesCore_initContext/HermesCore_releaseContext instead of
// createContext/destroyContext so the derived object is allocated and
// deleted with its own type.
//
// TODO: this header currently leaks Hermes C++ implementation details
// (<hermes/hermes.h>, std::unique_ptr member) into every consumer. If the
// consumer list grows beyond the two engine glue .cpps, hide the members
// behind a pimpl or a forward-declared base with a virtual destructor.
struct ContextBase {
  virtual ~ContextBase();

  virtual facebook::jsi::Runtime& getRuntime();
  virtual facebook::jsi::String toJsString(const std::string& str);
  virtual std::string toCppString(const facebook::jsi::String& str);

  // Non-throwing error contract: implementations record the error (default:
  // into lastError) instead of throwing. throwJsError is for JS errors
  // caught in a host function; platforms that can splice the JS stack into
  // the platform exception (see ContextJni) override it.
  virtual void throwJsException(const std::string& message);
  virtual void throwJsError(facebook::jsi::JSError& error);

  std::unique_ptr<facebook::hermes::HermesRuntime> runtime;
  std::string lastError;

 protected:
  // Channels created through this context; deleted with it.
  std::vector<InboundCallChannel*> inboundChannels;
  std::vector<OutboundCallChannel*> outboundChannels;
};

#endif  // ZIPLINE_CONTEXT_BASE_H
