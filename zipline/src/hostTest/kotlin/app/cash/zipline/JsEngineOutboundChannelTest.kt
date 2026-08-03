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

import app.cash.zipline.internal.bridge.OUTBOUND_CHANNEL_NAME
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Confirm connectivity from JavaScript to a Kotlin implementation of CallChannel.
 *
 * This low-level test uses raw JavaScript to test connectivity. In practice this interface will
 * only ever be used by Kotlin/JS.
 */
class HermesOutboundChannelTest {
  private val jsEngine = JsEngine.create()
  private val callChannel = LoggingCallChannel()

  @BeforeTest fun setUp() {
    jsEngine.initOutboundChannel(callChannel)
  }

  @AfterTest fun tearDown() {
    jsEngine.close()
  }

  @Test
  fun callHappyPath() {
    callChannel.callResult = "result one"
    val callResult = jsEngine.evaluate(
      """
      globalThis.$OUTBOUND_CHANNEL_NAME.call('firstArg');
    """.trimIndent(),
    )
    assertEquals("result one", callResult)
    assertEquals(listOf("call(firstArg)"), callChannel.log)
  }

  @Test
  fun disconnectHappyPath() {
    callChannel.disconnectResult = true

    val disconnectResult = jsEngine.evaluate(
      """
      globalThis.$OUTBOUND_CHANNEL_NAME.disconnect('theInstanceName');
    """.trimIndent(),
    )
    assertTrue(disconnectResult as Boolean)
    assertEquals(
      listOf(
        "disconnect(theInstanceName)",
      ),
      callChannel.log,
    )
  }

  @Test
  fun lotsOfCalls() {
    val count = jsEngine.evaluate(
      """
      |var count = 0;
      |for (var i = 0; i < 100000; i++) {
      |  var result = globalThis.$OUTBOUND_CHANNEL_NAME.disconnect('theInstanceName');
      |  if (result) count++;
      |}
      |count;
      """.trimMargin(),
    ) as Int
    assertEquals(100000, callChannel.log.size)
    assertEquals(100000, count)
  }
}
