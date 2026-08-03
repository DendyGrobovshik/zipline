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

import app.cash.zipline.testing.compileTestingJsModules
import app.cash.zipline.testing.loadTestingJs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Process resident memory in bytes, or -1 if unavailable on this platform. */
internal expect fun rssBytes(): Long

/** Java/Kotlin heap used bytes, or -1 if not applicable (native). */
internal expect fun heapUsedBytes(): Long

/** Force a full GC on this platform. */
internal expect fun gcCollect()

/**
 * Tracks total memory while creating and destroying many [Zipline] instances.
 *
 * The leak canary only watches [ZiplineService] references; this test watches
 * the whole process: the host-language heap and resident set size (heap +
 * native memory, including Hermes runtimes). Neither may grow linearly with
 * the number of instances created and closed.
 */
class MemoryTrackingTest {
  /**
   * Footprint sample that drains pending finalizers first. Closed Zipline
   * instances release their native Hermes runtime from finalizers, so a single
   * read can catch a transient of not-yet-finalized instances. The min of a
   * few GC-separated reads approximates the true footprint.
   */
  private fun footprintSample(): Pair<Long, Long> {
    var heapMin = Long.MAX_VALUE
    var rssMin = Long.MAX_VALUE
    repeat(3) {
      gcCollect()
      heapUsedBytes().let { if (it >= 0) heapMin = minOf(heapMin, it) }
      rssBytes().let { if (it >= 0) rssMin = minOf(rssMin, it) }
    }
    return (if (heapMin == Long.MAX_VALUE) -1L else heapMin) to
      (if (rssMin == Long.MAX_VALUE) -1L else rssMin)
  }

  @Test
  fun createAndDestroyDoesNotLeak() = runBlocking {
    // Modules are compiled once and only bytecode is executed per cycle —
    // the production bytecode-cache scenario. Without per-cycle compilation
    // there is no long convergence tail (RSS is flat from the start on both
    // platforms), so warmup is short and limits are tight.
    val warmup = 10
    val iterations = 100

    val compiler = Zipline.create(Dispatchers.Default)
    val modules = compileTestingJsModules(compiler.jsEngine)
    compiler.close()

    fun oneCycle() {
      val zipline = Zipline.create(Dispatchers.Default)
      for ((id, bytecode) in modules) {
        zipline.loadJsModule(bytecode, id)
      }
      zipline.jsEngine.evaluate("globalThis['testing'] = require('./zipline-root-zipline-testing.js');")
      zipline.jsEngine.evaluate("globalThis.blob = new Array(1000).fill('x').join('');")
      zipline.jsEngine.gc()
      zipline.close()
    }

    repeat(warmup) { oneCycle() }
    val (heapBaseline, rssBaseline) = footprintSample()
    check(rssBaseline > 0) { "RSS measurement unavailable on this platform" }

    val samples = mutableListOf<Pair<Long, Long>>()
    repeat(iterations) {
      oneCycle()
      if (it % 10 == 9) {
        samples += footprintSample()
      }
    }
    val (heapFinal, rssFinal) = footprintSample()

    println(
      "memoryTracking: warmup=$warmup iterations=$iterations" +
        " heapBaseline=$heapBaseline heapFinal=$heapFinal" +
        " rssBaseline=$rssBaseline rssFinal=$rssFinal" +
        " samples(heap,rss)=$samples"
    )

    if (heapBaseline >= 0) {
      // Host-language heap must return essentially to baseline after GC: the
      // cycle holds no host objects between iterations. Observed JVM growth
      // is <0.5 KB/cycle; budget: 1 KB/cycle.
      val heapGrowth = heapFinal - heapBaseline
      assertTrue(
        heapGrowth < iterations * 1L * 1024,
        "heap grew by $heapGrowth bytes over $iterations create/close cycles",
      )
    }

    // A real leak scales linearly with iterations (each Zipline holds a
    // multi-MB Hermes runtime, so a true per-cycle leak would add hundreds
    // of MB here). With cached bytecode the observed drift is <50 KB/cycle;
    // budget: 128 KB/cycle.
    val rssGrowth = rssFinal - rssBaseline
    assertTrue(
      rssGrowth < iterations * 128L * 1024,
      "RSS grew by $rssGrowth bytes over $iterations create/close cycles" +
        " (baseline=$rssBaseline final=$rssFinal)",
    )

    // Convergence decelerates; a leak does not. Comparing halves (rather
    // than a point-to-point slope) averages out sample noise. Only checked
    // when total growth exceeds the 64 KB/cycle noise floor — below that,
    // finalizer-timing noise (drops and rebounds) dominates and the trend
    // check is meaningless.
    if (rssGrowth > iterations * 64L * 1024) {
      val rssSamples = samples.map { it.second }
      val firstHalfGrowth = rssSamples[rssSamples.size / 2] - rssSamples[0]
      val secondHalfGrowth = rssSamples.last() - rssSamples[rssSamples.size / 2]
      assertTrue(
        secondHalfGrowth <= 0L || secondHalfGrowth < firstHalfGrowth,
        "RSS growth is not decelerating: firstHalf=$firstHalfGrowth" +
          " secondHalf=$secondHalfGrowth samples=$rssSamples",
      )
    }
  }

