package ai.aneesiqbal.rozan.airplay.rtsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RtspResponseParserTest {
    @Test
    fun `parses status line, headers and body`() {
        val response = parse("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nCSeq: 7\r\n\r\nok").single()
        assertEquals(RtspProtocol.HTTP_1_1, response.protocol)
        assertEquals(200, response.status)
        assertEquals("OK", response.reason)
        assertEquals("7", response.cSeq)
        assertContentEquals(latin1("ok"), response.body)
    }

    @Test
    fun `reason phrase is optional`() {
        assertEquals("", parse("RTSP/1.0 200 \r\n\r\n").single().reason)
        assertEquals("", parse("RTSP/1.0 200\r\n\r\n").single().reason)
        assertEquals("Not Enough Bandwidth", parse("RTSP/1.0 453 Not Enough Bandwidth\r\n\r\n").single().reason)
    }

    @Test
    fun `missing Content-Length means no body`() {
        val responses = parse("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\nRTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n")
        assertEquals(listOf("1", "2"), responses.map { it.cSeq })
        assertTrue(responses.all { it.body.isEmpty() })
    }

    @Test
    fun `1xx, 204 and 304 responses have no body`() {
        for (status in listOf(100, 101, 204, 304)) {
            val text = "HTTP/1.1 $status X\r\nContent-Length: 0\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nz"
            val responses = parse(text)
            assertEquals(listOf(status, 200), responses.map { it.status })
            assertEquals(0, responses[0].body.size)
        }
    }

    @Test
    fun `malformed status lines are rejected`() {
        val bad =
            listOf(
                "HTTP/1.1\r\n",
                " 200 OK\r\n",
                "HTTP/1.1 20 OK\r\n",
                "HTTP/1.1 2000 OK\r\n",
                "HTTP/1.1 2x0 OK\r\n",
                "HTTP/1.1 099 OK\r\n",
                "HTTP/1.1  200 OK\r\n",
                "HTTP/1.1 200 O\u0000K\r\n",
                "http/1.1 200 OK\r\n",
            )
        for (line in bad) {
            assertEquals(400, rejectStatus("$line\r\n"), line.trim())
        }
        assertEquals(505, rejectStatus("HTTP/1.0 200 OK\r\n\r\n"))
    }

    @Test
    fun `responses split at every boundary parse identically`() {
        val bytes = latin1("RTSP/1.0 200 OK\r\nCSeq: 5\r\nContent-Length: 4\r\n\r\nbody")
        for (split in 0..bytes.size) {
            val parser = RtspResponseParser()
            val responses = parser.feed(bytes, 0, split) + parser.feed(bytes, split, bytes.size - split)
            assertEquals("5", responses.single().cSeq, "split at $split")
            assertContentEquals(latin1("body"), responses.single().body)
            parser.finish()
        }
    }

    @Test
    fun `limits apply to responses`() {
        assertEquals(413, rejectStatus("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n", RtspLimits(maxBodyBytes = 10)))
        assertEquals(414, rejectStatus("HTTP/1.1 200 ${"x".repeat(20)}\r\n\r\n", RtspLimits(maxStartLineBytes = 20)))
        assertEquals(501, rejectStatus("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"))
    }

    @Test
    fun `cut-off response fails at finish`() {
        val parser = RtspResponseParser()
        parser.feed(latin1("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\na"))
        assertFailsWith<RtspException> { parser.finish() }
    }

    @Test
    fun `built requests and responses round-trip through the parsers`() {
        val body = ByteArray(300) { it.toByte() }
        val request =
            RtspRequest
                .Builder("SET_PARAMETER", "rtsp://[fe80::1%25en0]:7000/1", RtspProtocol.RTSP_1_0)
                .header("CSeq", "9")
                .header("X-Latin", "café\tbar")
                .body(body, "application/octet-stream")
                .build()
        val parsed = parseOne(request.toByteArray().toString(Charsets.ISO_8859_1))
        assertEquals(request.method, parsed.method)
        assertEquals(request.target, parsed.target)
        assertEquals("café\tbar", parsed.headers["x-latin"])
        assertContentEquals(body, parsed.body)

        val response = RtspResponse.builder(parsed, 200).body(body).build()
        val parser = RtspResponseParser()
        val back = parser.feed(response.toByteArray()).single()
        assertEquals(200, back.status)
        assertEquals("OK", back.reason)
        assertEquals("9", back.cSeq)
        assertContentEquals(body, back.body)
    }

    private fun parse(text: String): List<RtspResponse> {
        val parser = RtspResponseParser()
        val responses = parser.feed(latin1(text))
        parser.finish()
        return responses
    }

    private fun rejectStatus(
        text: String,
        limits: RtspLimits = RtspLimits(),
    ): Int {
        val parser = RtspResponseParser(limits)
        return assertFailsWith<RtspException>(text.take(40)) {
            parser.feed(latin1(text))
            parser.finish()
        }.status
    }
}
