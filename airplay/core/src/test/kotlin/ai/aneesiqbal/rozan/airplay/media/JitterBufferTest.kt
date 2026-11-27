package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JitterBufferTest {
    private val buffer = JitterBuffer()

    private fun insert(
        sequence: Int,
        payload: ByteArray = byteArrayOf(sequence.toByte()),
    ): Int = buffer.insert(sequence and 0xFFFF, (sequence * SPF), payload, 0, payload.size)

    /** Plays everything present in order and returns the sequence numbers. */
    private fun drain(): List<Int> {
        val played = mutableListOf<Int>()
        while (!buffer.isEmpty) {
            if (buffer.isHeadPresent()) played += buffer.head
            buffer.advance()
        }
        return played
    }

    @Test
    fun `packets in order are stored and played`() {
        for (s in 100..104) assertTrue(insert(s) >= 0)
        assertEquals(100, buffer.head)
        assertEquals(104, buffer.tail)
        val index = buffer.headIndex()
        assertEquals("64", buffer.data.copyOfRange(buffer.slotOffset(index), buffer.slotOffset(index) + 1).toHex())
        assertEquals(100 * SPF, buffer.timestamp(index))
        assertEquals(1, buffer.length(index))
        assertEquals(listOf(100, 101, 102, 103, 104), drain())
    }

    @Test
    fun `duplicates and late packets are dropped`() {
        insert(10)
        insert(11)
        assertEquals(JitterBuffer.DUPLICATE, insert(11))
        buffer.advance()
        assertEquals(JitterBuffer.LATE, insert(10))
        assertEquals(JitterBuffer.LATE, insert(9))
    }

    @Test
    fun `a gap is reported and reordered packets fill it`() {
        insert(257)
        insert(261)
        assertEquals(258, buffer.gapFirst)
        assertEquals(3, buffer.gapCount)
        insert(259)
        assertEquals(0, buffer.gapCount, "a packet inside the window opens no gap")
        assertEquals(261, buffer.tail)
        assertTrue(buffer.isPresent(259))
        assertFalse(buffer.isPresent(258))
        assertEquals(2, buffer.presentAfterHead(5))
        assertEquals(listOf(257, 259, 261), drain())
    }

    @Test
    fun `missing packets get timestamps from the tail`() {
        insert(20)
        insert(25)
        assertEquals(21 * SPF, buffer.estimateTimestamp(21, SPF))
        assertEquals(20 * SPF, buffer.estimateTimestamp(20, SPF))
    }

    @Test
    fun `sequence numbers wrap at 65536`() {
        insert(65_534)
        insert(65_535)
        insert(0)
        insert(2)
        assertEquals(1, buffer.gapFirst)
        assertEquals(1, buffer.gapCount)
        assertEquals(listOf(65_534, 65_535, 0, 2), drain())
    }

    @Test
    fun `a jump outside the window resets the buffer`() {
        insert(1_000)
        insert(1_001)
        assertTrue(insert(1_000 + 512) >= 0)
        assertTrue(buffer.wasReset)
        assertEquals(1_512, buffer.head)
        assertEquals(listOf(1_512), drain())

        insert(5_000)
        assertTrue(insert(5_000 - 600) >= 0, "far behind also means a restart")
        assertTrue(buffer.wasReset)
        assertEquals(4_400, buffer.head)
    }

    @Test
    fun `after draining, older packets are late and the next one may leave a gap`() {
        insert(30)
        insert(31)
        drain()
        assertTrue(buffer.isEmpty)
        assertEquals(JitterBuffer.LATE, insert(31), "a third copy of a played packet")
        assertTrue(insert(34) >= 0)
        assertEquals(32, buffer.head, "packets 32 and 33 are awaited")
        assertEquals(32, buffer.gapFirst)
        assertEquals(2, buffer.gapCount)
    }

    @Test
    fun `entries can be excluded from playout`() {
        val index = insert(40)
        assertTrue(buffer.isPlayable(index))
        buffer.markUnplayable(index)
        assertFalse(buffer.isPlayable(index))
        assertTrue(buffer.isHeadPresent())
    }

    @Test
    fun `flush without RTP-Info drops everything`() {
        for (s in 0..9) insert(s)
        buffer.flush(null, null)
        assertTrue(buffer.isEmpty)
        assertTrue(insert(3) >= 0, "no floor without a sequence number")
    }

    @Test
    fun `flush drops what is before both the sequence number and the timestamp`() {
        for (s in 0..9) insert(s)
        buffer.flush(6, 6 * SPF)
        assertEquals(listOf(6, 7, 8, 9), drain())
    }

    @Test
    fun `flush keeps packets that are only before one of the two`() {
        for (s in 0..9) insert(s)
        // Sequence numbers before 6 but timestamps before 3 only: 3, 4 and 5 stay.
        buffer.flush(6, 3 * SPF)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9), drain())
    }

    @Test
    fun `flush to a point past the buffer sets the next expected sequence number`() {
        for (s in 0..9) insert(s)
        buffer.flush(20, null)
        assertTrue(buffer.isEmpty)
        assertEquals(JitterBuffer.LATE, insert(15))
        assertTrue(insert(21) >= 0)
        assertEquals(20, buffer.head)
    }

    @Test
    fun `flush waits for the packets from the RTP-Info sequence number`() {
        for (s in 0..9) insert(s)
        insert(14)
        buffer.flush(12, null)
        assertEquals(12, buffer.head)
        assertEquals(14, buffer.tail)
        assertFalse(buffer.isHeadPresent())
    }

    @Test
    fun `the whole ring can be used`() {
        for (s in 0 until MediaLimits.JITTER_SLOTS) assertTrue(insert(s) >= 0)
        assertEquals(MediaLimits.JITTER_SLOTS - 1, buffer.tail)
        assertEquals(MediaLimits.JITTER_SLOTS, drain().size)
    }

    @Test
    fun `serial arithmetic matches sdiff16`() {
        assertEquals(1, JitterBuffer.serial16(0, 65_535))
        assertEquals(-1, JitterBuffer.serial16(65_535, 0))
        assertEquals(-32_768, JitterBuffer.serial16(32_768, 0))
        assertEquals(32_767, JitterBuffer.serial16(32_767, 0))
    }

    private companion object {
        const val SPF = 352
    }
}
