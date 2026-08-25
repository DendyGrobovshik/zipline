@file:OptIn(DelicateCoroutinesApi::class)

package app.cash.zipline.internal.cdp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A CDP WebSocket client for tests, backed by the Ktor client (JVM + Native). */
internal class CdpTestClient private constructor(
  private val outgoing: Channel<String>,
  private val incoming: Channel<String>,
  private val scope: CoroutineScope,
) : AutoCloseable {
  /** Queues a CDP command (id + method + optional trailing params snippet). */
  fun send(id: Int, method: String, extra: String = "") {
    val json = buildString {
      append("""{"id":""").append(id).append(""","method":""").append('"').append(method).append('"')
      if (extra.isNotEmpty()) append(',').append(extra)
      append('}')
    }
    outgoing.trySend(json)
  }

  suspend fun awaitResponse(id: Int): String = awaitMessage(""""id":$id""")

  suspend fun awaitEvent(method: String): String = awaitMessage(""""method":"$method"""")

  suspend fun awaitEventContaining(marker: String): String = awaitMessage(marker)

  /** Awaits a message containing [marker] (and [andAlso] when given). */
  /** The next raw frame, or null after [timeoutMs] without one. */
  suspend fun nextEvent(timeoutMs: Long = Long.MAX_VALUE): String? {
    if (stash.isNotEmpty()) return stash.removeAt(0)
    val deadline = TimeSource.Monotonic.markNow()
    while (timeoutMs == Long.MAX_VALUE || deadline.elapsedNow().inWholeMilliseconds < timeoutMs) {
      val message = incoming.tryReceive().getOrNull()
      if (message != null) return message
      delay(5)
    }
    return null
  }

  private val stash = mutableListOf<String>()

  suspend fun awaitMessage(marker: String, andAlso: String? = null): String {
    fun String.matches() = contains(marker) && (andAlso == null || contains(andAlso))
    stash.indexOfFirst { it.matches() }.let { index ->
      if (index != -1) return stash.removeAt(index)
    }
    val deadline = TimeSource.Monotonic.markNow()
    while (deadline.elapsedNow().inWholeSeconds < 30) {
      val message = incoming.tryReceive().getOrNull()
      if (message == null) {
        delay(5)
        continue
      }
      if (message.matches()) return message
      stash.add(message)
    }
    throw AssertionError("timed out waiting for $marker")
  }

  override fun close() {
    scope.cancel()
  }

  companion object {
    private val httpClient = HttpClient(CIO) {
      install(WebSockets)
    }

    /** Connects to `/devtools/page/<sessionId>`; returns once the handshake completed. */
    fun connect(port: Int, sessionId: String): CdpTestClient {
      val outgoing = Channel<String>(Channel.UNLIMITED)
      val incoming = Channel<String>(Channel.UNLIMITED)
      val connected = CompletableDeferred<Unit>()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      scope.launch {
        try {
          httpClient.webSocket("ws://127.0.0.1:$port/devtools/page/$sessionId") {
            connected.complete(Unit)
            launch {
              for (json in outgoing) {
                send(Frame.Text(json))
              }
            }
            for (frame in this@webSocket.incoming) {
              if (frame is Frame.Text) {
                incoming.trySend(frame.readText())
              }
            }
          }
        } finally {
          if (!connected.isCompleted) {
            connected.completeExceptionally(okio.IOException("connection failed"))
          }
          incoming.close()
        }
      }
      runBlocking { connected.await() }
      return CdpTestClient(outgoing, incoming, scope)
    }
  }
}
