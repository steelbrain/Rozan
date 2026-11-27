package ai.aneesiqbal.rozan.airplay.rtsp

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RtspReaderTest {
    private val two =
        "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n" +
            "POST /fp-setup RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 4\r\n\r\n\u0000\u0001\u0002\u0003"

    @Test
    fun `reads queued requests then null at a clean end`() {
        val reader = RtspRequestReader(ByteArrayInputStream(latin1(two)))
        assertEquals("OPTIONS", reader.read()?.method)
        val post = reader.read()
        assertEquals("/fp-setup", post?.target)
        assertContentEquals(byteArrayOf(0, 1, 2, 3), post?.body)
        assertNull(reader.read())
        assertNull(reader.read())
    }

    @Test
    fun `reads from a stream that returns one byte per read`() {
        val reader = RtspRequestReader(Trickle(latin1(two + two)), bufferSize = 3)
        assertEquals(listOf("1", "2", "1", "2"), List(4) { reader.read()?.cSeq })
        assertNull(reader.read())
    }

    @Test
    fun `empty stream yields null`() {
        assertNull(RtspRequestReader(ByteArrayInputStream(ByteArray(0))).read())
    }

    @Test
    fun `stream ending mid-request fails`() {
        val bytes = latin1(two)
        for (cut in listOf(1, 20, 33, bytes.size - 1)) {
            val reader = RtspRequestReader(ByteArrayInputStream(bytes, 0, cut))
            assertFailsWith<RtspException>("cut at $cut") {
                while (reader.read() != null) {
                    // Drain until the truncated request fails.
                }
            }
        }
    }

    @Test
    fun `garbage from the stream fails with RtspException`() {
        val reader = RtspRequestReader(ByteArrayInputStream(latin1("\u0000\u0001garbage\r\n\r\n")))
        assertEquals(400, assertFailsWith<RtspException> { reader.read() }.status)
    }

    @Test
    fun `response reader reads responses`() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhiHTTP/1.1 500 Internal Server Error\r\n\r\n"
        val reader = RtspResponseReader(Trickle(latin1(wire)), RtspLimits(maxBodyBytes = 16))
        assertEquals(200, reader.read()?.status)
        assertEquals(500, reader.read()?.status)
        assertNull(reader.read())
    }

    @Test
    fun `buffer size must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            RtspRequestReader(
                ByteArrayInputStream(ByteArray(0)),
                bufferSize = 0,
            )
        }
    }

    /** Returns at most one byte per read, like a slow network. */
    private class Trickle(
        private val bytes: ByteArray,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else -1

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            val b = read()
            if (b < 0) return -1
            buffer[offset] = b.toByte()
            return 1
        }
    }
}
