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
#ifndef ZIPLINE_HERMES_EXCEPTIONTHROWERS_H
#define ZIPLINE_HERMES_EXCEPTIONTHROWERS_H

#include <jni.h>

class ContextJni;

void throwJavaException(JNIEnv* env, const char* exceptionClass, const char* fmt, ...);
void throwJsExceptionFmt(JNIEnv* env, const ContextJni* context, const char* fmt, ...);

#endif  // ZIPLINE_HERMES_EXCEPTIONTHROWERS_H
