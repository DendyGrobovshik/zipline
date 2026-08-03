@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package app.cash.zipline

import app.cash.zipline.hermes.*
import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.INBOUND_CHANNEL_NAME
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.*

// Channels and sinks are keyed by engine context pointer so that multiple
// concurrent runtimes (e.g. an old and a new Zipline during a screen
// transition) each route their JS calls to their own endpoint. The maps are
// copy-on-write because registration (zipline thread) and callback
// invocation (any thread running JS) may race.
private val outboundChannels = AtomicReference<Map<Long, CallChannel>>(emptyMap())
private val rdmaChangeSinks = AtomicReference<Map<Long, RdmaChangeSink>>(emptyMap())

private fun <V> AtomicReference<Map<Long, V>>.putValue(key: Long, value: V) {
  while (true) {
    val current = load()
    if (compareAndSet(current, current + (key to value))) return
  }
}

private fun <V> AtomicReference<Map<Long, V>>.removeValue(key: Long) {
  while (true) {
    val current = load()
    if (key !in current) return
    if (compareAndSet(current, current - key)) return
  }
}

@Suppress("UNCHECKED_CAST")
private fun outboundChannelCallCallback(context: COpaquePointer?, callJson: CPointer<ByteVar>?): CPointer<ByteVar>? {
    val channel = outboundChannels.load()[context?.rawValue?.toLong() ?: return null] ?: return null
    val callJsonStr = callJson?.toKString() ?: return null
    val result: String = channel.call(callJsonStr)
    val bytes: ByteArray = result.encodeToByteArray()
    val byteCount: Int = bytes.size
    // The C++ caller releases the returned buffer with free(), so it must be
    // allocated with the system allocator, not Kotlin/Native's nativeHeap.
    val persistentPtr: CPointer<ByteVar> = platform.posix
        .malloc((byteCount + 1).convert())
        ?.reinterpret()
        ?: return null
    return bytes.usePinned { pinned ->
        val srcPtr: CPointer<ByteVar> = pinned.addressOf(0).reinterpret()
        platform.posix.memcpy(persistentPtr, srcPtr, byteCount.toULong())
        // Set the last byte to 0
        platform.posix.memset(persistentPtr + byteCount, 0, 1uL)
        persistentPtr
    }
}

private fun outboundChannelDisconnectCallback(context: COpaquePointer?, instanceName: CPointer<ByteVar>?): Int {
    val channel = outboundChannels.load()[context?.rawValue?.toLong() ?: return 0] ?: return 0
    val instanceNameStr = instanceName?.toKString() ?: return 0
    return if (channel.disconnect(instanceNameStr)) 1 else 0
}

// Called from C++ when JS invokes finishChanges() on the RDMA channel.
// Delegates to rdmaChangeSink.sendChanges() which flushes all accumulated
// changes to the UI. The C++ side manages the pendingChanges list and
// removeCounter internally; only the final sendChanges() call reaches Kotlin.
private fun rdmaChangeSinkSendChanges(context: COpaquePointer?) {
    val sink = rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return] ?: return
    sink.sendChanges()
}

