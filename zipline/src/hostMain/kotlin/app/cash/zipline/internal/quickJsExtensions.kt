package app.cash.zipline.internal

import app.cash.zipline.QuickJs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun collectModuleDependencies(quickJs: QuickJs) {
  quickJs.evaluate(COLLECT_DEPENDENCIES_DEFINE_JS, "collectDependencies.js")
}

internal fun getModuleDependencies(quickJs: QuickJs): List<String> {
  val dependenciesString = quickJs.getGlobalThis(CURRENT_MODULE_DEPENDENCIES)
    ?: "[]" // If define is never called, dependencies is returned as null
  return Json.decodeFromString(dependenciesString)
}

internal fun QuickJs.getGlobalThis(key: String): String? {
  return evaluate("globalThis.$key", "getGlobalThis.js") as String?
}

internal fun getLog(quickJs: QuickJs): String? = quickJs.getGlobalThis("log")

internal fun initModuleLoader(quickJs: QuickJs) {
  quickJs.evaluate(DEFINE_JS, "define.js")
  // Register __bridgeRegister JS function on globalThis (C side).
  // Runs before any modules load, so generated .kt files can call it.
  quickJs.bridgeInitAll()
}

internal fun loadJsModule(quickJs: QuickJs, script: String, id: String) {
  quickJs.evaluate("globalThis.$CURRENT_MODULE_ID = '$id';")
  quickJs.evaluate(script, id)
  quickJs.evaluate("delete globalThis.$CURRENT_MODULE_ID;")
  logBridgeDiagnostics(quickJs)
}

internal fun loadJsModule(quickJs: QuickJs, id: String, bytecode: ByteArray) {
  quickJs.evaluate("globalThis.$CURRENT_MODULE_ID = '$id';")
  quickJs.execute(bytecode)
  quickJs.evaluate("delete globalThis.$CURRENT_MODULE_ID;")
  logBridgeDiagnostics(quickJs)
}

/** Print __define_log after each module — shows which modules have bridge FQNs and registration status. */
private fun logBridgeDiagnostics(quickJs: QuickJs) {
  val log = quickJs.evaluate("var _l = globalThis.__define_log; globalThis.__define_log = ''; _l") as? String
  if (!log.isNullOrEmpty()) println("BRIDGE_DEFINE: $log")
}

internal fun runApplication(quickJs: QuickJs, mainModuleId: String, mainFunction: String) {
  quickJs.evaluate(
    script = "require('$mainModuleId').$mainFunction()",
    fileName = "RunApplication.kt",
  )
}
