package app.cash.zipline

import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.INBOUND_CHANNEL_NAME
import app.cash.zipline.internal.bridge.OUTBOUND_CHANNEL_NAME
import app.cash.zipline.testing.EchoRequest
import app.cash.zipline.testing.EchoResponse
import app.cash.zipline.testing.EchoService
import app.cash.zipline.testing.loadTestingJs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Separate Zipline instances are independent native runtimes: creating,
 * using, and destroying them concurrently (on different threads) must not
 * interfere with each other. A single instance is NOT thread-safe, so each
 * test touches an instance from only one thread at a time; only the
 * create/use/close of *separate* instances overlaps.
 */
class ConcurrentZiplineTest {

  private class JvmEchoService : EchoService {
    override fun echo(request: EchoRequest): EchoResponse {
      return EchoResponse("sup from the host, ${request.message}")
    }
  }

  /** Two instances alive at once are fully isolated. */
  @Test
  fun twoInstancesCoexist() = runBlocking {
    val z1 = Zipline.create(Dispatchers.Default)
    val z2 = Zipline.create(Dispatchers.Default)
    try {
      z1.jsEngine.evaluate("globalThis.value = 'one';")
      z2.jsEngine.evaluate("globalThis.value = 'two';")
      z1.jsEngine.evaluate("globalThis.onlyInZ1 = true;")

      assertEquals("one", z1.jsEngine.getGlobalProperty("value"))
      assertEquals("two", z2.jsEngine.getGlobalProperty("value"))
      assertNull(z2.jsEngine.getGlobalProperty("onlyInZ1"))
    } finally {
      z1.close()
      z2.close()
    }
  }

  /**
   * Closing one instance while another is being created, overlapped via
   * latches so closing and creation race.
   */
  @Test
  fun createSecondWhileClosingFirst() = runBlocking {
    repeat(10) {
      val z1 = Zipline.create(Dispatchers.Default)
      z1.jsEngine.evaluate("globalThis.value = 'one';")

      val createStarted = CompletableDeferred<Unit>()
      val closeDone = CompletableDeferred<Unit>()

      val closer = launch(Dispatchers.Default) {
        createStarted.await() // creation begins before the close completes
        z1.close()
        closeDone.complete(Unit)
      }
      createStarted.complete(Unit)
      val z2 = Zipline.create(Dispatchers.Default)
      closeDone.await()
      closer.join()

      try {
        z2.jsEngine.evaluate("globalThis.value = 'two';")
        assertEquals("two", z2.jsEngine.getGlobalProperty("value"))
      } finally {
        z2.close()
      }
    }
  }

  /** Many instances created, used, and destroyed in parallel on different threads. */
  @Test
  fun parallelCreateEvaluateClose() = runBlocking {
    (0 until 10).map { i ->
      async(Dispatchers.Default) {
        val zipline = Zipline.create(Dispatchers.Default)
        try {
          zipline.jsEngine.evaluate("globalThis.value = '' + $i;")
          i to zipline.jsEngine.getGlobalProperty("value")
        } finally {
          zipline.close()
        }
      }
    }.awaitAll().forEach { (i, value) ->
      assertEquals("$i", value)
    }
  }

  /** Two long-lived instances driven in parallel; results stay on their own instance. */
  @Test
  fun concurrentEvaluatesAreIsolated() = runBlocking {
    val z1 = Zipline.create(Dispatchers.Default)
    val z2 = Zipline.create(Dispatchers.Default)
    try {
      val r1 = async(Dispatchers.Default) {
        repeat(50) { i -> z1.jsEngine.evaluate("globalThis.value = 'z1-$i';") }
        z1.jsEngine.getGlobalProperty("value")
      }
      val r2 = async(Dispatchers.Default) {
        repeat(50) { i -> z2.jsEngine.evaluate("globalThis.value = 'z2-$i';") }
        z2.jsEngine.getGlobalProperty("value")
      }
      assertEquals("z1-49", r1.await())
      assertEquals("z2-49", r2.await())
    } finally {
      z1.close()
      z2.close()
    }
  }

