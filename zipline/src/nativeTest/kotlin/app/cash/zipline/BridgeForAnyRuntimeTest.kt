package app.cash.zipline

import app.cash.zipline.quickjs.*
import kotlinx.cinterop.*
import kotlin.test.*

/**
 * Runtime tests for bridgeForAny and JsNumberToLong against a real QuickJS context.
 */
@OptIn(ExperimentalForeignApi::class)
class BridgeForAnyRuntimeTest {
  private val quickJs = QuickJs.create()
  private val evalCtx get() = quickJs.contextForCompiling
  private val ctx get() = quickJs.context

  @AfterTest
  fun tearDown() {
    quickJs.close()
  }

  /** Evaluate JS and return the raw JSValue for low-level testing. */
  private fun evalRaw(script: String): CValue<JSValue> = memScoped {
    val code = script.utf8
    val result = JS_Eval(evalCtx, code, (code.size - 1).convert(), "test.js".utf8, JS_EVAL_FLAG_STRICT)
    if (JS_IsException(result) != 0) {
      throw QuickJsException("JS eval failed: $script")
    }
    return@memScoped result
  }

  @Test
  fun `JsNumberToLong handles JS int`() {
    val jsVal = evalRaw("42")
    try {
      assertEquals(42L, JsNumberToLong(ctx, jsVal))
    } finally {
      JS_FreeValue(ctx, jsVal)
    }
  }

  @Test
  fun `JsNumberToLong handles KotlinJS Long object`() {
    val jsVal = evalRaw("({low_1: 1, high_1: 0})")
    try {
      assertEquals(1L, JsNumberToLong(ctx, jsVal))
    } finally {
      JS_FreeValue(ctx, jsVal)
    }
  }

  @Test
  fun `JsNumberToLong handles negative KotlinJS Long`() {
    val jsVal = evalRaw("({low_1: -1, high_1: -1})")
    try {
      assertEquals(-1L, JsNumberToLong(ctx, jsVal))
    } finally {
      JS_FreeValue(ctx, jsVal)
    }
  }

  @Test
  fun `bridgeForAny returns number as Double`() {
    val jsVal = evalRaw("42")
    try {
      assertEquals(42.0, bridgeForAny(ctx, jsVal))
    } finally {
      JS_FreeValue(ctx, jsVal)
    }
  }

  @Test
  fun `bridgeForAny returns String`() {
    val jsVal = evalRaw("'hello'")
    try {
      assertEquals("hello", bridgeForAny(ctx, jsVal))
    } finally {
      JS_FreeValue(ctx, jsVal)
    }
  }

  @Test
  fun `bridgeForAny returns Boolean`() {
    val jsVal = evalRaw("true")
    try {
      assertEquals(true, bridgeForAny(ctx, jsVal))
    } finally {
      JS_FreeValue(ctx, jsVal)
    }
  }

  @Test
  fun `bridgeForAny returns null for undefined`() {
    val jsVal = evalRaw("undefined")
    try {
      assertNull(bridgeForAny(ctx, jsVal))
    } finally {
      JS_FreeValue(ctx, jsVal)
    }
  }
}
