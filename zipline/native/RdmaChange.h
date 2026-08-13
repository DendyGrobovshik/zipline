#ifndef ZIPLINE_RDMA_CHANGE_H
#define ZIPLINE_RDMA_CHANGE_H

#include <jsi/jsi.h>

#include <memory>

// RDMA Changes support, shared by the JNI (Android/JVM) and iOS engine
// layers. Both accumulate RdmaChange entries from JS host functions and
// flush them to the platform sink (redwood-treehouse).
enum class RdmaChangeType {
  Create,
  PropertyChange,
  ModifierChange,
  Add,
  Remove,
  Move,
  BridgeChange,
};

constexpr int RDMA_BATCH_SIZE = 2048;

struct RdmaChange {
  RdmaChangeType type;
  int id;
  int field1;    // tag (Create/Remove/Move), widgetTag (PropertyChange), childrenTag (Add)
  int field2;    // propertyTag (PropertyChange), childId (Add), index (Remove), fromIndex (Move)
  int field3;    // index (Add), toIndex (Move)
  int count;     // count (Move only)
  bool detach;   // detach flag (Remove only)
  // jsi::Value has no copy ctor; we share ownership of the underlying JS
  // value so it stays alive until the change is flushed to the platform.
  std::shared_ptr<facebook::jsi::Value> jsValue;

  RdmaChange() = default;
  RdmaChange(const RdmaChange&) = default;
  RdmaChange& operator=(const RdmaChange&) = default;
  RdmaChange(RdmaChange&&) = default;
  RdmaChange& operator=(RdmaChange&&) = default;
};

#endif  // ZIPLINE_RDMA_CHANGE_H
