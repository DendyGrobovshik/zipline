package androidx.collection.manual

import androidx.collection.MutableIntSet
import androidx.collection.mutableIntSetOf
import androidx.collection.intSetOf

class IntSetTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        "emptyIntSetConstructor" to { emptyIntSetConstructor() },
        "zeroCapacityIntSet" to { zeroCapacityIntSet() },
        "mutableIntSetBuilder" to { mutableIntSetBuilder() },
        "addToIntSet" to { addToIntSet() },
        "addExistingElement" to { addExistingElement() },
        "addAllArray" to { addAllArray() },
        "addAllIntSet" to { addAllIntSet() },
        "remove" to { remove() },
        "removeAllArray" to { removeAllArray() },
        "removeAllIntSet" to { removeAllIntSet() },
        "clear" to { clear() },
        "forEach" to { forEach() },
        "insertManyEntries" to { insertManyEntries() },
        "string" to { string() }
    )

    private fun emptyIntSetConstructor() {
        val set = MutableIntSet()
        checkEquals(7, set.capacity)
        checkEquals(0, set.size)
    }

    private fun zeroCapacityIntSet() {
        val set = MutableIntSet(0)
        checkEquals(0, set.capacity)
        checkEquals(0, set.size)
    }

    private fun mutableIntSetBuilder() {
        val empty = mutableIntSetOf()
        checkEquals(0, empty.size)
        val withElements = mutableIntSetOf(1, 2)
        checkEquals(2, withElements.size)
        checkCondition(1 in withElements)
        checkCondition(2 in withElements)
    }

    private fun addToIntSet() {
        val set = MutableIntSet()
        set += 1
        checkCondition(set.add(2))
        checkEquals(2, set.size)
        val elements = IntArray(2)
        var index = 0
        set.forEach { element -> elements[index++] = element }
        elements.sort()
        checkEquals(1, elements[0])
        checkEquals(2, elements[1])
    }

    private fun addExistingElement() {
        val set = MutableIntSet(12)
        set += 1
        checkCondition(!set.add(1))
        set += 1
        checkEquals(1, set.size)
        checkEquals(1, set.first())
    }

    private fun addAllArray() {
        val set = mutableIntSetOf(1)
        checkCondition(!set.addAll(intArrayOf(1)))
        checkEquals(1, set.size)
        checkCondition(set.addAll(intArrayOf(1, 2)))
        checkEquals(2, set.size)
        checkCondition(2 in set)
    }

    private fun addAllIntSet() {
        val set = mutableIntSetOf(1)
        checkCondition(!set.addAll(mutableIntSetOf(1)))
        checkEquals(1, set.size)
        checkCondition(set.addAll(mutableIntSetOf(1, 2)))
        checkEquals(2, set.size)
        checkCondition(2 in set)
    }

    private fun remove() {
        val set = MutableIntSet()
        checkCondition(!set.remove(1))
        set += 1
        checkCondition(set.remove(1))
        checkEquals(0, set.size)
        set += 1
        set -= 1
        checkEquals(0, set.size)
    }

    private fun removeAllArray() {
        val set = mutableIntSetOf(1, 2)
        checkCondition(!set.removeAll(intArrayOf(3, 5)))
        checkEquals(2, set.size)
        checkCondition(set.removeAll(intArrayOf(3, 1, 5)))
        checkEquals(1, set.size)
        checkCondition((1 in set).not())
    }

    private fun removeAllIntSet() {
        val set = mutableIntSetOf(1, 2)
        checkCondition(!set.removeAll(mutableIntSetOf(3, 5)))
        checkEquals(2, set.size)
        checkCondition(set.removeAll(mutableIntSetOf(3, 1, 5)))
        checkEquals(1, set.size)
        checkCondition((1 in set).not())
    }

    private fun clear() {
        val set = MutableIntSet()
        for (i in 0 until 32) {
            set += i
        }
        val capacity = set.capacity
        set.clear()
        checkEquals(0, set.size)
        checkEquals(capacity, set.capacity)
    }

    private fun forEach() {
        for (i in 0..48) {
            val set = MutableIntSet()
            for (j in 0 until i) {
                set += j
            }
            val elements = IntArray(i)
            var index = 0
            set.forEach { element -> elements[index++] = element }
            elements.sort()
            index = 0
            elements.forEach { element ->
                checkEquals(element, index)
                index++
            }
        }
    }

    private fun insertManyEntries() {
        val set = MutableIntSet()
        for (i in 0 until 1700) {
            set += i
        }
        checkEquals(1700, set.size)
    }

    private fun string() {
        val set = MutableIntSet()
        checkEquals("[]", set.toString())
        set += 1
        set += 5
        val s = set.toString()
        checkCondition(s == "[1, 5]" || s == "[5, 1]")
    }
}
