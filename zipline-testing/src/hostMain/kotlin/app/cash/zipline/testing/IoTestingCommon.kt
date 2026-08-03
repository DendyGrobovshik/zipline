/*
 * Copyright (C) 2023 Cash App
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
package app.cash.zipline.testing

import app.cash.zipline.JsEngine
import app.cash.zipline.Zipline
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

expect val systemFileSystem: FileSystem

expect val resourcesFileSystem: FileSystem?

val ziplineRoot: Path
  get() = ziplineRootOrNull!!

val ziplineRootOrNull: Path?
  get() = getEnv("ZIPLINE_ROOT")?.toPath()

internal expect fun getEnv(name: String): String?

/** Load our testing libraries into QuickJS. */
fun Zipline.loadTestingJs() {
  loadTestingJsModulesOnly()
  jsEngine.evaluate("globalThis['testing'] = require('./zipline-root-zipline-testing.js');")
}

/** Evaluate (register) the testing module scripts without `require`ing the graph. */
fun Zipline.loadTestingJsModulesOnly() {
  // Load modules in topologically-sorted order. In production the zipline-manifest does this.
  loadJsModuleFromResource("./kotlin-kotlin-stdlib.js")
  loadJsModuleFromResource("./kotlinx-atomicfu.js")
  loadJsModuleFromResource("./kotlinx-serialization-kotlinx-serialization-core.js")
  loadJsModuleFromResource("./kotlinx-serialization-kotlinx-serialization-json.js")
  loadJsModuleFromResource("./kotlinx-coroutines-core.js")
  loadJsModuleFromResource("./kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js")
  loadJsModuleFromResource("./zipline-root-zipline.js")
  loadJsModuleFromResource("./zipline-root-zipline-cryptography.js")
  loadJsModuleFromResource("./zipline-root-zipline-testing.js")
}

val testingModuleIds = listOf(
  "./kotlin-kotlin-stdlib.js",
  "./kotlinx-atomicfu.js",
  "./kotlinx-serialization-kotlinx-serialization-core.js",
  "./kotlinx-serialization-kotlinx-serialization-json.js",
  "./kotlinx-coroutines-core.js",
  "./kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js",
  "./zipline-root-zipline.js",
  "./zipline-root-zipline-cryptography.js",
  "./zipline-root-zipline-testing.js",
)

/**
 * Compile all testing modules once. Execute them with
 * `loadJsModule(bytecode, id)` per instance instead of recompiling per
 * instance — mirrors production bytecode caching and skips the compiler
 * transient that dominates native memory churn.
 */
fun compileTestingJsModules(jsEngine: JsEngine): List<Pair<String, ByteArray>> {
  return testingModuleIds.map { id ->
    id to jsEngine.compile(readJsAsResourceOrFile(id), id)
  }
}

/** Evaluate (register) a single testing module script on a bare [JsEngine]. */
fun JsEngine.loadJsModuleForTest(fileName: String) {
  val currentModuleId = "app_cash_zipline_currentModuleId"
  val script = readJsAsResourceOrFile(fileName)
  evaluate("globalThis.$currentModuleId = '$fileName';")
  evaluate(script, fileName)
  evaluate("delete globalThis.$currentModuleId;")
}

/** Full bundle load + require on a bare [JsEngine] (no Zipline). For leak isolation. */
fun JsEngine.loadTestingJsOnEngine() {
  loadTestingJsModulesOnlyOnEngine()
  evaluate("globalThis['testing'] = require('./zipline-root-zipline-testing.js');")
}

/** Evaluate (register) the testing module scripts on a bare [JsEngine]. */
fun JsEngine.loadTestingJsModulesOnlyOnEngine() {
  val currentModuleId = "app_cash_zipline_currentModuleId"
  fun load(fileName: String) {
    val script = readJsAsResourceOrFile(fileName)
    evaluate("globalThis.$currentModuleId = '$fileName';")
    evaluate(script, fileName)
    evaluate("delete globalThis.$currentModuleId;")
  }
  load("./kotlin-kotlin-stdlib.js")
  load("./kotlinx-atomicfu.js")
  load("./kotlinx-serialization-kotlinx-serialization-core.js")
  load("./kotlinx-serialization-kotlinx-serialization-json.js")
  load("./kotlinx-coroutines-core.js")
  load("./kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js")
  load("./zipline-root-zipline.js")
  load("./zipline-root-zipline-cryptography.js")
  load("./zipline-root-zipline-testing.js")
}

private fun Zipline.loadJsModuleFromResource(fileName: String) {
  val fileJs = readJsAsResourceOrFile(fileName)
  loadJsModule(fileJs, fileName)
}

/** Read as a resource on Android, and as a file on the file system on other platforms. */
private fun readJsAsResourceOrFile(fileName: String): String {
  val root = ziplineRootOrNull
  val resources = resourcesFileSystem

  val source = when {
    root != null -> {
      systemFileSystem.source(
        root / "zipline-testing/build/compileSync/js/main/developmentLibrary/kotlin" / fileName,
      )
    }

    resources != null -> resources.source(fileName.toPath())

    else -> error("no mechanism to read $fileName")
  }

  return source.buffer().use {
    it.readUtf8()
  }
}

/** True on Android and JVM platforms. */
expect val isJni: Boolean
