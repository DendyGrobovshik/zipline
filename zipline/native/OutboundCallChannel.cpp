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
#include "OutboundCallChannel.h"

#include <jsi/jsi.h>

namespace jsi = facebook::jsi;

OutboundCallChannel::OutboundCallChannel(ContextBase* context, std::string name)
    : context_(context), name_(std::move(name)) {}

OutboundCallChannel::~OutboundCallChannel() = default;

void OutboundCallChannel::attachToJavascript(jsi::Runtime& runtime, jsi::Object& jsObject) {
  jsi::Object callFn = jsi::Function::createFromHostFunction(
      runtime,
      jsi::PropNameID::forUtf8(runtime, "call"),
      1,
      [this](jsi::Runtime& rt, const jsi::Value& thisVal,
             const jsi::Value* args, size_t argc) -> jsi::Value {
        if (argc < 1 || !args[0].isString()) {
          throw jsi::JSError(rt, "OutboundCallChannel.call expects a string argument");
        }
        std::string arg = args[0].asString(rt).utf8(rt);
        std::string result = call(arg);
        return jsi::String::createFromUtf8(rt, result);
      });
  jsObject.setProperty(runtime, "call", callFn);

  jsi::Object disconnectFn = jsi::Function::createFromHostFunction(
      runtime,
      jsi::PropNameID::forUtf8(runtime, "disconnect"),
      1,
      [this](jsi::Runtime& rt, const jsi::Value& thisVal,
             const jsi::Value* args, size_t argc) -> jsi::Value {
        if (argc < 1 || !args[0].isString()) {
          throw jsi::JSError(rt, "OutboundCallChannel.disconnect expects a string argument");
        }
        std::string arg = args[0].asString(rt).utf8(rt);
        bool result = disconnect(arg);
        return jsi::Value(rt, result);
      });
  jsObject.setProperty(runtime, "disconnect", disconnectFn);
}
