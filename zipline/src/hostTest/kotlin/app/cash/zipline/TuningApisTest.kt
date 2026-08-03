/*
 * Copyright (C) 2015 Square, Inc.
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

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TuningApisTest {
  private val js = JsEngine.create()

  @AfterTest fun tearDown() {
    js.close()
  }

  /** QuickJS-style heap tuning knobs have no Hermes equivalent and must fail loudly. */
  @Test fun tuningApisAreUnsupported() {
    assertFailsWith<UnsupportedOperationException> { js.memoryLimit }
    assertFailsWith<UnsupportedOperationException> { js.memoryLimit = 1024L * 1024L }
    assertFailsWith<UnsupportedOperationException> { js.gcThreshold }
    assertFailsWith<UnsupportedOperationException> { js.gcThreshold = 256L * 1024L }
    assertFailsWith<UnsupportedOperationException> { js.maxStackSize }
    assertFailsWith<UnsupportedOperationException> { js.maxStackSize = 512L * 1024L }
  }

  @Test fun initialMemoryUsage() {
    val usage = js.memoryUsage
    assertTrue(usage.heapSize > 0L, usage.toString())
    assertTrue(usage.allocatedBytes > 0L, usage.toString())
    assertTrue(usage.allocatedBytes <= usage.heapSize, usage.toString())
    assertTrue(usage.totalAllocatedBytes >= usage.allocatedBytes, usage.toString())
    assertTrue(usage.va >= usage.heapSize, usage.toString())
  }

  @Test fun defineFunctionGrowsTotalAllocatedBytes() {
    val diff = diffMemoryUsage {
      js.evaluate(
        """
        globalThis.hypotenuse = function(a, b) {
          return Math.sqrt((a * a) + (b * b));
        };
        """,
      )
    }
    // totalAllocatedBytes is cumulative: it only ever goes up.
    assertTrue(diff.totalAllocatedBytes > 0L, diff.toString())
  }

  @Test fun typedArrayGrowsMemoryFootprint() {
    val diff = diffMemoryUsage {
      js.evaluate(
        """
        globalThis.buffer = new Uint8Array(1024 * 1024);
        """,
      )
    }
    // The backing store lives outside the GC heap (external/malloc), the
    // JSTypedArray object inside it; one of the two must reflect the 1 MB.
    assertTrue(diff.usedBytes >= 1024L * 1024L, diff.toString())
  }

  @Test fun explicitGcIncrementsNumCollections() {
    val before = js.memoryUsage
    js.gc()
    val after = js.memoryUsage
    assertTrue(after.numCollections > before.numCollections, "$before -> $after")
  }

  private fun diffMemoryUsage(block: () -> Unit): MemoryUsage {
    js.gc()
    val before = js.memoryUsage
    block()
    val after = js.memoryUsage

    return MemoryUsage(
      heapSize = after.heapSize - before.heapSize,
      allocatedBytes = after.allocatedBytes - before.allocatedBytes,
      totalAllocatedBytes = after.totalAllocatedBytes - before.totalAllocatedBytes,
      va = after.va - before.va,
      externalBytes = after.externalBytes - before.externalBytes,
      mallocSizeEstimate = after.mallocSizeEstimate - before.mallocSizeEstimate,
      peakAllocatedBytes = after.peakAllocatedBytes - before.peakAllocatedBytes,
      peakLiveAfterGC = after.peakLiveAfterGC - before.peakLiveAfterGC,
      numCollections = after.numCollections - before.numCollections,
      numMarkStackOverflows = after.numMarkStackOverflows - before.numMarkStackOverflows,
    )
  }
}
