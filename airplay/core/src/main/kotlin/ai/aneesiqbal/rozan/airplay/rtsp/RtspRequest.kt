package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * A request: parsed from a sender by [RtspRequestParser], or built by the receiver for a reverse or event
 * channel and sent with [toByteArray].
 *
 * Invalid arguments throw [IllegalArgumentException]; the parser only produces valid requests.
 *
 * @property method the method token, case-sensitive, e.g. `SETUP` or `POST`.
 * @property target the request target as sent, e.g. `rtsp://192.0.2.1/1234` or `/pair-setup`. Visible
 *   ASCII only.
 */
public class RtspRequest(
    public val method: String,
    public val target: String,
    override val protocol: RtspProtocol,
    override val headers: RtspHeaders = RtspHeaders.EMPTY,
    override val body: ByteArray = EMPTY_BODY,
) : RtspMessage {
    init {
        require(Syntax.isToken(method)) { "method is not a token" }
        require(Syntax.isTarget(target)) { "request target must be non-empty visible ASCII" }
        require(Syntax.TRANSFER_ENCODING !in headers) { "Transfer-Encoding is not supported" }
    }

    override fun toByteArray(): ByteArray =
        MessageWriter.write("$method $target ${protocol.token}", headers, body, writeContentLength = body.isNotEmpty())

    /** Summary without header values or body bytes, safe to log. */
    override fun toString(): String =
        "RtspRequest($method $target $protocol, ${headers.size} headers, ${body.size} body bytes)"

    /**
     * Builds an outgoing request.
     *
     * @param method the method token.
     * @param target the request target.
     * @param protocol the protocol version, `RTSP/1.0` unless given.
     */
    public class Builder(
        private val method: String,
        private val target: String,
        private val protocol: RtspProtocol = RtspProtocol.RTSP_1_0,
    ) {
        private val headers = RtspHeaders.Builder()
        private var body: ByteArray = EMPTY_BODY

        /** Appends a header field; see [RtspHeaders.Builder.add]. */
        public fun header(
            name: String,
            value: String,
        ): Builder {
            headers.add(name, value)
            return this
        }

        /** Sets the body and, when given, the `Content-Type` header. The array is not copied. */
        public fun body(
            bytes: ByteArray,
            contentType: String? = null,
        ): Builder {
            body = bytes
            if (contentType != null) headers.set(Syntax.CONTENT_TYPE, contentType)
            return this
        }

        /** Returns the request. */
        public fun build(): RtspRequest = RtspRequest(method, target, protocol, headers.build(), body)
    }
}
