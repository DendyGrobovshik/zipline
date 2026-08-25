@file:OptIn(ExperimentalForeignApi::class)

package app.cash.zipline.internal.cdp

import app.cash.zipline.Zipline
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Kotlin/Native plumbing for the CDP debug server: the ZIPLINE_CDP_PORT env
 * var read and the fetch-candidate list. Everything else (server, WebSocket,
 * HTTP fetches) is multiplatform Ktor code shared with the JNI platforms.
 */
internal actual fun cdpDebugPort(): Int? =
  Zipline.cdpDebugPort ?: getenv("ZIPLINE_CDP_PORT")?.toKString()?.toIntOrNull()

internal actual fun extraFetchCandidates(url: String): List<String> = emptyList()
