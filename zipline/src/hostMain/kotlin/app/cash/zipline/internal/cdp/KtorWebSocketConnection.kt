@file:OptIn(ExperimentalAtomicApi::class)

package app.cash.zipline.internal.cdp

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A [CdpClientConnection] over any multiplatform [WebSocketSession]: the
 * Ktor CIO server session on JNI platforms, or a RawWebSocket over
 * ktor-network on Kotlin/Native.
 */
internal class KtorWebSocketClientConnection(
  private val ws: WebSocketSession,
  private val scope: CoroutineScope,
) : CdpClientConnection {
  private val open = AtomicBoolean(true)

  override fun sendText(text: String) {
    if (!open.load()) return
    scope.launch {
      try {
        ws.send(Frame.Text(text))
        ws.flush()
      } catch (_: Throwable) {
        open.store(false)
      }
    }
  }

  suspend fun sendPong(data: ByteArray) {
    try {
      ws.send(Frame.Pong(data))
      ws.flush()
    } catch (_: Throwable) {
      open.store(false)
    }
  }

  override fun isOpen(): Boolean = open.load()

  override fun closeQuietly() {
    if (open.compareAndSet(true, false)) {
      scope.launch {
        try {
          ws.send(Frame.Close())
          ws.flush()
        } catch (_: Throwable) {
        }
      }
    }
  }
}

/**
 * Drives one debugger client over [ws]: registers it with this debug
 * session, forwards text frames to [CdpDebugServer.DebugSession.onCdpMessage],
 * answers pings, and unregisters when the client disconnects.
 */
internal suspend fun CdpDebugServer.DebugSession.serveWebSocket(
  ws: WebSocketSession,
  scope: CoroutineScope,
) {
  val conn = KtorWebSocketClientConnection(ws, scope)
  addClient(conn)
  try {
    for (frame in ws.incoming) {
      when (frame) {
        is Frame.Text -> onCdpMessage(frame.readText())
        is Frame.Ping -> conn.sendPong(frame.data)
        is Frame.Close -> break
        else -> Unit
      }
    }
  } finally {
    removeClient(conn)
    conn.closeQuietly()
  }
}
