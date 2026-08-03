package androidx.collection.manual

class JsArrayCopyTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        "arrayCopyStringArray" to { arrayCopyStringArray() },
        "arrayCopyOverlappingSameArray" to { arrayCopyOverlappingSameArray() },
        "arrayCopyOverlappingDstBeforeSrc" to { arrayCopyOverlappingDstBeforeSrc() },
        "arrayCopyOverlappingDstAfterSrc" to { arrayCopyOverlappingDstAfterSrc() },
        "arrayCopyLengthExceedsSrc" to { arrayCopyLengthExceedsSrc() },
        "arrayCopyEmptyArray" to { arrayCopyEmptyArray() },
        "arrayCopySingleElement" to { arrayCopySingleElement() },
        "arrayCopyPreservesType" to { arrayCopyPreservesType() },
    )

    private fun arrayCopyStringArray() {
        val src = arrayOf("a", "b", "c", "d")
        val dst = arrayOfNulls<String>(4)
        arrayCopy(src, dst, 0, 0, 4)
        checkEquals("a", dst[0])
        checkEquals("b", dst[1])
        checkEquals("c", dst[2])
        checkEquals("d", dst[3])
    }

    private fun arrayCopyOverlappingSameArray() {
        val arr = arrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        arrayCopy(arr, arr, 2, 0, 5)
        checkEquals(1, arr[0])
        checkEquals(2, arr[1])
        checkEquals(1, arr[2])
        checkEquals(2, arr[3])
        checkEquals(3, arr[4])
        checkEquals(4, arr[5])
        checkEquals(5, arr[6])
        checkEquals(8, arr[7])
        checkEquals(9, arr[8])
        checkEquals(10, arr[9])
    }

    private fun arrayCopyOverlappingDstBeforeSrc() {
        val src = arrayOf(1, 2, 3, 4, 5)
        val dst = arrayOfNulls<Any>(7)
        arrayCopy(src, dst, 0, 0, 3)
        checkEquals(1, dst[0])
        checkEquals(2, dst[1])
        checkEquals(3, dst[2])
    }

    private fun arrayCopyOverlappingDstAfterSrc() {
        val arr = arrayOf(10, 20, 30, 40, 50, 60, 70)
        arrayCopy(arr, arr, 3, 0, 4)
        checkEquals(10, arr[0])
        checkEquals(20, arr[1])
        checkEquals(30, arr[2])
        checkEquals(10, arr[3])
        checkEquals(20, arr[4])
        checkEquals(30, arr[5])
        checkEquals(40, arr[6])
    }

    private fun arrayCopyLengthExceedsSrc() {
        val src = arrayOf(1, 2)
        val dst = arrayOfNulls<Any>(5)
        try {
            arrayCopy(src, dst, 0, 0, 10)
            throw IllegalStateException("Unreachable")
        } catch (e: IndexOutOfBoundsException) {
        }
        checkEquals(null, dst[0])
        checkEquals(null, dst[1])
    }

    private fun arrayCopyEmptyArray() {
        val src = arrayOf<Any>()
        val dst = arrayOf<Any>()
        arrayCopy(src, dst, 0, 0, 0)
        checkEquals(0, dst.size)
    }

    private fun arrayCopySingleElement() {
        val src = arrayOf(42)
        val dst = arrayOfNulls<Any>(1)
        arrayCopy(src, dst, 0, 0, 1)
        checkEquals(42, dst[0])
    }

    private fun arrayCopyPreservesType() {
        val src = arrayOf("a", "b", "c")
        val dst = arrayOfNulls<String>(3)
        arrayCopy(src, dst, 0, 0, 3)
        checkEquals("a", dst[0])
        checkEquals("b", dst[1])
        checkEquals("c", dst[2])
    }
}

private fun <T> arrayCopy(source: Array<out T>, destination: Array<in T>, destinationOffset: Int, startIndex: Int, endIndex: Int) {
    checkRangeIndexes(startIndex, endIndex, source.size)
    val rangeSize = endIndex - startIndex
    checkRangeIndexes(destinationOffset, destinationOffset + rangeSize, destination.size)
    _arrayCopy(source, destination, destinationOffset, startIndex, endIndex)
}

private fun checkRangeIndexes(fromIndex: Int, toIndex: Int, size: Int) {
    if (fromIndex < 0 || toIndex > size) {
        throw IndexOutOfBoundsException()
    }
}

private external fun _arrayCopy(source: dynamic, destination: dynamic, destinationOffset: Int, startIndex: Int, endIndex: Int)