/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef ZIPLINE_HERMES_OUTBOUNDCALLCHANNEL_H
#define ZIPLINE_HERMES_OUTBOUNDCALLCHANNEL_H

#include "hermes-core.h"

#include <jsi/jsi.h>
#include <string>

namespace facebook {
namespace jsi {
class Object;
class Runtime;
class Value;
}
}  // namespace facebook

class OutboundCallChannel {
 public:
  OutboundCallChannel(ContextBase* context, std::string name);
  virtual ~OutboundCallChannel();

  void attachToJavascript(facebook::jsi::Runtime& runtime,
                         facebook::jsi::Object& jsObject);

  virtual std::string call(const std::string& callJson) = 0;
  virtual bool disconnect(const std::string& instanceName) = 0;

 protected:
  ContextBase* context_;
  std::string name_;
};

#endif  // ZIPLINE_HERMES_OUTBOUNDCALLCHANNEL_H
