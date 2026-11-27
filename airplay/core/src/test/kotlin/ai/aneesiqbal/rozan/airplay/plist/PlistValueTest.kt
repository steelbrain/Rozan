package ai.aneesiqbal.rozan.airplay.plist

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlistValueTest {
    @Test
    fun `dictionary getters return typed values or null`() {
        val dict =
            buildPlistDict {
                put("s", "text")
                put("i", 7)
                put("l", 1L shl 40)
                put("r", 2.5)
                put("b", false)
                put("d", byteArrayOf(9))
                put("a", PlistArray(listOf(PlistBool(true))))
                put("n", PlistDict(mapOf("x" to PlistBool(true))))
            }
        assertEquals("text", dict.string("s"))
        assertEquals(7L, dict.long("i"))
        assertEquals(1L shl 40, dict.long("l"))
        assertEquals(2.5, dict.double("r"))
        assertEquals(7.0, dict.double("i"))
        assertEquals(false, dict.bool("b"))
        assertContentEquals(byteArrayOf(9), dict.data("d"))
        assertEquals(1, dict.array("a")?.size)
        assertEquals(true, dict.dict("n")?.bool("x"))
        assertNull(dict.string("i"))
        assertNull(dict.long("s"))
        assertNull(dict.double("s"))
        assertNull(dict.bool("missing"))
        assertNull(dict.data("s"))
        assertNull(dict.array("n"))
        assertNull(dict.dict("a"))
        assertTrue("s" in dict)
        assertFalse("missing" in dict)
        assertEquals(8, dict.size)
    }

    @Test
    fun `builder keeps insertion order and replaces repeated keys in place`() {
        val dict =
            buildPlistDict {
                put("b", 1)
                put("a", 2)
                put("b", 3)
            }
        assertEquals(listOf("b", "a"), dict.entries.keys.toList())
        assertEquals(3L, dict.long("b"))
    }

    @Test
    fun `containers copy their input`() {
        val map = linkedMapOf<String, PlistValue>("k" to PlistBool(true))
        val dict = PlistDict(map)
        map["other"] = PlistBool(false)
        assertEquals(1, dict.size)
        val list = mutableListOf<PlistValue>(PlistBool(true))
        val array = PlistArray(list)
        list.add(PlistBool(false))
        assertEquals(1, array.size)
    }

    @Test
    fun `data is copied in and out`() {
        val source = byteArrayOf(1, 2, 3)
        val data = PlistData(source)
        source[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), data.toByteArray())
        data.toByteArray()[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), data.toByteArray())
        assertEquals(PlistData(byteArrayOf(1, 2, 3)), data)
        assertEquals(PlistData(byteArrayOf(1, 2, 3)).hashCode(), data.hashCode())
        assertEquals("PlistData(3 bytes)", data.toString())
    }

    @Test
    fun `dictionary equality ignores order`() {
        val a = PlistDict(linkedMapOf("x" to PlistBool(true), "y" to PlistBool(false)))
        val b = PlistDict(linkedMapOf("y" to PlistBool(false), "x" to PlistBool(true)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(
            PlistArray(listOf(PlistBool(true), PlistBool(false))),
            PlistArray(listOf(PlistBool(false), PlistBool(true))),
        )
    }

    @Test
    fun `integers cover signed and large unsigned values`() {
        assertEquals(PlistInteger(5), PlistInteger.ofUnsigned(5UL))
        val big = PlistInteger.ofUnsigned(ULong.MAX_VALUE)
        assertTrue(big.isLargeUnsigned)
        assertEquals(-1L, big.value)
        assertEquals(ULong.MAX_VALUE, big.toULongOrNull())
        assertNotEquals(PlistInteger(-1), big)
        assertNull(PlistInteger(-1).toULongOrNull())
        assertEquals(1.8446744073709552E19, big.toDouble())
        assertEquals("PlistInteger(18446744073709551615)", big.toString())
        assertEquals("PlistInteger(-1)", PlistInteger(-1).toString())
    }

    @Test
    fun `dates convert to and from instants`() {
        assertEquals(Instant.parse("2001-01-01T00:00:00Z"), PlistDate(0.0).toInstant())
        assertEquals(Instant.parse("2001-01-01T00:00:01.5Z"), PlistDate(1.5).toInstant())
        assertEquals(Instant.parse("2000-12-31T23:59:59.75Z"), PlistDate(-0.25).toInstant())
        assertEquals(PlistDate(1.5), PlistDate.of(Instant.parse("2001-01-01T00:00:01.5Z")))
        assertEquals(PlistDate(-978_307_200.0), PlistDate.of(Instant.EPOCH))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1e300, 1e300)) {
            assertFailsWith<PlistException> { PlistDate(bad).toInstant() }
        }
    }

    @Test
    fun `uids and limits validate their range`() {
        assertFailsWith<IllegalArgumentException> { PlistUid(-1) }
        assertFailsWith<IllegalArgumentException> { PlistUid(PlistUid.MAX_VALUE + 1) }
        assertFailsWith<IllegalArgumentException> { PlistLimits(maxObjects = 0) }
        assertFailsWith<IllegalArgumentException> { PlistLimits(maxDepth = 0) }
        assertFailsWith<IllegalArgumentException> { PlistLimits(maxDepth = PlistLimits.MAX_DEPTH_CEILING + 1) }
        assertEquals(10_000, PlistLimits.DEFAULT.maxObjects)
        assertEquals(32, PlistLimits.DEFAULT.maxDepth)
    }
}
