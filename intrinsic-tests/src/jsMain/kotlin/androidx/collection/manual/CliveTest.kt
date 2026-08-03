package androidx.collection.manual

import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.MutableIntSet
import androidx.collection.MutableIntObjectMap

class CliveTest : Tests { // Clive = Class without equals

    // Plain class WITHOUT equals() method - uses reference equality
    class Box<T>(val value: T)

    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        "scatterMapWithObjectWithoutEquals" to { scatterMapWithObjectWithoutEquals() },
        "scatterSetWithObjectWithoutEquals" to { scatterSetWithObjectWithoutEquals() },
        "intSetBasic" to { intSetBasic() },
        "intObjectMapBasic" to { intObjectMapBasic() }
    )

    private fun scatterMapWithObjectWithoutEquals() {
        val map = MutableScatterMap<Box<Int>, String>()

        val key1 = Box(1)
        val key2 = Box(2)
        val key3 = Box(1) // Same value as key1, but different reference

        map[key1] = "one"
        check(map.size == 1) { "size should be 1" }
        check(map[key1] == "one") { "map[key1] should be 'one'" }

        // key3 is a different object with same value - should NOT be found
        // because reference equality is used when there's no equals()
        check(map[key3] == null) { "Object without equals should use reference equality, but found: ${map[key3]}" }

        // key2 is a different reference with different value
        check(map[key2] == null) { "map[key2] should be null" }

        // Add key3 explicitly
        map[key3] = "one again"
        check(map.size == 2) { "key3 is different from key1, so should be added" }

        // Original key1 should still work
        check(map[key1] == "one") { "map[key1] should still be 'one'" }

        // key3 should now be findable
        check(map[key3] == "one again") { "map[key3] should be 'one again'" }
    }

    private fun scatterSetWithObjectWithoutEquals() {
        val set = MutableScatterSet<Box<Int>>()

        val key1 = Box(1)
        val key2 = Box(2)
        val key3 = Box(1) // Same value as key1, but different reference

        set.add(key1)
        check(set.size == 1) { "size should be 1" }
        check(key1 in set) { "key1 should be in set" }

        // key3 is a different object with same value - should NOT be found
        check(key3 !in set) { "Object without equals should use reference equality" }

        // key2 is a different reference
        check(key2 !in set) { "key2 should not be in set" }

        // Add key3 explicitly
        set.add(key3)
        check(set.size == 2) { "key3 is different from key1, so should be added" }

        // Original key1 should still work
        check(key1 in set) { "key1 should still be in set" }
        check(key3 in set) { "key3 should be in set" }
    }

    private fun intSetBasic() {
        val set = MutableIntSet()
        set.add(1)
        set.add(2)
        set.add(3)

        check(set.size == 3) { "size should be 3" }
        check(set.contains(1)) { "should contain 1" }
        check(set.contains(2)) { "should contain 2" }
        check(set.contains(3)) { "should contain 3" }
        check(!set.contains(4)) { "should not contain 4" }

        set.remove(2)
        check(set.size == 2) { "size should be 2 after remove" }
        check(!set.contains(2)) { "should not contain 2 after remove" }
        check(set.contains(1)) { "should still contain 1" }
        check(set.contains(3)) { "should still contain 3" }
    }

    private fun intObjectMapBasic() {
        val map = MutableIntObjectMap<String>()

        map.put(1, "one")
        map.put(2, "two")
        map.put(3, "three")

        check(map.size == 3) { "size should be 3" }
        check(map[1] == "one") { "map[1] should be 'one'" }
        check(map[2] == "two") { "map[2] should be 'two'" }
        check(map[3] == "three") { "map[3] should be 'three'" }
        check(map[4] == null) { "map[4] should be null" }

        map.remove(2)
        check(map.size == 2) { "size should be 2 after remove" }
        check(map[2] == null) { "map[2] should be null after remove" }
        check(map[1] == "one") { "map[1] should still be 'one'" }
        check(map[3] == "three") { "map[3] should still be 'three'" }
    }
}