/*
* This class is NOT thread safe. If multiple threads access an instance concurrently it must be
* synchronized externally.
*/
@OptIn(ExperimentalForeignApi::class)
@EngineApi
actual class JsEngine private constructor(
  private val contextPointer: COpaquePointer?,
) : AutoCloseable {
  private var closed = false

  actual companion object {
    actual fun create(): JsEngine {
      val runtime = HermesRuntime_create()
        ?: throw UnsupportedOperationException("Failed to create Hermes runtime")
      val jsiRuntime = HermesRuntime_getJsiRuntime(runtime)
        ?: throw UnsupportedOperationException("Failed to get jsi runtime from Hermes context")

      // Register JS intrinsics (IntSet/ScatterSet/ScatterMap/etc.) that back the
      // kotlinx.collections fast paths in Kotlin/JS. These are called from
      // generated Kotlin/JS code via _intsetFind, _scatterSetFind, etc.
      js_register_intrinsics(jsiRuntime)

      return JsEngine(runtime)
    }

    actual val version: String
      get() = Hermes_getVersion()!!.toKString()
  }

  actual var interruptHandler: InterruptHandler?
    get() = throw UnsupportedOperationException()
    set(value) {
      // Hermes has no per-instruction interrupt hook; its wall-clock
      // watchTimeLimit/asyncTriggerTimeout mechanism is not wired up yet.
      // Fail loudly instead of silently dropping the handler.
      throw UnsupportedOperationException("InterruptHandler is not supported by the Hermes native engine")
    }

  actual val memoryUsage: MemoryUsage
    get() {
      checkNotClosed()
      memScoped {
        val usage = alloc<HermesCoreMemoryUsage>()
        val ok = HermesContext_getMemoryUsage(contextPointer, usage.ptr)
        check(ok != 0) { "HermesContext_getMemoryUsage failed" }
        return MemoryUsage(
          heapSize = usage.heapSize,
          allocatedBytes = usage.allocatedBytes,
          totalAllocatedBytes = usage.totalAllocatedBytes,
          va = usage.va,
          externalBytes = usage.externalBytes,
          mallocSizeEstimate = usage.mallocSizeEstimate,
          peakAllocatedBytes = usage.peakAllocatedBytes,
          peakLiveAfterGC = usage.peakLiveAfterGC,
          numCollections = usage.numCollections,
          numMarkStackOverflows = usage.numMarkStackOverflows,
        )
      }
    }

  actual var memoryLimit: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("memoryLimit is not supported by the Hermes engine")
    }

  actual var gcThreshold: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("gcThreshold is not supported by the Hermes engine")
    }

  actual var maxStackSize: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("maxStackSize is not supported by the Hermes engine")
    }

  private fun HermesTaggedValue.toAny(errorFallback: String): Any? = when (tag) {
    HERMES_TAG_ERROR -> throw JsException(
      HermesContext_getLastError(contextPointer)?.toKString() ?: errorFallback,
    )
    HERMES_TAG_NULL -> null
    HERMES_TAG_INT -> number.toInt()
    // Numbers always cross as double; re-narrow integral values to Int.
    HERMES_TAG_DOUBLE -> number.toInt().let { if (it.toDouble() == number) it else number }
    HERMES_TAG_BOOL -> number != 0.0
    HERMES_TAG_STRING -> {
      val value = string!!.toKString()
      HermesContext_freeValue(contextPointer, string)
      value
    }
    else -> null
  }

  actual fun evaluate(script: String, fileName: String): Any? {
    checkNotClosed()
    return HermesContext_evaluate(contextPointer, script, fileName).useContents {
      toAny("Evaluation failed")
    }
  }

  actual fun compile(sourceCode: String, fileName: String, sourceMap: String?): ByteArray {
    checkNotClosed()
    memScoped {
      val bytecodeOut = alloc<CPointerVarOf<CPointer<ByteVar>>>()
      val bytecodeSizeOut = alloc<IntVar>()
      val result = HermesContext_compile(
        contextPointer,
        sourceCode,
        fileName,
        sourceMap,
        bytecodeOut.ptr,
        bytecodeSizeOut.ptr,
      )
      if (result == 0) {
        val error = HermesContext_getLastError(contextPointer)
        throw JsException(error?.toKString() ?: "Compilation failed")
      }
      val bytecodeSizeVal = bytecodeSizeOut.value
      val bytecode = if (bytecodeOut.value != null && bytecodeSizeVal > 0) {
        bytecodeOut.value!!.readBytes(bytecodeSizeVal)
      } else {
        ByteArray(0)
      }
      HermesContext_freeValue(contextPointer, bytecodeOut.value)
      return bytecode
    }
  }

  actual fun execute(bytecode: ByteArray, fileName: String): Any? {
    checkNotClosed()
    val byteArrayPin = bytecode.pin()
    val tagged = HermesContext_execute(
      contextPointer,
      byteArrayPin.addressOf(0).reinterpret<UByteVar>(),
      bytecode.size,
      fileName,
    )
    byteArrayPin.unpin()
    return tagged.useContents { toAny("Execution failed") }
  }

  actual fun gc() {
    checkNotClosed()
    HermesContext_gc(contextPointer)
  }

  actual fun getGlobalProperty(name: String): String? {
    checkNotClosed()
    memScoped {
      val valuePtr = alloc<CPointerVarOf<CPointer<ByteVar>>>()
      val result = HermesContext_getGlobalProperty(contextPointer, name, valuePtr.ptr)
      if (result == 0) {
        return null
      }
      val value = valuePtr.value?.toKString()
      HermesContext_freeValue(contextPointer, valuePtr.value)
      return value
    }
  }

  actual fun setGlobalProperty(name: String, value: String) {
    checkNotClosed()
    val result = HermesContext_setGlobalProperty(contextPointer, name, value)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to set global property")
    }
  }

  actual fun deleteGlobalProperty(name: String) {
    checkNotClosed()
    val result = HermesContext_deleteGlobalProperty(contextPointer, name)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to delete global property")
    }
  }

  actual fun callGlobalMethod(objectName: String, methodName: String) {
    checkNotClosed()
    val result = HermesContext_callGlobalMethod(contextPointer, objectName, methodName)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to call global method")
    }
  }

  actual fun callGlobalFunctionWithStringArg(functionName: String, arg: String): String? {
    checkNotClosed()
    memScoped {
      val resultOut = alloc<CPointerVarOf<CPointer<ByteVar>>>()
      val result = HermesContext_callGlobalFunctionWithStringArg(
        contextPointer,
        functionName,
        arg,
        resultOut.ptr,
      )
      if (result == 0) {
        val error = HermesContext_getLastError(contextPointer)
        throw UnsupportedOperationException(error?.toKString() ?: "Failed to call global function")
      }
      val value = resultOut.value?.toKString()
      HermesContext_freeValue(contextPointer, resultOut.value)
      return value
    }
  }

  actual fun callRequireMethod(moduleId: String, methodName: String) {
    checkNotClosed()
    val result = HermesContext_callRequireMethod(contextPointer, moduleId, methodName)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to call require method")
    }
  }

  actual fun installModuleLoader() {
    checkNotClosed()
    val result = HermesContext_installModuleLoader(contextPointer)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to install module loader")
    }
  }

  internal actual fun initOutboundChannel(outboundChannel: CallChannel) {
    checkNotClosed()
    val context = requireNotNull(contextPointer) { "Engine has no native context" }
    outboundChannels.putValue(context.rawValue.toLong(), outboundChannel)
    HermesContext_setOutboundChannelCallbacks(
      context,
      staticCFunction(::outboundChannelCallCallback),
      staticCFunction(::outboundChannelDisconnectCallback)
    )
    val result = HermesContext_setupOutboundCallChannel(contextPointer)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to setup outbound call channel")
    }
  }

  internal actual fun getInboundChannel(): CallChannel {
    checkNotClosed()
    if (HermesContext_hasGlobalObject(contextPointer, INBOUND_CHANNEL_NAME) != 1) {
      throw IllegalStateException(
        "A global JavaScript object called $INBOUND_CHANNEL_NAME was not found. " +
          "Try confirming that Zipline.get() has been called."
      )
    }
    return object : CallChannel {
      override fun call(callJson: String): String {
        checkNotClosed()
        val resultPtr: CPointer<ByteVar>? = HermesContext_callInbound(
          contextPointer,
          INBOUND_CHANNEL_NAME,
          callJson
        )
        if (resultPtr == null) {
          val error = HermesContext_getLastError(contextPointer)
          throw JsException(error?.toKString() ?: "Failed to call inbound channel")
        }
        val resultStr = resultPtr.toKString()
        platform.posix.free(resultPtr)
        return resultStr
      }

      override fun disconnect(instanceName: String): Boolean {
        checkNotClosed()
        val resultPtr: CPointer<ByteVar>? = HermesContext_callInboundDisconnect(
          contextPointer,
          INBOUND_CHANNEL_NAME,
          instanceName
        )
        if (resultPtr == null) {
          val error = HermesContext_getLastError(contextPointer)
          throw JsException(error?.toKString() ?: "Failed to call inbound disconnect")
        }
        val resultStr = resultPtr.toKString()
        platform.posix.free(resultPtr)
        return resultStr == "true"
      }
    }
  }

  actual var rdmaChangeSink: RdmaChangeSink? = null

  actual fun initRdmaChangesChannel() {
    checkNotClosed()
    if (rdmaChangeSink == null) return
    val context = requireNotNull(contextPointer) { "Engine has no native context" }
    // Store the Kotlin rdmaChangeSink in a per-context registry so the C++
    // layer can invoke it via rdmaChangeSinkSendChanges() when JS calls
    // finishChanges() on this engine's runtime.
    rdmaChangeSinks.putValue(context.rawValue.toLong(), rdmaChangeSink!!)
    // Wire the Kotlin rdmaChangeSink into the C++ layer so finishChanges()
    // can call rdmaChangeSink.sendChanges() when JS invokes finishChanges.
    // The C++ side manages pendingChanges and removeCounter internally; only
    // the final sendChanges() call delegates to Kotlin.
    HermesContext_setRdmaChangeSink(context, staticCFunction(::rdmaChangeSinkSendChanges))
    val result = HermesContext_initRdmaChangesChannel(contextPointer)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to init RDMA changes channel")
    }
  }

  internal fun checkNotClosed() {
    check(!closed) { "JsEngine instance was closed" }
  }

  actual override fun close() {
    if (!closed) {
      closed = true

      // Deregister this engine's channel/sink so late JS calls into the
      // (about to be destroyed) runtime can't reach Kotlin, and so the
      // per-context registries don't retain dead objects.
      if (contextPointer != null) {
        outboundChannels.removeValue(contextPointer.rawValue.toLong())
        HermesContext_setOutboundChannelCallbacks(contextPointer, null, null)

        rdmaChangeSinks.removeValue(contextPointer.rawValue.toLong())
        HermesContext_setRdmaChangeSink(contextPointer, null)
      }

      HermesRuntime_destroy(contextPointer)
    }
  }
}
