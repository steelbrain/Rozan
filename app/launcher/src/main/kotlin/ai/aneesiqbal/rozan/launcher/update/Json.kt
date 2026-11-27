package ai.aneesiqbal.rozan.launcher.update

/** A parsed JSON value. Only what [Json.parse] produces; never built by hand outside tests. */
internal sealed interface JsonValue {
    /** `{...}`: members in document order; keys are unique. */
    data class Object(
        val members: Map<String, JsonValue>,
    ) : JsonValue

    /** `[...]`. */
    data class Array(
        val items: List<JsonValue>,
    ) : JsonValue

    /** A string, unescaped. */
    data class Text(
        val value: String,
    ) : JsonValue

    /** A number, as written; [longOrNull] reads integers. */
    data class Number(
        val raw: String,
    ) : JsonValue {
        /** The value if it is an integer that fits a [Long] (no fraction, no exponent). */
        val longOrNull: Long? get() = raw.toLongOrNull()
    }

    /** `true` or `false`. */
    data class Bool(
        val value: Boolean,
    ) : JsonValue

    /** `null`. */
    data object Null : JsonValue
}

/** Input that is not the JSON, checksum list, URL or release the updater expects. */
internal class UpdateFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Throws [UpdateFormatException]; for parsers with many ways to fail. */
internal fun formatError(
    message: String,
    cause: Throwable? = null,
): Nothing = throw UpdateFormatException(message, cause)

/**
 * A strict RFC 8259 JSON parser for the release metadata, small enough to
 * test on the JVM (`org.json` is a stub there). It bounds everything an
 * attacker controls: the input length ([MAX_CHARS]), the nesting depth
 * ([MAX_DEPTH], so recursion cannot overflow the stack) and the number of
 * values ([MAX_VALUES]). Duplicate keys, trailing commas, comments, leading
 * zeros and unescaped control characters are rejected. Any failure is an
 * [UpdateFormatException].
 */
internal object Json {
    /** Longest input accepted, in UTF-16 chars; the metadata download is capped at 1 MiB of bytes. */
    const val MAX_CHARS = 1 shl 20

    /** Deepest nesting of arrays and objects. GitHub's release JSON nests 3 deep. */
    const val MAX_DEPTH = 32

    /** Most values (of any kind) in one document. */
    const val MAX_VALUES = 200_000

    /** Parses [text], which must hold exactly one JSON value (whitespace around it is allowed). */
    fun parse(text: String): JsonValue {
        if (text.length > MAX_CHARS) throw UpdateFormatException("JSON longer than $MAX_CHARS chars")
        val parser = Parser(text)
        val value = parser.value(depth = 0)
        parser.skipWhitespace()
        if (!parser.atEnd) parser.fail("data after the value")
        return value
    }

    private class Parser(
        private val text: String,
    ) {
        private var pos = 0
        private var values = 0

        val atEnd: Boolean get() = pos >= text.length

        fun fail(what: String): Nothing = throw UpdateFormatException("JSON: $what at offset $pos")

        fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun value(depth: Int): JsonValue {
            if (++values > MAX_VALUES) fail("more than $MAX_VALUES values")
            skipWhitespace()
            if (atEnd) fail("value expected")
            return when (val c = text[pos]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> JsonValue.Text(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        private fun literal(
            word: String,
            value: JsonValue,
        ): JsonValue {
            if (!text.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return value
        }

        private fun obj(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
            pos++ // {
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Object(members)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("member name expected")
                val key = string()
                skipWhitespace()
                expect(':')
                val member = value(depth)
                if (members.put(key, member) != null) fail("duplicate key")
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                    }

                    '}' -> {
                        pos++
                        return JsonValue.Object(members)
                    }

                    else -> {
                        fail("',' or '}' expected")
                    }
                }
            }
        }

        private fun array(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Array(items)
            }
            while (true) {
                items += value(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                    }

                    ']' -> {
                        pos++
                        return JsonValue.Array(items)
                    }

                    else -> {
                        fail("',' or ']' expected")
                    }
                }
            }
        }

        private fun string(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd) fail("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> escape(out)
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun escape(out: StringBuilder) {
            if (atEnd) fail("unterminated escape")
            when (val c = text[pos++]) {
                '"', '\\', '/' -> out.append(c)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> out.append(hex4())
                else -> fail("bad escape '\\$c'")
            }
        }

        // Surrogate pairs arrive as two \u escapes; each is kept as the UTF-16 unit it names.
        private fun hex4(): Char {
            if (pos + HEX_DIGITS > text.length) fail("truncated \\u escape")
            var code = 0
            repeat(HEX_DIGITS) {
                val digit = asciiHexValue(text[pos++])
                if (digit < 0) fail("bad \\u escape")
                code = code * HEX_RADIX + digit
            }
            return code.toChar()
        }

        private fun number(): JsonValue {
            val start = pos
            if (peek() == '-') pos++
            when {
                peek() == '0' -> pos++
                peek() in '1'..'9' -> digits()
                else -> fail("digit expected")
            }
            if (peek() == '.') {
                pos++
                if (peek() !in '0'..'9') fail("digit expected after '.'")
                digits()
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek() !in '0'..'9') fail("digit expected in exponent")
                digits()
            }
            return JsonValue.Number(text.substring(start, pos))
        }

        private fun digits() {
            while (peek() in '0'..'9') pos++
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("'$c' expected")
            pos++
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else END
    }

    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16

    // Never part of valid JSON outside strings, so it ends every token scan.
    private const val END = '\u0000'
}
