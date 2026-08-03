/*
 * Copyright (C) 2022 Block, Inc.
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

import assertk.assertThat
import assertk.assertions.startsWith
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Confirm our JavaScript engine executes in strict mode.
 *
 * https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Strict_mode
 */
// Ignored: Hermes has no global strict-mode option (QuickJS used
// JS_EVAL_FLAG_STRICT); guest code runs sloppy unless it declares
// 'use strict' itself. Note kotlin.test.Ignore takes no message on
// Kotlin/Native, so the reason lives in this comment.
@Ignore
class StrictModeTest {
  private val jsEngine = JsEngine.create()

  @AfterTest
  fun tearDown() {
    jsEngine.close()
  }

  @Test
  fun hermesIsStrictInEvaluate() {
    val code =
      """
      |const obj2 = { get x() { return 17; } };
      |obj2.x = 5; // throws a TypeError
      """.trimMargin()

    val e = assertFailsWith<Exception> {
      jsEngine.evaluate(code, "shouldFailInStrictMode.js")
    }
    assertThat(e.message!!).startsWith("no setter for property")
  }

  @Test
  fun hermesIsStrictInCompileAndRun() {
    val code =
      """
      |const obj2 = { get x() { return 17; } };
      |obj2.x = 5; // throws a TypeError
      """.trimMargin()

    val bytecode = jsEngine.compile(code, "shouldFailInStrictMode.js")

    val e = assertFailsWith<Exception> {
      jsEngine.execute(bytecode)
    }
    assertThat(e.message!!).startsWith("no setter for property")
  }
}
