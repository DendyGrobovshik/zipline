package app.cash.zipline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale.US

@Suppress("UnsafeDynamicallyLoadedCode") // Only loading from our own JAR contents.
internal actual fun loadNativeLibrary() {
  val osName = System.getProperty("os.name").lowercase(US)
  val osArch = System.getProperty("os.arch").lowercase(US)
  // Combined Hermes + JNI glue library. On Android, Hermes's CMake produces
  // libhermesvm.so with our JNI glue merged in (via target_sources in
  // hermes-jni-build/CMakeLists.txt). On macOS/Linux host, the Gradle
  // buildHermesHost* tasks produce libhermesvm.dylib/so the same way.
  // The JVM loads this single library; no separate libzipline_hermes_jni.so needed.
  val libName = when {
    osName.contains("linux") -> "libhermesvm.so"
    osName.contains("mac") -> "libhermesvm.dylib"
    osName.contains("windows") -> "hermesvm.dll"
    else -> throw IllegalStateException("Unsupported OS: $osName")
  }
  val resourcePath = "/jni/$osArch/$libName"
  val url = JsEngine::class.java.getResource(resourcePath)
      ?: throw IllegalStateException("Unable to read $resourcePath from JAR")
  val tempFile: Path
  try {
    tempFile = Files.createTempFile(libName.substringBeforeLast('.'), null)
    tempFile.toFile().deleteOnExit()
    url.openStream().use { Files.copy(it, tempFile, REPLACE_EXISTING) }
    System.load(tempFile.toAbsolutePath().toString())
  } catch (e: IOException) {
    throw RuntimeException("Unable to extract native library from JAR", e)
  } catch (e: UnsatisfiedLinkError) {
    throw RuntimeException("Unable to load JsEngine native library", e)
  }
}
