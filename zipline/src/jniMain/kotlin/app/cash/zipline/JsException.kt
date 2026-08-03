package app.cash.zipline

import androidx.annotation.Keep
import java.util.regex.Pattern

@Keep // Instruct ProGuard not to strip this type.
actual class JsException @JvmOverloads constructor(
  detailMessage: String,
  jsStackTrace: String? = null,
) : RuntimeException(detailMessage) {
  init {
    if (jsStackTrace != null) {
      addJavaScriptStack(jsStackTrace)
    }
  }

  private companion object {
    // Hermes JS stack frames are formatted as either:
    //   "at <name> (<file>:<line>:<col>)"    e.g. "at JavaScript.f1(explode.js:2:57)"
    //   "at <name> (native)"                  e.g. "at JavaScript.disconnect(native)"
    // The previous QuickJS-style "at <name> (<file>:<line>)" is the third shape.
    // We match the name and the file-part separately and split the file part
    // on ":" to extract line/col, which is more robust than a single
    // monolithic regex.
    private val STACK_TRACE_PATTERN =
      Pattern.compile("\\s*at (\\S+) \\(([^()]+)\\).*")

    private const val STACK_TRACE_CLASS_NAME = "JavaScript"

    // Hermes JNI bridge splices JS frames into the Java stack as
    // "<name>(<file>:<line>:<col>)" — by stuffing ":line:col" into the
    // StackTraceElement.fileName field. Strip the trailing ":col" so the
    // existing frames match the QuickJS-style "<name>(<file>:<line>)"
    // format that the test suite expects. The full column info is still
    // recoverable from the original fileName if a caller needs it.
    private val COLUMN_SUFFIX = Pattern.compile(":\\d+$")

    @JvmStatic // Expose for easy invocation from native.
    @JvmSynthetic // Hide from public API to Java consumers.
    fun Throwable.addJavaScriptStack(detailMessage: String) {
      val lines = detailMessage.split('\n').dropLastWhile(String::isEmpty)
      if (lines.isEmpty()) return
      val elements = mutableListOf<StackTraceElement>()

      var spliced = false
      for (stackTraceElement in stackTrace) {
        if (!spliced && stackTraceElement.isNativeMethod && stackTraceElement.isZipline) {
          spliced = true
          for (line in lines) {
            val jsElement = toStackTraceElement(line) ?: continue
            elements += jsElement
          }
        }
        // Rewrite JS frames to drop the column number.
        if (stackTraceElement.className == STACK_TRACE_CLASS_NAME) {
          elements += withoutColumn(stackTraceElement)
        } else {
          elements += stackTraceElement
        }
      }
      stackTrace = elements.toTypedArray()
    }

    private fun withoutColumn(el: StackTraceElement): StackTraceElement {
      val m = COLUMN_SUFFIX.matcher(el.fileName ?: "")
      val stripped = if (m.find()) {
        el.fileName!!.substring(0, m.start())
      } else {
        el.fileName
      }
      return StackTraceElement(
        el.className, el.methodName, stripped, el.lineNumber
      )
    }

    private val StackTraceElement.isZipline: Boolean
      get() = className == JsEngine::class.java.name || className == JniCallChannel::class.java.name

    private fun toStackTraceElement(s: String): StackTraceElement? {
      val m = STACK_TRACE_PATTERN.matcher(s)
      if (!m.matches()) return null
      val name = m.group(1) ?: return null
      val filePart = m.group(2) ?: return null
      if (filePart.endsWith("cpp", ignoreCase = true)) return null

      // filePart is one of:
      //   "native"                     -> name="disconnect", file="native", line=-1
      //   "test.js:2"                  -> file="test.js", line=2
      //   "test.js:2:57"               -> file="test.js", line=2
      // We strip an optional trailing ":<col>" component, mirroring
      // withoutColumn() below.
      val parts = filePart.split(":")
      val (file, lineNumber) = when (parts.size) {
        1 -> filePart to -1
        2 -> parts[0] to (parts[1].toIntOrNull() ?: -1)
        else -> parts[0] to (parts[1].toIntOrNull() ?: -1)  // drop column
      }
      return StackTraceElement(STACK_TRACE_CLASS_NAME, name, file, lineNumber)
    }
  }
}
