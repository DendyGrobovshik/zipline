/*
 * Copyright (C) 2021 Square, Inc.
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

/**
 * Introspect the Hermes runtime for its current memory usage. Fields mirror
 * the `hermes_*` keys of `facebook::jsi::Instrumentation::getHeapInfo`.
 */
@EngineApi
data class MemoryUsage(
  /** Bytes reserved by the GC heap (`hermes_heapSize`). */
  val heapSize: Long,

  /** Bytes of live JS heap objects (`hermes_allocatedBytes`). */
  val allocatedBytes: Long,

  /** Cumulative bytes allocated over the runtime lifetime (`hermes_totalAllocatedBytes`). */
  val totalAllocatedBytes: Long,

  /** Virtual address space of the heap (`hermes_va`). */
  val va: Long,

  /** Memory associated with JS objects but allocated outside the GC heap (`hermes_externalBytes`). */
  val externalBytes: Long,

  /** Estimate of malloc memory attributable to the runtime (`hermes_mallocSizeEstimate`). */
  val mallocSizeEstimate: Long,

  /** High-water mark of [allocatedBytes] (`hermes_peakAllocatedBytes`). */
  val peakAllocatedBytes: Long,

  /** High-water mark of live bytes measured just after a GC (`hermes_peakLiveAfterGC`). */
  val peakLiveAfterGC: Long,

  /** Total GC collections so far (`hermes_numCollections`). */
  val numCollections: Long,

  /** Times the GC mark stack overflowed (`hermes_numMarkStackOverflows`). */
  val numMarkStackOverflows: Long,
) {
  /** Best-effort total footprint: live heap plus out-of-heap malloc memory. */
  val usedBytes: Long
    get() = allocatedBytes + mallocSizeEstimate + externalBytes
}
