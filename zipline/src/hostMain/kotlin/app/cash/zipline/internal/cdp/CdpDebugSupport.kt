package app.cash.zipline.internal.cdp

import app.cash.zipline.JsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

/**
 * Entry point for CDP debugging of a [JsEngine]. Debugging is enabled by
 * [app.cash.zipline.Zipline.cdpDebugPort] (with the `ZIPLINE_CDP_PORT` environment
 * variable as a fallback on Kotlin/Native); [app.cash.zipline.Zipline.create] then
 * attaches each new engine to a shared debug server on that port.
 */
internal object CdpDebugSupport {
  private val serverLock = kotlinx.coroutines.sync.Mutex()
  private var server: CdpDebugServer? = null

  fun attachIfEnabled(jsEngine: JsEngine, scope: CoroutineScope) {
    val port = cdpDebugPort() ?: return
    val server = runBlocking { serverLock.withLock {
      server ?: try {
        CdpDebugServer(port).also {
          initCdpServer(port, it).start()
          server = it
        }
      } catch (t: Throwable) {
        // E.g. another process (or an earlier Zipline) already bound the port.
        // Debugging is best-effort; never take down Zipline creation.
        app.cash.zipline.internal.log(
          "warn",
          "Zipline CDP: cannot bind port $port (${t.message}); debugging disabled",
          null,
        )
        null
      }
    } } ?: return
    server.attach(jsEngine, scope)
  }

  fun detach(jsEngine: JsEngine) {
    runBlocking { serverLock.withLock { server } }?.detach(jsEngine)
  }
}
