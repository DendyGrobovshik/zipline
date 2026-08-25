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
package app.cash.zipline.cli

import app.cash.zipline.JsEngine
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.CURRENT_ZIPLINE_VERSION
import app.cash.zipline.loader.ManifestSigner
import app.cash.zipline.loader.ZiplineFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.HashingSink
import okio.buffer
import okio.sink

internal class ZiplineCompiler(
  private val outputDir: File,
  private val mainFunction: String?,
  private val mainModuleId: String?,
  private val manifestSigner: ManifestSigner?,
  private val version: String?,
  private val metadata: Map<String, String>,
  private val stripLineNumbers: Boolean,
  private val debugSourceUrlPrefix: String? = null,
  private val debugSourceRootDir: File? = null,
  private val serveSourceCode: Boolean = false,
) {
  companion object {
    private const val MODULE_PATH_PREFIX = "./"
    private const val ZIPLINE_EXTENSION = ".zipline"
  }

  fun compile(
    inputDir: File,
  ) {
    val jsFiles = getJsFiles(inputDir.listFiles()!!.asList())
    val modules = compileFilesInParallel(jsFiles)
    writeManifest(
      modules = modules,
    )
  }

  fun incrementalCompile(
    modifiedFiles: List<File>,
    addedFiles: List<File>,
    removedFiles: List<File>,
  ) {
    val modifiedFileNames = getJsFiles(modifiedFiles).map { it.name }.toSet()
    val removedFileNames = getJsFiles(removedFiles).map { it.name }.toSet()

    // Get the current manifest and remove any removed or modified modules.
    val manifestFile = File(outputDir.path, manifestFileName)
    val manifest = Json.decodeFromString<ZiplineManifest>(manifestFile.readText())
    val unchangedModules = manifest.modules.filter { (k, _) ->
      val moduleFileName = k.removePrefix(MODULE_PATH_PREFIX)
      moduleFileName !in removedFileNames && moduleFileName !in modifiedFileNames
    }

    // Delete Zipline files for any removed JS files.
    removedFileNames.forEach {
      File(outputDir.path + "/" + it.removeSuffix(".js") + ZIPLINE_EXTENSION).delete()
      if (debugSourceUrlPrefix != null) {
        File(outputDir, it).delete()
        File(outputDir, "$it.map").delete()
      }
    }

    // Compile the newly added or modified files and add them into the module list.
    val addedOrModifiedFiles = getJsFiles(addedFiles) + getJsFiles(modifiedFiles)
    val compiledModules = compileFilesInParallel(addedOrModifiedFiles)

    // Write back a new up-to-date manifest.
    writeManifest(
      modules = unchangedModules + compiledModules,
    )
  }

  private fun compileFilesInParallel(
    files: List<File>,
  ) = runBlocking {
    files
      .map { file ->
        async(Dispatchers.Default) {
          compileSingleFile(file)
        }
      }
      .awaitAll()
      .toMap()
  }

  /**
   * Rewrites a source map's "sources" entries so they resolve under the directory the
   * development server serves. Paths inside [sourceRootDir] become root-relative; paths in
   * sibling checkouts (e.g. redwood-tret, zipline-hermes) become `__kt_root__/...`; anything
   * else (CI/buildbot paths) is left unchanged and will simply be unavailable in DevTools.
   */
  private fun rewriteSourceMapSources(mapText: String, mapFile: File, sourceRootDir: File): String {
    val root = sourceRootDir.canonicalFile
    val parent = root.parentFile
    val mapDir = mapFile.canonicalFile.parentFile
    val mapJson = try {
      Json.parseToJsonElement(mapText).jsonObject
    } catch (_: Exception) {
      return mapText
    }
    val sources = mapJson["sources"]?.jsonArray ?: return mapText
    val rewritten = sources.map { element ->
      val source = (element as? JsonPrimitive)?.contentOrNull ?: return@map element
      val resolved = File(mapDir, source).canonicalFile
      when {
        resolved.path.startsWith(root.path + File.separator) ->
          JsonPrimitive(resolved.relativeTo(root).path)
        parent != null && resolved.path.startsWith(parent.path + File.separator) ->
          JsonPrimitive("__kt_root__/" + resolved.relativeTo(parent).path)
        else -> element
      }
    }
    return buildJsonObject {
      mapJson.forEach { (key, value) ->
        if (key == "sources") put("sources", JsonArray(rewritten)) else put(key, value)
      }
    }.toString()
  }

  private fun compileSingleFile(
    jsFile: File,
  ): Pair<String, ZiplineManifest.Module> {
    val jsSourceMapFile = File("${jsFile.path}.map")
    val outputZiplineFilePath = jsFile.nameWithoutExtension + ZIPLINE_EXTENSION
    val outputZiplineFile = File(outputDir.path, outputZiplineFilePath)

    if (serveSourceCode) {
      // Source mode: serve the raw JavaScript in the .zipline slot instead of
      // Hermes bytecode, so the engine compiles it on device. Runtime
      // compilation populates the scoping info table, which makes CDP frame
      // evaluation and scope inspection work (impossible with precompiled
      // bytecode). Requires the full (non-lean) engine in the app.
      var source = jsFile.readText()
      if (debugSourceUrlPrefix != null) {
        // Point the script's debug-info URL at the dev server; honored by
        // Hermes at runtime compile (//# sourceURL directive).
        source = source.trimEnd() +
          "\n//# sourceURL=${debugSourceUrlPrefix.trimEnd('/')}/${jsFile.name}\n"
      }
      val sha256 = outputZiplineFile.sink().use { fileSink ->
        val hashingSink = HashingSink.sha256(fileSink)
        hashingSink.buffer().use { it.writeUtf8(source) }
        hashingSink.hash
      }
      // Keep serving the .js/.js.map alongside for DevTools.
      if (debugSourceUrlPrefix != null) {
        File(outputDir, jsFile.name).writeText(source)
        if (jsSourceMapFile.exists()) {
          val mapText = jsSourceMapFile.readText()
          val rewritten = debugSourceRootDir?.let { rewriteSourceMapSources(mapText, jsSourceMapFile, it) }
            ?: mapText
          File(outputDir, jsSourceMapFile.name).writeText(rewritten)
        }
      }
      return "$MODULE_PATH_PREFIX${jsFile.name}" to ZiplineManifest.Module(
        url = outputZiplineFilePath,
        sha256 = sha256,
        dependsOnIds = parseDefineDependencies(source),
      )
    }

    val jsEngine = JsEngine.create()
    jsEngine.use {
      val sourceMap = if (jsSourceMapFile.exists()) jsSourceMapFile.readText() else null
      val bytecode = jsEngine.compile(jsFile.readText(), jsFile.name, sourceMap)

      // NOTE: stripLineNumbers is currently ignored — the QuickJS-era
      // implementation operated on QuickJS bytecode and has no Hermes
      // equivalent (the zipline-bytecode module was removed).

      val ziplineFile = ZiplineFile(CURRENT_ZIPLINE_VERSION, bytecode.toByteString())
      val sha256 = outputZiplineFile.sink().use { fileSink ->
        val hashingSink = HashingSink.sha256(fileSink)
        hashingSink.buffer().use {
          ziplineFile.writeTo(it)
        }
        hashingSink.hash
      }

      val dependencies = collectDependencies(jsEngine, bytecode)

      return "$MODULE_PATH_PREFIX${jsFile.name}" to ZiplineManifest.Module(
        url = outputZiplineFilePath,
        sha256 = sha256,
        dependsOnIds = dependencies,
      )
    }
  }

  private fun writeManifest(
    modules: Map<String, ZiplineManifest.Module>,
  ) {
    val unsignedManifest = ZiplineManifest.create(
      modules = modules,
      mainFunction = mainFunction,
      mainModuleId = mainModuleId,
      version = version,
      metadata = metadata,
    )

    val manifest = manifestSigner?.sign(unsignedManifest) ?: unsignedManifest

    val manifestFile = File(outputDir.path, manifestFileName)
    manifestFile.writeText(manifest.encodeJson())
  }

  private fun getJsFiles(files: List<File>) = files.filter { it.path.endsWith(".js") }

  @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Access :zipline internals.
  private val manifestFileName = app.cash.zipline.loader.internal.MANIFEST_FILE_NAME

  @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Access :zipline internals.
  private fun collectDependencies(jsEngine: JsEngine, bytecode: ByteArray): List<String> {
    app.cash.zipline.internal.collectModuleDependencies(jsEngine)
    jsEngine.execute(bytecode)
    return app.cash.zipline.internal.getModuleDependencies(jsEngine)
  }

  /**
   * Extracts module dependencies from the UMD wrapper's `define([...])` header,
   * e.g. `define(['exports', './foo.js'], factory)` -> `["./foo.js"]`.
   * Used in source mode where there is no bytecode to inspect.
   *
   * This regex-parses the first `define([` occurrence: it assumes compiler-
   * generated Kotlin/JS UMD output (single top-level `define` call, simple
   * string-literal dependency names), not arbitrary JavaScript.
   */
  private fun parseDefineDependencies(source: String): List<String> {
    val match = Regex("""define\(\s*\[([^]]*)]""").find(source) ?: return emptyList()
    return Regex("""['"]([^'"]+)['"]""").findAll(match.groupValues[1])
      .map { it.groupValues[1] }
      .filter { it != "exports" && it != "require" }
      .toList()
  }
}
