package androidx.collection.manual

fun checkCondition(condition: Boolean) {
    if (!condition) throw RuntimeException("Assertion failed")
}

fun checkEquals(expected: Any?, actual: Any?) {
    if (expected != actual) throw RuntimeException("Expected $expected but got $actual")
}

inline fun <reified T : Throwable> checkException(block: () -> Unit) {
    try {
        block()
        throw RuntimeException("Expected ${T::class.simpleName} but no exception was thrown")
    } catch (e: Throwable) {
        if (e is RuntimeException && e.message?.startsWith("Expected") == true) throw e
        if (e is T) return
        throw RuntimeException("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
    }
}
