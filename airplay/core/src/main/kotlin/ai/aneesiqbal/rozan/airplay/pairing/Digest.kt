package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto

/**
 * RTSP Digest authentication as AirPlay uses it for the per-connection code (docs/protocol/02-pairing.md
 * §6, RFC 2617): realm `raop`, MD5, and an optional `qop`.
 */
internal object Digest {
    /** The realm AirPlay senders expect, verbatim. */
    const val REALM: String = "raop"

    /** Length of the nonces this receiver issues: 16 random bytes as lowercase hex. */
    const val NONCE_LENGTH: Int = 32

    private const val SCHEME = "digest"
    private const val MAX_HEADER_LENGTH = 2048
    private const val MAX_PARAMETERS = 16

    /** The `WWW-Authenticate` value for [nonce]: exactly the two parameters senders expect (02 §6.1). */
    fun challenge(nonce: String): String = "Digest realm=\"$REALM\", nonce=\"$nonce\""

    /**
     * Parses an `Authorization: Digest ...` header into its parameters, names lower-cased. Returns null
     * for another scheme, a malformed or oversized header, or repeated parameters.
     */
    fun parse(header: String): Map<String, String>? {
        if (header.length > MAX_HEADER_LENGTH) return null
        val trimmed = header.trim()
        if (trimmed.length < SCHEME.length || !trimmed.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
            return null
        }
        if (trimmed.length > SCHEME.length && !trimmed[SCHEME.length].isWhitespace()) return null
        return DigestParameterParser(trimmed, SCHEME.length).parse()
    }

    /**
     * The expected `response` for [parameters] (from [parse]) when the password is [code] and the request
     * method is [method], or null when a required parameter is missing. Without `qop`:
     * `MD5(HA1:nonce:HA2)`; with it: `MD5(HA1:nonce:nc:cnonce:qop:HA2)`, where `HA1 = MD5(user:realm:code)`
     * and `HA2 = MD5(method:uri)`, all as lowercase hex.
     */
    fun expectedResponse(
        parameters: Map<String, String>,
        code: String,
        method: String,
    ): String? {
        val qop = parameters["qop"]
        val needed = if (qop == null) REQUIRED else REQUIRED + REQUIRED_WITH_QOP
        if (needed.any { it !in parameters }) return null
        val value: (String) -> String = { parameters.getValue(it) }
        val ha1 = md5Hex("${value("username")}:${value("realm")}:$code")
        val ha2 = md5Hex("$method:${value("uri")}")
        val nonce = value("nonce")
        return if (qop == null) {
            md5Hex("$ha1:$nonce:$ha2")
        } else {
            md5Hex("$ha1:$nonce:${value("nc")}:${value("cnonce")}:$qop:$ha2")
        }
    }

    private val REQUIRED = listOf("username", "realm", "nonce", "uri")
    private val REQUIRED_WITH_QOP = listOf("nc", "cnonce")

    /** Whether the `response` in [parameters] matches [code], compared in constant time. */
    fun matches(
        parameters: Map<String, String>,
        code: String,
        method: String,
    ): Boolean {
        val expected = expectedResponse(parameters, code, method) ?: return false
        val received = parameters["response"]?.lowercase() ?: return false
        return Crypto.constantTimeEquals(
            expected.toByteArray(Charsets.US_ASCII),
            received.toByteArray(Charsets.US_ASCII),
        )
    }

    /** Lowercase hex MD5 of the UTF-8 bytes of [text]. */
    fun md5Hex(text: String): String = toHex(Crypto.digest("MD5", text.toByteArray(Charsets.UTF_8)))

    /** Lowercase hex of [bytes]. */
    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            chars[2 * i] = HEX[b ushr 4]
            chars[2 * i + 1] = HEX[b and 0xF]
        }
        return String(chars)
    }

    private const val HEX = "0123456789abcdef"

    /** Parses `name=value` and `name="value"` pairs separated by commas, from [start] in [text]. */
    private class DigestParameterParser(
        private val text: String,
        private var pos: Int,
    ) {
        private val out = HashMap<String, String>()

        fun parse(): Map<String, String>? {
            while (true) {
                skipWhile { it == ' ' || it == '\t' || it == ',' }
                if (pos >= text.length) return out
                if (!readParameter()) return null
            }
        }

        /** Reads one `name=value`; false when it is malformed, repeated or one too many. */
        private fun readParameter(): Boolean {
            val name = readToken()?.lowercase() ?: return false
            skipWhile { it == ' ' || it == '\t' }
            if (pos >= text.length || text[pos] != '=') return false
            pos++
            skipWhile { it == ' ' || it == '\t' }
            val value = (if (pos < text.length && text[pos] == '"') readQuoted() else readToken()) ?: return false
            return out.size < MAX_PARAMETERS && out.put(name, value) == null
        }

        private inline fun skipWhile(skip: (Char) -> Boolean) {
            while (pos < text.length && skip(text[pos])) pos++
        }

        private fun readToken(): String? {
            val start = pos
            while (pos < text.length && isTokenChar(text[pos])) pos++
            return if (pos == start) null else text.substring(start, pos)
        }

        private fun readQuoted(): String? {
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> if (pos < text.length) sb.append(text[pos++]) else return null
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun isTokenChar(c: Char): Boolean =
            when (c) {
                ' ', '\t', ',', '=', '"' -> false
                else -> c.code in PRINTABLE_ASCII
            }
    }

    private val PRINTABLE_ASCII = 0x21..0x7E
}
