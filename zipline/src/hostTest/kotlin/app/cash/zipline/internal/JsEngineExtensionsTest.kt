package app.cash.zipline.internal

import app.cash.zipline.JsEngine
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.AfterTest
import kotlin.test.Test

class JsEngineExtensionsTest {
  private val jsEngine = JsEngine.create()

  @AfterTest
  fun tearDown() {
    jsEngine.close()
  }

  @Test
  fun globalPropertyOperations() {
    jsEngine.setGlobalProperty("testKey", "testValue")
    assertThat(jsEngine.getGlobalProperty("testKey")).isEqualTo("testValue")

    jsEngine.setGlobalProperty("testKey", "updatedValue")
    assertThat(jsEngine.getGlobalProperty("testKey")).isEqualTo("updatedValue")

    jsEngine.deleteGlobalProperty("testKey")
    assertThat(jsEngine.getGlobalProperty("testKey")).isNull()
  }

  @Test
  fun globalPropertyNonExistent() {
    assertThat(jsEngine.getGlobalProperty("nonExistentKey")).isNull()
    jsEngine.deleteGlobalProperty("nonExistentKey")
  }

  @Test
  fun callRequireMethodWithModuleLoader() {
    initModuleLoader(jsEngine)

    val moduleBytecode = jsEngine.compile(
      """
      globalThis.define(function(require, exports) {
        return {
          greet: function() { return 'Hello, World!'; }
        };
      });
      """,
      "myModule.js"
    )
    loadJsModule(jsEngine, "myModule", moduleBytecode)

    jsEngine.setGlobalProperty("greetResult", "unset")
    jsEngine.evaluate("greetResult = require('myModule').greet();", "check.js")
    assertThat(jsEngine.getGlobalProperty("greetResult")).isEqualTo("Hello, World!")
  }

  @Test
  fun loadJsModuleWithBytecode() {
    initModuleLoader(jsEngine)

    val moduleBytecode = jsEngine.compile(
      """
      globalThis.define(function(require, exports) {
        return {
          value: "hello"
        };
      });
      """,
      "testModule.js"
    )

    loadJsModule(jsEngine, "testModule", moduleBytecode)

    jsEngine.setGlobalProperty("loadedValue", "unset")
    jsEngine.evaluate("loadedValue = require('testModule').value;", "check.js")
    assertThat(jsEngine.getGlobalProperty("loadedValue")).isEqualTo("hello")
  }

  @Test
  fun runApplicationWithModuleLoader() {
    initModuleLoader(jsEngine)

    val mainModuleBytecode = jsEngine.compile(
      """
      globalThis.define(function(require, exports) {
        return {
          main: function() {
            globalThis.appStarted = "true";
          }
        };
      });
      """,
      "main.js"
    )
    loadJsModule(jsEngine, "main", mainModuleBytecode)

    runApplication(jsEngine, "main", "main")

    assertThat(jsEngine.getGlobalProperty("appStarted")).isEqualTo("true")
  }
}
