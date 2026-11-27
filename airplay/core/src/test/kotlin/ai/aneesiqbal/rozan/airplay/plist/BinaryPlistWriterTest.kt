package ai.aneesiqbal.rozan.airplay.plist

import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryPlistWriterTest {
    /** Bytes of the single object our writer produced for [value] (between header and offset table). */
    private fun objectBytes(value: PlistValue): String {
        val encoded = BinaryPlist.encode(value)
        val tableStart =
            (0 until 8).fold(0L) { acc, i ->
                (acc shl 8) or
                    (encoded[encoded.size - 8 + i].toLong() and 0xFF)
            }
        return encoded.copyOfRange(8, tableStart.toInt()).toHex()
    }

    @Test
    fun `encodes the smallest plist exactly`() {
        val expected =
            "62706c6973743030" + "09" + "08" + "000000000000" + "01" + "01" +
                "0000000000000001" + "0000000000000000" + "0000000000000009"
        assertEquals(expected, BinaryPlist.encode(PlistBool(true)).toHex())
    }

    @Test
    fun `integers use the smallest width`() {
        assertEquals("1000", objectBytes(PlistInteger(0)))
        assertEquals("10ff", objectBytes(PlistInteger(255)))
        assertEquals("110100", objectBytes(PlistInteger(256)))
        assertEquals("11ffff", objectBytes(PlistInteger(65535)))
        assertEquals("1200010000", objectBytes(PlistInteger(65536)))
        assertEquals("12ffffffff", objectBytes(PlistInteger(0xFFFFFFFFL)))
        assertEquals("130000000100000000", objectBytes(PlistInteger(1L shl 32)))
        assertEquals("13ffffffffffffffff", objectBytes(PlistInteger(-1)))
        assertEquals("14" + "0000000000000000" + "8000000000000000", objectBytes(PlistInteger.ofUnsigned(1UL shl 63)))
    }

    @Test
    fun `strings pick ASCII or UTF-16 and long ones carry an integer length`() {
        assertEquals("5161", objectBytes(PlistString("a")))
        assertEquals("50", objectBytes(PlistString("")))
        assertEquals("6100e9", objectBytes(PlistString("é")))
        assertEquals("62d83cdf51", objectBytes(PlistString("🍑")))
        assertEquals("5e" + "61".repeat(14), objectBytes(PlistString("a".repeat(14))))
        assertEquals("5f100f" + "61".repeat(15), objectBytes(PlistString("a".repeat(15))))
        assertEquals("5f110100" + "61".repeat(256), objectBytes(PlistString("a".repeat(256))))
    }

    @Test
    fun `other scalars encode as documented`() {
        assertEquals("233ff8000000000000", objectBytes(PlistReal(1.5)))
        assertEquals("330000000000000000", objectBytes(PlistDate(0.0)))
        assertEquals("43010203", objectBytes(PlistData(byteArrayOf(1, 2, 3))))
        assertEquals("8007", objectBytes(PlistUid(7)))
        assertEquals("810100", objectBytes(PlistUid(256)))
        assertEquals("83ffffffff", objectBytes(PlistUid(0xFFFFFFFFL)))
    }

    @Test
    fun `containers reference their children and equal scalars are stored once`() {
        val dict = PlistDict(mapOf("a" to PlistString("a"), "b" to PlistString("a")))
        // dict, "a" (key and value), "b": three objects.
        assertEquals("d2" + "0102" + "0101" + "5161" + "5162", objectBytes(dict))
        val array = PlistArray(listOf(PlistInteger(1), PlistInteger(1), PlistBool(true)))
        assertEquals("a3010102" + "1001" + "09", objectBytes(array))
    }

    @Test
    fun `more than 255 objects switch to two-byte references`() {
        val array = PlistArray(List(300) { PlistInteger(it.toLong()) })
        val encoded = BinaryPlist.encode(array)
        assertEquals(2, encoded[encoded.size - 25].toInt())
        assertEquals(array, BinaryPlist.decode(encoded))
    }

    @Test
    fun `writer enforces the nesting limit`() {
        var value: PlistValue = PlistString("x")
        repeat(32) { value = PlistArray(listOf(value)) }
        assertEquals(value, BinaryPlist.decode(BinaryPlist.encode(value)))
        val deeper = PlistArray(listOf(value))
        assertFailsWith<PlistException> { BinaryPlist.encode(deeper) }
        assertEquals(deeper, BinaryPlist.decode(BinaryPlist.encode(deeper, maxDepth = 33), PlistLimits(maxDepth = 33)))
    }

    @Test
    fun `random values round-trip through both encoders`() {
        val random = Random(1234)
        val generator = RandomPlist(random)
        repeat(500) {
            val value = generator.value()
            val binary = BinaryPlist.encode(value)
            assertEquals(value, BinaryPlist.decode(binary), "binary round trip #$it")
            assertContentEquals(binary, BinaryPlist.encode(BinaryPlist.decode(binary)))
            assertEquals(value, XmlPlist.decode(XmlPlist.encode(value)), "XML round trip #$it")
        }
    }

    @Test
    fun `dictionary order is preserved through both encoders`() {
        val keys = listOf("zeta", "alpha", "mid", "Beta", "0")
        val dict = PlistDict(keys.associateWith { PlistString(it) })
        assertEquals(keys, (BinaryPlist.decode(BinaryPlist.encode(dict)) as PlistDict).entries.keys.toList())
        assertEquals(keys, (XmlPlist.decode(XmlPlist.encode(dict)) as PlistDict).entries.keys.toList())
    }
}
