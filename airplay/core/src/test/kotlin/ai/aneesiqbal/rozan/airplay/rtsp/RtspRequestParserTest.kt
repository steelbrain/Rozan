package ai.aneesiqbal.rozan.airplay.rtsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtspRequestParserTest {
    private val setup =
        "SETUP rtsp://192.0.2.10/3615082961 RTSP/1.0\r\n" +
            "Content-Length: 5\r\n" +
            "Content-Type: application/x-apple-binary-plist\r\n" +
            "CSeq: 3\r\n" +
            "DACP-ID: 14413BE4996FEA4D\r\n" +
            "User-Agent: AirPlay/690.7.1\r\n" +
            "\r\n" +
            "hello"

    @Test
    fun `parses an RTSP request with headers and body`() {
        val request = parseOne(setup)
        assertEquals("SETUP", request.method)
        assertEquals("rtsp://192.0.2.10/3615082961", request.target)
        assertEquals(RtspProtocol.RTSP_1_0, request.protocol)
        assertEquals("3", request.cSeq)
        assertEquals("application/x-apple-binary-plist", request.headers["content-type"])
        assertContentEquals(latin1("hello"), request.body)
        assertEquals(5, request.headers.size)
    }

    @Test
    fun `parses an HTTP request without a body`() {
        val request = parseOne("GET /info HTTP/1.1\r\nX-Apple-ProtocolVersion: 1\r\n\r\n")
        assertEquals("GET", request.method)
        assertEquals("/info", request.target)
        assertEquals(RtspProtocol.HTTP_1_1, request.protocol)
        assertEquals(0, request.body.size)
        assertNull(request.cSeq)
    }

    @Test
    fun `header lookup ignores case and keeps original case, order and duplicates`() {
        val request = parseOne("OPTIONS * RTSP/1.0\r\ncseq: 1\r\nX-A: one\r\nx-a: two\r\nAccept: */*\r\n\r\n")
        assertEquals("1", request.headers["CSeq"])
        assertEquals("one", request.headers["X-A"])
        assertEquals(listOf("one", "two"), request.headers.getAll("X-a"))
        assertTrue("ACCEPT" in request.headers)
        assertFalse("Missing" in request.headers)
        assertEquals(
            listOf(
                RtspHeader("cseq", "1"),
                RtspHeader("X-A", "one"),
                RtspHeader("x-a", "two"),
                RtspHeader("Accept", "*/*"),
            ),
            request.headers.toList(),
        )
    }

    @Test
    fun `values lose surrounding whitespace and may be empty`() {
        val request = parseOne("GET / HTTP/1.1\r\nA: \t padded value \t\r\nB:\r\nC:   \r\nD:x\r\n\r\n")
        assertEquals("padded value", request.headers["A"])
        assertEquals("", request.headers["B"])
        assertEquals("", request.headers["C"])
        assertEquals("x", request.headers["D"])
    }

    @Test
    fun `obs-text in values is kept as ISO-8859-1 chars`() {
        val bytes = latin1("GET / HTTP/1.1\r\nX-Name: ") + "Café".toByteArray(Charsets.UTF_8) + latin1("\r\n\r\n")
        val parser = RtspRequestParser()
        val value = parser.feed(bytes).single().headers["X-Name"]!!
        assertEquals("Café", value.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8))
    }

    @Test
    fun `request split at every byte boundary parses identically`() {
        val bytes = latin1(setup)
        for (split in 0..bytes.size) {
            val parser = RtspRequestParser()
            val first = parser.feed(bytes, 0, split)
            val second = parser.feed(bytes, split, bytes.size - split)
            val request = (first + second).single()
            assertEquals("SETUP", request.method, "split at $split")
            assertEquals("3", request.cSeq)
            assertContentEquals(latin1("hello"), request.body)
            assertTrue(parser.isIdle)
        }
    }

    @Test
    fun `request fed one byte at a time parses`() {
        val bytes = latin1(setup + setup)
        val parser = RtspRequestParser()
        val requests = mutableListOf<RtspRequest>()
        for (i in bytes.indices) {
            requests += parser.feed(bytes, i, 1)
            assertEquals(i == setup.length - 1 || i == bytes.size - 1, parser.isIdle, "after byte $i")
        }
        assertEquals(2, requests.size)
        parser.finish()
    }

    @Test
    fun `several requests in one chunk come out in order`() {
        val text =
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n" +
                "POST /fp-setup RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 3\r\n\r\nabc" +
                "GET_PARAMETER rtsp://h/1 RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 0\r\n\r\n"
        val requests = parseAll(text)
        assertEquals(listOf("OPTIONS", "POST", "GET_PARAMETER"), requests.map { it.method })
        assertEquals(listOf("1", "2", "3"), requests.map { it.cSeq })
        assertContentEquals(latin1("abc"), requests[1].body)
    }

    @Test
    fun `body and the next request can share a chunk`() {
        val parser = RtspRequestParser()
        assertEquals(emptyList(), parser.feed(latin1("POST /a RTSP/1.0\r\nContent-Length: 6\r\n\r\nabc")))
        assertFalse(parser.isIdle)
        val requests = parser.feed(latin1("defPOST /b RTSP/1.0\r\nContent-Length: 2\r\n\r\nxy"))
        assertEquals(listOf("/a", "/b"), requests.map { it.target })
        assertContentEquals(latin1("abcdef"), requests[0].body)
        assertContentEquals(latin1("xy"), requests[1].body)
        assertTrue(parser.isIdle)
    }

    @Test
    fun `feed honours offset and length`() {
        val wire = latin1("GET /x HTTP/1.1\r\n\r\n")
        val padded = ByteArray(3) + wire + ByteArray(4) { 'Z'.code.toByte() }
        val request = RtspRequestParser().feed(padded, 3, wire.size).single()
        assertEquals("/x", request.target)
        assertFailsWith<IllegalArgumentException> { RtspRequestParser().feed(padded, 3, padded.size) }
        assertFailsWith<IllegalArgumentException> { RtspRequestParser().feed(padded, -1, 1) }
    }

    @Test
    fun `empty lines before a request are skipped within the start-line budget`() {
        assertEquals("/", parseOne("\r\n\r\nGET / HTTP/1.1\r\n\r\n").target)
        val limits = RtspLimits(maxStartLineBytes = 16)
        assertEquals("/", parseOne("\r\n".repeat(8) + "GET / HTTP/1.1\r\n\r\n", limits).target)
        assertEquals(400, rejectStatus("\r\n".repeat(9) + "GET / HTTP/1.1\r\n\r\n", limits))
    }

    @Test
    fun `Content-Length is validated`() {
        fun request(vararg lengths: String) =
            "POST / RTSP/1.0\r\n" + lengths.joinToString("") { "Content-Length: $it\r\n" } + "\r\n12345"
        assertContentEquals(latin1("12345"), parseOne(request("5", "5")).body)
        assertContentEquals(latin1("12345"), parseOne(request("0005")).body)
        for (bad in listOf("-1", "+5", "0x5", "5 5", "5,5", "", "five", "5.0", "²")) {
            assertEquals(400, rejectStatus(request(bad)), "Content-Length '$bad'")
        }
        assertEquals(400, rejectStatus(request("5", "6")))
        assertEquals(413, rejectStatus(request("99999999999999999999999999")))
        assertEquals(413, rejectStatus(request("${Int.MAX_VALUE}")))
    }

    @Test
    fun `Transfer-Encoding is rejected`() {
        assertEquals(501, rejectStatus("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))
        assertEquals(501, rejectStatus("POST / HTTP/1.1\r\ntransfer-encoding: identity\r\nContent-Length: 0\r\n\r\n"))
    }

    @Test
    fun `malformed header lines are rejected`() {
        val bad =
            listOf(
                "X: a\r\n folded\r\n",
                "\tX: a\r\n",
                "NoColon\r\n",
                "X : a\r\n",
                ": a\r\n",
                "X\u0000: a\r\n",
                "X(): a\r\n",
                "Xé: a\r\n",
                "X: a\u0000b\r\n",
                "X: a\u007fb\r\n",
                "X: a\u0001\r\n",
                "X: a\u001bb\r\n",
            )
        for (header in bad) {
            assertEquals(400, rejectStatus("GET / HTTP/1.1\r\n$header\r\n"), "header ${header.trim()}")
        }
    }

    @Test
    fun `line terminators must be CRLF`() {
        assertEquals(400, rejectStatus("GET / HTTP/1.1\n\n"))
        assertEquals(400, rejectStatus("GET / HTTP/1.1\r\nA: b\n\r\n"))
        assertEquals(400, rejectStatus("GET / HTTP/1.1\r\nA: b\rc\r\n\r\n"))
        assertEquals(400, rejectStatus("GET /\r HTTP/1.1\r\n\r\n"))
        assertEquals(400, rejectStatus("GET / HTTP/1.1\r\n\r\r\n"))
    }

    @Test
    fun `malformed request lines are rejected`() {
        val bad =
            listOf(
                "GET\r\n",
                "GET /\r\n",
                "GET  / HTTP/1.1\r\n",
                "GET /  HTTP/1.1\r\n",
                " GET / HTTP/1.1\r\n",
                "GET / HTTP/1.1 \r\n",
                "GET / HTTP/1.1 extra\r\n",
                "G@T / HTTP/1.1\r\n",
                "GET /\u0001 HTTP/1.1\r\n",
                "GET /é HTTP/1.1\r\n",
                "GET /\t HTTP/1.1\r\n",
                "GET / http/1.1\r\n",
                "GET / HTTP/1\r\n",
                "GET / HTTP\r\n",
                "GET / é\r\n",
            )
        for (line in bad) {
            assertEquals(400, rejectStatus("$line\r\n"), "request line ${line.trim()}")
        }
    }

    @Test
    fun `unsupported protocol versions fail with 505`() {
        for (version in listOf("HTTP/1.0", "HTTP/2.0", "RTSP/2.0", "RTSP/1.1", "PTTH/1.0")) {
            assertEquals(505, rejectStatus("GET / $version\r\n\r\n"), version)
        }
    }

    @Test
    fun `request line limit is inclusive`() {
        val limits = RtspLimits(maxStartLineBytes = 64)
        val exact = requestLine(64)
        assertEquals(64, exact.length)
        assertEquals(RtspProtocol.HTTP_1_1, parseOne("$exact\r\n\r\n", limits).protocol)
        assertEquals(414, rejectStatus("${requestLine(65)}\r\n\r\n", limits))
    }

    @Test
    fun `default request line limit is 8 KiB`() {
        assertEquals("GET", parseOne("${requestLine(8 * 1024)}\r\n\r\n").method)
        assertEquals(414, rejectStatus("${requestLine(8 * 1024 + 1)}\r\n\r\n"))
    }

    @Test
    fun `header byte limit counts each line with its CRLF`() {
        val limits = RtspLimits(maxHeaderBytes = 64)
        // "A: " + 59 chars + CRLF = 64 bytes.
        assertEquals(59, parseOne("GET / HTTP/1.1\r\nA: ${"v".repeat(59)}\r\n\r\n", limits).headers["A"]!!.length)
        assertEquals(431, rejectStatus("GET / HTTP/1.1\r\nA: ${"v".repeat(60)}\r\n\r\n", limits))
        // Two lines of 32 bytes each fit; one more byte does not.
        val line = "B: ${"w".repeat(27)}\r\n"
        assertEquals(32, line.length)
        assertEquals(2, parseOne("GET / HTTP/1.1\r\n$line$line\r\n", limits).headers.size)
        assertEquals(431, rejectStatus("GET / HTTP/1.1\r\n${line}C: ${"w".repeat(28)}\r\n\r\n", limits))
        assertEquals(431, rejectStatus("GET / HTTP/1.1\r\n$line${line}D\r\n\r\n", limits))
    }

    @Test
    fun `default header byte limit is 32 KiB`() {
        val value = "v".repeat(32 * 1024 - 5)
        assertEquals(value, parseOne("GET / HTTP/1.1\r\nA: $value\r\n\r\n").headers["A"])
        assertEquals(431, rejectStatus("GET / HTTP/1.1\r\nA: ${value}v\r\n\r\n"))
    }

    @Test
    fun `header count limit is inclusive`() {
        fun headers(count: Int) = (1..count).joinToString("") { "H$it: v\r\n" }
        assertEquals(100, parseOne("GET / HTTP/1.1\r\n${headers(100)}\r\n").headers.size)
        assertEquals(431, rejectStatus("GET / HTTP/1.1\r\n${headers(101)}\r\n"))
        assertEquals(3, parseOne("GET / HTTP/1.1\r\n${headers(3)}\r\n", RtspLimits(maxHeaderCount = 3)).headers.size)
        assertEquals(431, rejectStatus("GET / HTTP/1.1\r\n${headers(4)}\r\n", RtspLimits(maxHeaderCount = 3)))
    }

    @Test
    fun `body limit is inclusive and checked before the body arrives`() {
        val limits = RtspLimits(maxBodyBytes = 10)
        assertEquals(10, parseOne("POST / RTSP/1.0\r\nContent-Length: 10\r\n\r\n0123456789", limits).body.size)
        val parser = RtspRequestParser(limits)
        val failure =
            assertFailsWith<RtspException> { parser.feed(latin1("POST / RTSP/1.0\r\nContent-Length: 11\r\n\r\n")) }
        assertEquals(413, failure.status)
        assertEquals(413, rejectStatus("POST / RTSP/1.0\r\nContent-Length: 1\r\n\r\nx", RtspLimits(maxBodyBytes = 0)))
    }

    @Test
    fun `default body limit is 2 MiB`() {
        val size = 2 * 1024 * 1024
        val parser = RtspRequestParser()
        val head = latin1("POST /audio RTSP/1.0\r\nContent-Length: $size\r\n\r\n")
        assertEquals(emptyList(), parser.feed(head))
        assertEquals(
            size,
            parser
                .feed(ByteArray(size))
                .single()
                .body.size,
        )
        assertEquals(413, rejectStatus("POST / RTSP/1.0\r\nContent-Length: ${size + 1}\r\n\r\n"))
    }

    @Test
    fun `failure is sticky`() {
        val parser = RtspRequestParser()
        val first = assertFailsWith<RtspException> { parser.feed(latin1("GET / HTTP/1.0\r\n\r\n")) }
        assertEquals(505, first.status)
        assertFalse(parser.isIdle)
        val again = assertFailsWith<RtspException> { parser.feed(latin1("GET / HTTP/1.1\r\n\r\n")) }
        assertEquals(505, again.status)
        assertFailsWith<RtspException> { parser.finish() }
    }

    @Test
    fun `finish fails when a request is cut off`() {
        val cuts =
            listOf(
                "GET / HT",
                "GET / HTTP/1.1\r",
                "GET / HTTP/1.1\r\n",
                "GET / HTTP/1.1\r\nA: b\r\n",
                "GET / HTTP/1.1\r\nA: b\r\n\r",
                "POST / RTSP/1.0\r\nContent-Length: 4\r\n\r\nab",
            )
        for (cut in cuts) {
            val parser = RtspRequestParser()
            parser.feed(latin1(cut))
            assertFalse(parser.isIdle, cut)
            assertEquals(400, assertFailsWith<RtspException>(cut) { parser.finish() }.status)
        }
        val idle = RtspRequestParser()
        idle.feed(latin1("GET / HTTP/1.1\r\n\r\n\r\n"))
        assertTrue(idle.isIdle)
        idle.finish()
    }

    @Test
    fun `parsed requests re-serialize with a computed Content-Length at the end`() {
        val request = parseOne(setup)
        val again = parseOne(request.toByteArray().toString(Charsets.ISO_8859_1))
        assertEquals(request.method, again.method)
        assertEquals(request.target, again.target)
        assertEquals(
            request.headers
                .toBuilder()
                .remove("Content-Length")
                .add("Content-Length", "5")
                .build(),
            again.headers,
        )
        assertContentEquals(request.body, again.body)
    }

    @Test
    fun `limits reject nonsense`() {
        assertFailsWith<IllegalArgumentException> { RtspLimits(maxStartLineBytes = 0) }
        assertFailsWith<IllegalArgumentException> { RtspLimits(maxHeaderBytes = 0) }
        assertFailsWith<IllegalArgumentException> { RtspLimits(maxHeaderCount = 0) }
        assertFailsWith<IllegalArgumentException> { RtspLimits(maxBodyBytes = -1) }
        assertEquals(RtspLimits(8192, 32768, 100, 2097152), RtspLimits())
    }

    private fun requestLine(length: Int): String {
        val fixed = "GET / HTTP/1.1".length
        return "GET /${"a".repeat(length - fixed)} HTTP/1.1"
    }

    private fun rejectStatus(
        text: String,
        limits: RtspLimits = RtspLimits(),
    ): Int {
        val parser = RtspRequestParser(limits)
        return assertFailsWith<RtspException>("expected rejection of ${text.take(60)}") {
            parser.feed(latin1(text))
            parser.finish()
        }.status
    }
}
