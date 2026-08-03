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

import app.cash.zipline.internal.bridge.INBOUND_CHANNEL_NAME
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Confirm connectivity from Kotlin to a JavaScript implementation of CallChannel.
 *
 * This low-level test uses raw JavaScript to test connectivity. In practice this interface will
 * only ever be used by Kotlin/JS.
 */
class HermesInboundChannelTest {
  private val jsEngine = JsEngine.create()

  @AfterTest fun tearDown() {
    jsEngine.close()
  }

  @BeforeTest
  fun setUp() {
    jsEngine.evaluate(
      """
      globalThis.$INBOUND_CHANNEL_NAME = {};
      globalThis.$INBOUND_CHANNEL_NAME.call = function(jstring) {
      };
      globalThis.$INBOUND_CHANNEL_NAME.disconnect = function(instanceName) {
      };
    """.trimIndent(),
    )
  }

  @Test
  fun callHappyPath() {
    jsEngine.evaluate(
      """
      globalThis.$INBOUND_CHANNEL_NAME.call = function(callJson) {
        return 'received call(' + callJson + ') and the call was successful!';
      };
    """.trimIndent(),
    )

    val inboundChannel = jsEngine.getInboundChannel()
    val result = inboundChannel.call("firstArg")
    assertEquals("received call(firstArg) and the call was successful!", result)
  }

  @Test
  fun disconnectHappyPath() {
    jsEngine.evaluate(
      """
      var callLog = "";
      globalThis.$INBOUND_CHANNEL_NAME.call = function(callJson) {
        var result = callLog;
        callLog = "";
        return result;
      };
      globalThis.$INBOUND_CHANNEL_NAME.disconnect = function(instanceName) {
        callLog += 'disconnect(' + instanceName + ')';
        return true;
      };
    """.trimIndent(),
    )

    val inboundChannel = jsEngine.getInboundChannel()
    assertTrue(inboundChannel.disconnect("service one"))
    val result = inboundChannel.call("")
    assertEquals("disconnect(service one)", result)
  }

  @Test
  fun noInboundChannelThrows() {
    jsEngine.evaluate(
      """
      delete globalThis.$INBOUND_CHANNEL_NAME;
    """.trimIndent(),
    )

    val t = assertFailsWith<IllegalStateException> {
      jsEngine.getInboundChannel()
    }
    assertEquals("A global JavaScript object called $INBOUND_CHANNEL_NAME was not found. Try confirming that Zipline.get() has been called.", t.message)
  }

  @Test
  fun callFunctionAfterClosingHermesThrows() {
    val inboundChannel = jsEngine.getInboundChannel()
    jsEngine.close()

    val t = assertFailsWith<IllegalStateException> {
      inboundChannel.disconnect("service one")
    }
    assertEquals("JsEngine instance was closed", t.message)
  }
}
