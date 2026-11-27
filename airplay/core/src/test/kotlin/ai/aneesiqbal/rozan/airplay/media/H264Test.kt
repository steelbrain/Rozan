package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264Test {
    @Test
    fun `avcC from 03d D becomes csd-0 then csd-1`() {
        val avcC = hex(D_AVCC)
        val sets = assertNotNull(H264.parseAvcC(avcC, 0, avcC.size))
        assertEquals(
            "0000000167" + "64c028ac56200d814fe59b81010101" + "0000000128ee3cb0",
            sets.toHex(),
        )
    }

    @Test
    fun `avcC is read at an offset and trailing bytes are ignored`() {
        val avcC = hex("aaaa" + D_AVCC + "fdf8f800")
        val sets = assertNotNull(H264.parseAvcC(avcC, 2, avcC.size - 2))
        assertEquals(28, sets.size)
    }

    @Test
    fun `several SPS and PPS are all kept in order`() {
        val avcC = hex("0164c028ffe2" + "000267aa" + "000367bbcc" + "02" + "000168" + "00026811")
        val sets = assertNotNull(H264.parseAvcC(avcC, 0, avcC.size))
        assertEquals("0000000167aa" + "0000000167bbcc" + "0000000168" + "000000016811", sets.toHex())
    }

    @Test
    fun `avcC bounds reject bad records`() {
        val bad =
            listOf(
                "",
                "0164c028ffe1",
                // version 2
                "0264c028ffe1000467640028010004" + "28ee3cb0",
                // lengthSizeMinusOne 1
                "0164c028fde10004676400280100" + "0428ee3cb0",
                // no SPS
                "0164c028ffe001000428ee3cb0",
                // no PPS
                "0164c028ffe100046764002800",
                // SPS of length 0
                "0164c028ffe10000010004" + "28ee3cb0",
                // SPS longer than the record
                "0164c028ffe100ff676400",
                // PPS count missing
                "0164c028ffe10004" + "67640028",
                // PPS truncated
                "0164c028ffe10004" + "67640028" + "01" + "0005" + "28ee",
            )
        for (record in bad) {
            val bytes = hex(record)
            assertNull(H264.parseAvcC(bytes, 0, bytes.size), "record $record")
        }
    }

    @Test
    fun `parameter sets longer than the bounds are rejected`() {
        val longSps = "0201" + "67" + "00".repeat(512)
        val tooLong = hex("0164c028ffe1$longSps" + "01" + "000428ee3cb0")
        assertNull(H264.parseAvcC(tooLong, 0, tooLong.size), "SPS of 513 bytes")
        // Five SPS of 500 bytes each: 2500 bytes in total, over the 2048-byte budget.
        val sps500 = "01f4" + "67" + "00".repeat(499)
        val total = hex("0164c028ffe5" + sps500.repeat(5) + "01" + "000428ee3cb0")
        assertNull(H264.parseAvcC(total, 0, total.size), "2500 parameter-set bytes")
        val fits = hex("0164c028ffe4" + sps500.repeat(4) + "01" + "000428ee3cb0")
        assertNotNull(H264.parseAvcC(fits, 0, fits.size), "2004 parameter-set bytes")
    }

    @Test
    fun `hvc1 marks an HEVC configuration`() {
        val hevc = hex("01016000" + "68766331" + "00".repeat(20))
        assertTrue(H264.isHevcConfig(hevc, 0, hevc.size))
        val avcC = hex(D_AVCC)
        assertFalse(H264.isHevcConfig(avcC, 0, avcC.size))
        assertFalse(H264.isHevcConfig(hevc, 0, 7))
    }

    @Test
    fun `AVCC access units become Annex-B in place`() {
        // 03d §B packet 1 plaintext: one 17-byte IDR NAL.
        val unit = hex("0000001165888400210102030405060708090a0b0c")
        val mask = H264.avccToAnnexB(unit, 0, unit.size)
        assertEquals(1 shl 5, mask)
        assertEquals("00000001658884", unit.copyOf(7).toHex())
    }

    @Test
    fun `several NALs are rewritten and typed`() {
        val unit = hex("0000000206ff" + "0000000365aabb" + "0000000141")
        val copy = unit.copyOf()
        val mask = H264.avccToAnnexB(unit, 0, unit.size)
        assertEquals((1 shl 6) or (1 shl 5) or (1 shl 1), mask)
        assertEquals("0000000106ff" + "0000000165aabb" + "0000000141", unit.toHex())
        assertEquals(copy.size, unit.size)
    }

    @Test
    fun `bad NAL walks fail`() {
        val bad =
            listOf(
                // empty
                "",
                // length 0
                "00000000",
                // length past the end
                "0000000565aabb",
                // leftover bytes too short for a length
                "0000000165" + "0000",
                // forbidden bit set
                "00000002e5aa",
                // negative length
                "ffffffff65",
                // header only
                "00000001",
            )
        for (unit in bad) {
            val bytes = hex(unit)
            assertEquals(0, H264.avccToAnnexB(bytes, 0, bytes.size), "unit $unit")
        }
    }

    @Test
    fun `the walk honours offset and length`() {
        val bytes = hex("ffff" + "0000000141" + "ffff")
        assertEquals(1 shl 1, H264.avccToAnnexB(bytes, 2, 5))
        assertEquals("ffff0000000141ffff", bytes.toHex())
    }

    private companion object {
        const val D_AVCC = "0164c028ffe100106764c028ac56200d814fe59b8101010101000428ee3cb0"
    }
}
