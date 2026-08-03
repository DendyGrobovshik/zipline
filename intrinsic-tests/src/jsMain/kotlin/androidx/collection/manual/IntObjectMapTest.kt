package androidx.collection.manual

import androidx.collection.MutableIntObjectMap
import androidx.collection.mutableIntObjectMapOf
import androidx.collection.emptyIntObjectMap
import androidx.collection.intObjectMapOf

class IntObjectMapTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        "intObjectMap" to { intObjectMap() },
        "testEmptyIntObjectMap" to { testEmptyIntObjectMap() },
        "intObjectMapFunction" to { intObjectMapFunction() },
        "zeroCapacityHashMap" to { zeroCapacityHashMap() },
        "intObjectMapWithCapacity" to { intObjectMapWithCapacity() },
        "intObjectMapInitFunction" to { intObjectMapInitFunction() },
        "getOrDefault" to { getOrDefault() },
        "put" to { put() },
        "remove" to { remove() },
        "clear" to { clear() },
        "containsKey" to { containsKey() },
        "containsValue" to { containsValue() },
        "size" to { size() }
    )

    private fun intObjectMap() {
        val map = MutableIntObjectMap<String>()
        checkEquals(7, map.capacity)
        checkEquals(0, map.size)
    }

    private fun testEmptyIntObjectMap() {
        val map = emptyIntObjectMap<String>()
        checkEquals(0, map.capacity)
        checkEquals(0, map.size)
        checkCondition(map === emptyIntObjectMap<String>())
    }

    private fun intObjectMapFunction() {
        val map = mutableIntObjectMapOf<String>()
        checkEquals(7, map.capacity)
        checkEquals(0, map.size)
    }

    private fun zeroCapacityHashMap() {
        val map = MutableIntObjectMap<String>(0)
        checkEquals(0, map.capacity)
        checkEquals(0, map.size)
    }

    private fun intObjectMapWithCapacity() {
        val map = MutableIntObjectMap<String>(1800)
        checkEquals(4095, map.capacity)
        checkEquals(0, map.size)
    }

    private fun intObjectMapInitFunction() {
        val map1 = intObjectMapOf(1, "World")
        checkEquals(1, map1.size)
        checkEquals("World", map1[1])

        val map2 = intObjectMapOf(1, "World", 2, "Monde")
        checkEquals(2, map2.size)
        checkEquals("World", map2[1])
        checkEquals("Monde", map2[2])
    }

    private fun getOrDefault() {
        val map = intObjectMapOf(1, "Hello")
        checkEquals("Hello", map.getOrDefault(1, "Default"))
        checkEquals("Default", map.getOrDefault(2, "Default"))
    }

    private fun put() {
        val map = MutableIntObjectMap<String>()
        map.put(1, "One")
        checkEquals("One", map[1])
        checkEquals(1, map.size)
        map.put(1, "Uno")
        checkEquals("Uno", map[1])
        checkEquals(1, map.size)
    }

    private fun remove() {
        val map = mutableIntObjectMapOf(1, "One", 2, "Two")
        map.remove(1)
        checkEquals(1, map.size)
        checkCondition(map.containsKey(2))
        checkCondition(!map.containsKey(1))
    }

    private fun clear() {
        val map = mutableIntObjectMapOf(1, "One", 2, "Two")
        map.clear()
        checkEquals(0, map.size)
    }

    private fun containsKey() {
        val map = intObjectMapOf(1, "One")
        checkCondition(map.containsKey(1))
        checkCondition(!map.containsKey(2))
    }

    private fun containsValue() {
        val map = intObjectMapOf(1, "One", 2, "Two")
        checkCondition(map.containsValue("One"))
        checkCondition(!map.containsValue("Three"))
    }

    private fun size() {
        val map = MutableIntObjectMap<String>()
        checkEquals(0, map.size)
        map.put(1, "One")
        checkEquals(1, map.size)
        map.put(2, "Two")
        checkEquals(2, map.size)
        map.remove(1)
        checkEquals(1, map.size)
    }
}
