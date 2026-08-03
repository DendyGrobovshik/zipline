package androidx.collection.manual

import androidx.collection.LongSparseArray

class LongSparseArrayTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        "getOrDefaultPrefersStoredValue" to { getOrDefaultPrefersStoredValue() },
        "getOrDefaultUsesDefaultWhenAbsent" to { getOrDefaultUsesDefaultWhenAbsent() },
        "getOrDefaultReturnsNullWhenNullStored" to { getOrDefaultReturnsNullWhenNullStored() },
        "getOrDefaultDoesNotPersistDefault" to { getOrDefaultDoesNotPersistDefault() },
        "putIfAbsentDoesNotOverwriteStoredValue" to { putIfAbsentDoesNotOverwriteStoredValue() },
        "putIfAbsentReturnsStoredValue" to { putIfAbsentReturnsStoredValue() },
        "putIfAbsentStoresValueWhenAbsent" to { putIfAbsentStoresValueWhenAbsent() },
        "putIfAbsentReturnsNullWhenAbsent" to { putIfAbsentReturnsNullWhenAbsent() },
        "replaceWhenAbsentDoesNotStore" to { replaceWhenAbsentDoesNotStore() },
        "replaceStoresAndReturnsOldValue" to { replaceStoresAndReturnsOldValue() },
        "replaceStoresAndReturnsNullWhenMappedToNull" to { replaceStoresAndReturnsNullWhenMappedToNull() }
    )

    private fun getOrDefaultPrefersStoredValue() {
        val map = LongSparseArray<String>()
        map.put(1L, "1")
        checkEquals("1", map.get(1L, defaultValue = "2"))
    }

    private fun getOrDefaultUsesDefaultWhenAbsent() {
        val map = LongSparseArray<String>()
        checkEquals("1", map.get(1L, defaultValue = "1"))
    }

    private fun getOrDefaultReturnsNullWhenNullStored() {
        val map = LongSparseArray<String?>()
        map.put(1L, null)
        checkEquals(null, map.get(1L, defaultValue = "1"))
    }

    private fun getOrDefaultDoesNotPersistDefault() {
        val map = LongSparseArray<String>()
        map.get(1L, "1")
        checkCondition(!map.containsKey(1L))
    }

    private fun putIfAbsentDoesNotOverwriteStoredValue() {
        val map = LongSparseArray<String>()
        map.put(1L, "1")
        map.putIfAbsent(1L, "2")
        checkEquals("1", map[1L])
    }

    private fun putIfAbsentReturnsStoredValue() {
        val map = LongSparseArray<String>()
        map.put(1L, "1")
        checkEquals("1", map.putIfAbsent(1L, "2"))
    }

    private fun putIfAbsentStoresValueWhenAbsent() {
        val map = LongSparseArray<String>()
        map.putIfAbsent(1L, "2")
        checkEquals("2", map[1L])
    }

    private fun putIfAbsentReturnsNullWhenAbsent() {
        val map = LongSparseArray<String>()
        checkEquals(null, map.putIfAbsent(1L, "2"))
    }

    private fun replaceWhenAbsentDoesNotStore() {
        val map = LongSparseArray<String>()
        checkEquals(null, map.replace(1L, "1"))
        checkCondition(!map.containsKey(1L))
    }

    private fun replaceStoresAndReturnsOldValue() {
        val map = LongSparseArray<String>()
        map.put(1L, "1")
        checkEquals("1", map.replace(1L, "2"))
        checkEquals("2", map[1L])
    }

    private fun replaceStoresAndReturnsNullWhenMappedToNull() {
        val map = LongSparseArray<String?>()
        map.put(1L, null)
        checkEquals(null, map.replace(1L, "1"))
        checkEquals("1", map[1L])
    }
}
