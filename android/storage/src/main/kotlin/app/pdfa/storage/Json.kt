package app.pdfa.storage

/**
 * A deliberately small JSON codec for the manifest. Supports objects, strings, integers (as Long),
 * booleans and null. Arrays, fractions, exponents, nesting deeper than [decode]'s maxDepth, bad
 * `\u` escapes and unpaired UTF-16 surrogates are rejected with [IllegalArgumentException].
 */
internal object Json {

    const val DEFAULT_MAX_DEPTH = 16

    fun encode(obj: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in obj) {
            if (!first) sb.append(',')
            first = false
            appendString(sb, key)
            sb.append(':')
            when (value) {
                null -> sb.append("null")
                is String -> appendString(sb, value)
                is Long -> sb.append(value)
                is Int -> sb.append(value)
                is Boolean -> sb.append(value)
                else -> throw IllegalArgumentException("unsupported json value")
            }
        }
        return sb.append('}').toString()
    }

    fun decode(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): Map<String, Any?> {
        val parser = Parser(text, maxDepth)
        val result = parser.readObject()
        parser.skipWs()
        if (!parser.atEnd()) throw IllegalArgumentException("trailing data")
        return result
    }

    private fun appendString(sb: StringBuilder, s: String) {
        checkSurrogates(s)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Every high surrogate must be followed by a low one, and no low surrogate may stand alone. */
    internal fun checkSurrogates(s: CharSequence) {
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                Character.isHighSurrogate(c) -> {
                    if (k + 1 < s.length && Character.isLowSurrogate(s[k + 1])) k += 2
                    else throw IllegalArgumentException("unpaired surrogate")
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("unpaired surrogate")
                else -> k++
            }
        }
    }

    private class Parser(private val s: String, private val maxDepth: Int) {
        private var i = 0
        private var depth = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun readObject(): Map<String, Any?> {
            expect('{')
            if (++depth > maxDepth) fail()
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                i++
                depth--
                return out
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                out[key] = readValue()
                skipWs()
                val c = next()
                if (c == ',') continue
                if (c == '}') break
                fail()
            }
            depth--
            return out
        }

        private fun readValue(): Any? = when (peek()) {
            '"' -> readString()
            '{' -> readObject()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> readNumber()
            else -> fail()
        }

        private fun <T> literal(word: String, value: T): T {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return value
        }

        private fun readNumber(): Long {
            val start = i
            if (peek() == '-') i++
            val digitsStart = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == digitsStart) fail()
            if (i < s.length && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) fail()
            return s.substring(start, i).toLongOrNull() ?: fail()
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                if (c == '"') break
                if (c == '\\') sb.append(readEscape())
                else if (c < ' ') fail()
                else sb.append(c)
            }
            try {
                checkSurrogates(sb)
            } catch (e: IllegalArgumentException) {
                fail()
            }
            return sb.toString()
        }

        private fun readEscape(): Char {
            if (i >= s.length) fail()
            return when (s[i++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (i + 4 > s.length) fail()
                    val hex = s.substring(i, i + 4)
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                    i += 4
                    hex.toInt(16).toChar()
                }
                else -> fail()
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail()

        private fun next(): Char = if (i < s.length) s[i++] else fail()

        private fun expect(c: Char) {
            if (next() != c) fail()
        }

        private fun fail(): Nothing = throw IllegalArgumentException("malformed json")
    }
}
