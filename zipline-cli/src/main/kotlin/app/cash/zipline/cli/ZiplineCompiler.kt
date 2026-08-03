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

  private fun compileSingleFile(
    jsFile: File,
  ): Pair<String, ZiplineManifest.Module> {
    val jsSourceMapFile = File("${jsFile.path}.map")
    val outputZiplineFilePath = jsFile.nameWithoutExtension + ZIPLINE_EXTENSION
    val outputZiplineFile = File(outputDir.path, outputZiplineFilePath)

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
}
