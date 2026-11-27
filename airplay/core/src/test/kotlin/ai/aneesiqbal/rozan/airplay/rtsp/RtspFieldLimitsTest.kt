package ai.aneesiqbal.rozan.airplay.rtsp

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RtspFieldLimitsTest {
    private fun request(vararg headers: String): ByteArray =
        ("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray()

    @Test
    fun `header names and values over their limits fail with 431`() {
        val limits = RtspLimits(maxHeaderNameBytes = 8, maxHeaderValueBytes = 10)
        assertEquals(1, RtspRequestParser(limits).feed(request("Abcdefgh: 0123456789")).size)
        val longName = assertFailsWith<RtspException> { RtspRequestParser(limits).feed(request("Abcdefghi: x")) }
        assertEquals(RtspStatus.HEADER_FIELDS_TOO_LARGE, longName.status)
        val longValue = assertFailsWith<RtspException> { RtspRequestParser(limits).feed(request("A: 0123456789a")) }
        assertEquals(RtspStatus.HEADER_FIELDS_TOO_LARGE, longValue.status)
        // Surrounding whitespace is not counted.
        assertEquals(1, RtspRequestParser(limits).feed(request("A:    0123456789   ")).size)
    }

    @Test
    fun `body limit hook decides per request before the body arrives`() {
        val bodyLimit =
            RtspBodyLimit { method, _, headers ->
                if (method == "SET_PARAMETER" && headers["Content-Type"]?.startsWith("image/") == true) 100 else 10
            }
        val parser = RtspRequestParser(RtspLimits(maxBodyBytes = 50), bodyLimit)
        val image =
            "SET_PARAMETER rtsp://x/1 RTSP/1.0\r\nCSeq: 2\r\n" +
                "Content-Type: image/jpeg\r\nContent-Length: 50\r\n\r\n"
        assertEquals(1, parser.feed((image).toByteArray() + ByteArray(50)).size)
        // The global cap still applies on top of the hook.
        val tooBig = image.replace("50", "51").toByteArray()
        assertEquals(
            RtspStatus.CONTENT_TOO_LARGE,
            assertFailsWith<RtspException> { RtspRequestParser(RtspLimits(maxBodyBytes = 50), bodyLimit).feed(tooBig) }
                .status,
        )
        // Other requests get the hook's lower cap, rejected as soon as the headers end.
        val text =
            "SET_PARAMETER rtsp://x/1 RTSP/1.0\r\nCSeq: 3\r\n" +
                "Content-Type: text/parameters\r\nContent-Length: 11\r\n\r\n"
        val parser2 = RtspRequestParser(RtspLimits(maxBodyBytes = 50), bodyLimit)
        val error = assertFailsWith<RtspException> { parser2.feed(text.toByteArray()) }
        assertEquals(RtspStatus.CONTENT_TOO_LARGE, error.status)
    }

    @Test
    fun `reader passes the body limit through`() {
        val bytes = "POST /x RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 5\r\n\r\nhello".toByteArray()
        val reader = RtspRequestReader(ByteArrayInputStream(bytes), bodyLimit = { _, _, _ -> 4 })
        assertEquals(RtspStatus.CONTENT_TOO_LARGE, assertFailsWith<RtspException> { reader.read() }.status)
        val ok = RtspRequestReader(ByteArrayInputStream(bytes), bodyLimit = { _, _, _ -> 5 })
        assertEquals("hello", ok.read()?.body?.decodeToString())
    }
}
