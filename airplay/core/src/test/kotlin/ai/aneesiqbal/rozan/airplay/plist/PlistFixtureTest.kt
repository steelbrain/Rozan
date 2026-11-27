package ai.aneesiqbal.rozan.airplay.plist

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fixtures in `src/test/resources/plist/` were written as XML and converted with macOS
 * `plutil -convert binary1 -o NAME.bplist NAME.xml`, so the `.bplist` files are Apple's own encoder output.
 */
class PlistFixtureTest {
    @ParameterizedTest
    @MethodSource("fixtures")
    fun `binary fixture decodes to the same value as its XML source`(name: String) {
        val binary = BinaryPlist.decode(resource("$name.bplist"))
        val xml = XmlPlist.decode(resource("$name.xml"))
        assertEquals(xml, binary)
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `fixture survives our binary and XML encoders`(name: String) {
        val value = BinaryPlist.decode(resource("$name.bplist"))
        assertEquals(value, BinaryPlist.decode(BinaryPlist.encode(value)))
        assertEquals(value, XmlPlist.decode(XmlPlist.encode(value)))
    }

    @Test
    fun `scalars fixture holds the expected values`() {
        val dict = assertIs<PlistDict>(BinaryPlist.decode(resource("scalars.bplist")))
        assertEquals("hello", dict.string("ascii"))
        assertEquals("", dict.string("empty"))
        assertEquals(
            "The quick brown fox jumps over the lazy dog",
            dict.string("long ascii string over fifteen characters"),
        )
        assertEquals("Grüße, 世界 🍑", dict.string("unicode"))
        assertEquals("a & b < c > d \"quoted\" 'single'", dict.string("escaped"))
        assertEquals("line one\nline two\ttabbed", dict.string("multiline"))
        val ints =
            mapOf(
                "int 0" to 0L,
                "int 1" to 1L,
                "int 255" to 255L,
                "int 256" to 256L,
                "int 65535" to 65535L,
                "int 65536" to 65536L,
                "int 4294967295" to 4294967295L,
                "int 4294967296" to 4294967296L,
                "int max" to Long.MAX_VALUE,
                "int -1" to -1L,
                "int min" to Long.MIN_VALUE,
            )
        for ((key, expected) in ints) assertEquals(PlistInteger(expected), dict[key], key)
        assertEquals(PlistInteger.ofUnsigned(1UL shl 63), dict["uint 2^63"])
        assertEquals(ULong.MAX_VALUE, (dict["uint max"] as PlistInteger).toULongOrNull())
        assertEquals(0.5, dict.double("real half"))
        assertEquals(-1.25, dict.double("real negative"))
        assertEquals(Math.PI, dict.double("real pi"))
        assertEquals(1e300, dict.double("real huge"))
        assertEquals(Double.MIN_VALUE, dict.double("real tiny"))
        assertEquals(0.0, dict.double("real zero"))
        assertEquals(Double.POSITIVE_INFINITY, dict.double("real +inf"))
        assertEquals(Double.NEGATIVE_INFINITY, dict.double("real -inf"))
        assertEquals(true, dict.bool("true"))
        assertEquals(false, dict.bool("false"))
        assertEquals(PlistDate(0.0), dict["date reference"])
        assertEquals(Instant.parse("2024-02-29T12:34:56Z"), (dict["date leap day"] as PlistDate).toInstant())
        assertEquals(Instant.EPOCH, (dict["date before reference"] as PlistDate).toInstant())
        assertContentEquals(ByteArray(0), dict.data("data empty"))
        assertContentEquals(ByteArray(16) { it.toByte() }, dict.data("data short"))
        assertEquals(dict["repeated a"], dict["repeated b"])
    }

    @Test
    fun `plutil stores repeated strings once and the reader shares them`() {
        val bytes = resource("scalars.bplist")
        val count = bytes.size - 32 + 8
        val objectCount =
            (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[count + i].toLong() and 0xFF) }
        val dict = BinaryPlist.decode(bytes) as PlistDict
        // 1 dict + keys + values, minus the shared "same value" string and shared booleans/numbers.
        assertTrue(objectCount < 1 + 2L * dict.size, "expected plutil to deduplicate objects")
    }

    @Test
    fun `uid fixture decodes keyed-archiver references`() {
        val dict = BinaryPlist.decode(resource("uid.bplist")) as PlistDict
        val top = checkNotNull(dict.dict("\$top"))
        assertEquals(PlistUid(1), top["root"])
        assertEquals(PlistUid(70_000), top["wide"])
        val objects = checkNotNull(dict.array("\$objects"))
        assertEquals(PlistUid(0), (objects[1] as PlistDict)["next"])
    }

    @Test
    fun `many-objects fixture needs two-byte references and four-byte offsets`() {
        val bytes = resource("many-objects.bplist")
        assertEquals(4, bytes[bytes.size - 26].toInt(), "offset size")
        assertEquals(2, bytes[bytes.size - 25].toInt(), "reference size")
        val dict = BinaryPlist.decode(bytes) as PlistDict
        val strings = checkNotNull(dict.array("strings"))
        assertEquals(300, strings.size)
        assertEquals(PlistString("item 299"), strings[299])
        val blob = checkNotNull(dict.data("blob"))
        assertEquals(70_000, blob.size)
        assertTrue(blob.indices.all { blob[it] == ((it * 7 + 3) and 0xFF).toByte() })
        assertEquals("offsets past 65535 need wider offset integers", dict.string("after blob"))
    }

    @Test
    fun `nesting of exactly 32 decodes and 33 is rejected`() {
        var value = BinaryPlist.decode(resource("depth-32.bplist"))
        var depth = 0
        while (value is PlistArray) {
            depth++
            value = value[0]
        }
        assertEquals(32, depth)
        assertEquals(PlistString("bottom"), value)
        assertFailsWith<PlistException> { BinaryPlist.decode(resource("depth-33.bplist")) }
        assertFailsWith<PlistException> { XmlPlist.decode(resource("depth-33.xml")) }
        assertEquals(
            BinaryPlist.decode(resource("depth-33.bplist"), PlistLimits(maxDepth = 33)),
            XmlPlist.decode(resource("depth-33.xml"), PlistLimits(maxDepth = 33)),
        )
    }

    @Test
    fun `object limit applies to fixtures`() {
        assertFailsWith<PlistException> {
            BinaryPlist.decode(
                resource("many-objects.bplist"),
                PlistLimits(maxObjects = 100),
            )
        }
        assertFailsWith<PlistException> { XmlPlist.decode(resource("many-objects.xml"), PlistLimits(maxObjects = 100)) }
    }

    companion object {
        @JvmStatic
        fun fixtures(): List<String> = FIXTURES
    }
}
