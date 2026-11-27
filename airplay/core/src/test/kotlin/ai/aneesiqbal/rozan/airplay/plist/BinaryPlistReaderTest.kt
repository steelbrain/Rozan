package ai.aneesiqbal.rozan.airplay.plist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BinaryPlistReaderTest {
    private val trueObject = bytes(0x09)

    private fun assertRejected(
        input: ByteArray,
        limits: PlistLimits = PlistLimits.DEFAULT,
    ) {
        assertFailsWith<PlistException> { BinaryPlist.decode(input, limits) }
    }

    @Test
    fun `smallest valid plist decodes`() {
        assertEquals(PlistBool(true), BinaryPlist.decode(craftBplist(listOf(trueObject))))
        assertEquals(PlistBool(false), BinaryPlist.decode(craftBplist(listOf(bytes(0x08)))))
    }

    @Test
    fun `header is checked`() {
        assertTrue(BinaryPlist.isBinaryPlist(craftBplist(listOf(trueObject))))
        assertFalse(BinaryPlist.isBinaryPlist("bplist0".toByteArray()))
        val bad = craftBplist(listOf(trueObject))
        bad[7] = '1'.code.toByte()
        assertRejected(bad)
        assertRejected(ByteArray(0))
        assertRejected("bplist00".toByteArray())
    }

    @Test
    fun `input shorter than header plus trailer is rejected`() {
        val valid = craftBplist(listOf(trueObject))
        for (length in 0 until valid.size) assertRejected(valid.copyOf(length))
    }

    @Test
    fun `trailer fields are validated`() {
        val objects = listOf(trueObject)
        assertRejected(craftBplist(objects, offsetSize = 0))
        assertRejected(craftBplist(objects, refSize = 0))
        assertRejected(craftBplist(objects, refSize = 9))
        assertRejected(craftBplist(objects, count = 0))
        assertRejected(craftBplist(objects, count = -1))
        assertRejected(craftBplist(objects, count = Long.MAX_VALUE))
        assertRejected(craftBplist(objects, count = 2))
        assertRejected(craftBplist(objects, top = 1))
        assertRejected(craftBplist(objects, top = -1))
        assertRejected(craftBplist(objects, tableOffset = 0))
        assertRejected(craftBplist(objects, tableOffset = 8))
        assertRejected(craftBplist(objects, tableOffset = Long.MIN_VALUE))
        assertRejected(craftBplist(objects, tableOffset = 1L shl 40))
        assertRejected(craftBplist(objects, tableOffset = 10))
    }

    @Test
    fun `object count above the limit is rejected before parsing`() {
        val objects = List(11) { trueObject }
        assertEquals(PlistBool(true), BinaryPlist.decode(craftBplist(objects), PlistLimits(maxObjects = 11)))
        assertRejected(craftBplist(objects), PlistLimits(maxObjects = 10))
    }

    @Test
    fun `offsets outside the object area are rejected`() {
        assertRejected(craftBplist(listOf(trueObject), offsets = listOf(0)))
        assertRejected(craftBplist(listOf(trueObject), offsets = listOf(7)))
        assertRejected(craftBplist(listOf(trueObject), offsets = listOf(9)))
        assertRejected(craftBplist(listOf(trueObject), offsetSize = 8, offsets = listOf(-1)))
    }

    @Test
    fun `references outside the object table are rejected`() {
        assertRejected(craftBplist(listOf(bytes(0xA1, 1))))
        assertRejected(craftBplist(listOf(bytes(0xA1, 0xFF))))
        assertRejected(
            craftBplist(listOf(bytes(0xA1, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), trueObject), refSize = 8),
        )
        assertRejected(craftBplist(listOf(bytes(0xD1, 1, 2), bytes(0x51, 'k'.code), trueObject), count = 2))
    }

    @Test
    fun `cycles are rejected`() {
        // An array that contains itself, and two dictionaries that contain each other.
        assertRejected(craftBplist(listOf(bytes(0xA1, 0))))
        assertRejected(
            craftBplist(listOf(bytes(0xD1, 2, 1), bytes(0xD1, 2, 0), bytes(0x51, 'k'.code))),
        )
    }

    @Test
    fun `shared objects decode once and are shared`() {
        val plist = craftBplist(listOf(bytes(0xA3, 1, 1, 1), bytes(0x53, 'a'.code, 'b'.code, 'c'.code)))
        val array = BinaryPlist.decode(plist) as PlistArray
        assertEquals(3, array.size)
        assertSame(array[0], array[2])
    }

    @Test
    fun `shared subtrees cannot expand past the object limit`() {
        // Object i is an array holding object i+1 twice: 24 objects describe a tree of 2^24 values.
        val objects = (0 until 23).map { bytes(0xA2, it + 1, it + 1) } + listOf(trueObject)
        assertRejected(craftBplist(objects))
        // The same shape with 12 levels expands to 8191 values, inside the default limit of 10 000.
        val small = (0 until 12).map { bytes(0xA2, it + 1, it + 1) } + listOf(trueObject)
        assertTrue(BinaryPlist.decode(craftBplist(small)) is PlistArray)
        assertRejected(craftBplist(small), PlistLimits(maxObjects = 8000))
    }

    @Test
    fun `depth counts shared subtrees at every place they are referenced`() {
        // Objects 1..20 form a chain of 20 nested arrays. Object 0 references object 1 directly and again
        // through a chain of 15 arrays (objects 21..35), so the second reference sits 35 levels down.
        val chain = (1 until 20).map { bytes(0xA1, it + 1) } + listOf(bytes(0xA0))
        val detour = (21 until 35).map { bytes(0xA1, it + 1) } + listOf(bytes(0xA1, 1))
        val objects = listOf(bytes(0xA2, 1, 21)) + chain + detour
        assertEquals(36, objects.size)
        assertRejected(craftBplist(objects))
        assertTrue(BinaryPlist.decode(craftBplist(objects), PlistLimits(maxDepth = 36)) is PlistArray)
    }

    @Test
    fun `lengths that run past the object area are rejected`() {
        assertRejected(craftBplist(listOf(bytes(0x45, 1, 2))))
        assertRejected(craftBplist(listOf(bytes(0x53, 'a'.code))))
        assertRejected(craftBplist(listOf(bytes(0x62, 0, 'a'.code))))
        assertRejected(craftBplist(listOf(bytes(0xA2, 0))))
        assertRejected(craftBplist(listOf(bytes(0xD1, 0))))
        assertRejected(craftBplist(listOf(bytes(0x13, 0, 0, 0))))
        assertRejected(craftBplist(listOf(bytes(0x23, 0, 0))))
        assertRejected(craftBplist(listOf(bytes(0x33, 0))))
    }

    @Test
    fun `extended lengths are validated`() {
        // Length object with an 8-byte negative value, one that overflows Int, and one too large to exist.
        assertRejected(craftBplist(listOf(bytes(0x4F, 0x13, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))))
        assertRejected(craftBplist(listOf(bytes(0x4F, 0x13, 0, 0, 0, 1, 0, 0, 0, 0))))
        assertRejected(craftBplist(listOf(bytes(0x5F, 0x12, 0x7F, 0xFF, 0xFF, 0xFF))))
        assertRejected(craftBplist(listOf(bytes(0x6F, 0x12, 0x40, 0, 0, 0))))
        assertRejected(craftBplist(listOf(bytes(0xAF, 0x12, 0x7F, 0xFF, 0xFF, 0xFF))))
        // The length must be an integer object of 1 to 8 bytes.
        assertRejected(craftBplist(listOf(bytes(0x4F, 0x51, 'a'.code))))
        assertRejected(craftBplist(listOf(bytes(0x4F, 0x14) + ByteArray(16))))
        assertRejected(craftBplist(listOf(bytes(0x4F))))
        val ok = craftBplist(listOf(bytes(0x4F, 0x10, 16) + ByteArray(16)))
        assertEquals(PlistData(ByteArray(16)), BinaryPlist.decode(ok))
    }

    @Test
    fun `integers of every width decode`() {
        assertEquals(PlistInteger(0xAB), BinaryPlist.decode(craftBplist(listOf(bytes(0x10, 0xAB)))))
        assertEquals(PlistInteger(0xABCD), BinaryPlist.decode(craftBplist(listOf(bytes(0x11, 0xAB, 0xCD)))))
        assertEquals(
            PlistInteger(0xFFFFFFFFL),
            BinaryPlist.decode(craftBplist(listOf(bytes(0x12, 0xFF, 0xFF, 0xFF, 0xFF)))),
        )
        assertEquals(
            PlistInteger(-2),
            BinaryPlist.decode(
                craftBplist(
                    listOf(bytes(0x13) + ByteArray(8) { if (it == 7) -2 else -1 }),
                ),
            ),
        )
        val big = bytes(0x14) + ByteArray(8) + ByteArray(8) { -1 }
        assertEquals(PlistInteger.ofUnsigned(ULong.MAX_VALUE), BinaryPlist.decode(craftBplist(listOf(big))))
        val small = bytes(0x14) + ByteArray(15) + bytes(5)
        assertEquals(PlistInteger(5), BinaryPlist.decode(craftBplist(listOf(small))))
        val negative = bytes(0x14) + ByteArray(16) { if (it == 15) -3 else -1 }
        assertEquals(PlistInteger(-3), BinaryPlist.decode(craftBplist(listOf(negative))))
    }

    /**
     * docs/protocol/03-media-streams.md U24: a random 64-bit `streamConnectionID` of 2^63 or more may arrive
     * as a 16-byte integer (high half zero) or as a negative 8-byte integer; both must give the same
     * unsigned value.
     */
    @Test
    fun `large streamConnectionID decodes from both encodings`() {
        val id = 0xF123_4567_89AB_CDEFuL
        val key = bytes(0x5F, 0x10, 18) + "streamConnectionID".toByteArray()
        val idBytes = ByteArray(8) { (id shr (56 - 8 * it)).toByte() }
        val sixteen = craftBplist(listOf(bytes(0xD1, 1, 2), key, bytes(0x14) + ByteArray(8) + idBytes))
        val eight = craftBplist(listOf(bytes(0xD1, 1, 2), key, bytes(0x13) + idBytes))
        val fromSixteen = BinaryPlist.decode(sixteen) as PlistDict
        val fromEight = BinaryPlist.decode(eight) as PlistDict
        assertEquals(id, (fromSixteen["streamConnectionID"] as PlistInteger).toULongOrNull())
        assertEquals(id, fromSixteen.long("streamConnectionID")?.toULong())
        assertEquals(id, fromEight.long("streamConnectionID")?.toULong())
        // A 16-byte integer with any bit set in the high half is rejected.
        val tooBig = craftBplist(listOf(bytes(0xD1, 1, 2), key, bytes(0x14) + ByteArray(7) + bytes(1) + idBytes))
        assertRejected(tooBig)
    }

    @Test
    fun `integers outside 64 bits and bad widths are rejected`() {
        assertRejected(craftBplist(listOf(bytes(0x14, 1) + ByteArray(15))))
        // High half all ones but low half non-negative: below Long.MIN_VALUE.
        assertRejected(craftBplist(listOf(bytes(0x14) + ByteArray(8) { -1 } + ByteArray(8))))
        assertRejected(craftBplist(listOf(bytes(0x15) + ByteArray(32))))
    }

    @Test
    fun `reals of both widths decode and other widths are rejected`() {
        val float =
            bytes(0x22) +
                java.nio.ByteBuffer
                    .allocate(4)
                    .putFloat(1.5f)
                    .array()
        assertEquals(PlistReal(1.5), BinaryPlist.decode(craftBplist(listOf(float))))
        assertRejected(craftBplist(listOf(bytes(0x21, 0, 0))))
        assertRejected(craftBplist(listOf(bytes(0x24) + ByteArray(16))))
        assertRejected(craftBplist(listOf(bytes(0x32) + ByteArray(8))))
    }

    @Test
    fun `strings are validated`() {
        assertRejected(craftBplist(listOf(bytes(0x51, 0x80))))
        val utf16 = bytes(0x62, 0xD8, 0x3C, 0xDF, 0x51)
        assertEquals(PlistString("🍑"), BinaryPlist.decode(craftBplist(listOf(utf16))))
    }

    @Test
    fun `dictionary keys must be unique strings`() {
        assertRejected(craftBplist(listOf(bytes(0xD1, 1, 1), bytes(0x10, 1))))
        assertRejected(craftBplist(listOf(bytes(0xD2, 1, 1, 2, 2), bytes(0x51, 'k'.code), trueObject)))
        assertRejected(
            craftBplist(listOf(bytes(0xD2, 1, 2, 3, 3), bytes(0x51, 'k'.code), bytes(0x61, 0, 'k'.code), trueObject)),
        )
        val ok = craftBplist(listOf(bytes(0xD2, 1, 2, 3, 3), bytes(0x51, 'k'.code), bytes(0x51, 'j'.code), trueObject))
        assertEquals(listOf("k", "j"), (BinaryPlist.decode(ok) as PlistDict).entries.keys.toList())
    }

    @Test
    fun `unsupported object types are rejected`() {
        for (marker in listOf(0x00, 0x0F, 0x01, 0x70, 0x90, 0xB0, 0xC0, 0xE0, 0xF0)) {
            assertRejected(craftBplist(listOf(bytes(marker))))
        }
        assertRejected(craftBplist(listOf(bytes(0xC1, 1), trueObject)))
    }

    @Test
    fun `uids decode and oversized ones are rejected`() {
        assertEquals(PlistUid(0x0102), BinaryPlist.decode(craftBplist(listOf(bytes(0x81, 1, 2)))))
        assertEquals(
            PlistUid(0xFFFFFFFFL),
            BinaryPlist.decode(craftBplist(listOf(bytes(0x87, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF)))),
        )
        assertRejected(craftBplist(listOf(bytes(0x84, 1, 0, 0, 0, 0))))
        assertRejected(craftBplist(listOf(bytes(0x88) + ByteArray(9))))
    }

    @Test
    fun `wide references and offsets decode`() {
        val plist =
            craftBplist(listOf(bytes(0xA1, 0, 0, 0, 1), trueObject), refSize = 4, offsetSize = 8)
        assertEquals(PlistArray(listOf(PlistBool(true))), BinaryPlist.decode(plist))
        val odd = craftBplist(listOf(bytes(0xA1, 0, 0, 1), trueObject), refSize = 3, offsetSize = 3)
        assertEquals(PlistArray(listOf(PlistBool(true))), BinaryPlist.decode(odd))
    }
}
