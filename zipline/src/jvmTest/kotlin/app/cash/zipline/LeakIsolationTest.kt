package app.cash.zipline

import app.cash.zipline.testing.loadTestingJs
import app.cash.zipline.testing.loadTestingJsModulesOnly
import app.cash.zipline.testing.loadJsModuleForTest
import app.cash.zipline.testing.loadTestingJsModulesOnlyOnEngine
import app.cash.zipline.testing.loadTestingJsOnEngine
import kotlin.test.Ignore
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers

/**
 * Diagnostic used to investigate RSS growth across create/close cycles.
 *
 * CONCLUSION (2026-07-30): there is NO unbounded leak. What looked like a
 * ~350-480 KB/cycle leak in ziplineWithJs was slow convergence to a plateau:
 * a 1500-cycle run (engineCoroutinesScalarGlobalLong) grew +59 MB in the
 * first 100 cycles, decelerated to <10 KB/cycle by cycle 1000, and converged
 * to the same ~590 MB plateau as the "flat" variants. Trigger-matrix details
 * (an extra globalThis property present during the first module's factory
 * execution — which the native define() runs EAGERLY — slows convergence
 * tenfold; scalar/method-bearing globals slow it most) are documented on each
 * test. Context create/destroy is balanced (verified with a native counter);
 * compile-only, execute-only, and bare-engine cycles are flat from the start.
 *
 * Ignored because the variants take minutes; run manually when investigating
 * memory regressions.
 *
 * INTERPRETATION CAVEAT: tests sharing one Gradle test JVM inherit the
 * converged plateau from earlier tests, so a "flat" reading after a leaking
 * variant means convergence carryover, not a clean baseline. Only
 * first-in-JVM runs (baseline ~425-495 MB) are clean measurements; they are
 * marked as such on each test. Run variants individually (--tests "X") for
 * trustworthy numbers.
 */
