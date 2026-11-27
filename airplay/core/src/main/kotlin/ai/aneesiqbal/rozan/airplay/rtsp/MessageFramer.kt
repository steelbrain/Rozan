package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Incremental framing shared by the request and response parsers: start line, header lines, then a
 * `Content-Length` body.
 *
 * Wire rules (RFC 9112 grammar, applied strictly because the only legitimate peers are Apple's senders,
 * which always send CRLF):
 * - Lines end with CRLF. A bare LF or a CR not followed by LF is rejected, so there is exactly one way to
 *   split the stream into lines.
 * - Empty lines before a start line are skipped (RFC 9112 §2.2), within the start-line budget.
 * - Header lines starting with whitespace (obsolete line folding), without a colon, with whitespace
 *   before the colon, or with control characters are rejected.
 * - `Transfer-Encoding` is rejected with 501; bodies are framed only by `Content-Length`.
 *
 * Memory is bounded by [RtspLimits]: the line buffer never grows past the larger of the start-line and
 * header limits, and a body array is allocated only after its length has been checked against the cap.
 * After a failure every further call fails too, since the stream position is no longer known.
 */
internal class MessageFramer<T : Any>(
    private val limits: RtspLimits,
    private val head: Head<T>,
) {
    /** What differs between requests and responses. */
    interface Head<T : Any> {
        /** Validates and remembers the start line held in `line[0 until length]`. */
        fun startLine(
            line: ByteArray,
            length: Int,
        )

        /**
         * Largest body accepted for the message whose header fields are [headers], before
         * [RtspLimits.maxBodyBytes] is applied. Called before any body byte is buffered.
         */
        fun maxBodyBytes(headers: List<RtspHeader>): Int = Int.MAX_VALUE

        /** Body length to read, given the validated `Content-Length` (null when absent). */
        fun bodyLength(contentLength: Int?): Int

        /** Builds the message once its body is complete. */
        fun complete(
            headers: RtspHeaders,
            body: ByteArray,
        ): T
    }

    private enum class State { START_LINE, HEADERS, BODY }

    private val maxLineCapacity = maxOf(limits.maxStartLineBytes, limits.maxHeaderBytes)
    private var state = State.START_LINE
    private var line = ByteArray(minOf(INITIAL_LINE_CAPACITY, maxLineCapacity))
    private var lineLength = 0
    private var pendingCr = false
    private var lineComplete = false
    private var skippedBytes = 0
    private var headerBytes = 0
    private val headers = ArrayList<RtspHeader>()
    private var body = EMPTY_BODY
    private var bodyFilled = 0
    private var failure: RtspException? = null

    /** True when no part of a message is buffered (and no failure has happened). */
    val isIdle: Boolean
        get() = failure == null && state == State.START_LINE && lineLength == 0 && !pendingCr

    fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): List<T> {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "offset/length out of range" }
        failure?.let { throw RtspException("connection already failed: ${it.message}", it.status, it) }
        try {
            return parse(bytes, offset, offset + length)
        } catch (e: RtspException) {
            failure = e
            throw e
        }
    }

    /** Signals end of stream; fails if a message was cut off. */
    fun finish() {
        failure?.let { throw RtspException("connection already failed: ${it.message}", it.status, it) }
        if (!isIdle) reject("connection closed in the middle of a message")
    }

    private fun parse(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): List<T> {
        var completed: MutableList<T>? = null
        var pos = start
        while (pos < end) {
            val message: T?
            if (state == State.BODY) {
                pos = consumeBody(bytes, pos, end)
                message = if (bodyFilled == body.size) finishMessage() else null
            } else {
                pos = consumeLine(bytes, pos, end)
                message = if (lineComplete) completeLine() else null
            }
            if (message != null) {
                val list = completed ?: ArrayList<T>(2).also { completed = it }
                list += message
            }
        }
        return completed ?: emptyList()
    }

    private fun consumeBody(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        val count = minOf(end - start, body.size - bodyFilled)
        System.arraycopy(bytes, start, body, bodyFilled, count)
        bodyFilled += count
        return start + count
    }

    /** Appends bytes to the current line until its CRLF; returns the index after the last byte consumed. */
    private fun consumeLine(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var pos = start
        while (pos < end && !lineComplete) {
            val b = bytes[pos].toInt() and BYTE_MASK
            pos++
            if (pendingCr) {
                if (b != Syntax.LF) reject("CR not followed by LF")
                pendingCr = false
                lineComplete = true
            } else {
                when (b) {
                    Syntax.CR -> pendingCr = true
                    Syntax.LF -> reject("line ends with LF without CR")
                    else -> append(b)
                }
            }
        }
        return pos
    }

    private fun append(b: Int) {
        if (state == State.START_LINE) {
            if (lineLength >= limits.maxStartLineBytes) {
                reject("start line longer than ${limits.maxStartLineBytes} bytes", RtspStatus.URI_TOO_LONG)
            }
        } else if (lineLength + CRLF_LENGTH > limits.maxHeaderBytes - headerBytes - 1) {
            reject("header section longer than ${limits.maxHeaderBytes} bytes", RtspStatus.HEADER_FIELDS_TOO_LARGE)
        }
        if (lineLength == line.size) {
            line = line.copyOf(minOf(line.size * 2, maxLineCapacity))
        }
        line[lineLength++] = b.toByte()
    }

    private fun completeLine(): T? {
        lineComplete = false
        val length = lineLength
        lineLength = 0
        if (state == State.START_LINE) {
            if (length == 0) {
                skippedBytes += CRLF_LENGTH
                if (skippedBytes > limits.maxStartLineBytes) reject("too many empty lines before the start line")
            } else {
                head.startLine(line, length)
                state = State.HEADERS
            }
            return null
        }
        if (length > 0) {
            addHeader(length)
            return null
        }
        return endOfHeaders()
    }

    private fun addHeader(length: Int) {
        headerBytes += length + CRLF_LENGTH
        if (headers.size == limits.maxHeaderCount) {
            reject("more than ${limits.maxHeaderCount} header fields", RtspStatus.HEADER_FIELDS_TOO_LARGE)
        }
        if (Syntax.isWhitespace(line[0].toInt())) reject("obsolete header line folding")
        var colon = 0
        while (colon < length && line[colon].toInt() != COLON) {
            if (!Syntax.isTokenChar(line[colon].toInt() and BYTE_MASK)) reject("invalid character in header name")
            colon++
        }
        if (colon == length) reject("header line without a colon")
        if (colon == 0) reject("empty header name")
        var valueStart = colon + 1
        var valueEnd = length
        while (valueStart < valueEnd && Syntax.isWhitespace(line[valueStart].toInt())) valueStart++
        while (valueEnd > valueStart && Syntax.isWhitespace(line[valueEnd - 1].toInt())) valueEnd--
        checkFieldSizes(colon, valueEnd - valueStart)
        for (i in valueStart until valueEnd) {
            if (!Syntax.isFieldValueChar(line[i].toInt() and BYTE_MASK)) reject("invalid character in header value")
        }
        headers +=
            RtspHeader(
                String(line, 0, colon, Charsets.ISO_8859_1),
                String(line, valueStart, valueEnd - valueStart, Charsets.ISO_8859_1),
            )
    }

    private fun checkFieldSizes(
        nameLength: Int,
        valueLength: Int,
    ) {
        if (nameLength > limits.maxHeaderNameBytes) {
            reject("header name longer than ${limits.maxHeaderNameBytes} bytes", RtspStatus.HEADER_FIELDS_TOO_LARGE)
        }
        if (valueLength > limits.maxHeaderValueBytes) {
            reject("header value longer than ${limits.maxHeaderValueBytes} bytes", RtspStatus.HEADER_FIELDS_TOO_LARGE)
        }
    }

    private fun endOfHeaders(): T? {
        if (headers.any { it.name.equals(Syntax.TRANSFER_ENCODING, ignoreCase = true) }) {
            reject("Transfer-Encoding is not supported", RtspStatus.NOT_IMPLEMENTED)
        }
        val cap = minOf(limits.maxBodyBytes, head.maxBodyBytes(headers))
        val length = head.bodyLength(contentLength(cap))
        if (length == 0) return finishMessage()
        body = ByteArray(length)
        bodyFilled = 0
        state = State.BODY
        return null
    }

    /** The validated `Content-Length`: digits only, every copy equal, within the body cap. */
    private fun contentLength(cap: Int): Int? {
        var result: Int? = null
        for (header in headers.filter { it.name.equals(Syntax.CONTENT_LENGTH, ignoreCase = true) }) {
            val value = parseLength(header.value, cap)
            if (result != null && result != value) reject("conflicting Content-Length values")
            result = value
        }
        return result
    }

    private fun parseLength(
        text: String,
        cap: Int,
    ): Int {
        if (text.isEmpty()) reject("empty Content-Length")
        var value = 0L
        for (c in text) {
            if (c !in '0'..'9') reject("Content-Length is not a decimal number")
            value = value * DECIMAL + (c - '0')
            if (value > cap) {
                reject("body larger than $cap bytes", RtspStatus.CONTENT_TOO_LARGE)
            }
        }
        return value.toInt()
    }

    private fun finishMessage(): T {
        val message = head.complete(RtspHeaders(ArrayList(headers)), body)
        state = State.START_LINE
        headers.clear()
        headerBytes = 0
        skippedBytes = 0
        body = EMPTY_BODY
        bodyFilled = 0
        return message
    }

    private companion object {
        const val INITIAL_LINE_CAPACITY = 256
        const val CRLF_LENGTH = 2
        const val BYTE_MASK = 0xFF
        const val COLON = ':'.code
        const val DECIMAL = 10
    }
}

/** Throws the parse failure; [status] is the response the session layer may send before closing. */
internal fun reject(
    message: String,
    status: Int = RtspStatus.BAD_REQUEST,
): Nothing = throw RtspException(message, status)
