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

import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.OUTBOUND_CHANNEL_NAME
import assertk.assertThat
import assertk.assertions.startsWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HermesTest {
  private val jsEngine = JsEngine.create()

  @AfterTest
  fun tearDown() {
    jsEngine.close()
  }

  @Test
  fun version() {
    assertNotEquals("", JsEngine.version)
  }

  @Test fun helloWorld() {
    val hello = jsEngine.evaluate("'hello, world!'.toUpperCase();") as String?
    assertEquals("HELLO, WORLD!", hello)
  }

  @Test fun exceptionsInScriptThrowInKotlin() {
    val t = assertFailsWith<JsException> {
      jsEngine.evaluate("nope();")
    }
    assertThat(t.message!!).startsWith("Property 'nope' doesn't exist")
  }

  @Test fun returnTypes() {
    assertEquals("test", jsEngine.evaluate("\"test\";"))
    assertEquals(true, jsEngine.evaluate("true;"))
    assertEquals(false, jsEngine.evaluate("false;"))
    assertEquals(1, jsEngine.evaluate("1;"))
    assertEquals(1.123, jsEngine.evaluate("1.123;"))

    assertNull(jsEngine.evaluate("undefined;"))
    assertNull(jsEngine.evaluate("null;"))

    // Arrays/objects don't fit the tagged-scalar return; they cross
    // through the outbound call channel (the production marshalling path).
    val received = mutableListOf<String>()
    jsEngine.initOutboundChannel(
      object : CallChannel {
        override fun call(callJson: String): String {
          received += callJson
          return callJson
        }

        override fun disconnect(instanceName: String): Boolean = true
      },
    )

    jsEngine.evaluate(
      """
      |globalThis.$OUTBOUND_CHANNEL_NAME.call(JSON.stringify(["test", true, false, 1, 1.123, undefined, null]));
      """.trimMargin(),
    )

    assertEquals(
      listOf("""["test",true,false,1,1.123,null,null]"""),
      received,
    )
  }

  @Test fun gc() {
    assertNull(jsEngine.evaluate("""globalThis.gc();"""))
  }
}
