package app.cash.zipline.bridge.test

import java.util.Base64

actual fun decodeGuestBase64(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)
