package androidx.collection.manual

fun main() {
    println("Running all collection tests...")
    println("================================")

    var totalPassed = 0
    var totalFailed = 0

    val suites = listOf(
        LongSparseArrayTest() to "LongSparseArray",
        CircularArrayTest() to "CircularArray",
        CircularIntArrayTest() to "CircularIntArray",
        IntSetTest() to "IntSet",
        IntObjectMapTest() to "IntObjectMap",
        ScatterSetTest() to "ScatterSet",
        ScatterMapTest() to "ScatterMap",
        CliveTest() to "Clive",
        JsStringHashCodeTest() to "JsStringHashCode",
        JsArrayCopyTest() to "JsArrayCopy",
    )
    for ((suite, name) in suites) {
        val (passed, failed) = runTests(suite, name)
        totalPassed += passed
        totalFailed += failed
    }

    println("\n================================")
    println("Total: $totalPassed passed, $totalFailed failed")
    if (totalFailed > 0) {
        throw IllegalStateException("$totalFailed test(s) failed")
    }
}

interface Tests {
    fun tests(): List<Pair<String, () -> Unit>>
}

fun runTests(tests: Tests, name: String): Pair<Int, Int> {
    var passed = 0
    var failed = 0
    println("\n$name:")
    for ((testName, test) in tests.tests()) {
        try {
            test()
            println("  PASS: $testName")
            passed++
        } catch (e: Throwable) {
            println("  FAIL: $testName - ${e.message}")
            failed++
        }
    }
    return passed to failed
}
