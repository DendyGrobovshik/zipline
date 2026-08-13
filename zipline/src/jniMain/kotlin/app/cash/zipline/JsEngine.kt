package app.cash.zipline

import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.INBOUND_CHANNEL_NAME
import app.cash.zipline.internal.bridge.OUTBOUND_CHANNEL_NAME
import app.cash.zipline.internal.log
import java.io.Closeable

/**
 * An ECMAScript (JavaScript) interpreter backed by the JsEngine native engine.
 *
 * This class is NOT thread safe. If multiple threads access an instance concurrently it must be
 * synchronized externally.
 */
@EngineApi
actual class JsEngine private constructor(
  internal var context: Long,
) : AutoCloseable,
  Closeable {
  actual companion object {
    init {
      loadNativeLibrary()
    }

    /**
     * Create a new interpreter instance. Calls to this method **must** be matched with
     * calls to [close] on the returned instance to avoid leaking native memory.
     */
    @JvmStatic
    actual fun create(): JsEngine {
      val ctx = createContext()
      if (ctx == 0L) {
        throw OutOfMemoryError("Cannot create JsEngine instance")
      }
      return JsEngine(ctx)
    }

    @JvmStatic
    external fun createContext(): Long

    actual val version: String
      get() = jsEngineVersion
  }

  /**
   * Hermes has no per-instruction interrupt hook; its wall-clock
   * watchTimeLimit/asyncTriggerTimeout mechanism is not wired up yet.
   * Fail loudly instead of silently dropping the handler.
   */
  actual var interruptHandler: InterruptHandler?
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("InterruptHandler is not supported by the Hermes engine")
    }

  actual var rdmaChangeSink: RdmaChangeSink? = null

  /** Memory usage statistics for the JavaScript engine. */
  actual val memoryUsage: MemoryUsage
    get() = memoryUsage(context) ?: throw AssertionError()

  /** Hermes heap limit is fixed at runtime construction; resizing is unsupported. */
  actual var memoryLimit: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("memoryLimit is not supported by the Hermes engine")
    }

  /** Hermes GC is heap-pressure driven; there is no threshold callback. */
  actual var gcThreshold: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("gcThreshold is not supported by the Hermes engine")
    }

  /** Hermes stack overflow is guarded at runtime construction; resizing is unsupported. */
  actual var maxStackSize: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("maxStackSize is not supported by the Hermes engine")
    }

  /**
   * Evaluate [script] and return any result. [fileName] will be used in error
   * reporting.
   *
   * @throws JsException if there is an error evaluating the script.
   */
  actual fun evaluate(script: String, fileName: String): Any? {
    val bytecode = compile(script, fileName)
    return execute(bytecode)
  }

  actual fun evaluateForBridge(script: String, fileName: String): Any? = evaluate(script, fileName)

  internal actual fun initOutboundChannel(outboundChannel: CallChannel) {
    setOutboundCallChannel(context, OUTBOUND_CHANNEL_NAME, outboundChannel)
  }

  actual fun initRdmaChangesChannel() {
    // RDMA is an optional redwood-treehouse integration; without a sink there
    // is nothing to deliver changes to, and the native side would need
    // redwood classes that may be absent from the classpath.
    if (rdmaChangeSink == null) return
    initRdmaChangesChannel(context)
  }

  internal actual fun getInboundChannel(): CallChannel {
    val instance = getInboundCallChannel(context, INBOUND_CHANNEL_NAME)
    if (instance == 0L) {
      throw OutOfMemoryError("Cannot create JsEngine proxy to inbound channel")
    }
    return JniCallChannel(this, instance)
  }

  actual fun gc() {
    gc(context)
  }

  /**
   * Compile [sourceCode] and return the bytecode. [fileName] will be used in error
   * reporting. [sourceMap] is optional to enable Kotlin stacktraces.
   *
   * @throws JsException if the sourceCode could not be compiled.
   */
  actual fun compile(sourceCode: String, fileName: String, sourceMap: String?): ByteArray {
    return compile(context, sourceCode, fileName, sourceMap)
  }

  /**
   * Load and execute [bytecode] and return the result. [fileName] will be used
   * in error reporting.
   *
   * @throws JsException if there is an error loading or executing the code.
   */
  actual fun execute(bytecode: ByteArray, fileName: String): Any? {
    return execute(context, bytecode, fileName)
  }

  actual fun getGlobalProperty(name: String): String? {
    return getGlobalProperty(context, name)
  }

  actual fun setGlobalProperty(name: String, value: String) {
    setGlobalProperty(context, name, value)
  }

  actual fun deleteGlobalProperty(name: String) {
    deleteGlobalProperty(context, name)
  }

  actual fun callGlobalMethod(objectName: String, methodName: String) {
    callGlobalMethod(context, objectName, methodName)
  }

  actual fun callGlobalFunctionWithStringArg(functionName: String, arg: String): String? {
    return callGlobalFunctionWithStringArg(context, functionName, arg)
  }

  actual fun callRequireMethod(moduleId: String, methodName: String) {
    callRequireMethod(context, moduleId, methodName)
  }

  actual fun installModuleLoader() {
    installModuleLoader(context)
  }

  actual override fun close() {
    val contextToClose = context
    if (contextToClose != 0L) {
      context = 0L
      destroyContext(contextToClose)
    }
  }

  protected fun finalize() {
    if (context != 0L) {
      log("warn", "JsEngine instance leaked!", null)
    }
  }

  private external fun destroyContext(context: Long)
  private external fun getInboundCallChannel(context: Long, name: String): Long
  private external fun setOutboundCallChannel(context: Long, name: String, callChannel: CallChannel)
  private external fun execute(context: Long, bytecode: ByteArray, fileName: String): Any?
  private external fun compile(context: Long, sourceCode: String, fileName: String, sourceMap: String?): ByteArray
  private external fun memoryUsage(context: Long): MemoryUsage?
  private external fun gc(context: Long)
  private external fun getGlobalProperty(context: Long, name: String): String?
  private external fun setGlobalProperty(context: Long, name: String, value: String)
  private external fun deleteGlobalProperty(context: Long, name: String)
  private external fun callGlobalMethod(context: Long, objectName: String, methodName: String)
  private external fun callGlobalFunctionWithStringArg(context: Long, functionName: String, arg: String): String?
  private external fun callRequireMethod(context: Long, moduleId: String, methodName: String)
  private external fun installModuleLoader(context: Long)
  @JvmName("initRdmaChangesChannel")
  private external fun initRdmaChangesChannel(context: Long)

  internal actual fun bridgeInitAll() {
    bridgeInitAllNative(getJsContext(context))
  }

  @JvmName("bridgeInitAllNative")
  private external fun bridgeInitAllNative(jsContext: Long)
  private external fun getJsContext(context: Long): Long
}

internal expect fun loadNativeLibrary()
