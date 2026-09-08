package app.cash.zipline.bridge.test

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
actual fun decodeGuestBase64(encoded: String): ByteArray = Base64.decode(encoded)

/**
 * Decodes an embedded guest module (a ZiplineFile container) into raw QuickJS bytecode.
 * The container format is parsed with the canonical [app.cash.zipline.loader.ZiplineFile] reader.
 * Host-only: the loader has no JS target, so this lives in the platform actual rather than commonTest.
 */
internal fun decodeGuestModule(encoded: String): ByteArray {
  val container = app.cash.zipline.loader.ZiplineFile.read(
    okio.Buffer().write(decodeGuestBase64(encoded)),
  )
  return container.jsBytecode.toByteArray()
}
