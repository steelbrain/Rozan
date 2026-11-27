package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Incremental parser for responses to requests the receiver sent, e.g. on the reverse (PTTH) or event
 * connection. Same framing, limits and failure behaviour as [RtspRequestParser].
 *
 * The status line is `RTSP/1.0` or `HTTP/1.1`, a three-digit status and an optional reason phrase. The
 * body is `Content-Length` bytes; a missing `Content-Length` means no body (RFC 2326 §12.14). Reading an
 * HTTP body until the connection closes is not supported, since AirPlay senders always send the header.
 * Responses with status 1xx, 204 or 304 never have a body.
 *
 * @property limits the size limits applied to every response; `maxStartLineBytes` bounds the status line.
 */
public class RtspResponseParser(
    public val limits: RtspLimits = RtspLimits(),
) {
    private val framer = MessageFramer(limits, ResponseHead())

    /** True when no part of a response is buffered, i.e. the stream may end here cleanly. */
    public val isIdle: Boolean get() = framer.isIdle

    /**
     * Parses `bytes[offset until offset + length]` and returns the responses it completes, possibly none.
     *
     * @throws RtspException if the input is malformed or over a limit, or the parser already failed.
     */
    public fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<RtspResponse> = framer.feed(bytes, offset, length)

    /**
     * Signals that the stream has ended.
     *
     * @throws RtspException if a response was only partly received, or the parser already failed.
     */
    public fun finish(): Unit = framer.finish()

    private class ResponseHead : MessageFramer.Head<RtspResponse> {
        private var protocol = RtspProtocol.RTSP_1_0
        private var status = 0
        private var reason = ""

        override fun startLine(
            line: ByteArray,
            length: Int,
        ) {
            val space = indexOf(line, Syntax.SP, 0, length)
            if (space <= 0) reject("status line needs a protocol and a status code")
            val statusEnd = space + 1 + STATUS_DIGITS
            if (statusEnd > length) reject("status code must have three digits")
            var code = 0
            for (i in space + 1 until statusEnd) {
                val digit = line[i] - '0'.code.toByte()
                if (digit !in 0..DIGIT_MAX) reject("status code must have three digits")
                code = code * DECIMAL + digit
            }
            if (code < MIN_STATUS) reject("status code out of range")
            if (statusEnd < length && line[statusEnd].toInt() != Syntax.SP) reject("status code must have three digits")
            for (i in statusEnd + 1 until length) {
                val valid = Syntax.isFieldValueChar(line[i].toInt() and BYTE_MASK)
                if (!valid) reject("invalid character in reason phrase")
            }
            protocol = parseProtocol(String(line, 0, space, Charsets.ISO_8859_1))
            status = code
            reason =
                if (statusEnd < length) String(line, statusEnd + 1, length - statusEnd - 1, Charsets.ISO_8859_1) else ""
        }

        override fun bodyLength(contentLength: Int?): Int =
            if (RtspResponse.isBodiless(status)) 0 else contentLength ?: 0

        override fun complete(
            headers: RtspHeaders,
            body: ByteArray,
        ): RtspResponse = RtspResponse(protocol, status, reason, headers, body)
    }

    private companion object {
        const val STATUS_DIGITS = 3
        const val MIN_STATUS = 100
        const val DIGIT_MAX = 9
        const val DECIMAL = 10
        const val BYTE_MASK = 0xFF
    }
}
