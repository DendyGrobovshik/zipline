package app.cash.zipline.internal.cdp

import app.cash.zipline.Zipline

internal actual fun cdpDebugPort(): Int? =
  Zipline.cdpDebugPort ?: generatedCdpDebugPort

/**
 * The port baked into the app by the Zipline Gradle plugin
 * (`zipline { cdpDebugPort = ... }` generates app.cash.zipline.ZiplineCdpConfig), or null
 * when the app didn't set one. Read reflectively once: the class is generated into the
 * app, so this precompiled library cannot reference it statically.
 */
private val generatedCdpDebugPort: Int? by lazy {
  runCatching {
    Class.forName("app.cash.zipline.ZiplineCdpConfig")
      .getField("CDP_DEBUG_PORT")
      .getInt(null)
  }.getOrNull()
}

internal actual fun extraFetchCandidates(url: String): List<String> {
  // The Android emulator NAT alias for the host machine.
  val nat = url.replace("://localhost:", "://10.0.2.2:").replace("://127.0.0.1:", "://10.0.2.2:")
  return if (nat != url) listOf(nat) else emptyList()
}
