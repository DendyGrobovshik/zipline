package androidx.collection.manual

import androidx.collection.CircularArray

class CircularArrayTest : Tests {
    private val ELEMENT_X = "x"
    private val ELEMENT_Y = "y"
    private val ELEMENT_Z = "z"

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
        checkException<IllegalArgumentException> { CircularArray<String>(0) }
    }

    private fun creatingWithOverCapacity() {
        checkException<IllegalArgumentException> { CircularArray<String>(Int.MAX_VALUE) }
    }

    private fun basicOperations() {
        val array = CircularArray<String>()
        checkCondition(array.isEmpty())
        checkEquals(0, array.size())
        array.addFirst(ELEMENT_X)
        array.addFirst(ELEMENT_Y)
        array.addLast(ELEMENT_Z)
        checkCondition(array.isEmpty().not())
        checkEquals(3, array.size())
        checkEquals(ELEMENT_Y, array.first)
        checkEquals(ELEMENT_Z, array.last)
        checkEquals(ELEMENT_X, array[1])
        checkEquals(ELEMENT_Y, array.popFirst())
        checkEquals(ELEMENT_Z, array.popLast())
        checkEquals(ELEMENT_X, array.first)
        checkEquals(ELEMENT_X, array.last)
        checkEquals(ELEMENT_X, array.popFirst())
        checkCondition(array.isEmpty())
        checkEquals(0, array.size())
    }

    private fun overpoppingFromStart() {
        val array = CircularArray<String>()
        array.addFirst(ELEMENT_X)
        array.popFirst()
        checkException<IndexOutOfBoundsException> { array.popFirst() }
    }

    private fun overpoppingFromEnd() {
        val array = CircularArray<String>()
        array.addFirst(ELEMENT_X)
        array.popLast()
        checkException<IndexOutOfBoundsException> { array.popLast() }
    }

    private fun removeFromEitherEnd() {
        val array = CircularArray<String>()
        array.addFirst(ELEMENT_X)
        array.addFirst(ELEMENT_Y)
        array.addLast(ELEMENT_Z)
        array.removeFromStart(0)
        array.removeFromEnd(0)
        array.removeFromStart(2)
        checkEquals(1, array.size())
        checkEquals(ELEMENT_Z, array.first)
    }

    private fun overremovalFromStart() {
        val array = CircularArray<String>()
        array.addFirst(ELEMENT_X)
        checkException<IndexOutOfBoundsException> { array.removeFromStart(5) }
    }
}
