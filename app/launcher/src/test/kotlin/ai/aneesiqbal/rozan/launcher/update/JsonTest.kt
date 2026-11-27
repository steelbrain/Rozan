package ai.aneesiqbal.rozan.launcher.update

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class JsonTest {
    @Test
    fun `parses every kind of value`() {
        val value = Json.parse(""" { "a": [1, -2.5e3, true, false, null, "x"], "b": {}, "c": [] } """)
        val root = assertIs<JsonValue.Object>(value)
        assertEquals(listOf("a", "b", "c"), root.members.keys.toList())
        val items = assertIs<JsonValue.Array>(root.members["a"]).items
        assertEquals(
            listOf(
                JsonValue.Number("1"),
                JsonValue.Number("-2.5e3"),
                JsonValue.Bool(true),
                JsonValue.Bool(false),
                JsonValue.Null,
                JsonValue.Text("x"),
            ),
            items,
        )
        assertEquals(JsonValue.Object(emptyMap()), root.members["b"])
        assertEquals(JsonValue.Array(emptyList()), root.members["c"])
    }

    @Test
    fun `unescapes strings`() {
        val text = Json.parse(""""q\" b\\ s\/ \b\f\n\r\t \u00e9 \ud83d\ude00"""")
        assertEquals(JsonValue.Text("q\" b\\ s/ \b\u000C\n\r\t é \uD83D\uDE00"), text)
    }

    @Test
    fun `reads integers as longs and nothing else`() {
        assertEquals(7953856L, JsonValue.Number("7953856").longOrNull)
        assertEquals(-1L, JsonValue.Number("-1").longOrNull)
        assertNull(JsonValue.Number("1.0").longOrNull)
        assertNull(JsonValue.Number("1e3").longOrNull)
        assertNull(JsonValue.Number("99999999999999999999").longOrNull)
    }

    @Test
    fun `parses a GitHub release`() {
        val root = assertIs<JsonValue.Object>(Json.parse(ReleaseJson.github()))
        assertEquals(JsonValue.Text("v0.2.0"), root.members["tag_name"])
    }

    @Test
    fun `rejects every truncation of a valid document`() {
        val json = ReleaseJson.github()
        for (length in 0 until json.length) {
            val prefix = json.substring(0, length)
            // A prefix that ends right after the top-level value plus whitespace is still that value.
            if (prefix.trimEnd() == json.trimEnd()) continue
            assertFailsWith<UpdateFormatException>("prefix of $length chars") { Json.parse(prefix) }
        }
    }

    @Test
    fun `rejects malformed documents`() {
        val bad =
            listOf(
                "",
                "   ",
                "{",
                "[1,]",
                "{\"a\":1,}",
                "{\"a\" 1}",
                "{a:1}",
                "{\"a\":1 \"b\":2}",
                "01",
                "-",
                "1.",
                "1e",
                ".5",
                "+1",
                "tru",
                "nul",
                "\"unterminated",
                "\"bad \\x escape\"",
                "\"short \\u12\"",
                "\"not hex \\u12g4\"",
                "\"control \u0001 char\"",
                "\"tab \t inside\"",
                "[1] [2]",
                "{\"a\":1}x",
                "// comment\n1",
                "'single'",
            )
        for (text in bad) assertFailsWith<UpdateFormatException>(text) { Json.parse(text) }
    }

    @Test
    fun `rejects non-ascii hex digits in escapes`() {
        // U+0663 is ARABIC-INDIC DIGIT THREE, which Character.digit accepts.
        assertFailsWith<UpdateFormatException> { Json.parse("\"\\u\u0663\u0663\u0663\u0663\"") }
    }

    @Test
    fun `rejects duplicate keys`() {
        assertFailsWith<UpdateFormatException> { Json.parse("""{"tag_name":"v1.0.0","tag_name":"v9.9.9"}""") }
    }

    @Test
    fun `rejects input over the length limit`() {
        val big = "\"" + "a".repeat(Json.MAX_CHARS) + "\""
        assertFailsWith<UpdateFormatException> { Json.parse(big) }
        // Just under the limit is fine.
        val fits = "\"" + "a".repeat(Json.MAX_CHARS - 2) + "\""
        assertIs<JsonValue.Text>(Json.parse(fits))
    }

    @Test
    fun `rejects nesting deeper than the limit without overflowing the stack`() {
        val ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        assertIs<JsonValue.Array>(Json.parse(ok))
        val deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1)
        assertFailsWith<UpdateFormatException> { Json.parse(deep) }
        val hostile = "[{\"a\":".repeat(100_000)
        assertFailsWith<UpdateFormatException> { Json.parse(hostile) }
    }

    @Test
    fun `rejects more values than the limit`() {
        val many = "[" + List(Json.MAX_VALUES) { "0" }.joinToString(",") + "]"
        assertFailsWith<UpdateFormatException> { Json.parse(many) }
    }

    @Test
    fun `random garbage fails only with a format error`() {
        val alphabet = "{}[]:,\"\\/ntrufalse0123456789.eE+-u \n\tabc"
        for (seed in 0 until 2_000) {
            val random = Random(seed)
            val text = String(CharArray(random.nextInt(0, 64)) { alphabet[random.nextInt(alphabet.length)] })
            try {
                Json.parse(text)
            } catch (expected: UpdateFormatException) {
                // The only failure allowed.
            }
        }
        for (seed in 0 until 500) {
            val random = Random(seed)
            val text = String(random.nextBytes(random.nextInt(0, 256)), Charsets.UTF_8)
            try {
                Json.parse(text)
            } catch (expected: UpdateFormatException) {
                // The only failure allowed.
            }
        }
    }

    @Test
    fun `mutated releases fail only with a format error`() {
        val json = ReleaseJson.github()
        val random = Random(7)
        repeat(2_000) {
            val chars = json.toCharArray()
            repeat(random.nextInt(1, 4)) { chars[random.nextInt(chars.size)] = random.nextInt(0, 128).toChar() }
            try {
                LatestRelease.parse(String(chars))
            } catch (expected: UpdateFormatException) {
                // The only failure allowed.
            }
        }
    }
}
