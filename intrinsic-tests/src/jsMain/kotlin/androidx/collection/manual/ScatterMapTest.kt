package androidx.collection.manual

import androidx.collection.MutableScatterMap
import androidx.collection.mutableScatterMapOf
import androidx.collection.emptyScatterMap
import androidx.collection.scatterSetOf
import androidx.collection.objectListOf

class ScatterMapTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        // Broken in upstream androidx.collection (disabled):
        // "scatterMap" to { scatterMap() },
        // "scatterMapFunction" to { scatterMapFunction() },
        // "scatterMapWithCapacity" to { scatterMapWithCapacity() },
        // "addToSmallMap" to { addToSmallMap() },
        // "removeDoesNotCauseGrowthOnInsert" to { removeDoesNotCauseGrowthOnInsert() },
        // "joinToString" to { joinToString() },
        // "asMutableMapValuesIterator" to { asMutableMapValuesIterator() },
        // "asMutableMapKeysIterator" to { asMutableMapKeysIterator() },
        // "asMutableMapEntriesIterator" to { asMutableMapEntriesIterator() },
        // "trim" to { trim() },
        // "insertManyRemoveMany" to { insertManyRemoveMany() },
        "emptyScatterMap" to { emptyScatterMap() },
        "zeroCapacityMap" to { zeroCapacityMap() },
        "scatterMapPairsFunction" to { scatterMapPairsFunction() },
        "addToMap" to { addToMap() },
        "insertIndex0" to { insertIndex0() },
        "addToSizedMap" to { addToSizedMap() },
        "addToZeroCapacityMap" to { addToZeroCapacityMap() },
        "replaceExistingKey" to { replaceExistingKey() },
        "put" to { put() },
        "putAllMap" to { putAllMap() },
        "putAllArray" to { putAllArray() },
        "putAllIterable" to { putAllIterable() },
        "putAllSequence" to { putAllSequence() },
        "plus" to { plus() },
        "plusMap" to { plusMap() },
        "plusArray" to { plusArray() },
        "plusIterable" to { plusIterable() },
        "plusSequence" to { plusSequence() },
        "nullKey" to { nullKey() },
        "nullValue" to { nullValue() },
        "findNonExistingKey" to { findNonExistingKey() },
        "getOrDefault" to { getOrDefault() },
        "getOrElse" to { getOrElse() },
        "getOrPut" to { getOrPut() },
        "compute" to { compute() },
        "remove" to { remove() },
        "removeThenAdd" to { removeThenAdd() },
        "removeIf" to { removeIf() },
        "minus" to { minus() },
        "minusArray" to { minusArray() },
        "minusIterable" to { minusIterable() },
        "minusSequence" to { minusSequence() },
        "minusScatterSet" to { minusScatterSet() },
        "minusObjectList" to { minusObjectList() },
        "conditionalRemove" to { conditionalRemove() },
        "insertManyEntries" to { insertManyEntries() },
        "forEach" to { forEach() },
        "forEachKey" to { forEachKey() },
        "forEachValue" to { forEachValue() },
        "clear" to { clear() },
        "string" to { string() },
        "equals" to { equals() },
        "containsKey" to { containsKey() },
        "contains" to { contains() },
        "containsValue" to { containsValue() },
        "empty" to { empty() },
        "count" to { count() },
        "any" to { any() },
        "all" to { all() },
        "asMapSize" to { asMapSize() },
        "asMapIsEmpty" to { asMapIsEmpty() },
        "asMapContainsValue" to { asMapContainsValue() },
        "asMapContainsKey" to { asMapContainsKey() },
        "asMapGet" to { asMapGet() },
        "asMapValues" to { asMapValues() },
        "asMapKeys" to { asMapKeys() },
        "asMapEntries" to { asMapEntries() },
        "asMapToList" to { asMapToList() },
        "asMutableMapClear" to { asMutableMapClear() },
        "asMutableMapPut" to { asMutableMapPut() },
        "asMutableMapRemove" to { asMutableMapRemove() },
        "asMutableMapPutAll" to { asMutableMapPutAll() },
        "asMutableMapValuesContains" to { asMutableMapValuesContains() },
        "asMutableMapValuesAdd" to { asMutableMapValuesAdd() },
        "asMutableMapValuesRemoveRetain" to { asMutableMapValuesRemoveRetain() },
        "asMutableMapKeysContains" to { asMutableMapKeysContains() },
        "asMutableMapKeysAdd" to { asMutableMapKeysAdd() },
        "asMutableMapKeysRemoveRetain" to { asMutableMapKeysRemoveRetain() },
        "asMutableMapEntriesContains" to { asMutableMapEntriesContains() },
        "asMutableMapEntriesAdd" to { asMutableMapEntriesAdd() },
        "asMutableMapEntriesRemoveRetain" to { asMutableMapEntriesRemoveRetain() },
        "asMapEquals" to { asMapEquals() },
        "asMapToString" to { asMapToString() },
        "insertOneRemoveOne" to { insertOneRemoveOne() },
    )

    private fun scatterMap() {
        val map = MutableScatterMap<String, String>()
        checkEquals(7, map.capacity)
        checkEquals(0, map.size)
    }

    private fun emptyScatterMap() {
        val map = emptyScatterMap<String, String>()
        checkEquals(0, map.capacity)
        checkEquals(0, map.size)

        checkCondition(map === emptyScatterMap<String, String>())
    }

    private fun scatterMapFunction() {
        val map = mutableScatterMapOf<String, String>()
        checkEquals(7, map.capacity)
        checkEquals(0, map.size)
    }

    private fun zeroCapacityMap() {
        val map = MutableScatterMap<String, String>(0)
        checkEquals(0, map.capacity)
        checkEquals(0, map.size)
    }

    private fun scatterMapWithCapacity() {
        val map = MutableScatterMap<String, String>(1800)
        checkEquals(4095, map.capacity)
        checkEquals(0, map.size)
    }

    private fun scatterMapPairsFunction() {
        val map = mutableScatterMapOf("Hello" to "World", "Bonjour" to "Monde")
        checkEquals(2, map.size)
        checkEquals("World", map["Hello"])
        checkEquals("Monde", map["Bonjour"])
    }

    private fun addToMap() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"

        checkEquals(1, map.size)
        checkEquals("World", map["Hello"])
    }

    private fun insertIndex0() {
        val map = MutableScatterMap<Float, Long>()
        map.put(1f, 100L)
        checkEquals(100L, map[1f])
    }

    private fun addToSizedMap() {
        val map = MutableScatterMap<String, String>(12)
        map["Hello"] = "World"

        checkEquals(1, map.size)
        checkEquals("World", map["Hello"])
    }

    private fun addToSmallMap() {
        val map = MutableScatterMap<String, String>(2)
        map["Hello"] = "World"

        checkEquals(1, map.size)
        checkEquals(7, map.capacity)
        checkEquals("World", map["Hello"])
    }

    private fun addToZeroCapacityMap() {
        val map = MutableScatterMap<String, String>(0)
        map["Hello"] = "World"

        checkEquals(1, map.size)
        checkEquals("World", map["Hello"])
    }

    private fun replaceExistingKey() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Hello"] = "Monde"

        checkEquals(1, map.size)
        checkEquals("Monde", map["Hello"])
    }

    private fun put() {
        val map = MutableScatterMap<String, String?>()

        checkEquals(null, map.put("Hello", "World"))
        checkEquals("World", map.put("Hello", "Monde"))
        checkEquals(null, map.put("Bonjour", null))
        checkEquals(null, map.put("Bonjour", "Monde"))
    }

    private fun putAllMap() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        map.putAll(mapOf("Hallo" to "Welt", "Hola" to "Mundo"))

        checkEquals(5, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun putAllArray() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        map.putAll(arrayOf("Hallo" to "Welt", "Hola" to "Mundo"))

        checkEquals(5, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun putAllIterable() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        map.putAll(listOf("Hallo" to "Welt", "Hola" to "Mundo"))

        checkEquals(5, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun putAllSequence() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        map.putAll(listOf("Hallo" to "Welt", "Hola" to "Mundo").asSequence())

        checkEquals(5, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun plus() {
        val map = MutableScatterMap<String, String>()
        map += "Hello" to "World"

        checkEquals(1, map.size)
        checkEquals("World", map["Hello"])
    }

    private fun plusMap() {
        val map = MutableScatterMap<String, String>()
        map += mapOf("Hallo" to "Welt", "Hola" to "Mundo")

        checkEquals(2, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun plusArray() {
        val map = MutableScatterMap<String, String>()
        map += arrayOf("Hallo" to "Welt", "Hola" to "Mundo")

        checkEquals(2, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun plusIterable() {
        val map = MutableScatterMap<String, String>()
        map += listOf("Hallo" to "Welt", "Hola" to "Mundo")

        checkEquals(2, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun plusSequence() {
        val map = MutableScatterMap<String, String>()
        map += listOf("Hallo" to "Welt", "Hola" to "Mundo").asSequence()

        checkEquals(2, map.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun nullKey() {
        val map = MutableScatterMap<String?, String>()
        map[null] = "World"

        checkEquals(1, map.size)
        checkEquals("World", map[null])
    }

    private fun nullValue() {
        val map = MutableScatterMap<String, String?>()
        map["Hello"] = null

        checkEquals(1, map.size)
        checkEquals(null, map["Hello"])
    }

    private fun findNonExistingKey() {
        val map = MutableScatterMap<String, String?>()
        map["Hello"] = "World"

        checkEquals(null, map["Bonjour"])
    }

    private fun getOrDefault() {
        val map = MutableScatterMap<String, String?>()
        map["Hello"] = "World"

        checkEquals("Monde", map.getOrDefault("Bonjour", "Monde"))
    }

    private fun getOrElse() {
        val map = MutableScatterMap<String, String?>()
        map["Hello"] = "World"
        map["Bonjour"] = null

        checkEquals("Monde", map.getOrElse("Bonjour") { "Monde" })
        checkEquals("Welt", map.getOrElse("Hallo") { "Welt" })
    }

    private fun getOrPut() {
        val map = MutableScatterMap<String, String?>()
        map["Hello"] = "World"

        var counter = 0
        map.getOrPut("Hello") {
            counter++
            "Monde"
        }
        checkEquals("World", map["Hello"])
        checkEquals(0, counter)

        map.getOrPut("Bonjour") {
            counter++
            "Monde"
        }
        checkEquals("Monde", map["Bonjour"])
        checkEquals(1, counter)

        map.getOrPut("Bonjour") {
            counter++
            "Welt"
        }
        checkEquals("Monde", map["Bonjour"])
        checkEquals(1, counter)

        map.getOrPut("Hallo") {
            counter++
            null as String?
        }
        checkEquals(null, map["Hallo"])
        checkEquals(2, counter)

        map.getOrPut("Hallo") {
            counter++
            "Welt"
        }
        checkEquals("Welt", map["Hallo"])
        checkEquals(3, counter)
    }

    private fun compute() {
        val map = MutableScatterMap<String, String?>()
        map["Hello"] = "World"

        var computed = map.compute("Hello") { _, _ -> "New World" }
        checkEquals("New World", map["Hello"])
        checkEquals("New World", computed)

        computed = map.compute("Bonjour") { _, _ -> "Monde" }
        checkEquals("Monde", map["Bonjour"])
        checkEquals("Monde", computed)

        map.compute("Bonjour") { _, v -> v ?: "Welt" }
        checkEquals("Monde", map["Bonjour"])

        map.compute("Hallo") { _, _ -> null }
        checkEquals(null, map["Hallo"])

        map.compute("Hallo") { _, v -> v ?: "Welt" }
        checkEquals("Welt", map["Hallo"])
    }

    private fun remove() {
        val map = MutableScatterMap<String?, String?>()
        checkEquals(null, map.remove("Hello"))

        map["Hello"] = "World"
        checkEquals("World", map.remove("Hello"))
        checkEquals(0, map.size)

        map[null] = "World"
        checkEquals("World", map.remove(null))
        checkEquals(0, map.size)

        map["Hello"] = null
        checkEquals(null, map.remove("Hello"))
        checkEquals(0, map.size)
    }

    private fun removeThenAdd() {
        val map = MutableScatterMap<String, String>(6)
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        map["Konnichiwa"] = "Sekai"
        map["Ciao"] = "Mondo"
        map["Annyeong"] = "Sesang"

        map.remove("Hello")
        map.remove("Bonjour")
        map.remove("Hallo")
        map.remove("Konnichiwa")
        map.remove("Ciao")
        map.remove("Annyeong")

        checkEquals(0, map.size)

        val capacity = map.capacity

        map["Hello"] = "World"

        checkEquals(1, map.size)
        checkEquals(capacity, map.capacity)
    }

    private fun removeIf() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        map["Konnichiwa"] = "Sekai"
        map["Ciao"] = "Mondo"
        map["Annyeong"] = "Sesang"

        map.removeIf { key, value -> key.startsWith('H') || value.startsWith('S') }

        checkEquals(2, map.size)
        checkEquals("Monde", map["Bonjour"])
        checkEquals("Mondo", map["Ciao"])
    }

    private fun removeDoesNotCauseGrowthOnInsert() {
        val map = MutableScatterMap<String, String>(10)
        checkEquals(15, map.capacity)

        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        map["Konnichiwa"] = "Sekai"
        map["Ciao"] = "Mondo"
        map["Annyeong"] = "Sesang"

        for (i in 0..7) {
            map[i.toString()] = i.toString()
        }

        for (i in 0..5) {
            map.remove(i.toString())
        }

        map["Foo"] = "Bar"
        checkEquals(15, map.capacity)

        checkEquals("Bar", map["Foo"])
    }

    private fun minus() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        map -= "Hello"

        checkEquals(2, map.size)
        checkEquals(null, map["Hello"])
    }

    private fun minusArray() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        map -= arrayOf("Hallo", "Bonjour")

        checkEquals(1, map.size)
        checkEquals(null, map["Hallo"])
        checkEquals(null, map["Bonjour"])
    }

    private fun minusIterable() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        map -= listOf("Hallo", "Bonjour")

        checkEquals(1, map.size)
        checkEquals(null, map["Hallo"])
        checkEquals(null, map["Bonjour"])
    }

    private fun minusSequence() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        map -= listOf("Hallo", "Bonjour").asSequence()

        checkEquals(1, map.size)
        checkEquals(null, map["Hallo"])
        checkEquals(null, map["Bonjour"])
    }

    private fun minusScatterSet() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        map -= scatterSetOf("Hallo", "Bonjour")

        checkEquals(1, map.size)
        checkEquals(null, map["Hallo"])
        checkEquals(null, map["Bonjour"])
    }

    private fun minusObjectList() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        map -= objectListOf("Hallo", "Bonjour")

        checkEquals(1, map.size)
        checkEquals(null, map["Hallo"])
        checkEquals(null, map["Bonjour"])
    }

    private fun conditionalRemove() {
        val map = MutableScatterMap<String?, String?>()
        checkCondition(!map.remove("Hello", "World"))

        map["Hello"] = "World"
        checkCondition(map.remove("Hello", "World"))
        checkEquals(0, map.size)
    }

    private fun insertManyEntries() {
        val map = MutableScatterMap<String, String>()

        for (i in 0 until 1700) {
            val s = i.toString()
            map[s] = s
        }

        checkEquals(1700, map.size)
    }

    private fun forEach() {
        for (i in 0..48) {
            val map = MutableScatterMap<String, String>()

            for (j in 0 until i) {
                val s = j.toString()
                map[s] = s
            }

            var counter = 0
            map.forEach { key, value ->
                checkEquals(key, value)
                counter++
            }

            checkEquals(i, counter)
        }
    }

    private fun forEachKey() {
        for (i in 0..48) {
            val map = MutableScatterMap<String, String>()

            for (j in 0 until i) {
                val s = j.toString()
                map[s] = s
            }

            var counter = 0
            map.forEachKey { key ->
                checkCondition(key.toIntOrNull() != null)
                counter++
            }

            checkEquals(i, counter)
        }
    }

    private fun forEachValue() {
        for (i in 0..48) {
            val map = MutableScatterMap<String, String>()

            for (j in 0 until i) {
                val s = j.toString()
                map[s] = s
            }

            var counter = 0
            map.forEachValue { value ->
                checkCondition(value.toIntOrNull() != null)
                counter++
            }

            checkEquals(i, counter)
        }
    }

    private fun clear() {
        val map = MutableScatterMap<String, String>()

        for (i in 0 until 32) {
            val s = i.toString()
            map[s] = s
        }

        val capacity = map.capacity
        map.clear()

        checkEquals(0, map.size)
        checkEquals(capacity, map.capacity)

        var count = 0
        map.forEach { _, _ -> count++ }
        checkEquals(0, count)
    }

    private fun string() {
        val map = MutableScatterMap<String?, String?>()
        checkEquals("{}", map.toString())

        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        val str = map.toString()
        checkCondition(str == "{Hello=World, Bonjour=Monde}" || str == "{Bonjour=Monde, Hello=World}")

        map.clear()
        map["Hello"] = null
        checkEquals("{Hello=null}", map.toString())

        map.clear()
        map[null] = "Monde"
        checkEquals("{null=Monde}", map.toString())

        val selfAsKeyMap = MutableScatterMap<Any, String>()
        selfAsKeyMap[selfAsKeyMap] = "Hello"
        checkEquals("{(this)=Hello}", selfAsKeyMap.toString())

        val selfAsValueMap = MutableScatterMap<String, Any>()
        selfAsValueMap["Hello"] = selfAsValueMap
        checkEquals("{Hello=(this)}", selfAsValueMap.toString())

        val map2 = MutableScatterMap<String?, String?>(2)
        map2["Hello"] = "World"
        map2["Bonjour"] = "Monde"
        val str2 = map2.toString()
        checkCondition(str2 == "{Hello=World, Bonjour=Monde}" || str2 == "{Bonjour=Monde, Hello=World}")
    }

    private fun joinToString() {
        val map = mutableScatterMapOf(1 to 1f, 2 to 2f, 3 to 3f, 4 to 4f, 5 to 5f)
        val order = IntArray(5)
        var index = 0
        map.forEach { key, _ -> order[index++] = key }
        checkEquals(
            "${order[0]}=${order[0].toFloat()}, ${order[1]}=${order[1].toFloat()}, " +
                "${order[2]}=${order[2].toFloat()}, ${order[3]}=${order[3].toFloat()}, " +
                "${order[4]}=${order[4].toFloat()}",
            map.joinToString()
        )
        checkEquals(
            "x${order[0]}=${order[0].toFloat()}, ${order[1]}=${order[1].toFloat()}, " +
                "${order[2]}=${order[2].toFloat()}...",
            map.joinToString(prefix = "x", postfix = "y", limit = 3)
        )
        checkEquals(
            ">${order[0]}=${order[0].toFloat()}-${order[1]}=${order[1].toFloat()}-" +
                "${order[2]}=${order[2].toFloat()}-${order[3]}=${order[3].toFloat()}-" +
                "${order[4]}=${order[4].toFloat()}<",
            map.joinToString(separator = "-", prefix = ">", postfix = "<")
        )
        val names = arrayOf("one", "two", "three", "four", "five")
        checkEquals(
            "${names[order[0]]}, ${names[order[1]]}, ${names[order[2]]}...",
            map.joinToString(limit = 3) { key, _ -> names[key] }
        )
    }

    private fun equals() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        checkCondition(!map.equals(null))
        checkEquals(map, map)

        val map2 = MutableScatterMap<String?, String?>()
        map2["Bonjour"] = null
        map2[null] = "Monde"

        checkCondition(map != map2)

        map2["Hello"] = "World"
        checkEquals(map, map2)
    }

    private fun containsKey() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        checkCondition(map.containsKey("Hello"))
        checkCondition(map.containsKey(null))
        checkCondition(!map.containsKey("World"))
    }

    private fun contains() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        checkCondition("Hello" in map)
        checkCondition(null in map)
        checkCondition(!("World" in map))
    }

    private fun containsValue() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        checkCondition(map.containsValue("World"))
        checkCondition(map.containsValue(null))
        checkCondition(!map.containsValue("Hello"))
    }

    private fun empty() {
        val map = MutableScatterMap<String?, String?>()
        checkCondition(map.isEmpty())
        checkCondition(!map.isNotEmpty())
        checkCondition(map.none())
        checkCondition(!map.any())

        map["Hello"] = "World"

        checkCondition(!map.isEmpty())
        checkCondition(map.isNotEmpty())
        checkCondition(map.any())
        checkCondition(!map.none())
    }

    private fun count() {
        val map = MutableScatterMap<String, String>()
        checkEquals(0, map.count())

        map["Hello"] = "World"
        checkEquals(1, map.count())

        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        map["Konnichiwa"] = "Sekai"
        map["Ciao"] = "Mondo"
        map["Annyeong"] = "Sesang"

        checkEquals(2, map.count { key, _ -> key.startsWith("H") })
        checkEquals(0, map.count { key, _ -> key.startsWith("W") })
    }

    private fun any() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        map["Konnichiwa"] = "Sekai"
        map["Ciao"] = "Mondo"
        map["Annyeong"] = "Sesang"

        checkCondition(map.any { key, _ -> key.startsWith("K") })
        checkCondition(!map.any { key, _ -> key.startsWith("W") })
    }

    private fun all() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        map["Konnichiwa"] = "Sekai"
        map["Ciao"] = "Mondo"
        map["Annyeong"] = "Sesang"

        checkCondition(map.any { key, value -> key.length >= 5 && value.length >= 4 })
        checkCondition(!map.all { key, _ -> key.startsWith("W") })
    }

    private fun asMapSize() {
        val map = MutableScatterMap<String, String>()
        val asMap = map.asMap()
        checkEquals(0, asMap.size)

        map["Hello"] = "World"
        checkEquals(1, asMap.size)
    }

    private fun asMapIsEmpty() {
        val map = MutableScatterMap<String, String>()
        val asMap = map.asMap()
        checkCondition(asMap.isEmpty())

        map["Hello"] = "World"
        checkCondition(!asMap.isEmpty())
    }

    private fun asMapContainsValue() {
        val map = MutableScatterMap<String, String?>()
        map["Hello"] = "World"
        map["Bonjour"] = null

        val asMap = map.asMap()
        checkCondition(asMap.containsValue("World"))
        checkCondition(asMap.containsValue(null))
        checkCondition(!asMap.containsValue("Monde"))
    }

    private fun asMapContainsKey() {
        val map = MutableScatterMap<String?, String>()
        map["Hello"] = "World"
        map[null] = "Monde"

        val asMap = map.asMap()
        checkCondition(asMap.containsKey("Hello"))
        checkCondition(asMap.containsKey(null))
        checkCondition(!asMap.containsKey("Bonjour"))
    }

    private fun asMapGet() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        val asMap = map.asMap()
        checkEquals("World", asMap["Hello"])
        checkEquals("Monde", asMap[null])
        checkEquals(null, asMap["Bonjour"])
        checkEquals(null, asMap["Hallo"])
    }

    private fun asMapValues() {
        val map = MutableScatterMap<String?, String?>()
        val values = map.asMap().values
        checkCondition(values.isEmpty())
        checkEquals(0, values.size)

        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null
        map["Hello2"] = "World"

        checkEquals(map.size, values.size)
        for (value in values) {
            checkCondition(map.containsValue(value))
        }

        map.forEachValue { value -> checkCondition(values.contains(value)) }
    }

    private fun asMapKeys() {
        val map = MutableScatterMap<String?, String?>()
        val keys = map.asMap().keys
        checkCondition(keys.isEmpty())
        checkEquals(0, keys.size)

        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        checkEquals(map.size, keys.size)
        for (key in keys) {
            checkCondition(map.containsKey(key))
        }

        map.forEachKey { key -> checkCondition(keys.contains(key)) }
    }

    private fun asMapEntries() {
        val map = MutableScatterMap<String?, String?>()
        val entries = map.asMap().entries
        checkCondition(entries.isEmpty())
        checkEquals(0, entries.size)

        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        checkEquals(map.size, entries.size)
        for (entry in entries) {
            checkEquals(map[entry.key], entry.value)
        }
    }

    private fun asMapToList() {
        val map = mutableScatterMapOf(0 to 0, 1 to -1)

        val list = map.asMap().toList()

        checkEquals(map.size, list.size)
        checkCondition(list.contains(0 to 0))
        checkCondition(list.contains(1 to -1))
    }

    private fun asMutableMapClear() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        val mutableMap = map.asMutableMap()
        mutableMap.clear()

        checkEquals(0, mutableMap.size)
        checkEquals(map.size, map.size)
        checkCondition(map.isEmpty())
        checkCondition(mutableMap.isEmpty())
    }

    private fun asMutableMapPut() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        val mutableMap = map.asMutableMap()
        checkEquals(map.size, mutableMap.size)
        checkEquals(3, mutableMap.size)

        checkEquals(null, mutableMap.put("Hallo", "Welt"))
        checkEquals(map.size, mutableMap.size)
        checkEquals(4, mutableMap.size)

        checkEquals(null, mutableMap.put("Bonjour", "Monde"))
        checkEquals(map.size, mutableMap.size)
        checkEquals(4, mutableMap.size)
    }

    private fun asMutableMapRemove() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        val mutableMap = map.asMutableMap()
        checkEquals(map.size, mutableMap.size)
        checkEquals(3, mutableMap.size)

        checkEquals(null, mutableMap.remove("Hallo"))
        checkEquals(map.size, mutableMap.size)
        checkEquals(3, mutableMap.size)

        checkEquals("World", mutableMap.remove("Hello"))
        checkEquals(map.size, mutableMap.size)
        checkEquals(2, mutableMap.size)
    }

    private fun asMutableMapPutAll() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        val mutableMap = map.asMutableMap()
        mutableMap.putAll(mapOf("Hallo" to "Welt", "Hola" to "Mundo"))

        checkEquals(map.size, mutableMap.size)
        checkEquals(5, mutableMap.size)
        checkEquals("Welt", map["Hallo"])
        checkEquals("Mundo", map["Hola"])
    }

    private fun asMutableMapValuesContains() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val values = mutableMap.values

        checkCondition(!values.contains("Mundo"))
        checkCondition(values.contains("Monde"))
        checkCondition(!values.containsAll(listOf("Monde", "Mundo")))
        checkCondition(values.containsAll(listOf("Monde", "Welt")))
    }

    private fun asMutableMapValuesAdd() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val values = mutableMap.values

        checkException<UnsupportedOperationException> {
            values.add("XXX")
        }

        checkException<UnsupportedOperationException> {
            values.addAll(listOf("XXX"))
        }
    }

    private fun asMutableMapValuesRemoveRetain() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val values = mutableMap.values

        checkCondition(values.remove("Monde"))
        checkEquals(map.size, mutableMap.size)
        checkEquals(map.size, values.size)
        checkEquals(2, map.size)

        checkCondition(!values.remove("Monde"))
        checkEquals(2, map.size)

        checkCondition(!values.removeAll(listOf("Mundo")))
        checkEquals(2, map.size)

        checkCondition(values.removeAll(listOf("World", "Welt")))
        checkEquals(0, map.size)

        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        checkEquals(3, map.size)

        checkCondition(!values.retainAll(listOf("World", "Monde", "Welt")))
        checkEquals(3, map.size)

        checkCondition(values.retainAll(listOf("World", "Welt")))
        checkEquals(2, map.size)

        values.clear()
        checkEquals(0, map.size)
    }

    private fun asMutableMapValuesIterator() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val values = mutableMap.values

        for (value in values) {
            checkCondition(map.containsValue(value))
        }

        val size = map.size
        checkEquals(3, map.size)
        val iterator = values.iterator()
        iterator.remove()
        checkEquals(size, map.size)

        checkCondition(iterator.hasNext())
        checkEquals("Monde", iterator.next())
        iterator.remove()
        checkEquals(2, map.size)

        checkCondition(!MutableScatterMap<String, String>().asMutableMap().values.iterator().hasNext())
    }

    private fun asMutableMapKeysContains() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val keys = mutableMap.keys

        checkCondition(!keys.contains("Hola"))
        checkCondition(keys.contains("Bonjour"))
        checkCondition(!keys.containsAll(listOf("Bonjour", "Hola")))
        checkCondition(keys.containsAll(listOf("Bonjour", "Hallo")))
    }

    private fun asMutableMapKeysAdd() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val keys = mutableMap.keys

        checkException<UnsupportedOperationException> {
            keys.add("XXX")
        }

        checkException<UnsupportedOperationException> {
            keys.addAll(listOf("XXX"))
        }
    }

    private fun asMutableMapKeysRemoveRetain() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val keys = mutableMap.keys

        checkCondition(keys.remove("Bonjour"))
        checkEquals(map.size, mutableMap.size)
        checkEquals(map.size, keys.size)
        checkEquals(2, map.size)

        checkCondition(!keys.remove("Bonjour"))
        checkEquals(2, map.size)

        checkCondition(!keys.removeAll(listOf("Hola")))
        checkEquals(2, map.size)

        checkCondition(keys.removeAll(listOf("Hello", "Hallo")))
        checkEquals(0, map.size)

        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        checkEquals(3, map.size)

        checkCondition(!keys.retainAll(listOf("Hello", "Bonjour", "Hallo")))
        checkEquals(3, map.size)

        checkCondition(keys.retainAll(listOf("Hello", "Hallo")))
        checkEquals(2, map.size)

        keys.clear()
        checkEquals(0, map.size)
    }

    private fun asMutableMapKeysIterator() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val keys = mutableMap.keys

        for (key in keys) {
            checkCondition(key in map)
        }

        val size = map.size
        checkEquals(3, map.size)
        val iterator = keys.iterator()
        iterator.remove()
        checkEquals(size, map.size)

        checkCondition(iterator.hasNext())
        checkEquals("Bonjour", iterator.next())
        iterator.remove()
        checkEquals(2, map.size)

        checkCondition(!MutableScatterMap<String, String>().asMutableMap().keys.iterator().hasNext())
    }

    private fun asMutableMapEntriesContains() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val entries = mutableMap.entries

        checkCondition(!entries.contains(TestEntry("Hola", "Mundo")))
        checkCondition(entries.contains(TestEntry("Bonjour", "Monde")))
        checkCondition(!entries.contains(TestEntry("Bonjour", "Le Monde")))
    }

    private fun asMutableMapEntriesAdd() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val entries = mutableMap.entries

        checkException<UnsupportedOperationException> {
            entries.add(TestEntry("X", "XX"))
        }

        checkException<UnsupportedOperationException> {
            entries.addAll(listOf(TestEntry("X", "XX")))
        }
    }

    private fun asMutableMapEntriesRemoveRetain() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val entries = mutableMap.entries

        checkCondition(entries.remove(TestEntry("Bonjour", "Monde")))
        checkEquals(map.size, mutableMap.size)
        checkEquals(map.size, entries.size)
        checkEquals(2, map.size)

        checkCondition(!entries.remove(TestEntry("Bonjour", "Monde")))
        checkEquals(2, map.size)

        checkCondition(!entries.remove(TestEntry("Hello", "The World")))
        checkEquals(2, map.size)

        checkCondition(!entries.removeAll(listOf(TestEntry("Hola", "Mundo"))))
        checkEquals(2, map.size)

        checkCondition(entries.removeAll(listOf(TestEntry("Hello", "World"), TestEntry("Hallo", "Welt"))))
        checkEquals(0, map.size)

        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"
        checkEquals(3, map.size)

        checkCondition(!entries.retainAll(listOf(TestEntry("Hello", "World"), TestEntry("Bonjour", "Monde"), TestEntry("Hallo", "Welt"))))
        checkEquals(3, map.size)

        checkCondition(entries.retainAll(listOf(TestEntry("Hello", "World"), TestEntry("Bonjour", "Le Monde"), TestEntry("Hallo", "Welt"))))
        checkEquals(2, map.size)

        checkCondition(entries.retainAll(listOf(TestEntry("Hello", "World"))))
        checkEquals(1, map.size)

        entries.clear()
        checkEquals(0, map.size)
    }

    private fun asMutableMapEntriesIterator() {
        val map = MutableScatterMap<String, String>()
        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        map["Hallo"] = "Welt"

        val mutableMap = map.asMutableMap()
        val entries = mutableMap.entries

        for (entry in entries) {
            checkEquals(entry.value, map[entry.key])
        }

        val size = map.size
        checkEquals(3, map.size)
        val iterator = entries.iterator()
        iterator.remove()
        checkEquals(size, map.size)

        checkCondition(iterator.hasNext())
        val next = iterator.next()
        checkEquals("Bonjour", next.key)
        checkEquals("Monde", next.value)
        iterator.remove()
        checkEquals(2, map.size)

        map.clear()
        map["Hello"] = "World"
        map["Hallo"] = "Welt"

        checkCondition(!map.any { _, value -> value == "XX" })
        for (entry in entries) {
            val oldValue = entry.setValue("XX")
            checkCondition(oldValue.startsWith("W"))
        }
        checkCondition(map.all { _, value -> value == "XX" })

        checkCondition(!MutableScatterMap<String, String>().asMutableMap().entries.iterator().hasNext())
    }

    private fun asMapEquals() {
        val map = MutableScatterMap<String?, String?>()
        map["Hello"] = "World"
        map[null] = "Monde"
        map["Bonjour"] = null

        checkCondition(!map.asMap().equals(null))
        checkCondition(!map.asMutableMap().equals(null))
        checkEquals(map.asMap(), map.asMap())
        checkEquals(map.asMutableMap(), map.asMutableMap())

        val map2 = MutableScatterMap<String?, String?>()
        map2["Bonjour"] = null
        map2[null] = "Monde"

        checkCondition(map.asMap() != map2.asMap())
        checkCondition(map.asMutableMap() != map2.asMutableMap())

        map2["Hello"] = "World"
        checkEquals(map.asMap(), map2.asMap())
        checkEquals(map.asMutableMap(), map2.asMutableMap())
    }

    private fun asMapToString() {
        val map = MutableScatterMap<String?, String?>()
        checkEquals("{}", map.asMap().toString())
        checkEquals("{}", map.asMutableMap().toString())

        map["Hello"] = "World"
        map["Bonjour"] = "Monde"
        val str1 = map.asMap().toString()
        checkCondition(str1 == "{Hello=World, Bonjour=Monde}" || str1 == "{Bonjour=Monde, Hello=World}")
        val str2 = map.asMutableMap().toString()
        checkCondition(str2 == "{Hello=World, Bonjour=Monde}" || str2 == "{Bonjour=Monde, Hello=World}")

        map.clear()
        map["Hello"] = null
        checkEquals("{Hello=null}", map.asMap().toString())
        checkEquals("{Hello=null}", map.asMutableMap().toString())

        map.clear()
        map[null] = "Monde"
        checkEquals("{null=Monde}", map.asMap().toString())
        checkEquals("{null=Monde}", map.asMutableMap().toString())

        val selfAsKeyMap = MutableScatterMap<Any, String>()
        selfAsKeyMap[selfAsKeyMap] = "Hello"
        checkEquals("{(this)=Hello}", selfAsKeyMap.asMap().toString())
        checkEquals("{(this)=Hello}", selfAsKeyMap.asMutableMap().toString())

        val selfAsValueMap = MutableScatterMap<String, Any>()
        selfAsValueMap["Hello"] = selfAsValueMap
        checkEquals("{Hello=(this)}", selfAsValueMap.asMap().toString())
        checkEquals("{Hello=(this)}", selfAsValueMap.asMutableMap().toString())

        val map2 = MutableScatterMap<String?, String?>(2)
        map2["Hello"] = "World"
        map2["Bonjour"] = "Monde"
        val str3 = map2.asMap().toString()
        checkCondition(str3 == "{Hello=World, Bonjour=Monde}" || str3 == "{Bonjour=Monde, Hello=World}")
        val str4 = map2.asMutableMap().toString()
        checkCondition(str4 == "{Hello=World, Bonjour=Monde}" || str4 == "{Bonjour=Monde, Hello=World}")
    }

    private fun trim() {
        val map = MutableScatterMap<String, String>()
        checkEquals(7, map.trim())

        map["Hello"] = "World"
        map["Hallo"] = "Welt"

        checkEquals(0, map.trim())

        for (i in 0 until 1700) {
            val s = i.toString()
            map[s] = s
        }

        checkEquals(2047, map.capacity)

        for (i in 0 until 1700) {
            if (i and 0x1 == 0x0) {
                val s = i.toString()
                map.remove(s)
            }
        }

        checkEquals(1024, map.trim())
        checkEquals(0, map.trim())
    }

    private fun insertOneRemoveOne() {
        val map = MutableScatterMap<Int, String>()

        for (i in 0..1000000) {
            map[i] = i.toString()
            map.remove(i)
            checkCondition(map.capacity < 16)
        }
    }

    private fun insertManyRemoveMany() {
        val map = MutableScatterMap<Int, String>()

        for (i in 0..100) {
            map[i] = i.toString()
        }

        for (i in 0..100) {
            if (i % 2 == 0) {
                map.remove(i)
            }
        }

        for (i in 0..100) {
            if (i % 2 == 0) {
                map[i] = i.toString()
            }
        }

        for (i in 0..100) {
            if (i % 2 != 0) {
                map.remove(i)
            }
        }

        for (i in 0..100) {
            if (i % 2 != 0) {
                map[i] = i.toString()
            }
        }

        checkEquals(127, map.capacity)
        for (i in 0..100) {
            checkCondition(map.contains(i))
        }
    }

    class TestEntry(override val key: String, override var value: String) : MutableMap.MutableEntry<String, String> {
        override fun setValue(newValue: String): String {
            val old = value
            value = newValue
            return old
        }
    }
}
