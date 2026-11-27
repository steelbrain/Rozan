package ai.aneesiqbal.rozan.airplay.time

import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NtpTimestampTest {
    @Test
    fun `fields split the 64 bits`() {
        val t = NtpTimestamp(0x83ab5d6f_66666666uL.toLong())
        assertEquals(0x83ab5d6fL, t.seconds)
        assertEquals(0x66666666L, t.fraction)
        assertEquals(t, NtpTimestamp.of(0x83ab5d6fL, 0x66666666L))
        assertEquals(0xFFFF_FFFFL, NtpTimestamp(-1).seconds)
        assertEquals(0xFFFF_FFFFL, NtpTimestamp(-1).fraction)
    }

    /** docs/protocol/03d-media-test-vectors.md §E: the timing exchange. */
    @Test
    fun `spec timing exchange vector`() {
        val t1 = NtpTimestamp(0x00001388_00000000L)
        val t2 = NtpTimestamp(0x83ab5d6f_66666666uL.toLong())
        val t3 = NtpTimestamp(0x83ab5d6f_66703afbuL.toLong())
        assertEquals(t1, NtpTimestamp.fromNanos(5_000_000_000_000L))
        assertEquals(5_000_000_000_000L, t1.toNanos())
        assertEquals(2_209_045_871_399_999_999L, t2.toNanos())
        assertEquals(2_209_045_871_400_149_999L, t3.toNanos())
        val t4 = 5_000_004_149_999L
        val delay = (t4 - t1.toNanos()) - (t3.toNanos() - t2.toNanos())
        val offset = ((t2.toNanos() - t1.toNanos()) + (t3.toNanos() - t4)) / 2
        assertEquals(3_999_999L, delay)
        assertEquals(2_209_040_871_397_999_999L, offset)
    }

    /** docs/protocol/03d-media-test-vectors.md §F and §D: sync packet time and video timestamp. */
    @Test
    fun `spec sync and video timestamp vectors`() {
        assertEquals(2_209_045_872_399_999_999L, NtpTimestamp(0x83ab5d70_66666666uL.toLong()).toNanos())
        val video = NtpTimestamp(0x0000deef_599f9a1dL)
        assertEquals(57_071_350_091_583L, video.toNanos())
        val clientDomain = video.toNanos() + NtpTimestamp.UNIX_EPOCH_OFFSET_SECONDS * NtpTimestamp.NANOS_PER_SECOND
        assertEquals(2_209_045_871_350_091_583L, clientDomain)
    }

    @Test
    fun `nanosecond conversion truncates and round-trips within one nanosecond`() {
        assertEquals(0L, NtpTimestamp(0).toNanos())
        assertEquals(NtpTimestamp(0), NtpTimestamp.fromNanos(0))
        // The largest fraction is just under one second.
        assertEquals(999_999_999L, NtpTimestamp(0xFFFF_FFFFL).toNanos())
        assertEquals(NtpTimestamp.NANOS_LIMIT - 1, NtpTimestamp(-1).toNanos())
        assertEquals(0xFFFF_FFFFL, NtpTimestamp.fromNanos(NtpTimestamp.NANOS_LIMIT - 1).seconds)
        assertEquals(NtpTimestamp.of(1, 1L shl 31), NtpTimestamp.fromNanos(1_500_000_000L))
        val random = Random(42)
        repeat(100_000) {
            val nanos = random.nextLong(0, NtpTimestamp.NANOS_LIMIT)
            val back = NtpTimestamp.fromNanos(nanos).toNanos()
            assertTrue(back == nanos || back == nanos - 1, "$nanos -> $back")
            val bits = random.nextLong()
            val again = NtpTimestamp.fromNanos(NtpTimestamp(bits).toNanos())
            // Two truncations, each worth up to one nanosecond (about 4.3 fraction units).
            assertTrue(NtpTimestamp(bits).bits - again.bits in 0..5, "bits $bits")
        }
    }

    @Test
    fun `out of range input is rejected`() {
        assertFailsWith<IllegalArgumentException> { NtpTimestamp.fromNanos(-1) }
        assertFailsWith<IllegalArgumentException> { NtpTimestamp.fromNanos(NtpTimestamp.NANOS_LIMIT) }
        assertFailsWith<IllegalArgumentException> { NtpTimestamp.of(1L shl 32, 0) }
        assertFailsWith<IllegalArgumentException> { NtpTimestamp.of(0, -1) }
    }

    @Test
    fun `wall clock conversion uses the Unix offset and both eras`() {
        val epoch = NtpTimestamp.of(NtpTimestamp.UNIX_EPOCH_OFFSET_SECONDS, 0)
        assertEquals(0L, epoch.toUnixNanos())
        assertEquals(epoch, NtpTimestamp.fromUnixNanos(0))
        // RFC 5905 figure 4: 2036-02-07T06:28:16Z is the start of era 1, seconds field 0.
        val eraStart = TimeUnit.SECONDS.toNanos(Instant.parse("2036-02-07T06:28:16Z").epochSecond)
        assertEquals(NtpTimestamp(0), NtpTimestamp.fromUnixNanos(eraStart))
        assertEquals(eraStart, NtpTimestamp(0).toUnixNanos())
        val instant = Instant.parse("2026-11-26T12:00:00.25Z")
        val unixNanos = TimeUnit.SECONDS.toNanos(instant.epochSecond) + instant.nano
        val ntp = NtpTimestamp.fromUnixNanos(unixNanos)
        assertEquals(instant.epochSecond + NtpTimestamp.UNIX_EPOCH_OFFSET_SECONDS, ntp.seconds)
        assertEquals(1L shl 30, ntp.fraction)
        assertEquals(unixNanos, ntp.toUnixNanos())
        val before1970 = -TimeUnit.DAYS.toNanos(365) + 500_000_000
        assertEquals(before1970, NtpTimestamp.fromUnixNanos(before1970).toUnixNanos())
        assertFailsWith<IllegalArgumentException> { NtpTimestamp.fromUnixNanos(Long.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { NtpTimestamp.fromUnixNanos(Long.MAX_VALUE) }
    }
}
