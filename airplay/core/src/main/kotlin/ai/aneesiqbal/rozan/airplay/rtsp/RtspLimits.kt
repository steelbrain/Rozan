package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Size limits applied while parsing a message. Anything larger is rejected before it is buffered, so a
 * parser never holds more than about `maxStartLineBytes + maxHeaderBytes + maxBodyBytes` bytes.
 *
 * @property maxStartLineBytes longest request line (or status line), excluding its CRLF. Over the limit
 *   fails with 414.
 * @property maxHeaderBytes longest header section: the header lines after the start line, each counted
 *   with its CRLF, not counting the empty line that ends the section. Over the limit fails with 431.
 * @property maxHeaderCount most header fields in one message, duplicates counted. Over the limit fails
 *   with 431.
 * @property maxBodyBytes largest `Content-Length` accepted. Over the limit fails with 413. Zero rejects
 *   every body. A request parser may lower it per request (see [RtspBodyLimit]).
 * @property maxHeaderNameBytes longest header field name. Over the limit fails with 431.
 * @property maxHeaderValueBytes longest header field value, after trimming. Over the limit fails with 431.
 */
public data class RtspLimits(
    val maxStartLineBytes: Int = DEFAULT_START_LINE_BYTES,
    val maxHeaderBytes: Int = DEFAULT_HEADER_BYTES,
    val maxHeaderCount: Int = DEFAULT_HEADER_COUNT,
    val maxBodyBytes: Int = DEFAULT_BODY_BYTES,
    val maxHeaderNameBytes: Int = DEFAULT_HEADER_BYTES,
    val maxHeaderValueBytes: Int = DEFAULT_HEADER_BYTES,
) {
    init {
        require(maxStartLineBytes > 0) { "maxStartLineBytes must be positive" }
        require(maxHeaderBytes > 0) { "maxHeaderBytes must be positive" }
        require(maxHeaderCount > 0) { "maxHeaderCount must be positive" }
        require(maxBodyBytes >= 0) { "maxBodyBytes must not be negative" }
        require(maxHeaderNameBytes > 0) { "maxHeaderNameBytes must be positive" }
        require(maxHeaderValueBytes >= 0) { "maxHeaderValueBytes must not be negative" }
    }

    /** Default values. */
    public companion object {
        /** 8 KiB. */
        public const val DEFAULT_START_LINE_BYTES: Int = 8 * 1024

        /** 32 KiB. */
        public const val DEFAULT_HEADER_BYTES: Int = 32 * 1024

        /** 100 fields. */
        public const val DEFAULT_HEADER_COUNT: Int = 100

        /** 2 MiB. */
        public const val DEFAULT_BODY_BYTES: Int = 2 * 1024 * 1024
    }
}
