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

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

/**
 * High-water-mark RSS from getrusage (bytes on Darwin). Monotone, so only
 * growth is meaningful — exactly what the leak checks need.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun rssBytes(): Long = memScoped {
  val usage = alloc<rusage>()
  if (getrusage(RUSAGE_SELF, usage.ptr) == 0) usage.ru_maxrss else -1L
}

/** No managed host heap on Kotlin/Native; the check is skipped for -1. */
internal actual fun heapUsedBytes(): Long = -1L

@OptIn(NativeRuntimeApi::class)
internal actual fun gcCollect() {
  GC.collect()
}
