package app.cash.zipline.internal.cdp

import app.cash.zipline.internal.log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8LineTo
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.RawWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.coroutineContext

/**
 * The CDP debug server for all platforms: ktor-network sockets on loopback
 * plus the ktor-websockets frame codec (Ktor's server engines are JVM-only,
 * so the HTTP request parsing and upgrade handshake stay manual — the same
 * few lines the Ktor plugin would run anyway).
 */
private class KtorNetworkCdpServer(
  private val port: Int,
  private val core: CdpDebugServer,
) : CdpServerHandle {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun start() {
    // Bind eagerly so a port conflict fails synchronously (attachIfEnabled
    // logs and disables debugging in that case).
    val server = runBlocking {
      aSocket(selectorManager).tcp().bind("127.0.0.1", port)
    }
    scope.launch {
      log("info", "Zipline CDP debug server listening on port $port (ktor-network)", null)
      while (isActive) {
        val client = try {
          server.accept()
        } catch (t: Throwable) {
          break // Server socket closed.
        }
        launch { handleConnection(client) }
      }
    }
  }

  private suspend fun handleConnection(socket: Socket) {
    val read = socket.openReadChannel()
    val write = socket.openWriteChannel(autoFlush = true)
    try {
      val request = readHttpRequest(read) ?: return socket.closeQuietly()
      val path = request.path.substringBefore('?')
      val host = request.headers["host"]

      when {
        path == "/json/version" -> {
          writeHttpResponse(write, 200, "OK", core.versionJson(host))
          socket.closeQuietly()
        }

        path == "/json" || path == "/json/list" -> {
          writeHttpResponse(write, 200, "OK", core.targetsJson(host))
          socket.closeQuietly()
        }

        path.startsWith("/devtools/page/") -> {
          val id = path.removePrefix("/devtools/page/")
          val session = core.session(id)
          val key = request.headers["sec-websocket-key"]
          val upgrade = request.headers["upgrade"]?.lowercase()
          if (session == null || key == null || upgrade != "websocket") {
            writeHttpResponse(write, 404, "Not Found", "[]")
            socket.closeQuietly()
            return
          }
          writeWebSocketUpgrade(write, key)
          session.serveWebSocket(
            RawWebSocket(
              read,
              write,
              maxFrameSize = 64L * 1024L * 1024L,
              masking = false,
              coroutineContext,
            ),
            scope,
          )
        }

        else -> {
          writeHttpResponse(write, 404, "Not Found", "[]")
          socket.closeQuietly()
        }
      }
    } catch (t: Throwable) {
      log("warn", "Zipline CDP: connection failed: ${t.message}", t)
      socket.closeQuietly()
    }
  }

  private class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
  )

  private suspend fun readHttpRequest(read: ByteReadChannel): HttpRequest? {
    val requestLine = readHttpLine(read) ?: return null
    if (requestLine.isEmpty()) return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) return null
    val headers = LinkedHashMap<String, String>()
    while (true) {
      val line = readHttpLine(read) ?: return null
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon > 0) {
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
      }
    }
    return HttpRequest(parts[0], parts[1], headers)
  }

  private suspend fun readHttpLine(read: ByteReadChannel): String? {
    val line = StringBuilder()
    if (!read.readUTF8LineTo(line, 16384)) return null
    return line.toString().trimEnd('\r')
  }

  private suspend fun writeHttpResponse(
    write: ByteWriteChannel,
    status: Int,
    statusText: String,
    body: String,
  ) {
    write.writeStringUtf8(
      "HTTP/1.1 $status $statusText\r\n" +
        "Content-Type: application/json; charset=utf-8\r\n" +
        "Content-Length: ${body.encodeToByteArray().size}\r\n" +
        "Connection: close\r\n\r\n$body",
    )
    write.flush()
  }

  private suspend fun writeWebSocketUpgrade(write: ByteWriteChannel, secWebSocketKey: String) {
    // okio provides both primitives (SHA-1 digest, base64 encoding).
    val accept = (secWebSocketKey + WEBSOCKET_GUID).encodeToByteArray().toByteString().sha1().base64()
    write.writeStringUtf8(
      "HTTP/1.1 101 Switching Protocols\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Accept: $accept\r\n\r\n",
    )
    write.flush()
  }

  private fun Socket.closeQuietly() {
    try {
      close()
    } catch (_: Throwable) {
    }
  }

  companion object {
    private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private val selectorManager by lazy { SelectorManager(Dispatchers.Default) }
  }
}

internal fun initCdpServer(port: Int, core: CdpDebugServer): CdpServerHandle =
  KtorNetworkCdpServer(port, core)
