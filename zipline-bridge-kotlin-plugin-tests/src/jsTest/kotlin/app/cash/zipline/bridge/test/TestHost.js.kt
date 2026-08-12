/*
 * JS host backend stub for BridgeEndToEndTest.
 *
 * The JS target only provides the guest; QuickJs does not exist on JS, so the host-side
 * tests cannot run here. These actuals exist only to satisfy the expect/actual contract
 * for the shared commonTest sources.
 */
package app.cash.zipline.bridge.test

actual fun decodeGuestBase64(encoded: String): ByteArray =
  throw UnsupportedOperationException("bridge host is not supported on the JS target")

actual class TestHost actual constructor() {
  actual fun loadGuest(): Unit =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun evaluateOne(script: String): Any? =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun close(): Unit =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")
}
