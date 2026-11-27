package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * A response: built by the receiver with [Builder] and sent with [toByteArray], or parsed by
 * [RtspResponseParser] from a sender answering a request the receiver made.
 *
 * Invalid arguments throw [IllegalArgumentException]; the parser only produces valid responses.
 *
 * @property status the three-digit status code, 100..999.
 * @property reason the reason phrase; may be empty.
 */
public class RtspResponse(
    override val protocol: RtspProtocol,
    public val status: Int,
    public val reason: String = RtspStatus.reasonPhrase(status, protocol),
    override val headers: RtspHeaders = RtspHeaders.EMPTY,
    override val body: ByteArray = EMPTY_BODY,
) : RtspMessage {
    init {
        require(status in MIN_STATUS..MAX_STATUS) { "status must have three digits" }
        require(reason.all { Syntax.isFieldValueChar(it.code) }) { "invalid character in reason phrase" }
        require(Syntax.TRANSFER_ENCODING !in headers) { "Transfer-Encoding is not supported" }
        require(body.isEmpty() || !isBodiless(status)) { "status $status cannot carry a body" }
    }

    override fun toByteArray(): ByteArray =
        MessageWriter.write(
            "${protocol.token} $status $reason",
            headers,
            body,
            writeContentLength = !isBodiless(status),
        )

    /** Summary without header values or body bytes, safe to log. */
    override fun toString(): String =
        "RtspResponse($protocol $status $reason, ${headers.size} headers, ${body.size} body bytes)"

    /**
     * Builds a response.
     *
     * @param protocol the protocol version; use the request's, see [RtspResponse.builder].
     * @param status the status code.
     */
    public class Builder(
        private val protocol: RtspProtocol,
        private val status: Int,
    ) {
        private val headers = RtspHeaders.Builder()
        private var reason: String? = null
        private var body: ByteArray = EMPTY_BODY

        /** Replaces the standard reason phrase. */
        public fun reason(text: String): Builder {
            reason = text
            return this
        }

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

        /** Returns the response. */
        public fun build(): RtspResponse =
            RtspResponse(
                protocol = protocol,
                status = status,
                reason = reason ?: RtspStatus.reasonPhrase(status, protocol),
                headers = headers.build(),
                body = body,
            )
    }

    /** Factories. */
    public companion object {
        private const val MIN_STATUS = 100
        private const val MAX_STATUS = 999
        private const val NOT_MODIFIED = 304
        private const val INFORMATIONAL_END = 199

        /**
         * Starts a response to [request]: same protocol version and, if the request had one, its `CSeq`
         * as the first header.
         */
        public fun builder(
            request: RtspRequest,
            status: Int,
        ): Builder {
            val builder = Builder(request.protocol, status)
            request.cSeq?.let { builder.header(Syntax.CSEQ, it) }
            return builder
        }

        /** True for statuses whose responses never have a body: 1xx, 204 and 304 (RFC 9110 §6.4.1). */
        internal fun isBodiless(status: Int): Boolean =
            status in MIN_STATUS..INFORMATIONAL_END || status == RtspStatus.NO_CONTENT || status == NOT_MODIFIED
    }
}
