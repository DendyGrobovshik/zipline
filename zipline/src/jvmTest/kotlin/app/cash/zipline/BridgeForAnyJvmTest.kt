package app.cash.zipline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime tests for the JNI bridge dispatch (via QuickJs.evaluate()).
 * These exercise the same Context::toJavaObject path that the generated C bridges use.
 */
class BridgeForAnyJvmTest {
  private val quickJs = QuickJs.create()

  @AfterTest
  fun tearDown() {
    quickJs.close()
  }

  @Test
  fun `evaluate returns Int`() {
    assertEquals(1, quickJs.evaluate("1"))
  }

  @Test
  fun `evaluate returns Double`() {
    assertEquals(3.14, quickJs.evaluate("3.14"))
  }

  @Test
  fun `evaluate returns Boolean`() {
    assertEquals(true, quickJs.evaluate("true"))
    assertEquals(false, quickJs.evaluate("false"))
  }

  @Test
  fun `evaluate returns String`() {
    assertEquals("hello", quickJs.evaluate("'hello'"))
  }

  @Test
  fun `evaluate returns null for undefined`() {
    assertNull(quickJs.evaluate("undefined"))
  }

  @Test
  fun `evaluate returns null for null`() {
    assertNull(quickJs.evaluate("null"))
  }

  @Test
  fun `evaluate returns array`() {
    val result = quickJs.evaluate("[1, 'two', true]") as Array<*>
    assertEquals(3, result.size)
    assertEquals(1, result[0])
    assertEquals("two", result[1])
    assertEquals(true, result[2])
  }
}