@Ignore
class LeakIsolationTest {
  private fun rssBytes(): Long {
    val pid = ProcessHandle.current().pid()
    val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString())
      .redirectErrorStream(true).start()
    // MallocStackLogging prints warnings into ps output; the RSS value is the
    // last non-empty line.
    val output = process.inputStream.bufferedReader().readLines()
      .lastOrNull { it.isNotBlank() }?.trim() ?: return -1L
    process.waitFor()
    return output.toLong() * 1024L
  }

  /** Min of 3 GC-drained reads: filters out not-yet-finalized instances. */
  private fun footprint(): Long {
    var min = Long.MAX_VALUE
    repeat(3) {
      System.gc()
      Thread.sleep(25)
      min = minOf(min, rssBytes())
    }
    return min
  }

  private fun report(name: String, cycles: Int, warmup: Int = 20, sampleEvery: Int = -1, cycle: () -> Unit) {
    val every = if (sampleEvery > 0) sampleEvery else cycles / 5
    repeat(warmup) { cycle() }
    val baseline = footprint()
    val samples = mutableListOf<Long>()
    repeat(cycles) {
      cycle()
      if ((it + 1) % every == 0) samples += footprint()
    }
    val growthPerCycle = (samples.last() - baseline) / cycles
    println("$name: baseline=$baseline samples=$samples growthPerCycle=${growthPerCycle}B")
  }

  /**
   * bareEngine: 436 B/cycle — flat. HermesRuntime create/destroy is clean.
   */
  @Test
  fun bareEngine() = report("bareEngine", 300) {
    JsEngine.create().close()
  }

  /**
   * engineWithEval: 54 B/cycle — flat. Tiny per-cycle evaluation leaks nothing.
   */
  @Test
  fun engineWithEval() = report("engineWithEval", 300) {
    val js = JsEngine.create()
    js.evaluate("globalThis.blob = new Array(1000).fill('x').join('');")
    js.close()
  }

  /**
   * engineCompileOnly: 382 B/cycle — flat. Compiling 383 KB of JS per cycle
   * leaks nothing; the compiler's llvh arenas plateau after warm-up.
   */
  @Test
  fun engineCompileOnly() = report("engineCompileOnly", 300) {
    val js = JsEngine.create()
    js.compile(bigScript, "big.js")
    js.close()
  }

  /**
   * engineExecuteOnly: 54 B/cycle — flat. Executing 621 KB of pre-compiled
   * bytecode per cycle leaks nothing.
   */
  @Test
  fun engineExecuteOnly() {
    val compiler = JsEngine.create()
    val bytecode = compiler.compile(bigScript, "big.js")
    compiler.close()
    report("engineExecuteOnly", 300) {
      val js = JsEngine.create()
      js.execute(bytecode, "big.js")
      js.close()
    }
  }

  /**
   * ziplineNoJs: 6498 B/cycle — small and converging (samples flatten within
   * 300 cycles). Thread/allocator warm-up on Dispatchers.Default, not a leak.
   */
  @Test
  fun ziplineNoJs() = report("ziplineNoJs", 300) {
    Zipline.create(Dispatchers.Default).close()
  }

  /**
   * ziplineWithJs: ~350-430 KB/cycle apparent growth at 300 cycles — this is
   * the slow-convergence curve, not a leak (see class comment). The trigger
   * matrix below shows: extra globalThis property during the FIRST module's
   * eager factory execution + later require = 10x slower convergence;
   * engine-only paths are flat from the start.
   */
  @Test
  fun ziplineWithJs() = report("ziplineWithJs", 300) {
    val z = Zipline.create(Dispatchers.Default)
    z.loadTestingJs()
    z.close()
  }

  /**
   * Zipline + all 9 module scripts evaluated (registered) but the final
   * `require(...)` that executes the module graph skipped.
   *
   * Measured 33368 B/cycle (flat), but ran after ziplineWithJs in the same
   * JVM, so it inherited the already-converged plateau; the flat reading is
   * convergence carryover, not a clean baseline.
   */
  @Test
  fun ziplineModulesNoRequire() = report("ziplineModulesNoRequire", 300) {
    val z = Zipline.create(Dispatchers.Default)
    z.loadTestingJsModulesOnly()
    z.close()
  }

  /**
   * Bare engine + module loader + full bundle load AND require, but no
   * Zipline (no endpoint, no outbound channel, no Dispatchers.Default).
   *
   * Measured 28016 B/cycle (flat), but ran after two other tests in the same
   * JVM — flat reading is convergence carryover, not a clean baseline.
   */
  @Test
  fun engineBundleNoZipline() = report("engineBundleNoZipline", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.loadTestingJsOnEngine()
    js.close()
  }

  /**
   * Zipline + modules, require only the stdlib.
   *
   * Measured 17803 B/cycle (flat), but ran after ziplineRequireCoroutines in
   * the same JVM — flat reading is convergence carryover.
   */
  @Test
  fun ziplineRequireStdlib() = report("ziplineRequireStdlib", 300) {
    val z = Zipline.create(Dispatchers.Default)
    z.loadTestingJsModulesOnly()
    z.jsEngine.evaluate("globalThis.m = require('./kotlin-kotlin-stdlib.js');")
    z.close()
  }

  /**
   * Zipline + modules, require only the coroutines module.
   *
   * Measured 406650 B/cycle, first test in its JVM (baseline 459 MB -> 581 MB,
   * decelerating samples) — genuine slow-convergence curve, not run-order
   * carryover. The require triggers lazy compilation of the coroutines
   * module's functions on top of the Zipline bridge globals.
   */
  @Test
  fun ziplineRequireCoroutines() = report("ziplineRequireCoroutines", 300) {
    val z = Zipline.create(Dispatchers.Default)
    z.loadTestingJsModulesOnly()
    z.jsEngine.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    z.close()
  }

  /**
   * Footprint within cycles: after create / require / gc / close.
   *
   * Probe (no growthPerCycle). Showed require adding +50-100 MB per early
   * cycle and close releasing almost none, footprint ratcheting — but the
   * no-channel probe below shows the identical pattern, so this is warm-up
   * convergence, indistinguishable at 6 cycles.
   */
  @Test
  fun perCycleFootprintProbe() {
    repeat(6) { i ->
      val js = JsEngine.create()
      js.installModuleLoader()
      js.initOutboundChannel(object : app.cash.zipline.internal.bridge.CallChannel {
        override fun call(callJson: String) = "[]"
        override fun disconnect(instanceName: String) = true
      })
      val afterCreate = rssBytes()
      js.loadTestingJsModulesOnlyOnEngine()
      js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
      val afterRequire = rssBytes()
      js.gc()
      val afterGc = rssBytes()
      val usage = js.memoryUsage
      js.close()
      System.gc()
      Thread.sleep(25)
      val afterClose = rssBytes()
      println(
        "probe cycle=$i create=$afterCreate require=$afterRequire gc=$afterGc close=$afterClose" +
          " hermesAlloc=${usage.allocatedBytes} heap=${usage.heapSize}"
      )
    }
  }

  /**
   * Same as perCycleFootprintProbe but WITHOUT the outbound channel.
   *
   * Probe (no growthPerCycle). Ratchets identically to the channel variant
   * during early cycles, proving the per-cycle probe only sees warm-up.
   */
  @Test
  fun perCycleFootprintProbeNoChannel() {
    repeat(6) { i ->
      val js = JsEngine.create()
      js.installModuleLoader()
      val afterCreate = rssBytes()
      js.loadTestingJsModulesOnlyOnEngine()
      js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
      val afterRequire = rssBytes()
      js.gc()
      val usage = js.memoryUsage
      js.close()
      System.gc()
      Thread.sleep(25)
      val afterClose = rssBytes()
      println(
        "probeNoCh cycle=$i create=$afterCreate require=$afterRequire close=$afterClose" +
          " hermesAlloc=${usage.allocatedBytes} heap=${usage.heapSize}"
      )
    }
  }

  /**
   * Bare engine + module loader + the NATIVE outbound channel (as Zipline
   * installs it) + coroutines require. No Zipline/endpoint/scope/dispatcher.
   *
   * Measured 432646 B/cycle, first test in its JVM (baseline 441 MB) —
   * genuine slow-convergence curve. Showed the Zipline endpoint/dispatcher is
   * not required for the effect. Note: a standalone C++ repro with the C-API
   * channel was flat, so the JVM environment is implicated.
   */
  @Test
  fun engineCoroutinesWithOutboundChannel() = report("engineCoroutinesWithOutboundChannel", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.initOutboundChannel(object : app.cash.zipline.internal.bridge.CallChannel {
      override fun call(callJson: String) = "[]"
      override fun disconnect(instanceName: String) = true
    })
    js.loadTestingJsOnEngine()
    js.close()
  }

  /**
   * Bare engine + module loader + coroutines require only (no full graph).
   *
   * Measured 35280 B/cycle (flat), but ran after
   * engineCoroutinesWithOutboundChannel in the same JVM — flat reading is
   * convergence carryover, not a clean baseline.
   */
  @Test
  fun engineRequireCoroutinesOnly() = report("engineRequireCoroutinesOnly", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Bare engine + PURE-JS outbound channel (no native host functions).
   *
   * Measured 396055 B/cycle, only test in its JVM — genuine slow-convergence
   * curve. Proved no JNI/global-ref involvement: a plain JS object on
   * globalThis suffices.
   */
  @Test
  fun engineCoroutinesWithJsChannel() = report("engineCoroutinesWithJsChannel", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.evaluate(
      "globalThis.app_cash_zipline_outboundChannel = {" +
        "  call: function(s) { return '[]'; }," +
        "  disconnect: function(n) { return true; }" +
        "};"
    )
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Same as engineCoroutinesWithJsChannel but the extra global has an
   * unrelated name.
   *
   * Measured 411839 B/cycle, only test in its JVM — the channel NAME is
   * irrelevant; any extra global on globalThis produces the curve.
   */
  @Test
  fun engineCoroutinesWithUnrelatedGlobal() = report("engineCoroutinesWithUnrelatedGlobal", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.evaluate(
      "globalThis.unrelatedGlobal = {" +
        "  call: function(s) { return '[]'; }," +
        "  disconnect: function(n) { return true; }" +
        "};"
    )
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Extra global added AFTER module registration, just before require.
   *
   * Measured 36864 B/cycle (flat-ish) — but run order vs
   * engineCoroutinesGlobalDeletedBeforeRequire in the same JVM is
   * JUnit-order dependent, so this reading may be convergence carryover.
   * Takeaway if clean: the global's presence at require time does not matter.
   */
  @Test
  fun engineCoroutinesGlobalBeforeRequireOnly() = report("engineCoroutinesGlobalBeforeRequireOnly", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("globalThis.lateGlobal = {call: function(s) { return '[]'; }};")
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Extra global added before modules but DELETED before require.
   *
   * Measured 406650 B/cycle (baseline 447 MB, +122 MB, decelerating) —
   * genuine curve. Takeaway: the global's presence during module script eval
   * (eager factory execution) is what slows convergence, not its presence at
   * require time.
   */
  @Test
  fun engineCoroutinesGlobalDeletedBeforeRequire() = report("engineCoroutinesGlobalDeletedBeforeRequire", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.evaluate("globalThis.earlyGlobal = {call: function(s) { return '[]'; }};")
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("delete globalThis.earlyGlobal;")
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Extra global is a SCALAR.
   *
   * Measured 486167 B/cycle, first test in its JVM (baseline 426 MB) —
   * genuine curve. Surprisingly a number-valued global slows convergence more
   * than an object or function global (but see the carryover caveat on the
   * object/function variants before concluding value-sensitivity).
   */
  @Test
  fun engineCoroutinesScalarGlobal() = report("engineCoroutinesScalarGlobal", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.evaluate("globalThis.scalarGlobal = 1;")
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Long run: does the growth converge or continue unbounded?
   *
   * Measured 66060 B/cycle average over 1500 cycles, but the curve is the
   * story: +59 MB in the first 100 cycles, decelerating to <10 KB/cycle past
   * cycle 1000, converging to the same ~590 MB plateau as every other
   * variant. This is the definitive proof that the effect is slow
   * convergence, not a leak.
   */
  @Test
  fun engineCoroutinesScalarGlobalLong() {
    report("engineCoroutinesScalarGlobalLong", 1500, warmup = 50, sampleEvery = 100) {
      val js = JsEngine.create()
      js.installModuleLoader()
      js.evaluate("globalThis.scalarGlobal = 1;")
      js.loadTestingJsModulesOnlyOnEngine()
      js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
      js.close()
    }
  }

  /**
   * Extra global is an EMPTY OBJECT.
   *
   * Measured 55050 B/cycle (flat-ish), but ran after
   * engineCoroutinesScalarGlobal in the same JVM — the flat reading may be
   * convergence carryover rather than a true value-sensitivity signal.
   */
  @Test
  fun engineCoroutinesObjectGlobal() = report("engineCoroutinesObjectGlobal", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.evaluate("globalThis.objectGlobal = {};")
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Extra global is a FUNCTION.
   *
   * Measured 20916 B/cycle (flat), but ran after two leaking variants in the
   * same JVM — flat reading may be convergence carryover.
   */
  @Test
  fun engineCoroutinesFunctionGlobal() = report("engineCoroutinesFunctionGlobal", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    js.evaluate("globalThis.functionGlobal = function() { return 1; };")
    js.loadTestingJsModulesOnlyOnEngine()
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Extra global present only during the coroutines module's own eval.
   *
   * Measured 9448 B/cycle (flat), but ran after
   * engineCoroutinesGlobalDuringStdlibEvalOnly in the same JVM — flat
   * reading may be convergence carryover.
   */
  @Test
  fun engineCoroutinesGlobalDuringCoroutinesEvalOnly() = report("engineCoroutinesGlobalDuringCoroutinesEvalOnly", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    val modules = listOf(
      "./kotlin-kotlin-stdlib.js",
      "./kotlinx-atomicfu.js",
      "./kotlinx-serialization-kotlinx-serialization-core.js",
      "./kotlinx-serialization-kotlinx-serialization-json.js",
      "./kotlinx-coroutines-core.js",
      "./kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js",
      "./zipline-root-zipline.js",
      "./zipline-root-zipline-cryptography.js",
      "./zipline-root-zipline-testing.js",
    )
    for (module in modules) {
      if (module == "./kotlinx-coroutines-core.js") {
        js.evaluate("globalThis.scopedGlobal = {call: function(s) { return '[]'; }};")
      }
      js.loadJsModuleForTest(module)
      if (module == "./kotlinx-coroutines-core.js") {
        js.evaluate("delete globalThis.scopedGlobal;")
      }
    }
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Extra global present only during the STDLIB module's eval.
   *
   * Measured 482781 B/cycle, first test in its JVM (baseline 427 MB) —
   * genuine curve. The FIRST module's eager factory execution is the
   * convergence-sensitive phase; globals present later matter much less.
   */
  @Test
  fun engineCoroutinesGlobalDuringStdlibEvalOnly() = report("engineCoroutinesGlobalDuringStdlibEvalOnly", 300) {
    val js = JsEngine.create()
    js.installModuleLoader()
    val modules = listOf(
      "./kotlin-kotlin-stdlib.js",
      "./kotlinx-atomicfu.js",
      "./kotlinx-serialization-kotlinx-serialization-core.js",
      "./kotlinx-serialization-kotlinx-serialization-json.js",
      "./kotlinx-coroutines-core.js",
      "./kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js",
      "./zipline-root-zipline.js",
      "./zipline-root-zipline-cryptography.js",
      "./zipline-root-zipline-testing.js",
    )
    for (module in modules) {
      if (module == "./kotlin-kotlin-stdlib.js") {
        js.evaluate("globalThis.scopedGlobal = {call: function(s) { return '[]'; }};")
      }
      js.loadJsModuleForTest(module)
      if (module == "./kotlin-kotlin-stdlib.js") {
        js.evaluate("delete globalThis.scopedGlobal;")
      }
    }
    js.evaluate("globalThis.m = require('./kotlinx-coroutines-core.js');")
    js.close()
  }

  /**
   * Zipline + modules, require only the zipline bridge module.
   *
   * Measured 20370 B/cycle (flat), but ran after two other tests in the same
   * JVM — flat reading is convergence carryover.
   */
  @Test
  fun ziplineRequireBridge() = report("ziplineRequireBridge", 300) {
    val z = Zipline.create(Dispatchers.Default)
    z.loadTestingJsModulesOnly()
    z.jsEngine.evaluate("globalThis.m = require('./zipline-root-zipline.js');")
    z.close()
  }

  private val bigScript: String = buildString {
    repeat(5000) { i ->
      append("function fn$i(a) { return a * $i + 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; }\n")
    }
    append("globalThis.sum = fn0(1) + fn4999(2);")
  }
}