  /** Creating and destroying other instances doesn't disturb a live one. */
  @Test
  fun createCloseOthersDoesNotAffectLiveInstance() = runBlocking {
    val live = Zipline.create(Dispatchers.Default)
    try {
      live.jsEngine.evaluate("globalThis.value = 'live';")
      repeat(10) { i ->
        val temp = Zipline.create(Dispatchers.Default)
        temp.jsEngine.evaluate("globalThis.value = 'temp-$i';")
        temp.close()
        assertEquals("live", live.jsEngine.getGlobalProperty("value"))
      }
    } finally {
      live.close()
    }
  }

  /** Services keep working on an instance while others create/destroy in parallel. */
  @Test
  fun servicesWorkWhileOtherInstanceLoops() = runBlocking {
    val zipline = Zipline.create(Dispatchers.Default)
    try {
      // 20 create/destroy cycles running in parallel with the service call.
      val looper = launch(Dispatchers.Default) {
        repeat(40) {
          val temp = Zipline.create(Dispatchers.Default)
          temp.close()
        }
      }
      zipline.loadTestingJs()
      zipline.bind<EchoService>("supService", JvmEchoService())
      zipline.jsEngine.evaluate(
        "globalThis.result = testing.app.cash.zipline.testing.callSupService('homie');",
      )

      assertEquals(
        "JavaScript received 'sup from the host, homie' from the JVM",
        zipline.jsEngine.getGlobalProperty("result"),
      )
      looper.join()
    } finally {
      zipline.close()
    }
  }

  /** Inbound channels of two instances are independent. */
  @Test
  fun inboundChannelsAreIndependent() = runBlocking {
    val z1 = Zipline.create(Dispatchers.Default)
    val z2 = Zipline.create(Dispatchers.Default)
    try {
      // loadTestingJs loads zipline-root-zipline.js, whose GlobalBridge registers
      // the real app_cash_zipline_inboundChannel in each runtime.
      z1.loadTestingJs()
      z2.loadTestingJs()
      z1.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareJsBridges()")
      z2.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareJsBridges()")

      // The real inbound bridge exists in both instances.
      z1.jsEngine.getInboundChannel()
      z2.jsEngine.getInboundChannel()

      // Break the bridge in z2 only.
      z2.jsEngine.evaluate("delete globalThis.$INBOUND_CHANNEL_NAME;")
      assertFailsWith<IllegalStateException> {
        z2.jsEngine.getInboundChannel()
      }

      // z1's bridge still routes calls end-to-end: take a guest-bound
      // EchoService (registered by prepareJsBridges) and call through it.
      val echo = z1.take<EchoService>("helloService")
      assertEquals(EchoResponse("hello from JavaScript, host"), echo.echo(EchoRequest("host")))

      // Modifying service bound to z1 only: calls through z1's real inbound
      // bridge mutate z1's state. z2 cannot see it at all.
      var z1Count = 0
      z1.bind<EchoService>("statefulCounter", object : EchoService {
        override fun echo(request: EchoRequest): EchoResponse {
          z1Count++
          return EchoResponse("z1 #$z1Count")
        }
      })
      // The guest in z1 calls the host-bound service through z1's inbound
      // bridge, mutating only z1's state.
      assertEquals(
        "z1 #1",
        z1.jsEngine.evaluate("testing.app.cash.zipline.testing.callEchoServiceByName('statefulCounter', '')")
      )
      assertEquals(
        "z1 #2",
        z1.jsEngine.evaluate("testing.app.cash.zipline.testing.callEchoServiceByName('statefulCounter', '')")
      )
      assertEquals(2, z1Count)

      // z2's guest cannot reach the service bound to z1.
      val ex = assertFailsWith<Exception> {
        z2.jsEngine.evaluate("testing.app.cash.zipline.testing.callEchoServiceByName('statefulCounter', '')")
      }
      assertTrue(ex.cause?.message?.contains("no such service") == true ||
        ex.message!!.contains("no such service"))
    } finally {
      z1.close()
      z2.close()
    }
  }

