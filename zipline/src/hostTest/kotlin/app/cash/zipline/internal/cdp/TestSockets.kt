package app.cash.zipline.internal.cdp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket as KtorServerSocket
import io.ktor.network.sockets.Socket as KtorSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Blocking sockets for tests (the mini dev server in CdpDebugTest), backed by
 * ktor-network, which is multiplatform. The production CDP transports are
 * Ktor-based; this shim exists only so tests can speak plain HTTP over
 * loopback. All methods throw [okio.IOException] on IO errors.
 */
private val testSocketSelectorManager by lazy { SelectorManager(Dispatchers.Default) }

/** Blocking server socket. Accepts loopback connections only. */
internal class DebugServerSocket(port: Int) {
  private val server: KtorServerSocket = runBlocking {
    aSocket(testSocketSelectorManager).tcp().bind("127.0.0.1", port)
  }

  /** Blocks until a client connects. Throws [okio.IOException] when closed. */
  fun accept(): DebugSocket = DebugSocket(runBlocking { server.accept() })

  fun close() = server.close()
}

/** Blocking connected socket; [write] serializes concurrent writers. */
internal class DebugSocket(
  private val socket: KtorSocket,
) {
  private val readChannel = socket.openReadChannel()
  private val writeChannel = socket.openWriteChannel(autoFlush = false)

  fun setTcpNoDelay() {
    // No-op: loopback test traffic doesn't need it.
  }

  /** Reads a single byte, or -1 on EOF. */
  fun read(): Int = runBlocking {
    try {
      readChannel.readByte().toInt() and 0xFF
    } catch (_: Throwable) {
      -1
    }
  }

  /** Reads up to [length] bytes into [buffer] at [offset]; returns the count, or -1 on EOF. */
  fun readInto(buffer: ByteArray, offset: Int, length: Int): Int = runBlocking {
    readChannel.readAvailable(buffer, offset, length)
  }

  /** Writes all of [bytes]. Test-only: call from a single thread per socket. */
  fun write(bytes: ByteArray) {
    runBlocking {
      writeChannel.writeFully(bytes, 0, bytes.size)
      writeChannel.flush()
    }
  }

  fun close() = socket.close()
}

/** Closes quietly. */
internal fun DebugSocket.closeQuietly() {
  try {
    close()
  } catch (_: okio.IOException) {
  }
}
