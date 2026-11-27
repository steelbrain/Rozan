package ai.aneesiqbal.rozan.airplay.rtsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class RtspMessageTest {
    @Test
    fun `response echoes the request protocol and CSeq`() {
        val request = RtspRequest("SETUP", "rtsp://h/1", RtspProtocol.RTSP_1_0, RtspHeaders.of("CSeq" to "12"))
        val wire =
            RtspResponse
                .builder(request, RtspStatus.OK)
                .header("Server", "AirTunes/0.0")
                .body(latin1("bplist"), "application/x-apple-binary-plist")
                .build()
                .toByteArray()
        assertEquals(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: 12\r\n" +
                "Server: AirTunes/0.0\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: 6\r\n" +
                "\r\n" +
                "bplist",
            wire.toString(Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `HTTP response without CSeq or body still carries Content-Length`() {
        val request = RtspRequest("GET", "/info", RtspProtocol.HTTP_1_1)
        val wire = RtspResponse.builder(request, RtspStatus.NOT_FOUND).build().toByteArray()
        assertEquals("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n", wire.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `bodiless statuses omit Content-Length and refuse a body`() {
        val upgrade =
            RtspResponse
                .Builder(RtspProtocol.HTTP_1_1, RtspStatus.SWITCHING_PROTOCOLS)
                .header("Upgrade", "PTTH/1.0")
                .header("Connection", "Upgrade")
                .build()
        assertEquals(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: PTTH/1.0\r\nConnection: Upgrade\r\n\r\n",
            upgrade.toByteArray().toString(Charsets.ISO_8859_1),
        )
        assertEquals(
            "HTTP/1.1 204 No Content\r\n\r\n",
            RtspResponse(RtspProtocol.HTTP_1_1, 204).toByteArray().toString(Charsets.ISO_8859_1),
        )
        assertFailsWith<IllegalArgumentException> { RtspResponse(RtspProtocol.HTTP_1_1, 204, body = ByteArray(1)) }
    }

    @Test
    fun `a Content-Length given by the caller is replaced by the computed one`() {
        val response =
            RtspResponse(
                RtspProtocol.RTSP_1_0,
                200,
                headers = RtspHeaders.of("Content-Length" to "99", "CSeq" to "1"),
                body = latin1("abc"),
            )
        assertEquals(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 3\r\n\r\nabc",
            response.toByteArray().toString(Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `reason phrases depend on the protocol`() {
        assertEquals("Request Entity Too Large", RtspStatus.reasonPhrase(413, RtspProtocol.RTSP_1_0))
        assertEquals("Content Too Large", RtspStatus.reasonPhrase(413, RtspProtocol.HTTP_1_1))
        assertEquals("Parameter Not Understood", RtspStatus.reasonPhrase(451, RtspProtocol.RTSP_1_0))
        assertEquals("", RtspStatus.reasonPhrase(451, RtspProtocol.HTTP_1_1))
        assertEquals("Method Not Valid in This State", RtspStatus.reasonPhrase(455, RtspProtocol.RTSP_1_0))
        assertEquals("OK", RtspStatus.reasonPhrase(200, RtspProtocol.HTTP_1_1))
        assertEquals(
            "RTSP/1.0 299 \r\nContent-Length: 0\r\n\r\n",
            RtspResponse(RtspProtocol.RTSP_1_0, 299).toByteArray().toString(Charsets.ISO_8859_1),
        )
        assertEquals(
            "RTSP/1.0 470 Connection Authorization Required\r\nContent-Length: 0\r\n\r\n",
            RtspResponse
                .Builder(RtspProtocol.RTSP_1_0, 470)
                .reason("Connection Authorization Required")
                .build()
                .toByteArray()
                .toString(Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `invalid responses are programming errors`() {
        assertFailsWith<IllegalArgumentException> { RtspResponse(RtspProtocol.HTTP_1_1, 99) }
        assertFailsWith<IllegalArgumentException> { RtspResponse(RtspProtocol.HTTP_1_1, 1000) }
        assertFailsWith<IllegalArgumentException> { RtspResponse(RtspProtocol.HTTP_1_1, 200, "OK\r\nX: y") }
        assertFailsWith<IllegalArgumentException> {
            RtspResponse.Builder(RtspProtocol.HTTP_1_1, 200).header("Transfer-Encoding", "chunked").build()
        }
    }

    @Test
    fun `request builder writes Content-Length only with a body`() {
        val event =
            RtspRequest
                .Builder("POST", "/event", RtspProtocol.HTTP_1_1)
                .header("X-Apple-Session-ID", "00000000-0000-0000-0000-000000000000")
                .body(latin1("<plist/>"), "text/x-apple-plist+xml")
                .build()
        assertEquals(
            "POST /event HTTP/1.1\r\n" +
                "X-Apple-Session-ID: 00000000-0000-0000-0000-000000000000\r\n" +
                "Content-Type: text/x-apple-plist+xml\r\n" +
                "Content-Length: 8\r\n" +
                "\r\n" +
                "<plist/>",
            event.toByteArray().toString(Charsets.ISO_8859_1),
        )
        val options = RtspRequest.Builder("OPTIONS", "*").header("CSeq", "1").build()
        assertEquals("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n", options.toByteArray().toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `invalid requests are programming errors`() {
        assertFailsWith<IllegalArgumentException> { RtspRequest("GET /", "/", RtspProtocol.HTTP_1_1) }
        assertFailsWith<IllegalArgumentException> { RtspRequest("", "/", RtspProtocol.HTTP_1_1) }
        assertFailsWith<IllegalArgumentException> { RtspRequest("GET", "", RtspProtocol.HTTP_1_1) }
        assertFailsWith<IllegalArgumentException> { RtspRequest("GET", "/a b", RtspProtocol.HTTP_1_1) }
        assertFailsWith<IllegalArgumentException> { RtspRequest("GET", "/é", RtspProtocol.HTTP_1_1) }
    }

    @Test
    fun `header builder validates names and values`() {
        val builder = RtspHeaders.Builder()
        for (name in listOf("", "A B", "A:", "Xé", "A\r\n", "(x)")) {
            assertFailsWith<IllegalArgumentException>(name) { builder.add(name, "v") }
        }
        for (value in listOf("a\rb", "a\nb", "a\u0000", " lead", "trail\t", "Ā", "\u007f")) {
            assertFailsWith<IllegalArgumentException>(value) { builder.add("X", value) }
        }
        builder.add("X", "").add("Y", "a\tb c").add("Z", "ÿ")
        assertEquals(3, builder.build().size)
    }

    @Test
    fun `header set replaces the first match in place and drops the rest`() {
        val headers =
            RtspHeaders
                .of("A" to "1", "b" to "2", "C" to "3", "B" to "4")
                .toBuilder()
                .set("B", "new")
                .set("D", "5")
                .build()
        assertEquals(
            listOf(RtspHeader("A", "1"), RtspHeader("B", "new"), RtspHeader("C", "3"), RtspHeader("D", "5")),
            headers.toList(),
        )
        assertEquals(
            listOf("1"),
            headers
                .toBuilder()
                .remove("b")
                .build()
                .getAll("a"),
        )
        assertEquals(RtspHeaders.of("A" to "1"), RtspHeaders.of("A" to "1"))
        assertNotEquals(RtspHeaders.of("A" to "1"), RtspHeaders.of("a" to "1"))
        assertEquals(0, RtspHeaders.EMPTY.size)
    }

    @Test
    fun `protocol tokens`() {
        assertEquals(RtspProtocol.RTSP_1_0, RtspProtocol.fromToken("RTSP/1.0"))
        assertEquals(RtspProtocol.HTTP_1_1, RtspProtocol.fromToken("HTTP/1.1"))
        assertEquals(null, RtspProtocol.fromToken("http/1.1"))
        assertEquals("HTTP/1.1", RtspProtocol.HTTP_1_1.toString())
    }
}