  private class GreetingEchoService(
    private val greeting: String,
  ) : EchoService {
    override fun echo(request: EchoRequest): EchoResponse {
      return EchoResponse("$greeting, ${request.message}")
    }
  }

  /** Outbound channels (the real endpoint bridge) of two instances are independent. */
  @Test
  fun outboundChannelsAreIndependent() = runBlocking {
    val z1 = Zipline.create(Dispatchers.Default)
    val z2 = Zipline.create(Dispatchers.Default)
    try {
      z1.loadTestingJs()
      z2.loadTestingJs()

      // Both instances have the real app_cash_zipline_outboundChannel, backed
      // by their own endpoint. Bind identically-named services with distinct
      // responses so we can tell which endpoint answered.
      z1.bind<EchoService>("supService", GreetingEchoService("from z1"))
      z2.bind<EchoService>("supService", GreetingEchoService("from z2"))

      z1.jsEngine.evaluate("globalThis.r = testing.app.cash.zipline.testing.callSupService('x');")
      assertEquals(
        "JavaScript received 'from z1, x' from the JVM",
        z1.jsEngine.getGlobalProperty("r"),
      )
      z2.jsEngine.evaluate("globalThis.r = testing.app.cash.zipline.testing.callSupService('x');")
      assertEquals(
        "JavaScript received 'from z2, x' from the JVM",
        z2.jsEngine.getGlobalProperty("r"),
      )

      // Deleting z2's outbound channel breaks z2's calls but not z1's.
      z2.jsEngine.evaluate("delete globalThis.$OUTBOUND_CHANNEL_NAME;")
      assertFailsWith<Exception> {
        z2.jsEngine.evaluate("testing.app.cash.zipline.testing.callSupService('y');")
      }
      z1.jsEngine.evaluate("globalThis.r2 = testing.app.cash.zipline.testing.callSupService('y');")
      assertEquals(
        "JavaScript received 'from z1, y' from the JVM",
        z1.jsEngine.getGlobalProperty("r2"),
      )
    } finally {
      z1.close()
      z2.close()
    }
  }

  /** A service bound in one instance is not visible in the other. */
  @Test
  fun boundServicesAreIndependent() = runBlocking {
    val zA = Zipline.create(Dispatchers.Default)
    val zB = Zipline.create(Dispatchers.Default)
    try {
      zA.loadTestingJs()
      zB.loadTestingJs()

      zA.bind<EchoService>("serviceA", GreetingEchoService("sup from the host"))
      zB.bind<EchoService>("serviceB", GreetingEchoService("sup from the host"))

      // Each instance can call its own service.
      zA.jsEngine.evaluate(
        "globalThis.r = testing.app.cash.zipline.testing.callEchoServiceByName('serviceA', 'x');",
      )
      assertEquals("sup from the host, x", zA.jsEngine.getGlobalProperty("r"))

      zB.jsEngine.evaluate(
        "globalThis.r = testing.app.cash.zipline.testing.callEchoServiceByName('serviceB', 'x');",
      )
      assertEquals("sup from the host, x", zB.jsEngine.getGlobalProperty("r"))

      // The other instance's service does not exist.
      val eA = assertFailsWith<Exception> {
        zA.jsEngine.evaluate("testing.app.cash.zipline.testing.callEchoServiceByName('serviceB', 'x');")
      }
      assertTrue(eA.message!!.contains("no such service"))

      val eB = assertFailsWith<Exception> {
        zB.jsEngine.evaluate("testing.app.cash.zipline.testing.callEchoServiceByName('serviceA', 'x');")
      }
      assertTrue(eB.message!!.contains("no such service"))
    } finally {
      zA.close()
      zB.close()
    }
  }
}
