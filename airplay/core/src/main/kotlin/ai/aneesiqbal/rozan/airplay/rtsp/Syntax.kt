package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Character classes from RFC 9110 §5.6 and RFC 9112, shared by RTSP/1.0 (RFC 2326 uses the same grammar).
 * Bytes are passed as unsigned ints (0..255) and strings are treated as ISO-8859-1, one char per byte.
 */
internal object Syntax {
    const val CR: Int = 0x0D
    const val LF: Int = 0x0A
    const val SP: Int = 0x20
    const val HTAB: Int = 0x09

    const val CONTENT_LENGTH: String = "Content-Length"
    const val CONTENT_TYPE: String = "Content-Type"
    const val TRANSFER_ENCODING: String = "Transfer-Encoding"
    const val CSEQ: String = "CSeq"

    private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"
    private const val ASCII_LIMIT = 0x80
    private const val DEL = 0x7F
    private const val BYTE_MAX = 0xFF

    private val tokenChars: BooleanArray =
        BooleanArray(ASCII_LIMIT) { c ->
            c.toChar().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS }
        }

    /** tchar: the characters allowed in methods and header names. */
    fun isTokenChar(c: Int): Boolean = c in 0 until ASCII_LIMIT && tokenChars[c]

    /** field-vchar / SP / HTAB: anything visible, obs-text (0x80..0xFF), space and tab. */
    fun isFieldValueChar(c: Int): Boolean = c == HTAB || (c in SP..BYTE_MAX && c != DEL)

    /** Visible ASCII without space: what a request target may contain. */
    fun isTargetChar(c: Int): Boolean = c in (SP + 1) until DEL

    fun isWhitespace(c: Int): Boolean = c == SP || c == HTAB

    fun isToken(text: String): Boolean = text.isNotEmpty() && text.all { isTokenChar(it.code) }

    fun isTarget(text: String): Boolean = text.isNotEmpty() && text.all { isTargetChar(it.code) }

    /** A field value with no leading or trailing whitespace, as the parser would produce it. */
    fun isFieldValue(text: String): Boolean =
        text.all { isFieldValueChar(it.code) } &&
            (text.isEmpty() || (!isWhitespace(text.first().code) && !isWhitespace(text.last().code)))
}
