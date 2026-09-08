/*
 * Kotlin/Native host backend for BridgeEndToEndTest.
 *
 * evaluateForBridge evaluates the script directly and runs the raw JS value through
 * bridgeForAny, which dispatches to the generated X_toKotlin converters (registered via
 * @EagerInitialization in generated_bridges).
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.JsEngine
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
actual class TestHost actual constructor() {
  private val jsEngine = JsEngine.create()

  init {
    initModuleLoader(jsEngine)
  }

  actual fun loadGuest() {
    for ((id, base64) in GeneratedGuest.modules) {
      loadJsModule(jsEngine, id, decodeGuestModule(base64))
    }
  }

  actual fun evaluateOne(script: String): Any? = jsEngine.evaluateForBridge(script, "test.js")

  actual fun close() {
    jsEngine.close()
  }
}
