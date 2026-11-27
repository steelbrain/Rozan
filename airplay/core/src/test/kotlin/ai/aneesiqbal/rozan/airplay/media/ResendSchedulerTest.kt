package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

class ResendSchedulerTest {
    private val jitter = JitterBuffer()
    private val scheduler = ResendScheduler()
    private val unknown = PlayDeadline { Long.MIN_VALUE }

    private fun insert(sequence: Int) {
        jitter.insert(sequence, sequence * 352, ByteArray(1), 0, 1)
        if (jitter.gapCount > 0) scheduler.onGap(jitter.gapFirst, jitter.gapCount, NOW)
    }

    private fun request(i: Int): String = scheduler.requests.copyOfRange(i * 8, i * 8 + 8).toHex()

    @Test
    fun `a gap is requested as in 03d G`() {
        insert(257)
        insert(261)
        assertEquals(1, scheduler.collect(NOW, jitter, 0, unknown))
        assertEquals("80d5000101020003", request(0))
        assertEquals(0, scheduler.collect(NOW, jitter, 0, unknown), "not again right away")
    }

    @Test
    fun `one retry after RTT plus 20 ms, then never again`() {
        insert(257)
        insert(261)
        val rtt = 5_000_000L
        scheduler.collect(NOW, jitter, rtt, unknown)
        assertEquals(0, scheduler.collect(NOW + rtt + 19_999_999, jitter, rtt, unknown))
        assertEquals(1, scheduler.collect(NOW + rtt + 20_000_000, jitter, rtt, unknown))
        assertEquals("80d5000201020003", request(0), "the counter moves on")
        assertEquals(0, scheduler.collect(NOW + 10 * MS, jitter, rtt, unknown))
        assertEquals(0, scheduler.collect(NOW + 1_000 * MS, jitter, rtt, unknown))
        assertEquals(2L, scheduler.requestsWritten)
    }

    @Test
    fun `a retry asks only for what is still missing`() {
        insert(100)
        insert(106)
        scheduler.collect(NOW, jitter, 0, unknown)
        insert(101)
        insert(105)
        assertEquals(1, scheduler.collect(NOW + 20 * MS, jitter, 0, unknown))
        assertEquals("80d5000200660003", request(0), "102..104")
    }

    @Test
    fun `filled gaps are not requested again`() {
        insert(10)
        insert(13)
        scheduler.collect(NOW, jitter, 0, unknown)
        insert(11)
        insert(12)
        assertEquals(0, scheduler.collect(NOW + 20 * MS, jitter, 0, unknown))
    }

    @Test
    fun `a gap due too soon is not requested`() {
        insert(10)
        insert(12)
        val rtt = 10_000_000L
        val soon = PlayDeadline { NOW + 2 * rtt + 20 * MS }
        assertEquals(0, scheduler.collect(NOW, jitter, rtt, soon))
        val later = PlayDeadline { NOW + 2 * rtt + 20 * MS + 1 }
        insert(14)
        assertEquals(1, scheduler.collect(NOW, jitter, rtt, later))
        assertEquals("80d50001000d0001", request(0), "only the new gap is left")
    }

    @Test
    fun `at most 50 packets are asked for per second`() {
        insert(0)
        insert(61)
        assertEquals(1, scheduler.collect(NOW, jitter, 0, unknown))
        assertEquals("80d5000100010032", request(0), "50 of the 60")
        assertEquals(0, scheduler.collect(NOW + 500 * MS, jitter, 0, unknown), "budget used up")
        assertEquals(1, scheduler.collect(NOW + 1_000 * MS, jitter, 0, unknown))
        assertEquals("80d5000200010032", request(0))
    }

    @Test
    fun `gaps beyond the table are dropped and counted`() {
        var s = 0
        insert(s)
        repeat(MediaLimits.MAX_RESEND_GAPS + 3) {
            s += 2
            insert(s)
        }
        assertEquals(3L, scheduler.gapsDropped)
        val written = scheduler.collect(NOW, jitter, 0, unknown)
        assertEquals(MediaLimits.MAX_RESEND_REQUESTS_PER_BATCH, written)
    }

    @Test
    fun `clear forgets every gap`() {
        insert(1)
        insert(5)
        scheduler.clear()
        assertEquals(0, scheduler.collect(NOW, jitter, 0, unknown))
    }

    private companion object {
        const val MS = 1_000_000L
        const val NOW = 10_000 * MS
    }
}
