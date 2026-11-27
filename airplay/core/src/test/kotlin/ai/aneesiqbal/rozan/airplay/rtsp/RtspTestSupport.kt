package ai.aneesiqbal.rozan.airplay.rtsp

/** ISO-8859-1 bytes of [text], one byte per char, as the wire format uses. */
fun latin1(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

/** Parses [text] in one chunk and returns the requests, failing the test if any byte is left over. */
fun parseAll(
    text: String,
    limits: RtspLimits = RtspLimits(),
): List<RtspRequest> {
    val parser = RtspRequestParser(limits)
    val requests = parser.feed(latin1(text))
    parser.finish()
    return requests
}

/** Parses exactly one request from [text]. */
fun parseOne(
    text: String,
    limits: RtspLimits = RtspLimits(),
): RtspRequest = parseAll(text, limits).single()
