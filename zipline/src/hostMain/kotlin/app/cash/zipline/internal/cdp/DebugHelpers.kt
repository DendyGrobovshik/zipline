package app.cash.zipline.internal.cdp

import kotlinx.coroutines.launch

/**
 * Platform plumbing for the CDP debug server: threads, locks, HTTP fetches
 * and port configuration. Actuals exist for JNI (java.net/Thread) and
 * Kotlin/Native (POSIX/coroutines) platforms.
 */

/**
 * Starts a background thread named [name] running [block], swallowing any
 * failure. Implemented with coroutines on every platform (a fresh
 * single-thread context per call, i.e. a real thread on JVM and a worker on
 * Kotlin/Native). Note these threads are not daemon-marked on JVM; the debug
 * server is a debug-time feature whose host processes exit via System.exit.
 */
@kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal fun startDebugThread(name: String, block: () -> Unit) {
  kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.newSingleThreadContext(name), block = {
    try {
      block()
    } catch (_: Throwable) {
      // A crashing debug-server thread must not take down the host app.
    }
  })
}

/** The TCP port the CDP debug server should listen on, or null when debugging is disabled. */
internal expect fun cdpDebugPort(): Int?

internal interface CdpServerHandle {
  /** Starts accepting connections (returns immediately). */
  fun start()
}

/**
 * Extra URL variants to try when fetching script sources from the dev server,
 * after the loopback-literal variant and the URL itself (e.g. the Android
 * emulator NAT alias 10.0.2.2 for localhost URLs).
 */
internal expect fun extraFetchCandidates(url: String): List<String>
