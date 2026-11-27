package ai.aneesiqbal.rozan.airplay.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtpTest {
    @Test
    fun `timestamps wrap at 2^32`() {
        val max = RtpTimestamp.of(0xFFFF_FFFFL)
        assertEquals(RtpTimestamp.of(351), max + 352)
        assertEquals(max, RtpTimestamp.of(351) - 352)
        assertEquals(352, RtpTimestamp.of(351) - max)
        assertEquals(-352, max - RtpTimestamp.of(351))
        assertTrue(RtpTimestamp.of(351).isAfter(max))
        assertFalse(max.isAfter(RtpTimestamp.of(351)))
        assertEquals(0xFFFF_FFFFL, max.value)
        assertEquals(-1, max.bits)
        assertFailsWith<IllegalArgumentException> { RtpTimestamp.of(1L shl 32) }
        assertFailsWith<IllegalArgumentException> { RtpTimestamp.of(-1) }
    }

    @Test
    fun `serial distance holds up to 2^31 - 1 frames`() {
        val a = RtpTimestamp.of(10)
        assertEquals(Int.MAX_VALUE, (a + Int.MAX_VALUE) - a)
        assertEquals(Int.MIN_VALUE, (a + Int.MIN_VALUE) - a)
    }

    /** docs/protocol/03d-media-test-vectors.md §F: sync packet latency and play time. */
    @Test
    fun `spec sync packet vector`() {
        val rtpPlay = RtpTimestamp(0x12340000)
        val rtpNow = RtpTimestamp(0x12352d77)
        assertEquals(305_397_760L, rtpPlay.value)
        assertEquals(77_175, rtpNow - rtpPlay)
        val anchorClientNs = NtpTimestamp(0x83ab5d70_66666666uL.toLong()).toNanos()
        val frame = rtpPlay + 66_150
        assertEquals(305_463_910L, frame.value)
        val clientPlayNs = anchorClientNs + RtpTimestamp.framesToNanos((frame - rtpPlay).toLong(), 44_100)
        assertEquals(2_209_045_873_899_999_999L, clientPlayNs)
        val theta = 2_209_040_871_397_999_999L
        assertEquals(5_002_502_000_000L, clientPlayNs - theta)
    }

    @Test
    fun `frames convert to nanoseconds without overflow`() {
        assertEquals(1_500_000_000L, RtpTimestamp.framesToNanos(66_150, 44_100))
        assertEquals(22_675L, RtpTimestamp.framesToNanos(1, 44_100))
        assertEquals(-22_675L, RtpTimestamp.framesToNanos(-1, 44_100))
        assertEquals(7_981_859_410L, RtpTimestamp.framesToNanos(352_000_000L / 1000, 44_100))
        // 2e10 frames: frames * 1e9 alone would overflow a Long, the result does not.
        val frames = 20_000_000_000L
        val expected =
            java.math.BigInteger
                .valueOf(frames)
                .multiply(java.math.BigInteger.valueOf(1_000_000_000L))
        val exact = expected.divide(java.math.BigInteger.valueOf(44_100)).toLong()
        assertEquals(exact, RtpTimestamp.framesToNanos(frames, 44_100))
        assertFailsWith<IllegalArgumentException> { RtpTimestamp.framesToNanos(1, 0) }
    }

    @Test
    fun `sequence numbers wrap at 65536`() {
        val max = RtpSequenceNumber(65_535)
        assertEquals(RtpSequenceNumber(0), max + 1)
        assertEquals(max, RtpSequenceNumber(0) - 1)
        assertEquals(1, RtpSequenceNumber(0) - max)
        assertEquals(-1, max - RtpSequenceNumber(0))
        assertEquals(32_767, RtpSequenceNumber(32_767) - RtpSequenceNumber(0))
        assertEquals(-32_768, RtpSequenceNumber(32_768) - RtpSequenceNumber(0))
        assertTrue(RtpSequenceNumber(2).isAfter(max))
        assertFailsWith<IllegalArgumentException> { RtpSequenceNumber(65_536) }
        assertFailsWith<IllegalArgumentException> { RtpSequenceNumber(-1) }
    }

    /** docs/protocol/03d-media-test-vectors.md §G: sequence numbers 258 to 260 missing. */
    @Test
    fun `gap detection with sdiff16`() {
        val tail = RtpSequenceNumber(257)
        val arrived = RtpSequenceNumber(261)
        assertEquals(4, arrived - tail)
        assertEquals(listOf(258, 259, 260), (1 until (arrived - tail)).map { (tail + it).value })
    }

    @Test
    fun `extender grows across wraps and handles reordering`() {
        val extender = RtpTimestampExtender()
        assertEquals(0xFFFF_FE00L, extender.extend(RtpTimestamp.of(0xFFFF_FE00L)))
        assertEquals(0xFFFF_FF60L, extender.extend(RtpTimestamp.of(0xFFFF_FF60L)))
        assertEquals((1L shl 32) + 0xC0, extender.extend(RtpTimestamp.of(0xC0)))
        // A late packet from before the wrap still maps below the wrap point.
        assertEquals(0xFFFF_FF60L, extender.extend(RtpTimestamp.of(0xFFFF_FF60L)))
        assertEquals((1L shl 32) + 0x220, extender.extend(RtpTimestamp.of(0x220)))
        var t = RtpTimestamp.of(0x220)
        var expected = (1L shl 32) + 0x220
        repeat(30_000) {
            t += 352_000
            expected += 352_000
            assertEquals(expected, extender.extend(t))
        }
        assertTrue(expected > 3 * (1L shl 32))
        extender.reset()
        assertEquals(5L, extender.extend(RtpTimestamp.of(5)))
    }
}
