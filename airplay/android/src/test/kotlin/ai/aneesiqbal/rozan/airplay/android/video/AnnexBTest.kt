package ai.aneesiqbal.rozan.airplay.android.video

import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun findsThreeAndFourByteStartCodes() {
        val data = bytes(0, 0, 1, 0x67, 0xAA, 0, 0, 0, 1, 0x68, 0xBB)
        assertEquals(3, AnnexB.nextNalStart(data, 0, data.size))
        assertEquals(9, AnnexB.nextNalStart(data, 4, data.size))
        assertEquals(-1, AnnexB.nextNalStart(data, 10, data.size))
    }

    @Test
    fun respectsTheEndBound() {
        val data = bytes(0xFF, 0, 0, 1, 0x65)
        assertEquals(-1, AnnexB.nextNalStart(data, 0, 3))
        assertEquals(4, AnnexB.nextNalStart(data, 0, data.size))
    }

    @Test
    fun detectsAvcIdrAfterParameterSetsAndSei() {
        val idr = bytes(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x06, 5, 0, 0, 1, 0x65, 0x88)
        val slice = bytes(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 1, 0x41, 0x9A)
        assertTrue(AnnexB.isKeyFrame(VideoCodec.H264, idr, 0, idr.size))
        assertFalse(AnnexB.isKeyFrame(VideoCodec.H264, slice, 0, slice.size))
    }

    @Test
    fun detectsHevcIrap() {
        // HEVC NAL header type is bits 1..6 of the first byte: IDR_W_RADL = 19 -> 0x26, TRAIL_R = 1 -> 0x02.
        val idr = bytes(0, 0, 0, 1, 0x40, 0x01, 0, 0, 1, 0x26, 0x01, 0xAF)
        val trail = bytes(0, 0, 0, 1, 0x02, 0x01, 0xD0)
        val cra = bytes(0, 0, 1, 0x2A, 0x01, 0xAA)
        assertTrue(AnnexB.isKeyFrame(VideoCodec.HEVC, idr, 0, idr.size))
        assertFalse(AnnexB.isKeyFrame(VideoCodec.HEVC, trail, 0, trail.size))
        assertTrue(AnnexB.isKeyFrame(VideoCodec.HEVC, cra, 0, cra.size))
    }

    @Test
    fun keyFrameScanHonoursOffsetAndLength() {
        val data = bytes(0, 0, 1, 0x65, 0x11, 0, 0, 1, 0x41, 0x22)
        assertTrue(AnnexB.isKeyFrame(VideoCodec.H264, data, 0, 5))
        assertFalse(AnnexB.isKeyFrame(VideoCodec.H264, data, 5, 5))
    }

    @Test
    fun garbageAndTruncatedInputAreNotKeyFrames() {
        val random = java.util.Random(7)
        repeat(500) {
            val data = ByteArray(random.nextInt(64)).also(random::nextBytes)
            // Must not throw, whatever the bytes are.
            AnnexB.isKeyFrame(VideoCodec.H264, data, 0, data.size)
            AnnexB.isKeyFrame(VideoCodec.HEVC, data, 0, data.size)
            AnnexB.splitAvcParameterSets(data)
        }
        assertFalse(AnnexB.isKeyFrame(VideoCodec.H264, bytes(0, 0, 1), 0, 3))
        assertFalse(AnnexB.isKeyFrame(VideoCodec.H264, ByteArray(0), 0, 0))
    }

    @Test
    fun splitsAvcParameterSets() {
        val sets = bytes(0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x1F, 0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80)
        val (sps, pps) = AnnexB.splitAvcParameterSets(sets)!!
        assertArrayEquals(bytes(0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x1F), sps)
        assertArrayEquals(bytes(0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80), pps)
    }

    @Test
    fun splitNeedsBothSpsAndPps() {
        assertNull(AnnexB.splitAvcParameterSets(bytes(0, 0, 0, 1, 0x67, 0x42)))
        assertNull(AnnexB.splitAvcParameterSets(ByteArray(0)))
    }
}
