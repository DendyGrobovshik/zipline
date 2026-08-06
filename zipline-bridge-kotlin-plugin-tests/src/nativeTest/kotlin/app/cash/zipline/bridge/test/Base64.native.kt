package app.cash.zipline.bridge.test

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
actual fun decodeGuestBase64(encoded: String): ByteArray = Base64.decode(encoded)
