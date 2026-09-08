package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

@OptIn(ExperimentalCompilerApi::class)
class ZiplineBridgeCommandLineProcessor : CommandLineProcessor {
  override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID

  override val pluginOptions = listOf(
    CliOption(
      optionName = COutputDirOptionName,
      valueDescription = "<path>",
      description = "Output directory for generated C bridge files",
      required = false,
    ),
    CliOption(
      optionName = NativeOutputDirOptionName,
      valueDescription = "<path>",
      description = "Output directory for generated Kotlin/Native bridge source files",
      required = false,
    ),
    CliOption(
      optionName = JsDispatchOptionName,
      valueDescription = "true|false",
      description = "Enable JS bridge dispatch injection",
      required = false,
    ),
  )

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ) {
    when (option.optionName) {
      COutputDirOptionName ->
        configuration.put(COutputDirKey, value)
      NativeOutputDirOptionName ->
        configuration.put(NativeOutputDirKey, value)
      JsDispatchOptionName ->
        configuration.put(JsDispatchKey, value.toBoolean())
    }
  }
}

val COutputDirKey = CompilerConfigurationKey.create<String>("cOutputDir")
val NativeOutputDirKey = CompilerConfigurationKey.create<String>("nativeOutputDir")
val JsDispatchKey = CompilerConfigurationKey.create<Boolean>("jsDispatch")
const val COutputDirOptionName = "cOutputDir"
const val NativeOutputDirOptionName = "nativeOutputDir"
const val JsDispatchOptionName = "jsDispatch"
