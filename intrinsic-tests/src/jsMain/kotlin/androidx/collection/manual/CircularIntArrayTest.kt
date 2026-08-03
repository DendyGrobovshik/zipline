package androidx.collection.manual

import androidx.collection.CircularIntArray

class CircularIntArrayTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        "creatingWithZeroCapacity" to { creatingWithZeroCapacity() },
        "creatingWithOverCapacity" to { creatingWithOverCapacity() },
        "basicOperations" to { basicOperations() },
        "overpoppingFromStart" to { overpoppingFromStart() },
        "overpoppingFromEnd" to { overpoppingFromEnd() },
        "removeFromEitherEnd" to { removeFromEitherEnd() },
        "overremovalFromStart" to { overremovalFromStart() }
    )

    private fun creatingWithZeroCapacity() {
        checkException<IllegalArgumentException> { CircularIntArray(0) }
    }

    private fun creatingWithOverCapacity() {
        checkException<IllegalArgumentException> { CircularIntArray(Int.MAX_VALUE) }
    }

    private fun basicOperations() {
        val array = CircularIntArray()
        checkCondition(array.isEmpty())
        checkEquals(0, array.size())
        array.addFirst(42)
        array.addFirst(43)
        array.addLast(-1)
        checkCondition(array.isEmpty().not())
        checkEquals(3, array.size())
        checkEquals(43, array.first)
        checkEquals(-1, array.last)
        checkEquals(42, array[1])
        checkEquals(43, array.popFirst())
        checkEquals(-1, array.popLast())
        checkEquals(42, array.first)
        checkEquals(42, array.last)
        checkEquals(42, array.popFirst())
        checkCondition(array.isEmpty())
        checkEquals(0, array.size())
    }

    private fun overpoppingFromStart() {
        val array = CircularIntArray()
        array.addFirst(42)
        array.popFirst()
        checkException<IndexOutOfBoundsException> { array.popFirst() }
    }

    private fun overpoppingFromEnd() {
        val array = CircularIntArray()
        array.addFirst(42)
        array.popLast()
        checkException<IndexOutOfBoundsException> { array.popLast() }
    }

    private fun removeFromEitherEnd() {
        val array = CircularIntArray()
        array.addFirst(42)
        array.addFirst(43)
        array.addLast(-1)
        array.removeFromStart(0)
        array.removeFromStart(-1)
        array.removeFromEnd(0)
        array.removeFromEnd(-1)
        array.removeFromStart(2)
        checkEquals(-1, array.first)
        array.removeFromEnd(1)
        checkCondition(array.isEmpty())
        checkEquals(0, array.size())
    }

    private fun overremovalFromStart() {
        val array = CircularIntArray()
        array.addFirst(42)
        array.addFirst(43)
        array.addLast(-1)
        checkException<IndexOutOfBoundsException> { array.removeFromStart(4) }
    }
}
