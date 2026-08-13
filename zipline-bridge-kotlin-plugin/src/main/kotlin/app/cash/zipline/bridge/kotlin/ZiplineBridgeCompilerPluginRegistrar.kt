package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class ZiplineBridgeCompilerPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID
  override val supportsK2 get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val cOutputDir = configuration[COutputDirKey]
    val nativeOutputDir = configuration[NativeOutputDirKey]
    val jsDispatch = configuration[JsDispatchKey] ?: (cOutputDir == null && nativeOutputDir == null)
    IrGenerationExtension.registerExtension(
      extension = ZiplineBridgeIrGenerationExtension(cOutputDir, nativeOutputDir, jsDispatch),
    )
  }
}
