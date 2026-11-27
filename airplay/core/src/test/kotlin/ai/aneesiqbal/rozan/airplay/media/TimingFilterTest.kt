package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimingFilterTest {
    @Test
    fun `request and reply match 03d E`() {
        val filter = TimingFilter(T1)
        assertEquals(0L, filter.baseNanos)
        val request = ByteArray(32)
        assertTrue(filter.writeRequest(request, 0, T1))
        assertEquals(E_REQUEST, request.toHex())
        assertFalse(filter.isSynced)

        val reply = hex(E_REPLY)
        assertTrue(filter.onReply(reply, 0, reply.size, T4))
        assertTrue(filter.isSynced)
        assertEquals(2_209_040_871_397_999_999L, filter.offsetNanos)
        assertEquals(3_999_999L, filter.roundTripNanos)
        assertEquals(1, filter.acceptedReplies)
    }

    @Test
    fun `a reply is read at an offset`() {
        val filter = TimingFilter(T1)
        filter.writeRequest(ByteArray(32), 0, T1)
        val reply = hex("ffff$E_REPLY")
        assertTrue(filter.onReply(reply, 2, 32, T4))
        assertEquals(2_209_040_871_397_999_999L, filter.offsetNanos)
    }

    @Test
    fun `replies to unknown or already answered requests are dropped`() {
        val filter = TimingFilter(T1)
        val reply = hex(E_REPLY)
        assertFalse(filter.onReply(reply, 0, 32, T4), "no request outstanding")
        filter.writeRequest(ByteArray(32), 0, T1)
        assertTrue(filter.onReply(reply, 0, 32, T4))
        assertFalse(filter.onReply(reply, 0, 32, T4), "the request was answered already")
    }

    @Test
    fun `a repeated T3 is dropped even for a new request`() {
        val filter = TimingFilter(T1)
        filter.writeRequest(ByteArray(32), 0, T1)
        assertTrue(filter.onReply(hex(E_REPLY), 0, 32, T4))
        filter.writeRequest(ByteArray(32), 0, T1)
        assertFalse(filter.onReply(hex(E_REPLY), 0, 32, T4))
    }

    @Test
    fun `malformed replies are dropped`() {
        val filter = TimingFilter(T1)
        filter.writeRequest(ByteArray(32), 0, T1)
        val reply = hex(E_REPLY)
        assertFalse(filter.onReply(reply, 0, 31, T4), "short")
        val wrongType = reply.copyOf().also { it[1] = 0xD2.toByte() }
        assertFalse(filter.onReply(wrongType, 0, 32, T4), "a request, not a reply")
        assertTrue(filter.onReply(reply, 0, 32, T4), "the request is still outstanding")
    }

    @Test
    fun `delays outside 0 to 1 s are dropped`() {
        val filter = TimingFilter(T1)
        filter.writeRequest(ByteArray(32), 0, T1)
        // T4 = T1 + 150 µs equals the client turnaround: zero delay.
        assertFalse(filter.onReply(hex(E_REPLY), 0, 32, T1 + 150_000))
        filter.writeRequest(ByteArray(32), 0, T1 + 1)
        val late = reply(origin = T1 + 1, t2 = 2_209_045_871_400_000_000L, t3 = 2_209_045_871_400_000_000L)
        assertFalse(filter.onReply(late, 0, 32, T1 + 1 + 1_000_000_001L), "delay over 1 s")
        assertFalse(filter.isSynced)
    }

    @Test
    fun `only the last eight requests are matched`() {
        val filter = TimingFilter(T1)
        for (i in 0 until 9) filter.writeRequest(ByteArray(32), 0, T1 + i * 1_000_000L)
        val oldest = reply(origin = T1, t2 = CLIENT, t3 = CLIENT)
        assertFalse(filter.onReply(oldest, 0, 32, T1 + 10_000_000L))
        val newest = reply(origin = T1 + 8_000_000L, t2 = CLIENT, t3 = CLIENT + 1)
        assertTrue(filter.onReply(newest, 0, 32, T1 + 10_000_000L))
    }

    @Test
    fun `the smallest delay wins and the estimate is smoothed`() {
        val filter = TimingFilter(T1)
        val first = exchange(filter, local = T1, offset = THETA, delay = 4_000_000)
        assertEquals(first.offset, filter.offsetNanos, "the first estimate is used directly")
        assertEquals(first.delay, filter.roundTripNanos)

        val better = exchange(filter, local = T1 + 1_000_000_000L, offset = THETA + 2_000, delay = 2_000_000)
        val afterBetter = first.offset + (better.offset - first.offset) / 20
        assertEquals(afterBetter, filter.offsetNanos)
        assertEquals(better.delay, filter.roundTripNanos)

        // A worse sample does not change the best one; the estimate keeps moving towards it.
        exchange(filter, local = T1 + 2_000_000_000L, offset = THETA + 1_000_000, delay = 10_000_000)
        assertEquals(afterBetter + (better.offset - afterBetter) / 20, filter.offsetNanos)
        assertEquals(better.delay, filter.roundTripNanos)
    }

    @Test
    fun `old samples leave the filter`() {
        val filter = TimingFilter(T1)
        exchange(filter, local = T1, offset = THETA, delay = 1_000_000)
        for (i in 1..8) exchange(filter, local = T1 + i * 1_000_000_000L, offset = THETA, delay = 5_000_000L + i)
        // The 1 ms sample was pushed out by eight later ones; the best is now 5 ms + 1.
        assertTrue(filter.roundTripNanos in 5_000_000L..5_000_002L, "rtt ${filter.roundTripNanos}")
    }

    @Test
    fun `the base keeps encoded times in the NTP range`() {
        assertEquals(0L, TimingFilter(1_000_000_000L).baseNanos)
        assertEquals(-1_000_000_000L, TimingFilter(0L).baseNanos)
        assertEquals(-6_000_000_000L, TimingFilter(-5_000_000_000L).baseNanos)
        val big = (1L shl 31) * 1_000_000_000L
        assertEquals(big - 1_000_000_000L, TimingFilter(big).baseNanos)

        val negative = TimingFilter(-5_000_000_000L)
        val request = ByteArray(32)
        assertTrue(negative.writeRequest(request, 0, -5_000_000_000L))
        assertEquals(NtpTimestamp.fromNanos(1_000_000_000L).bits, request.i64Be(24))
        assertFalse(negative.writeRequest(request, 0, -7_000_000_000L), "before the base")
    }

    @Test
    fun `a timing request from the sender is answered`() {
        val filter = TimingFilter(T1)
        val request = hex(E_REQUEST)
        val answer = ByteArray(32)
        assertTrue(filter.writeAnswer(request, 0, 32, T1 + 1_000, T1 + 2_000, answer))
        assertEquals("80d3000700000000", answer.copyOf(8).toHex())
        assertEquals(0x00001388_00000000L, answer.i64Be(8))
        assertEquals(NtpTimestamp.fromNanos(T1 + 1_000).bits, answer.i64Be(16))
        assertEquals(NtpTimestamp.fromNanos(T1 + 2_000).bits, answer.i64Be(24))
        assertFalse(filter.writeAnswer(request, 0, 31, T1, T1, answer))
    }

    private class Exchange(
        val offset: Long,
        val delay: Long,
    )

    /** Runs one request and reply with the given true offset and delay; returns what the filter should see. */
    private fun exchange(
        filter: TimingFilter,
        local: Long,
        offset: Long,
        delay: Long,
    ): Exchange {
        val request = ByteArray(32)
        assertTrue(filter.writeRequest(request, 0, local))
        val t2Bits = NtpTimestamp.fromNanos(local + offset + delay / 2).bits
        val t3Bits = NtpTimestamp.fromNanos(local + offset + delay / 2 + 100_000).bits
        val t4 = local + delay + 100_000
        val reply = replyBits(request.i64Be(24), t2Bits, t3Bits)
        assertTrue(filter.onReply(reply, 0, 32, t4))
        val t2 = NtpTimestamp(t2Bits).toNanos()
        val t3 = NtpTimestamp(t3Bits).toNanos()
        return Exchange(((t2 - local) + (t3 - t4)) / 2, (t4 - local) - (t3 - t2))
    }

    private fun reply(
        origin: Long,
        t2: Long,
        t3: Long,
    ): ByteArray =
        replyBits(NtpTimestamp.fromNanos(origin).bits, NtpTimestamp.fromNanos(t2).bits, NtpTimestamp.fromNanos(t3).bits)

    private fun replyBits(
        origin: Long,
        t2: Long,
        t3: Long,
    ): ByteArray {
        val out = hex("80d3000700000000" + "00".repeat(24))
        out.putI64Be(8, origin)
        out.putI64Be(16, t2)
        out.putI64Be(24, t3)
        return out
    }

    private companion object {
        const val T1 = 5_000_000_000_000L
        const val T4 = 5_000_004_149_999L
        const val THETA = 2_209_040_871_397_999_999L
        const val CLIENT = 2_209_045_871_400_000_000L
        const val E_REQUEST = "80d2000700000000" + "0000000000000000" + "0000000000000000" + "0000138800000000"
        const val E_REPLY = "80d3000700000000" + "0000138800000000" + "83ab5d6f66666666" + "83ab5d6f66703afb"
    }
}
