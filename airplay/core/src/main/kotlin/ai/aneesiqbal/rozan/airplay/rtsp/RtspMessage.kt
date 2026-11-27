package ai.aneesiqbal.rozan.airplay.rtsp

import java.io.ByteArrayOutputStream

/** A request or response on the control channel. */
public sealed interface RtspMessage {
    /** Protocol version from the start line. */
    public val protocol: RtspProtocol

    /** Header fields in wire order. */
    public val headers: RtspHeaders

    /**
     * The body: exactly `Content-Length` bytes, or empty. The array is shared, not copied, so do not
     * modify it.
     */
    public val body: ByteArray

    /** The `CSeq` header, which RTSP requires on every message, or null. */
    public val cSeq: String? get() = headers[Syntax.CSEQ]

    /**
     * Serializes the message: start line, the headers in order, a `Content-Length` computed from [body],
     * an empty line, then the body. Any `Content-Length` in [headers] is dropped in favour of the computed
     * one. Requests carry `Content-Length` only when they have a body; responses always do, except 1xx,
     * 204 and 304, which have no body.
     */
    public fun toByteArray(): ByteArray
}

internal val EMPTY_BODY: ByteArray = ByteArray(0)

/** Writes the wire form of a message; every string has already been validated as ISO-8859-1. */
internal object MessageWriter {
    private val CRLF = byteArrayOf(Syntax.CR.toByte(), Syntax.LF.toByte())
    private const val HEADER_ESTIMATE = 32

    fun write(
        startLine: String,
        headers: RtspHeaders,
        body: ByteArray,
        writeContentLength: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream(startLine.length + headers.size * HEADER_ESTIMATE + body.size + HEADER_ESTIMATE)
        out.latin1(startLine)
        out.write(CRLF, 0, CRLF.size)
        for (header in headers) {
            if (header.name.equals(Syntax.CONTENT_LENGTH, ignoreCase = true)) continue
            out.latin1(header.name)
            out.latin1(": ")
            out.latin1(header.value)
            out.write(CRLF, 0, CRLF.size)
        }
        if (writeContentLength) {
            out.latin1(Syntax.CONTENT_LENGTH)
            out.latin1(": ")
            out.latin1(body.size.toString())
            out.write(CRLF, 0, CRLF.size)
        }
        out.write(CRLF, 0, CRLF.size)
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    /** Writes [text] one byte per char; callers pass only validated ISO-8859-1 strings. */
    private fun ByteArrayOutputStream.latin1(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        write(bytes, 0, bytes.size)
    }
}
