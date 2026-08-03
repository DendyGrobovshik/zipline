/*
 * Copyright (C) 2024 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline

internal actual fun rssBytes(): Long {
  return try {
    val pid = ProcessHandle.current().pid()
    val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString())
      .redirectErrorStream(true)
      .start()
    // MallocStackLogging prints warnings into ps output; the RSS value is the
    // last non-empty line.
    val output = process.inputStream.bufferedReader().readLines()
      .lastOrNull { it.isNotBlank() }?.trim() ?: return -1L
    process.waitFor()
    output.toLong() * 1024L // ps reports KiB
  } catch (e: Exception) {
    -1L
  }
}

internal actual fun heapUsedBytes(): Long {
  val rt = Runtime.getRuntime()
  return rt.totalMemory() - rt.freeMemory()
}

internal actual fun gcCollect() {
  System.gc()
}
