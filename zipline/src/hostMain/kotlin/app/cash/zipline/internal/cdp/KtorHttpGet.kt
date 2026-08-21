package app.cash.zipline.internal.cdp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * HTTP GET via the Ktor CIO client (multiplatform: JVM, Android, Native).
 * Returns the response body on HTTP 200, null on other statuses. Throws
 * [okio.IOException] on connection/IO errors.
 */
internal suspend fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
  try {
    val response = httpClient.get {
      url(url)
      timeout {
        connectTimeoutMillis = connectTimeoutMs.toLong()
        socketTimeoutMillis = readTimeoutMs.toLong()
        requestTimeoutMillis = (connectTimeoutMs + readTimeoutMs).toLong()
      }
    }
    return if (response.status.isSuccess()) response.bodyAsText() else null
  } catch (t: Throwable) {
    throw okio.IOException("fetch failed for $url", t)
  }
}

private val httpClient by lazy {
  HttpClient(CIO) {
    install(HttpTimeout)
  }
}
