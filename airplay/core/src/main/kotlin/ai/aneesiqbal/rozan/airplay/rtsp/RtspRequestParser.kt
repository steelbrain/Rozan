package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Incremental parser for the requests a sender writes on one control connection.
 *
 * Feed it bytes as they arrive, in any split: a request may span many chunks and one chunk may hold
 * several requests. Each call returns the requests completed by that chunk, in order.
 *
 * Accepted: `METHOD SP target SP RTSP/1.0` or `HTTP/1.1`, header fields, then a body of exactly
 * `Content-Length` bytes (none when the header is absent). See [RtspLimits] for the size limits and
 * [RtspProtocol] for the version policy. Lines must end with CRLF; a bare LF is rejected.
 *
 * Any problem throws [RtspException] with a suggested [RtspException.status]. Requests completed earlier
 * in the same chunk are dropped with it, and the parser stays failed: the connection should be closed.
 * Not thread-safe; use one parser per connection.
 *
 * Bytes that follow a request in the same chunk are taken as the start of the next request. After a
 * request that hands the connection over to another protocol (e.g. `POST /reverse` answered with 101),
 * stop feeding this parser; the sender waits for the receiver before writing anything else there.
 *
 * @property limits the size limits applied to every request.
 * @param bodyLimit lowers [RtspLimits.maxBodyBytes] per request, from its request line and headers.
 */
public class RtspRequestParser(
    public val limits: RtspLimits = RtspLimits(),
    bodyLimit: RtspBodyLimit? = null,
) {
    private val framer = MessageFramer(limits, RequestHead(bodyLimit))

    /** True when no part of a request is buffered, i.e. the stream may end here cleanly. */
    public val isIdle: Boolean get() = framer.isIdle

    /**
     * Parses `bytes[offset until offset + length]` and returns the requests it completes, possibly none.
     *
     * @throws RtspException if the input is malformed or over a limit, or the parser already failed.
     */
    public fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<RtspRequest> = framer.feed(bytes, offset, length)

    /**
     * Signals that the stream has ended.
     *
     * @throws RtspException if a request was only partly received, or the parser already failed.
     */
    public fun finish(): Unit = framer.finish()

    private class RequestHead(
        private val bodyLimit: RtspBodyLimit?,
    ) : MessageFramer.Head<RtspRequest> {
        private var method = ""
        private var target = ""
        private var protocol = RtspProtocol.RTSP_1_0

        override fun startLine(
            line: ByteArray,
            length: Int,
        ) {
            val firstSpace = indexOf(line, Syntax.SP, 0, length)
            val secondSpace = if (firstSpace < 0) -1 else indexOf(line, Syntax.SP, firstSpace + 1, length)
            if (secondSpace < 0) reject("request line needs a method, a target and a protocol")
            if (firstSpace == 0) reject("empty method")
            if (secondSpace == firstSpace + 1) reject("empty request target")
            for (i in 0 until firstSpace) {
                if (!Syntax.isTokenChar(line[i].toInt() and BYTE_MASK)) reject("invalid character in method")
            }
            for (i in firstSpace + 1 until secondSpace) {
                if (!Syntax.isTargetChar(line[i].toInt() and BYTE_MASK)) reject("invalid character in request target")
            }
            method = String(line, 0, firstSpace, Charsets.ISO_8859_1)
            target = String(line, firstSpace + 1, secondSpace - firstSpace - 1, Charsets.ISO_8859_1)
            protocol = parseProtocol(String(line, secondSpace + 1, length - secondSpace - 1, Charsets.ISO_8859_1))
        }

        override fun maxBodyBytes(headers: List<RtspHeader>): Int =
            bodyLimit?.maxBodyBytes(method, target, RtspHeaders(ArrayList(headers))) ?: Int.MAX_VALUE

        override fun bodyLength(contentLength: Int?): Int = contentLength ?: 0

        override fun complete(
            headers: RtspHeaders,
            body: ByteArray,
        ): RtspRequest = RtspRequest(method, target, protocol, headers, body)
    }
}

private const val BYTE_MASK = 0xFF

/** Something shaped like a protocol version (`NAME/1.0`) that is not one of ours: 505 rather than 400. */
private val VERSION_SHAPE = Regex("[A-Z]{1,16}/[0-9]{1,4}\\.[0-9]{1,4}")

/** Maps a version token to [RtspProtocol]; well-formed but unsupported versions fail with 505. */
internal fun parseProtocol(token: String): RtspProtocol {
    RtspProtocol.fromToken(token)?.let { return it }
    if (VERSION_SHAPE.matches(token)) reject("unsupported protocol version", RtspStatus.VERSION_NOT_SUPPORTED)
    reject("malformed protocol version")
}

/** Index of the first byte equal to [value] in `bytes[from until to]`, or -1. */
internal fun indexOf(
    bytes: ByteArray,
    value: Int,
    from: Int,
    to: Int,
): Int {
    for (i in from until to) {
        if (bytes[i].toInt() == value) return i
    }
    return -1
}
