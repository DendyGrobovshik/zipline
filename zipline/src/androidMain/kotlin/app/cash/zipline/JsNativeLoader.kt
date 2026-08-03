/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.zipline

internal actual fun loadNativeLibrary() {
  // Combined Hermes + JNI glue library. CMake produces libhermesvmlean.so with
  // our JNI glue merged in (via zipline_glue static lib linked into hermesvmlean).
  // No separate libzipline_hermes_jni.so needed. Lean mode excludes JIT/parser.
  System.loadLibrary("hermesvmlean")
}
