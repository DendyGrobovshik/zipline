package androidx.collection.manual

fun main() {
    println("Running all collection tests...")
    println("================================")

    var totalPassed = 0
    var totalFailed = 0

    totalPassed += runTests(LongSparseArrayTest(), "LongSparseArray")
    totalPassed += runTests(CircularArrayTest(), "CircularArray")
    totalPassed += runTests(CircularIntArrayTest(), "CircularIntArray")
    totalPassed += runTests(IntSetTest(), "IntSet")
    totalPassed += runTests(IntObjectMapTest(), "IntObjectMap")
    totalPassed += runTests(ScatterSetTest(), "ScatterSet")
    totalPassed += runTests(ScatterMapTest(), "ScatterMap")
    totalPassed += runTests(CliveTest(), "Clive")
    totalPassed += runTests(JsStringHashCodeTest(), "JsStringHashCode")
    totalPassed += runTests(JsArrayCopyTest(), "JsArrayCopy")

    println("\n================================")
    println("Total: $totalPassed passed, $totalFailed failed")
}

interface Tests {
    fun tests(): List<Pair<String, () -> Unit>>
}

fun runTests(tests: Tests, name: String): Int {
    var passed = 0
    println("\n$name:")
    for ((testName, test) in tests.tests()) {
        try {
            test()
            println("  PASS: $testName")
            passed++
        } catch (e: Throwable) {
            println("  FAIL: $testName - ${e.message}")
        }
    }
    return passed
}