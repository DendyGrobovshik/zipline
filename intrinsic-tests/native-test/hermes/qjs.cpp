#include <jsi/jsi.h>
#include <hermes/hermes.h>
#include "../../../zipline/native/common/intset-builtins.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fstream>
#include <sstream>
#include <iostream>

namespace jsi = facebook::jsi;

// Simple REPL-like function to evaluate JS code
std::string evalJs(jsi::Runtime& rt, const std::string& code) {
    try {
        jsi::Value result = rt.global().getProperty(rt, "eval").asObject(rt).asFunction(rt).call(rt, {
            jsi::String::createFromUtf8(rt, code)
        });
        if (result.isString()) {
            return result.asString(rt).utf8(rt);
        }
        return "undefined";
    } catch (const jsi::JSError& e) {
        return "Error: " + std::string(e.what());
    }
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <js-file>" << std::endl;
        return 1;
    }

    // Create Hermes runtime
    auto runtime = facebook::hermes::makeHermesRuntime();
    if (!runtime) {
        std::cerr << "Failed to create Hermes runtime" << std::endl;
        return 1;
    }

    // Register intrinsics
    js_register_intrinsics(runtime.get());

    // Minimal console shim: Kotlin/JS println() maps to console.log, which
    // bare Hermes does not provide.
    {
        jsi::Object console(*runtime);
        auto logFn = [](jsi::Runtime& rt, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            for (size_t i = 0; i < count; i++) {
                if (args[i].isString()) {
                    printf("%s", args[i].asString(rt).utf8(rt).c_str());
                } else if (args[i].isNumber()) {
                    printf("%g", args[i].asNumber());
                } else if (args[i].isBool()) {
                    printf("%s", args[i].asBool() ? "true" : "false");
                } else if (args[i].isNull()) {
                    printf("null");
                } else if (args[i].isUndefined()) {
                    printf("undefined");
                } else {
                    printf("[object]");
                }
                if (i + 1 < count) printf(" ");
            }
            printf("\n");
            return jsi::Value::undefined();
        };
        console.setProperty(
            *runtime, "log",
            jsi::Function::createFromHostFunction(
                *runtime, jsi::PropNameID::forUtf8(*runtime, "log"), 1, logFn));
        console.setProperty(
            *runtime, "error",
            jsi::Function::createFromHostFunction(
                *runtime, jsi::PropNameID::forUtf8(*runtime, "error"), 1, logFn));
        console.setProperty(
            *runtime, "warn",
            jsi::Function::createFromHostFunction(
                *runtime, jsi::PropNameID::forUtf8(*runtime, "warn"), 1, logFn));
        runtime->global().setProperty(*runtime, "console", console);
    }

    // Read and evaluate the JS file
    std::ifstream file(argv[1]);
    if (!file.is_open()) {
        std::cerr << "Failed to open file: " << argv[1] << std::endl;
        return 1;
    }

    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string code = buffer.str();

    std::string result = evalJs(*runtime, code);
    std::cout << result << std::endl;

    // evalJs prefixes uncaught JS exceptions with "Error: ".
    return result.rfind("Error: ", 0) == 0 ? 1 : 0;
}