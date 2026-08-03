package androidx.collection.manual

class JsStringHashCodeTest : Tests {
    override fun tests(): List<Pair<String, () -> Unit>> = listOf(
        "stringHashCodeEmpty" to { stringHashCodeEmpty() },
        "stringHashCodeHello" to { stringHashCodeHello() },
        "stringHashCodeWorld" to { stringHashCodeWorld() },
        "stringHashCodeCaseSensitive" to { stringHashCodeCaseSensitive() },
        "stringHashCodeSingleChar" to { stringHashCodeSingleChar() },
        "stringHashCodeTabChar" to { stringHashCodeTabChar() },
        "stringHashCodeUnicodeSingle" to { stringHashCodeUnicodeSingle() },
        "stringHashCodeCodePoint" to { stringHashCodeCodePoint() },
        "stringHashCodeUnicode" to { stringHashCodeUnicode() },
        "stringHashCodeMixed" to { stringHashCodeMixed() },
        "stringHashCode123" to { stringHashCode123() },
        "stringHashCodeA" to { stringHashCodeA() },
        "stringHashCodeOverflow" to { stringHashCodeOverflow() },
    )

    private fun stringHashCodeEmpty() {
        checkEquals(0, "".hashCode())
    }

    private fun stringHashCodeHello() {
        checkEquals(69609650, "Hello".hashCode())
    }

    private fun stringHashCodeWorld() {
        checkEquals(83766130, "World".hashCode())
    }

    private fun stringHashCodeCaseSensitive() {
        checkEquals(99162322, "hello".hashCode())
    }

    private fun stringHashCodeSingleChar() {
        checkEquals(97, "a".hashCode())
    }

    private fun stringHashCodeTabChar() {
        checkEquals(9, "\t".hashCode())
    }

    private fun stringHashCodeUnicodeSingle() {
        val pi = "\u03C0"
        checkEquals(0x3C0, pi.hashCode())
    }

    private fun stringHashCodeCodePoint() {
        val emojiString = "🥦"
        checkEquals(1772776, emojiString.hashCode())
    }

    private fun stringHashCodeUnicode() {
        checkEquals(25921943, "日本語".hashCode())
    }

    private fun stringHashCodeMixed() {
        checkEquals(722785560, "0a\u03C0🥦pq".hashCode())
    }

    private fun stringHashCode123() {
        checkEquals(48690, "123".hashCode())
    }

    private fun stringHashCodeA() {
        checkEquals(65, "A".hashCode())
    }

    private fun stringHashCodeOverflow() {
        checkEquals(1158167830, "abacabadabacaba".hashCode())
    }
}