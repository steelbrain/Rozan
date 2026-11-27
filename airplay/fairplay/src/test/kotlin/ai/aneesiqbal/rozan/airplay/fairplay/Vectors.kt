package ai.aneesiqbal.rozan.airplay.fairplay

/** The vector files of `docs/protocol/vectors/`, copied to `src/test/resources/vectors/`. */
object Vectors {
    val v2: Map<String, Any?> by lazy { load("fairplay-v2.json") }
    val v3: Map<String, Any?> by lazy { load("fairplay-v3.json") }
    val tables: Map<String, Any?> by lazy { load("fairplay-v3-tables.json") }
    val coverage: Map<String, Any?> by lazy { load("fairplay-v3-coverage.json") }
    val sessions: Map<String, Any?> by lazy { load("fairplay-sessions.json") }

    val v2Cases: List<Map<String, Any?>> get() = v2.list("cases").map { it.asObject() }
    val v3Cases: List<Map<String, Any?>> get() = v3.list("cases").map { it.asObject() }
    val coverageCases: List<Map<String, Any?>> get() = coverage.list("cases").map { it.asObject() }
    val sapHashCases: List<Map<String, Any?>> get() = coverage.list("sap_hash_cases").map { it.asObject() }
    val scenarios: List<Map<String, Any?>> get() = sessions.list("scenarios").map { it.asObject() }

    fun v2Case(name: String): Map<String, Any?> = v2Cases.single { it.string("name") == name }

    fun v3Case(name: String): Map<String, Any?> = v3Cases.single { it.string("name") == name }

    fun v2CaseNames(): List<String> = v2Cases.map { it.string("name") }

    fun v3CaseNames(): List<String> = v3Cases.map { it.string("name") }

    fun resourceBytes(name: String): ByteArray {
        val stream = checkNotNull(Vectors::class.java.getResourceAsStream("/vectors/$name")) { "missing $name" }
        return stream.use { it.readBytes() }
    }

    private fun load(name: String): Map<String, Any?> = Json.parse(resourceBytes(name).decodeToString()).asObject()
}

fun Any?.asObject(): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST") // Json.parse builds objects as Map<String, Any?>.
    return this as Map<String, Any?>
}

fun Map<String, Any?>.obj(key: String): Map<String, Any?> = checkNotNull(this[key]) { "no $key" }.asObject()

fun Map<String, Any?>.list(key: String): List<Any?> = checkNotNull(this[key]) { "no $key" } as List<*>

fun Map<String, Any?>.string(key: String): String = checkNotNull(this[key]) { "no $key" } as String

fun Map<String, Any?>.int(key: String): Int = (checkNotNull(this[key]) { "no $key" } as Long).toInt()

fun Map<String, Any?>.hex(key: String): ByteArray = hexToBytes(string(key))

fun hexToBytes(text: String): ByteArray = hex(text)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** A minimal JSON reader for the vector files: objects, arrays, strings, integers, booleans and null. */
object Json {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        check(reader.done()) { "trailing data at ${reader.position}" }
        return value
    }

    private class Reader(
        private val text: String,
    ) {
        var position = 0

        fun done() = position == text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun value(): Any? {
            skipWhitespace()
            return when (val c = text[position]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $position")
            }
        }

        private fun literal(
            word: String,
            value: Any?,
        ): Any? {
            check(text.startsWith(word, position)) { "bad literal at $position" }
            position += word.length
            return value
        }

        private fun number(): Long {
            val start = position
            if (text[position] == '-') position++
            while (position < text.length && text[position].isDigit()) position++
            check(position < text.length && text[position] !in ".eE") { "only integers are supported" }
            return text.substring(start, position).toLong()
        }

        private fun string(): String {
            position++ // opening quote
            val out = StringBuilder()
            while (true) {
                val c = text[position++]
                when (c) {
                    '"' -> {
                        return out.toString()
                    }

                    '\\' -> {
                        when (val e = text[position++]) {
                            '"', '\\', '/' -> {
                                out.append(e)
                            }

                            'n' -> {
                                out.append('\n')
                            }

                            't' -> {
                                out.append('\t')
                            }

                            'r' -> {
                                out.append('\r')
                            }

                            'b' -> {
                                out.append('\b')
                            }

                            'f' -> {
                                out.append('\u000C')
                            }

                            'u' -> {
                                out.append(text.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }

                            else -> {
                                error("bad escape at $position")
                            }
                        }
                    }

                    else -> {
                        out.append(c)
                    }
                }
            }
        }

        private fun array(): List<Any?> {
            position++ // [
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (text[position] == ']') {
                position++
                return out
            }
            while (true) {
                out.add(value())
                skipWhitespace()
                when (text[position++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected , or ] at $position")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            position++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (text[position] == '}') {
                position++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                check(text[position++] == ':') { "expected : at $position" }
                out[key] = value()
                skipWhitespace()
                when (text[position++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected , or } at $position")
                }
            }
        }
    }
}
