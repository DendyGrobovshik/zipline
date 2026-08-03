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

import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class HermesCompileJvmTest {
  private var jsEngine = JsEngine.create()

  @After fun tearDown() {
    jsEngine.close()
  }

  @Test fun exceptionsInScriptIncludeStackTrace() {
    val code = jsEngine.compile(
      """
      |f1();
      |
      |function f1() {
      |  f2();
      |}
      |
      |function f2() {
      |  nope();
      |}
      |
      """.trimMargin(),
      "myFile.js",
    )
    val t = assertFailsWith<JsException> {
      jsEngine.execute(code)
    }
    assertEquals("Property 'nope' doesn't exist", t.message)
    // Hermes uses "global" for the implicit top-level call (not "<eval>" like
    // QuickJS), and stack frames include a column number that's been stripped
    // by JsException.addJavaScriptStack.
    assertEquals("JavaScript.f2(myFile.js:8)", t.stackTrace[0].toString())
    assertEquals("JavaScript.f1(myFile.js:4)", t.stackTrace[1].toString())
    assertEquals("JavaScript.global(myFile.js:1)", t.stackTrace[2].toString())
    assertEquals("app.cash.zipline.JsEngine.execute(Native Method)", t.stackTrace[3].toString())
  }
}
