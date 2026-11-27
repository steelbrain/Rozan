package ai.aneesiqbal.rozan.airplay.session

/**
 * `text/parameters` bodies of GET_PARAMETER and SET_PARAMETER (docs/protocol/01 §6.10-§6.11, 03 §6.1):
 * lines of `name: value` (or a bare `name` in GET_PARAMETER), each ending in CRLF.
 */
internal object TextParameters {
    /** Most lines looked at in one body; senders send one. */
    const val MAX_LINES = 16

    /** Longest line looked at; longer ones are skipped. */
    const val MAX_LINE_CHARS = 128

    private const val MAX_U32 = 0xFFFF_FFFFL
    private const val MAX_U32_DIGITS = 10

    /** The trimmed lines of [body], at most [MAX_LINES], skipping empty and over-long ones. */
    fun lines(body: ByteArray): List<String> {
        val text = String(body, Charsets.ISO_8859_1)
        val out = ArrayList<String>()
        for (raw in text.splitToSequence('\n')) {
            if (out.size == MAX_LINES) break
            val line = raw.trim()
            if (line.isNotEmpty() && line.length <= MAX_LINE_CHARS) out += line
        }
        return out
    }

    /** The value of the `name: value` line for [name] (case-insensitive), or null. */
    fun value(
        lines: List<String>,
        name: String,
    ): String? =
        lines.firstNotNullOfOrNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals(name, ignoreCase = true)) {
                line.substring(colon + 1).trim()
            } else {
                null
            }
        }

    /** A `volume:` value in dB, or null when it is not a finite number (03 §6.1: ignore it). */
    fun volume(value: String): Float? = value.toFloatOrNull()?.takeIf { it.isFinite() }

    /**
     * `progress: <start>/<current>/<end>` as three unsigned 32-bit RTP timestamps, or null when malformed
     * (03 §8.1: ignore it).
     */
    fun progress(value: String): LongArray? {
        val parts = value.split('/')
        if (parts.size != 3) return null
        val out = LongArray(3)
        for (i in 0 until 3) out[i] = u32(parts[i].trim()) ?: return null
        return out
    }

    /**
     * The `RTP-Info` header of FLUSH/RECORD (03 §6.2, §8.1): `seq` (0..65535) and `rtptime`
     * (0..4294967295), each at most 10 decimal digits. A missing or malformed field is null.
     */
    fun rtpInfo(header: String?): RtpInfo {
        if (header == null) return RtpInfo(null, null)
        var seq: Int? = null
        var rtpTime: Long? = null
        for (part in header.split(';', ',')) {
            val name = part.substringBefore('=').trim().lowercase()
            val value = part.substringAfter('=', missingDelimiterValue = "").trim()
            when (name) {
                "seq" -> seq = u32(value)?.takeIf { it <= RtpInfo.MAX_SEQ }?.toInt()
                "rtptime" -> rtpTime = u32(value)
            }
        }
        return RtpInfo(seq, rtpTime)
    }

    /** A decimal unsigned 32-bit value of 1..10 digits, or null. */
    fun u32(text: String): Long? {
        if (text.isEmpty() || text.length > MAX_U32_DIGITS || !text.all { it in '0'..'9' }) return null
        return text.toLong().takeIf { it <= MAX_U32 }
    }
}

/** Parsed `RTP-Info`: the next sequence number and RTP timestamp, each null when absent or invalid. */
internal class RtpInfo(
    val seq: Int?,
    val rtpTime: Long?,
) {
    companion object {
        /** Largest RTP sequence number. */
        const val MAX_SEQ = 0xFFFFL
    }
}

/** Media type helpers for `Content-Type` values. */
internal object ContentTypes {
    /** Binary property list. */
    const val BPLIST = "application/x-apple-binary-plist"

    /** Raw bytes (pairing, FairPlay). */
    const val OCTET_STREAM = "application/octet-stream"

    /** `name: value` lines. */
    const val TEXT_PARAMETERS = "text/parameters"

    /** DMAP-tagged track metadata. */
    const val DMAP = "application/x-dmap-tagged"

    /** A session description, the body of a classic `ANNOUNCE` (01 §6.17). */
    const val SDP = "application/sdp"

    /** The media type of [header]: lowercase, without parameters, or null when absent. */
    fun mediaType(header: String?): String? = header?.substringBefore(';')?.trim()?.lowercase()
}
