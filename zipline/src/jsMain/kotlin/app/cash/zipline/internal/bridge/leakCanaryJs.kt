/*
 * Copyright (C) 2022 Block, Inc.
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
package app.cash.zipline.internal.bridge

import app.cash.zipline.ZiplineService

// TODO: enable leak canary once JsEngine ships FinalizationRegistry
//  (https://github.com/facebook/jsEngine/blob/main/doc/Features.md).
//  Until then the JS-side tracking primitives would throw on load, so we
//  replace them with no-op stubs. The Kotlin/Native side has its own
//  implementation in leakCanaryNative.kt that does NOT use
//  FinalizationRegistry.

internal actual fun trackLeaks(
  endpoint: Endpoint,
  serviceName: String,
  callHandler: OutboundCallHandler,
  service: ZiplineService,
) {
  // no-op
}

internal actual fun detectLeaks() {
  // no-op
}
