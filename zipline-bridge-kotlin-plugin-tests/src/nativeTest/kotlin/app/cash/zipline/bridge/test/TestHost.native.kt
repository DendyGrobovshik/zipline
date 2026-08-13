/*
 * Kotlin/Native host backend for BridgeEndToEndTest.
 *
 * evaluateForBridge evaluates the script directly and runs the raw JS value through
 * bridgeForAny, which dispatches to the generated X_toKotlin converters (registered via
 * @EagerInitialization in generated_bridges).
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.QuickJs
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
actual class TestHost actual constructor() {
  private val quickJs = QuickJs.create()

  init {
    initModuleLoader(quickJs)
  }

  actual fun loadGuest() {
    for ((id, base64) in GeneratedGuest.modules) {
      loadJsModule(quickJs, id, decodeGuestModule(base64))
    }
  }

  actual fun evaluateOne(script: String): Any? = quickJs.evaluateForBridge(script, "test.js")

  actual fun close() {
    quickJs.close()
  }
}
