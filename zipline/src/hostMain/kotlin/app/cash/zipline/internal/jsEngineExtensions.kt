package app.cash.zipline.internal

import app.cash.zipline.JsEngine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun collectModuleDependencies(jsEngine: JsEngine) {
  jsEngine.evaluate(COLLECT_DEPENDENCIES_DEFINE_JS, "collectDependencies.js")
}

internal fun getModuleDependencies(jsEngine: JsEngine): List<String> {
  val dependenciesString = jsEngine.getGlobalProperty(CURRENT_MODULE_DEPENDENCIES)
    ?: "[]" // If define is never called, dependencies is returned as null
  return Json.decodeFromString(dependenciesString)
}

internal fun getLog(jsEngine: JsEngine): String? = jsEngine.getGlobalProperty("log")

public fun initModuleLoader(jsEngine: JsEngine) {
  jsEngine.installModuleLoader()
  // Register __bridgeRegister JS function on globalThis (C side).
  // Runs before any modules load, so generated .kt files can call it.
  jsEngine.bridgeInitAll()
}

internal fun loadJsModule(jsEngine: JsEngine, script: String, id: String) {
  jsEngine.evaluate("globalThis.$CURRENT_MODULE_ID = '$id';")
  jsEngine.evaluate(script, id)
  jsEngine.evaluate("delete globalThis.$CURRENT_MODULE_ID;")
}

public fun loadJsModule(jsEngine: JsEngine, id: String, bytecode: ByteArray) {
  jsEngine.setGlobalProperty(CURRENT_MODULE_ID, id)
  jsEngine.execute(bytecode, id)
  jsEngine.deleteGlobalProperty(CURRENT_MODULE_ID)
}

internal fun runApplication(jsEngine: JsEngine, mainModuleId: String, mainFunction: String) {
  jsEngine.callRequireMethod(mainModuleId, mainFunction)
}
