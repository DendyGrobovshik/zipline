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

import assertk.assertThat
import assertk.assertions.startsWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HermesCompileTest {
  private var jsEngine = JsEngine.create()

  @AfterTest fun tearDown() {
    jsEngine.close()
  }

  @Test fun helloWorld() {
    val code = jsEngine.compile("'hello, world!'.toUpperCase();", "myFile.js")
    assertNotEquals(0, code.size)

    jsEngine.close()
    jsEngine = JsEngine.create()

    val hello = jsEngine.execute(code)
    assertEquals("HELLO, WORLD!", hello)
  }

  @Test fun badCode() {
    val t = assertFailsWith<JsException> {
      jsEngine.compile("@#%(*W#(UF(E", "myFile.js")
    }
    // Hermes compile() returns false on parse errors and writes no
    // diagnostic; the glue throws a generic message. Real error info
    // (including the failing token) is only available from hermesc.
    assertThat(t.message!!).startsWith("Failed to compile JavaScript")
  }

  @Test fun multipleParts() {
    val code = jsEngine.compile("myFunction();", "myFileA.js")
    assertNotEquals(0, code.size)

    val functionDef =
        jsEngine.compile("function myFunction() { return 'this is the answer'; }", "myFileB.js")
    assertNotEquals(0, functionDef.size)

    jsEngine.close()
    jsEngine = JsEngine.create()

    val t = assertFailsWith<JsException> {
      jsEngine.execute(code)
    }
    assertThat(t.message!!).startsWith("Property 'myFunction' doesn't exist")

    assertNull(jsEngine.execute(functionDef))
    assertEquals("this is the answer", jsEngine.execute(code))
  }
}
