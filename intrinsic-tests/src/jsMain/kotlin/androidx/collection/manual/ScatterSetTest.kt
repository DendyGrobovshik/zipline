package androidx.collection.manual

import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.mutableScatterSetOf
import androidx.collection.emptyScatterSet
import androidx.collection.scatterSetOf
import androidx.collection.objectListOf

class ScatterSetTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        // Broken in upstream androidx.collection (disabled):
        // "emptyScatterSetConstructor" to { emptyScatterSetConstructor() },
        // "emptyScatterSetWithCapacity" to { emptyScatterSetWithCapacity() },
        // "removeDoesNotCauseGrowthOnInsert" to { removeDoesNotCauseGrowthOnInsert() },
        // "joinToString" to { joinToString() },
        // "hashCodeAddValues" to { hashCodeAddValues() },
        // "asSetHashCodeAddValues" to { asSetHashCodeAddValues() },
        // "trim" to { trim() },
        // "insertManyRemoveMany" to { insertManyRemoveMany() },
        "immutableEmptyScatterSet" to { immutableEmptyScatterSet() },
        "zeroCapacityScatterSet" to { zeroCapacityScatterSet() },
        "mutableScatterSetBuilder" to { mutableScatterSetBuilder() },
        "addToScatterSet" to { addToScatterSet() },
        "addToSizedScatterSet" to { addToSizedScatterSet() },
        "addExistingElement" to { addExistingElement() },
        "addAllArray" to { addAllArray() },
        "addAllIterable" to { addAllIterable() },
        "addAllSequence" to { addAllSequence() },
        "addAllScatterSet" to { addAllScatterSet() },
        "addAllObjectList" to { addAllObjectList() },
        "plusAssignArray" to { plusAssignArray() },
        "plusAssignIterable" to { plusAssignIterable() },
        "plusAssignSequence" to { plusAssignSequence() },
        "plusAssignScatterSet" to { plusAssignScatterSet() },
        "plusAssignObjectList" to { plusAssignObjectList() },
        "nullElement" to { nullElement() },
        "firstWithValue" to { firstWithValue() },
        "firstEmpty" to { firstEmpty() },
        "firstMatching" to { firstMatching() },
        "firstMatchingEmpty" to { firstMatchingEmpty() },
        "firstMatchingNoMatch" to { firstMatchingNoMatch() },
        "firstOrNull" to { firstOrNull() },
        "remove" to { remove() },
        "removeThenAdd" to { removeThenAdd() },
        "removeAllArray" to { removeAllArray() },
        "removeAllIterable" to { removeAllIterable() },
        "removeAllSequence" to { removeAllSequence() },
        "removeAllScatterSet" to { removeAllScatterSet() },
        "removeAllObjectList" to { removeAllObjectList() },
        "minusAssignArray" to { minusAssignArray() },
        "minusAssignIterable" to { minusAssignIterable() },
        "minusAssignSequence" to { minusAssignSequence() },
        "minusAssignScatterSet" to { minusAssignScatterSet() },
        "minusAssignObjectList" to { minusAssignObjectList() },
        "insertManyEntries" to { insertManyEntries() },
        "forEach" to { forEach() },
        "clear" to { clear() },
        "string" to { string() },
        "equals" to { equals() },
        "contains" to { contains() },
        "empty" to { empty() },
        "count" to { count() },
        "any" to { any() },
        "all" to { all() },
        "asSet" to { asSet() },
        "asMutableSet" to { asMutableSet() },
        "asSetEquals" to { asSetEquals() },
        "asSetString" to { asSetString() },
        "scatterSetOfEmpty" to { scatterSetOfEmpty() },
        "scatterSetOfOne" to { scatterSetOfOne() },
        "scatterSetOfTwo" to { scatterSetOfTwo() },
        "scatterSetOfThree" to { scatterSetOfThree() },
        "scatterSetOfFour" to { scatterSetOfFour() },
        "mutableScatterSetOfOne" to { mutableScatterSetOfOne() },
        "mutableScatterSetOfTwo" to { mutableScatterSetOfTwo() },
        "mutableScatterSetOfThree" to { mutableScatterSetOfThree() },
        "mutableScatterSetOfFour" to { mutableScatterSetOfFour() },
        "removeIf" to { removeIf() },
        "insertOneRemoveOne" to { insertOneRemoveOne() },
        "removeWhenIterating" to { removeWhenIterating() },
        "removeWhenForEach" to { removeWhenForEach() }
    )

    private fun emptyScatterSetConstructor() {
        val set = MutableScatterSet<String>()
        checkEquals(7, set.capacity)
        checkEquals(0, set.size)
    }

    private fun immutableEmptyScatterSet() {
        val set: ScatterSet<String> = emptyScatterSet()
        checkEquals(0, set.capacity)
        checkEquals(0, set.size)
    }

    private fun zeroCapacityScatterSet() {
        val set = MutableScatterSet<String>(0)
        checkEquals(0, set.capacity)
        checkEquals(0, set.size)
    }

    private fun emptyScatterSetWithCapacity() {
        val set = MutableScatterSet<String>(1800)
        checkEquals(4095, set.capacity)
        checkEquals(0, set.size)
    }

    private fun mutableScatterSetBuilder() {
        val empty = mutableScatterSetOf<String>()
        checkEquals(0, empty.size)

        val withElements = mutableScatterSetOf("Hello", "World")
        checkEquals(2, withElements.size)
        checkCondition("Hello" in withElements)
        checkCondition("World" in withElements)
    }

    private fun addToScatterSet() {
        val set = MutableScatterSet<String>()
        set += "Hello"
        checkCondition(set.add("World"))

        checkEquals(2, set.size)
        val elements = Array(2) { "" }
        var index = 0
        set.forEach { element -> elements[index++] = element }
        elements.sort()
        checkEquals("Hello", elements[0])
        checkEquals("World", elements[1])
    }

    private fun addToSizedScatterSet() {
        val set = MutableScatterSet<String>(12)
        set += "Hello"

        checkEquals(1, set.size)
        checkEquals("Hello", set.first())
    }

    private fun addExistingElement() {
        val set = MutableScatterSet<String>(12)
        set += "Hello"
        checkCondition(!set.add("Hello"))
        set += "Hello"

        checkEquals(1, set.size)
        checkEquals("Hello", set.first())
    }

    private fun addAllArray() {
        val set = mutableScatterSetOf("Hello")
        checkCondition(!set.addAll(arrayOf("Hello")))
        checkEquals(1, set.size)
        checkCondition(set.addAll(arrayOf("Hello", "World")))
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun addAllIterable() {
        val set = mutableScatterSetOf("Hello")
        checkCondition(!set.addAll(listOf("Hello")))
        checkEquals(1, set.size)
        checkCondition(set.addAll(listOf("Hello", "World")))
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun addAllSequence() {
        val set = mutableScatterSetOf("Hello")
        checkCondition(!set.addAll(listOf("Hello").asSequence()))
        checkEquals(1, set.size)
        checkCondition(set.addAll(listOf("Hello", "World").asSequence()))
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun addAllScatterSet() {
        val set = mutableScatterSetOf("Hello")
        checkCondition(!set.addAll(mutableScatterSetOf("Hello")))
        checkEquals(1, set.size)
        checkCondition(set.addAll(mutableScatterSetOf("Hello", "World")))
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun addAllObjectList() {
        val set = mutableScatterSetOf("Hello")
        checkCondition(!set.addAll(objectListOf("Hello")))
        checkEquals(1, set.size)
        checkCondition(set.addAll(objectListOf("Hello", "World")))
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun plusAssignArray() {
        val set = mutableScatterSetOf("Hello")
        set += arrayOf("Hello")
        checkEquals(1, set.size)
        set += arrayOf("Hello", "World")
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun plusAssignIterable() {
        val set = mutableScatterSetOf("Hello")
        set += listOf("Hello")
        checkEquals(1, set.size)
        set += listOf("Hello", "World")
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun plusAssignSequence() {
        val set = mutableScatterSetOf("Hello")
        set += listOf("Hello").asSequence()
        checkEquals(1, set.size)
        set += listOf("Hello", "World").asSequence()
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun plusAssignScatterSet() {
        val set = mutableScatterSetOf("Hello")
        set += mutableScatterSetOf("Hello")
        checkEquals(1, set.size)
        set += mutableScatterSetOf("Hello", "World")
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun plusAssignObjectList() {
        val set = mutableScatterSetOf("Hello")
        set += objectListOf("Hello")
        checkEquals(1, set.size)
        set += objectListOf("Hello", "World")
        checkEquals(2, set.size)
        checkCondition("World" in set)
    }

    private fun nullElement() {
        val set = MutableScatterSet<String?>()
        set += null

        checkEquals(1, set.size)
        checkEquals(null, set.first())
    }

    private fun firstWithValue() {
        val set = MutableScatterSet<String>()
        set += "Hello"
        set += "World"
        var element: String? = null
        var otherElement: String? = null
        set.forEach { if (element == null) element = it else otherElement = it }
        checkEquals(element, set.first())
        set -= element!!
        checkEquals(otherElement, set.first())
    }

    private fun firstEmpty() {
        checkException<NoSuchElementException> {
            val set = MutableScatterSet<String>()
            set.first()
        }
    }

    private fun firstMatching() {
        val set = MutableScatterSet<String>()
        set += "Hello"
        set += "World"
        checkEquals("Hello", set.first { it.contains('H') })
        checkEquals("World", set.first { it.contains('W') })
    }

    private fun firstMatchingEmpty() {
        checkException<NoSuchElementException> {
            val set = MutableScatterSet<String>()
            set.first { it.contains('H') }
        }
    }

    private fun firstMatchingNoMatch() {
        checkException<NoSuchElementException> {
            val set = MutableScatterSet<String>()
            set += "Hello"
            set += "World"
            set.first { it.startsWith("Q") }
        }
    }

    private fun firstOrNull() {
        val set = MutableScatterSet<String>()
        checkEquals(null, set.firstOrNull { it.startsWith('H') })
        set += "Hello"
        set += "World"
        var element: String? = null
        set.forEach { if (element == null) element = it }
        checkEquals(element, set.firstOrNull { it.contains('l') })
        checkEquals("Hello", set.firstOrNull { it.contains('H') })
        checkEquals("World", set.firstOrNull { it.contains('W') })
        checkEquals(null, set.firstOrNull { it.startsWith('Q') })
    }

    private fun remove() {
        val set = MutableScatterSet<String?>()
        checkCondition(!set.remove("Hello"))

        set += "Hello"
        checkCondition(set.remove("Hello"))
        checkEquals(0, set.size)

        set += "Hello"
        set -= "Hello"
        checkEquals(0, set.size)

        set += null
        checkCondition(set.remove(null))
        checkEquals(0, set.size)

        set += null
        set -= null
        checkEquals(0, set.size)
    }

    private fun removeThenAdd() {
        val set = MutableScatterSet<String?>(6)
        set += "Hello"
        set += "Bonjour"
        set += "Hallo"
        set += "Konnichiwa"
        set += "Ciao"
        set += "Annyeong"

        set.remove("Hello")
        set.remove("Bonjour")
        set.remove("Hallo")
        set.remove("Konnichiwa")
        set.remove("Ciao")
        set.remove("Annyeong")

        checkEquals(0, set.size)

        val capacity = set.capacity

        set += "Hello"

        checkEquals(1, set.size)
        checkEquals(capacity, set.capacity)
    }

    private fun removeAllArray() {
        val set = mutableScatterSetOf("Hello", "World")
        checkCondition(!set.removeAll(arrayOf("Hola", "Bonjour")))
        checkEquals(2, set.size)
        checkCondition(set.removeAll(arrayOf("Hola", "Hello", "Bonjour")))
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun removeAllIterable() {
        val set = mutableScatterSetOf("Hello", "World")
        checkCondition(!set.removeAll(listOf("Hola", "Bonjour")))
        checkEquals(2, set.size)
        checkCondition(set.removeAll(listOf("Hola", "Hello", "Bonjour")))
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun removeAllSequence() {
        val set = mutableScatterSetOf("Hello", "World")
        checkCondition(!set.removeAll(sequenceOf("Hola", "Bonjour")))
        checkEquals(2, set.size)
        checkCondition(set.removeAll(sequenceOf("Hola", "Hello", "Bonjour")))
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun removeAllScatterSet() {
        val set = mutableScatterSetOf("Hello", "World")
        checkCondition(!set.removeAll(mutableScatterSetOf("Hola", "Bonjour")))
        checkEquals(2, set.size)
        checkCondition(set.removeAll(mutableScatterSetOf("Hola", "Hello", "Bonjour")))
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun removeAllObjectList() {
        val set = mutableScatterSetOf("Hello", "World")
        checkCondition(!set.removeAll(objectListOf("Hola", "Bonjour")))
        checkEquals(2, set.size)
        checkCondition(set.removeAll(objectListOf("Hola", "Hello", "Bonjour")))
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun removeDoesNotCauseGrowthOnInsert() {
        val set = MutableScatterSet<String>(10)
        checkEquals(15, set.capacity)

        set += "Hello"
        set += "Bonjour"
        set += "Hallo"
        set += "Konnichiwa"
        set += "Ciao"
        set += "Annyeong"

        for (i in 0..7) {
            set += i.toString()
        }

        for (i in 0..5) {
            set.remove(i.toString())
        }

        set += "Foo"
        checkEquals(15, set.capacity)

        checkCondition(set.contains("Foo"))
    }

    private fun minusAssignArray() {
        val set = mutableScatterSetOf("Hello", "World")
        set -= arrayOf("Hola", "Bonjour")
        checkEquals(2, set.size)
        set -= arrayOf("Hola", "Hello", "Bonjour")
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun minusAssignIterable() {
        val set = mutableScatterSetOf("Hello", "World")
        set -= listOf("Hola", "Bonjour")
        checkEquals(2, set.size)
        set -= listOf("Hola", "Hello", "Bonjour")
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun minusAssignSequence() {
        val set = mutableScatterSetOf("Hello", "World")
        set -= sequenceOf("Hola", "Bonjour")
        checkEquals(2, set.size)
        set -= sequenceOf("Hola", "Hello", "Bonjour")
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun minusAssignScatterSet() {
        val set = mutableScatterSetOf("Hello", "World")
        set -= mutableScatterSetOf("Hola", "Bonjour")
        checkEquals(2, set.size)
        set -= mutableScatterSetOf("Hola", "Hello", "Bonjour")
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun minusAssignObjectList() {
        val set = mutableScatterSetOf("Hello", "World")
        set -= objectListOf("Hola", "Bonjour")
        checkEquals(2, set.size)
        set -= objectListOf("Hola", "Hello", "Bonjour")
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))
    }

    private fun insertManyEntries() {
        val set = MutableScatterSet<String>()

        for (i in 0 until 1700) {
            set += i.toString()
        }

        checkEquals(1700, set.size)
    }

    private fun forEach() {
        for (i in 0..48) {
            val set = MutableScatterSet<Int>()

            for (j in 0 until i) {
                set += j
            }

            val elements = Array(i) { -1 }
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

    private fun clear() {
        val set = MutableScatterSet<String>()

        for (i in 0 until 32) {
            set += i.toString()
        }

        val capacity = set.capacity
        set.clear()

        checkEquals(0, set.size)
        checkEquals(capacity, set.capacity)
    }

    private fun string() {
        val set = MutableScatterSet<String?>()
        checkEquals("[]", set.toString())

        set += "Hello"
        set += "Bonjour"
        val str = set.toString()
        checkCondition(str == "[Hello, Bonjour]" || str == "[Bonjour, Hello]")

        set.clear()
        set += null
        checkEquals("[null]", set.toString())

        set.clear()

        val selfAsElement = MutableScatterSet<Any>()
        selfAsElement.add(selfAsElement)
        checkEquals("[(this)]", selfAsElement.toString())
    }

    private fun joinToString() {
        val set = scatterSetOf(1, 2, 3, 4, 5)
        val order = IntArray(5)
        var index = 0
        set.forEach { element -> order[index++] = element }
        checkEquals(
            "${order[0]}, ${order[1]}, ${order[2]}, ${order[3]}, ${order[4]}",
            set.joinToString()
        )
        checkEquals(
            "x${order[0]}, ${order[1]}, ${order[2]}...",
            set.joinToString(prefix = "x", postfix = "y", limit = 3)
        )
        checkEquals(
            ">${order[0]}-${order[1]}-${order[2]}-${order[3]}-${order[4]}<",
            set.joinToString(separator = "-", prefix = ">", postfix = "<")
        )
        val names = arrayOf("one", "two", "three", "four", "five")
        checkEquals(
            "${names[order[0]]}, ${names[order[1]]}, ${names[order[2]]}...",
            set.joinToString(limit = 3) { names[it] }
        )
    }

    private fun hashCodeAddValues() {
        val set = mutableScatterSetOf<String?>()
        checkEquals(217, set.hashCode())
        set += null
        checkEquals(218, set.hashCode())
        set += "Hello"
        val h1 = set.hashCode()
        set += "World"
        checkCondition(h1 != set.hashCode())
    }

    private fun equals() {
        val set = MutableScatterSet<String?>()
        set += "Hello"
        set += null
        set += "Bonjour"

        checkCondition(!set.equals(null))
        checkEquals(set, set)

        val set2 = MutableScatterSet<String?>()
        set2 += "Bonjour"
        set2 += null

        checkCondition(set != set2)

        set2 += "Hello"
        checkEquals(set, set2)
    }

    private fun contains() {
        val set = MutableScatterSet<String?>()
        set += "Hello"
        set += null
        set += "Bonjour"

        checkCondition(set.contains("Hello"))
        checkCondition(set.contains(null))
        checkCondition(!set.contains("World"))
    }

    private fun empty() {
        val set = MutableScatterSet<String?>()
        checkCondition(set.isEmpty())
        checkCondition(!set.isNotEmpty())
        checkCondition(set.none())
        checkCondition(!set.any())

        set += "Hello"

        checkCondition(!set.isEmpty())
        checkCondition(set.isNotEmpty())
        checkCondition(set.any())
        checkCondition(!set.none())
    }

    private fun count() {
        val set = MutableScatterSet<String>()
        checkEquals(0, set.count())

        set += "Hello"
        checkEquals(1, set.count())

        set += "Bonjour"
        set += "Hallo"
        set += "Konnichiwa"
        set += "Ciao"
        set += "Annyeong"

        checkEquals(2, set.count { it.startsWith("H") })
        checkEquals(0, set.count { it.startsWith("W") })
    }

    private fun any() {
        val set = MutableScatterSet<String>()
        set += "Hello"
        set += "Bonjour"
        set += "Hallo"
        set += "Konnichiwa"
        set += "Ciao"
        set += "Annyeong"

        checkCondition(set.any { it.startsWith("K") })
        checkCondition(!set.any { it.startsWith("W") })
    }

    private fun all() {
        val set = MutableScatterSet<String>()
        set += "Hello"
        set += "Bonjour"
        set += "Hallo"
        set += "Konnichiwa"
        set += "Ciao"
        set += "Annyeong"

        checkCondition(set.all { it.length >= 4 })
        checkCondition(!set.all { it.length >= 5 })
    }

    private fun asSet() {
        val scatterSet = mutableScatterSetOf("Hello", "World")
        val set = scatterSet.asSet()
        checkEquals(2, set.size)
        checkCondition(set.containsAll(listOf("Hello", "World")))
        checkCondition(!set.containsAll(listOf("Hola", "World")))
        checkCondition(set.contains("Hello"))
        checkCondition(set.contains("World"))
        checkCondition(!set.contains("Hola"))
        checkCondition(!set.isEmpty())
        val elements = Array(2) { "" }
        set.forEachIndexed { index, element -> elements[index] = element }
        elements.sort()
        checkEquals("Hello", elements[0])
        checkEquals("World", elements[1])
    }

    private fun asMutableSet() {
        val scatterSet = mutableScatterSetOf("Hello", "World")
        val set = scatterSet.asMutableSet()
        checkCondition("Hello" in set)
        checkCondition("World" in set)
        checkCondition(!("Bonjour" in set))

        checkCondition(!set.add("Hello"))
        checkEquals(2, set.size)

        checkCondition(set.add("Hola"))
        checkEquals(3, set.size)
        checkCondition("Hola" in set)

        checkCondition(!set.addAll(listOf("World", "Hello")))
        checkEquals(3, set.size)

        checkCondition(set.addAll(listOf("Hello", "Mundo")))
        checkEquals(4, set.size)
        checkCondition("Mundo" in set)

        checkCondition(!set.remove("Bonjour"))
        checkEquals(4, set.size)

        checkCondition(set.remove("World"))
        checkEquals(3, set.size)
        checkCondition(!("World" in set))

        checkCondition(!set.retainAll(listOf("Hola", "Hello", "Mundo")))
        checkEquals(3, set.size)

        checkCondition(set.retainAll(listOf("Hola", "Hello")))
        checkEquals(2, set.size)
        checkCondition(!("Mundo" in set))

        checkCondition(!set.removeAll(listOf("Bonjour", "Mundo")))
        checkEquals(2, set.size)

        checkCondition(set.removeAll(listOf("Hello", "Mundo")))
        checkEquals(1, set.size)
        checkCondition(!("Hello" in set))

        set.clear()
        checkEquals(0, set.size)
        checkCondition(!("Hola" in set))
    }

    private fun asSetEquals() {
        val set = MutableScatterSet<String?>()
        set += "Hello"
        set += null
        set += "Bonjour"

        checkCondition(!set.asSet().equals(null))
        checkCondition(!set.asMutableSet().equals(null))
        checkEquals(set.asSet(), set.asSet())
        checkEquals(set.asMutableSet(), set.asMutableSet())

        val set2 = MutableScatterSet<String?>()
        set2 += "Bonjour"
        set2 += null

        checkCondition(set.asSet() != set2.asSet())
        checkCondition(set.asMutableSet() != set2.asMutableSet())

        set2 += "Hello"
        checkEquals(set.asSet(), set2.asSet())
        checkEquals(set.asMutableSet(), set2.asMutableSet())
    }

    private fun asSetString() {
        val set = MutableScatterSet<String?>()
        checkEquals("[]", set.asSet().toString())
        checkEquals("[]", set.asMutableSet().toString())

        set += "Hello"
        set += "Bonjour"
        val str1 = set.asSet().toString()
        checkCondition(str1 == "[Hello, Bonjour]" || str1 == "[Bonjour, Hello]")
        val str2 = set.asMutableSet().toString()
        checkCondition(str2 == "[Hello, Bonjour]" || str2 == "[Bonjour, Hello]")

        set.clear()
        set += null
        checkEquals("[null]", set.asSet().toString())
        checkEquals("[null]", set.asMutableSet().toString())

        set.clear()

        val selfAsElement = MutableScatterSet<Any>()
        selfAsElement.add(selfAsElement)
        checkEquals("[(this)]", selfAsElement.asSet().toString())
        checkEquals("[(this)]", selfAsElement.asMutableSet().toString())
    }

    private fun asSetHashCodeAddValues() {
        val set = mutableScatterSetOf<String?>()
        checkEquals(217, set.asSet().hashCode())
        checkEquals(217, set.asMutableSet().hashCode())
        set += null
        checkEquals(218, set.asSet().hashCode())
        checkEquals(218, set.asMutableSet().hashCode())

        set += "Hello"
        val h1 = set.hashCode()
        set += "World"
        checkCondition(h1 != set.asSet().hashCode())
        checkCondition(h1 != set.asMutableSet().hashCode())
    }

    private fun trim() {
        val set = mutableScatterSetOf("Hello", "World", "Hola", "Mundo", "Bonjour", "Monde")
        val capacity = set.capacity
        checkEquals(0, set.trim())
        set.clear()
        checkEquals(capacity, set.trim())
        checkEquals(0, set.capacity)
        set.addAll(
            arrayOf(
                "Hello", "World", "Hola", "Mundo", "Bonjour", "Monde",
                "Hallo", "Welt", "Konnichiwa", "Sekai", "Ciao", "Mondo", "Annyeong", "Sesang"
            )
        )
        set.removeAll(
            arrayOf("Hallo", "Welt", "Konnichiwa", "Sekai", "Ciao", "Mondo", "Annyeong", "Sesang")
        )
        checkCondition(set.trim() > 0)
        checkEquals(capacity, set.capacity)
    }

    private fun scatterSetOfEmpty() {
        val empty: ScatterSet<String> = scatterSetOf()
        val emptyDefault: ScatterSet<String> = emptyScatterSet()
        checkCondition(empty === emptyDefault)
        val empty2: ScatterSet<String> = scatterSetOf()
        checkEquals(0, empty2.size)
    }

    private fun scatterSetOfOne() {
        val set = scatterSetOf("Hello")
        checkEquals(1, set.size)
        checkEquals("Hello", set.first())
    }

    private fun scatterSetOfTwo() {
        val set = scatterSetOf("Hello", "World")
        checkEquals(2, set.size)
        checkCondition("Hello" in set)
        checkCondition("World" in set)
        checkCondition(!("Bonjour" in set))
    }

    private fun scatterSetOfThree() {
        val set = scatterSetOf("Hello", "World", "Hola")
        checkEquals(3, set.size)
        checkCondition("Hello" in set)
        checkCondition("World" in set)
        checkCondition("Hola" in set)
        checkCondition(!("Bonjour" in set))
    }

    private fun scatterSetOfFour() {
        val set = scatterSetOf("Hello", "World", "Hola", "Mundo")
        checkEquals(4, set.size)
        checkCondition("Hello" in set)
        checkCondition("World" in set)
        checkCondition("Hola" in set)
        checkCondition("Mundo" in set)
        checkCondition(!("Bonjour" in set))
    }

    private fun mutableScatterSetOfOne() {
        val set = mutableScatterSetOf("Hello")
        checkEquals(1, set.size)
        checkEquals("Hello", set.first())
    }

    private fun mutableScatterSetOfTwo() {
        val set = mutableScatterSetOf("Hello", "World")
        checkEquals(2, set.size)
        checkCondition("Hello" in set)
        checkCondition("World" in set)
        checkCondition(!("Bonjour" in set))
    }

    private fun mutableScatterSetOfThree() {
        val set = mutableScatterSetOf("Hello", "World", "Hola")
        checkEquals(3, set.size)
        checkCondition("Hello" in set)
        checkCondition("World" in set)
        checkCondition("Hola" in set)
        checkCondition(!("Bonjour" in set))
    }

    private fun mutableScatterSetOfFour() {
        val set = mutableScatterSetOf("Hello", "World", "Hola", "Mundo")
        checkEquals(4, set.size)
        checkCondition("Hello" in set)
        checkCondition("World" in set)
        checkCondition("Hola" in set)
        checkCondition("Mundo" in set)
        checkCondition(!("Bonjour" in set))
    }

    private fun removeIf() {
        val set = MutableScatterSet<String>()
        set.add("Hello")
        set.add("Bonjour")
        set.add("Hallo")
        set.add("Konnichiwa")
        set.add("Ciao")
        set.add("Annyeong")

        set.removeIf { value -> value.startsWith('H') }

        checkEquals(4, set.size)
        checkCondition(set.contains("Bonjour"))
        checkCondition(set.contains("Konnichiwa"))
        checkCondition(set.contains("Ciao"))
        checkCondition(set.contains("Annyeong"))
    }

    private fun insertOneRemoveOne() {
        val set = MutableScatterSet<Int>()

        for (i in 0..1000000) {
            set.add(i)
            set.remove(i)
            checkCondition(set.capacity < 16)
        }
    }

    private fun insertManyRemoveMany() {
        val set = MutableScatterSet<Int>()

        for (i in 0..100) {
            set.add(i)
        }

        for (i in 0..100) {
            if (i % 2 == 0) {
                set.remove(i)
            }
        }

        for (i in 0..100) {
            if (i % 2 == 0) {
                set.add(i)
            }
        }

        for (i in 0..100) {
            if (i % 2 != 0) {
                set.remove(i)
            }
        }

        for (i in 0..100) {
            if (i % 2 != 0) {
                set.add(i)
            }
        }

        checkEquals(127, set.capacity)
        for (i in 0..100) {
            checkCondition(set.contains(i))
        }
    }

    private fun removeWhenIterating() {
        val set = MutableScatterSet<String>()
        set.add("Hello")
        set.add("Bonjour")
        set.add("Hallo")
        set.add("Konnichiwa")
        set.add("Ciao")
        set.add("Annyeong")

        val iterator = set.asMutableSet().iterator()
        while (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }

        checkEquals(0, set.size)
    }

    private fun removeWhenForEach() {
        val set = MutableScatterSet<String>()
        set.add("Hello")
        set.add("Bonjour")
        set.add("Hallo")
        set.add("Konnichiwa")
        set.add("Ciao")
        set.add("Annyeong")

        set.forEach { element -> set.remove(element) }

        checkEquals(0, set.size)
    }
}