  /**
   * Emits per-cycle memory samples as CSV lines (`memprofile,cycle,heap,rss`)
   * with no warmup, for plotting the full convergence curve. Extract from the
   * test XML and plot with `zipline/plot_memory_profile.py`.
   */
  @Test
  fun memoryProfileForPlot() = runBlocking {
    val iterations = 100
    println("memprofile,cycle,heapBytes,rssBytes")
    repeat(iterations) { i ->
      val zipline = Zipline.create(Dispatchers.Default)
      zipline.loadTestingJs()
      zipline.jsEngine.evaluate("globalThis.blob = new Array(1000).fill('x').join('');")
      zipline.jsEngine.gc()
      zipline.close()
      gcCollect()
      println("memprofile,${i + 1},${heapUsedBytes()},${rssBytes()}")
    }
  }

  /**
   * Same as [memoryProfileForPlot] but modules are compiled ONCE and only
   * bytecode is executed per cycle — the production bytecode-cache scenario.
   * Skips the per-cycle compiler transient that dominates native RSS churn.
   * Emits `memprofile-bc` lines.
   */
  @Test
  fun memoryProfileForPlotBytecode() = runBlocking {
    val compiler = Zipline.create(Dispatchers.Default)
    val modules = compileTestingJsModules(compiler.jsEngine)
    compiler.close()

    val iterations = 100
    println("memprofile-bc,cycle,heapBytes,rssBytes")
    repeat(iterations) { i ->
      val zipline = Zipline.create(Dispatchers.Default)
      for ((id, bytecode) in modules) {
        zipline.loadJsModule(bytecode, id)
      }
      zipline.jsEngine.evaluate("globalThis['testing'] = require('./zipline-root-zipline-testing.js');")
      zipline.jsEngine.evaluate("globalThis.blob = new Array(1000).fill('x').join('');")
      zipline.jsEngine.gc()
      zipline.close()
      gcCollect()
      println("memprofile-bc,${i + 1},${heapUsedBytes()},${rssBytes()}")
    }
  }

  /** A live instance reports a sane, growing-then-collectable Hermes heap. */
  @Test
  fun hermesHeapReportsLiveAndCollectedBytes() = runBlocking {
    val zipline = Zipline.create(Dispatchers.Default)
    try {
      val before = zipline.jsEngine.memoryUsage
      // Structural invariants on a fresh runtime.
      assertTrue(before.heapSize > 0, "heap size should be positive: $before")
      assertTrue(before.allocatedBytes > 0, "allocated bytes should be positive: $before")
      assertTrue(before.allocatedBytes <= before.heapSize, "allocated must fit in heap: $before")
      assertTrue(before.heapSize <= before.va, "heap must fit in reserved VA: $before")
      assertTrue(before.totalAllocatedBytes >= before.allocatedBytes, "cumulative >= live: $before")
      // Peaks are only updated at the end of a GC, so they start at zero.
      assertTrue(before.peakAllocatedBytes == 0L, "no GC yet, peak not recorded: $before")
      assertTrue(before.peakLiveAfterGC == 0L, "no GC yet, peak live not recorded: $before")
      assertTrue(before.mallocSizeEstimate >= 0, "malloc estimate is non-negative: $before")
      assertTrue(before.externalBytes >= 0, "external bytes is non-negative: $before")
      assertTrue(before.numMarkStackOverflows == 0L, "no mark stack overflows expected: $before")

      // 200 000 small ints: at least ~8 bytes/element backing store in the GC
      // heap, so the live set must grow by a measurable, lower-bounded amount.
      zipline.jsEngine.evaluate("globalThis.blob = new Array(200000).fill(0);")
      val grown = zipline.jsEngine.memoryUsage
      val growth = grown.allocatedBytes - before.allocatedBytes
      assertTrue(
        growth >= 512L * 1024,
        "expected >=512KB growth from a 200k-element array: before=${before.allocatedBytes} " +
          "after=${grown.allocatedBytes}",
      )
      assertTrue(grown.allocatedBytes <= grown.heapSize, "allocated must fit in heap: $grown")
      assertTrue(
        grown.totalAllocatedBytes > before.totalAllocatedBytes,
        "cumulative allocations must increase: $before -> $grown",
      )

      // Explicit GC must reclaim (almost) all of the growth.
      zipline.jsEngine.evaluate("globalThis.blob = null;")
      zipline.jsEngine.gc()
      val collected = zipline.jsEngine.memoryUsage
      assertTrue(
        collected.numCollections > before.numCollections,
        "explicit gc() must record a collection: $before -> $collected",
      )
      val reclaimed = grown.allocatedBytes - collected.allocatedBytes
      assertTrue(
        reclaimed >= growth * 3 / 4,
        "expected >=75% of the growth reclaimed: growth=$growth reclaimed=$reclaimed",
      )
      assertTrue(
        collected.allocatedBytes <= before.allocatedBytes + growth / 4,
        "expected heap back near baseline: before=${before.allocatedBytes} " +
          "collected=${collected.allocatedBytes}",
      )
      // After a GC the peaks are populated and must cover the post-GC live set.
      assertTrue(
        collected.peakAllocatedBytes >= collected.allocatedBytes,
        "peak must cover the live set after GC: $collected",
      )
      assertTrue(
        collected.peakLiveAfterGC >= collected.allocatedBytes,
        "peak-live-after-GC must cover the post-GC live set: $collected",
      )
      assertTrue(
        collected.totalAllocatedBytes >= grown.totalAllocatedBytes,
        "cumulative allocations must never decrease: $grown -> $collected",
      )

      println("hermesHeap: before=$before grown=$grown collected=$collected")
    } finally {
      zipline.close()
    }
  }
}
