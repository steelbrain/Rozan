package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspStatus
import java.util.Base64
import java.util.Locale

/**
 * The stream an `ANNOUNCE` describes in the classic RAOP flow (docs/protocol/01 §6.17, 03 §1.6): ALAC with
 * the realtime stream's fixed configuration, a FairPlay-wrapped key and the CBC IV. The arrays are the
 * caller's to zero with [destroy].
 *
 * @property fairPlayKeyBlob `a=fpaeskey`, the 72-byte FairPlay key blob (04 §1.5).
 * @property audioIv `a=aesiv`, the 16-byte IV of every packet (03 §2.2).
 * @property framesPerPacket the `fmtp` frameLength, 1..4096: the stream's `spf`.
 * @property latencyMinFrames `a=min-latency`, clamped, or null when absent or malformed.
 * @property latencyMaxFrames `a=max-latency`, clamped, or null when absent or malformed.
 */
internal class SessionDescription(
    val fairPlayKeyBlob: ByteArray,
    val audioIv: ByteArray,
    val framesPerPacket: Int,
    val latencyMinFrames: Int?,
    val latencyMaxFrames: Int?,
) {
    /** Zeroes the key blob and the IV. */
    fun destroy() {
        fairPlayKeyBlob.fill(0)
        audioIv.fill(0)
    }

    companion object {
        /** Largest SDP body (01 §6.17); the framer refuses a larger ANNOUNCE body with 413. */
        const val MAX_BYTES = 8 * 1024

        /** Most lines in a body, empty ones not counted. */
        const val MAX_LINES = 64

        /** Longest line, without its line ending. */
        const val MAX_LINE_BYTES = 1024

        /** Size of the `fpaeskey` blob. */
        const val KEY_BLOB_BYTES = 72

        /** Size of the `aesiv`. */
        const val IV_BYTES = 16

        /** The only payload type: the data socket accepts PT 96 only (03c §4.2). */
        private const val PAYLOAD_TYPE = "96"
        private const val ENCODING_ALAC = "AppleLossless"
        private const val FMTP_FIELDS = 11
        private const val MAX_KEY_CHARS = 128
        private const val MAX_IV_CHARS = 32
        private const val TAB = 0x09
        private const val SPACE = 0x20
        private const val DEL = 0x7F

        /**
         * The `ALACSpecificConfig` fields after frameLength that must equal 03c §4.8.1's values, by index:
         * compatibleVersion, bitDepth, pb, mb, kb, numChannels, maxRun. maxFrameBytes and avgBitRate (7, 8)
         * may be anything; sampleRate (9) is checked on its own.
         */
        private val FIXED_FMTP = longArrayOf(0, 16, 40, 10, 14, 2, 255)
        private const val FMTP_SAMPLE_RATE_INDEX = 10

        /**
         * Parses and validates [body] (01 §6.17).
         *
         * @throws RtspException 400 for a malformed or oversized description, 415 for a well-formed one of a
         *   stream this receiver does not play (another codec or media type, RSA or no encryption, another
         *   ALAC configuration).
         */
        fun parse(body: ByteArray): SessionDescription {
            if (body.isEmpty()) bad("ANNOUNCE without an SDP body")
            if (body.size > MAX_BYTES) bad("SDP longer than $MAX_BYTES bytes")
            val fields = Fields()
            var lines = 0
            var start = 0
            while (start <= body.size) {
                var end = start
                while (end < body.size && body[end] != '\n'.code.toByte()) end++
                var stop = end
                if (stop > start && body[stop - 1] == '\r'.code.toByte()) stop--
                if (stop > start) {
                    if (++lines > MAX_LINES) bad("SDP has more than $MAX_LINES lines")
                    fields.line(line(body, start, stop))
                }
                start = end + 1
            }
            return fields.build()
        }

        private fun line(
            body: ByteArray,
            start: Int,
            end: Int,
        ): String {
            if (end - start > MAX_LINE_BYTES) bad("SDP line longer than $MAX_LINE_BYTES bytes")
            for (i in start until end) {
                val b = body[i].toInt() and 0xFF
                if ((b < SPACE && b != TAB) || b == DEL) bad("SDP line with a control byte")
            }
            // Byte for byte; only ASCII fields are interpreted.
            val text = String(body, start, end - start, Charsets.ISO_8859_1)
            if (text.length < 2 || text[0] !in 'a'..'z' || text[1] != '=') bad("SDP line is not <type>=<value>")
            return text
        }

        /** The lines that matter, collected first and judged at the end, so a codec mismatch wins over syntax. */
        private class Fields {
            var versions = 0
            var version: String? = null
            val media = ArrayList<String>(1)
            var rtpmap: String? = null
            var fmtp: String? = null
            var fpaeskey: String? = null
            var aesiv: String? = null
            var rsaaeskey = false
            var minLatency: String? = null
            var maxLatency: String? = null

            fun line(text: String) {
                val value = text.substring(2)
                when (text[0]) {
                    'v' -> {
                        versions++
                        version = value.trim()
                    }

                    'm' -> {
                        media += value
                    }

                    'a' -> {
                        attribute(value)
                    }

                    // o=, s=, c=, t= and the rest are ignored: the c= address is never used (01 §6.17).
                    else -> {}
                }
            }

            private fun attribute(value: String) {
                val name = value.substringBefore(':')
                val content = value.substringAfter(':', missingDelimiterValue = "").trim()
                when (name) {
                    "rtpmap" -> rtpmap = forPayloadType(content, rtpmap, "rtpmap")
                    "fmtp" -> fmtp = forPayloadType(content, fmtp, "fmtp")
                    "fpaeskey" -> fpaeskey = once(content, fpaeskey, name)
                    "aesiv" -> aesiv = once(content, aesiv, name)
                    "rsaaeskey" -> rsaaeskey = true
                    "min-latency" -> minLatency = content
                    "max-latency" -> maxLatency = content
                }
            }

            /** The text after `96 ` of an `rtpmap`/`fmtp` for payload type 96; other payload types are ignored. */
            private fun forPayloadType(
                content: String,
                previous: String?,
                name: String,
            ): String? {
                val type = content.substringBefore(' ').trim()
                if (type != PAYLOAD_TYPE) return previous
                return once(content.substringAfter(' ', missingDelimiterValue = "").trim(), previous, name)
            }

            private fun once(
                value: String,
                previous: String?,
                name: String,
            ): String {
                if (previous != null) bad("SDP repeats $name")
                return value
            }

            fun build(): SessionDescription {
                if (versions != 1 || version != "0") bad("SDP without exactly one v=0")
                checkMedia()
                if (rsaaeskey) unsupported("RSA-encrypted audio (rsaaeskey) is not supported")
                val encoding = rtpmap ?: bad("SDP without an rtpmap for payload type 96")
                if (!encoding.substringBefore('/').trim().equals(ENCODING_ALAC, ignoreCase = true)) {
                    unsupported("only AppleLossless audio is supported")
                }
                val framesPerPacket = alacConfig(fmtp ?: bad("SDP without an fmtp for payload type 96"))
                val key = fpaeskey ?: unsupported("unencrypted audio (no fpaeskey) is not supported")
                val iv = aesiv ?: bad("SDP without an aesiv")
                val keyBlob = base64(key, MAX_KEY_CHARS, KEY_BLOB_BYTES, "fpaeskey")
                val audioIv = base64(iv, MAX_IV_CHARS, IV_BYTES, "aesiv")
                return SessionDescription(keyBlob, audioIv, framesPerPacket, latency(minLatency), latency(maxLatency))
            }

            /** Exactly one `m=audio <port> RTP/AVP 96`. */
            private fun checkMedia() {
                if (media.isEmpty()) bad("SDP without a media line")
                if (media.size > 1) unsupported("SDP with more than one media stream")
                val tokens = media[0].split(' ', '\t').filter { it.isNotEmpty() }
                if (tokens.size < 4) bad("malformed SDP media line")
                when {
                    tokens[0] != "audio" -> unsupported("SDP media ${clip(tokens[0])} is not audio")
                    tokens[2] != "RTP/AVP" -> unsupported("SDP media profile is not RTP/AVP")
                    tokens.size != 4 || tokens[3] != PAYLOAD_TYPE -> unsupported("SDP media format is not 96 alone")
                }
            }
        }

        /** The 11 `fmtp` fields (03c §4.8.1); returns frameLength. */
        private fun alacConfig(text: String): Int {
            val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size != FMTP_FIELDS) bad("fmtp for AppleLossless needs $FMTP_FIELDS fields")
            val values =
                LongArray(FMTP_FIELDS) { TextParameters.u32(parts[it]) ?: bad("fmtp field is not a 32-bit decimal") }
            val frameLength = values[0]
            if (frameLength !in 1..SetupRequest.MAX_ALAC_SPF.toLong()) unsupported("ALAC frame length out of range")
            for ((index, expected) in FIXED_FMTP.withIndex()) {
                if (values[index + 1] != expected) unsupported("unsupported ALAC configuration")
            }
            if (values[FMTP_SAMPLE_RATE_INDEX] != SetupRequest.SAMPLE_RATE.toLong()) {
                unsupported("unsupported audio sample rate")
            }
            return frameLength.toInt()
        }

        /** Base64 of exactly [bytes] bytes, padding optional, at most [maxChars] characters. */
        private fun base64(
            text: String,
            maxChars: Int,
            bytes: Int,
            name: String,
        ): ByteArray {
            if (text.isEmpty() || text.length > maxChars) bad("$name has the wrong length")
            val decoded =
                try {
                    Base64.getDecoder().decode(text)
                } catch (e: IllegalArgumentException) {
                    throw RtspException("$name is not base64", cause = e)
                }
            if (decoded.size != bytes) {
                decoded.fill(0)
                bad("$name is not $bytes bytes")
            }
            return decoded
        }

        private fun latency(text: String?): Int? =
            text?.let(TextParameters::u32)?.coerceAtMost(SetupRequest.MAX_LATENCY_FRAMES.toLong())?.toInt()

        private fun clip(text: String): String = text.take(16)

        private fun bad(message: String): Nothing = throw RtspException(message)

        private fun unsupported(message: String): Nothing =
            throw RtspException(message, RtspStatus.UNSUPPORTED_MEDIA_TYPE)
    }
}

/**
 * The sender's ports from the `Transport` header of a classic SETUP (01 §6.8.1), each 0 when absent or out
 * of range.
 *
 * @property clientControlPort `control_port`: where resend requests go (03c §4.5).
 * @property clientTimingPort `timing_port`: where NTP requests go (03c §4.6).
 */
internal class ClassicTransport(
    val clientControlPort: Int,
    val clientTimingPort: Int,
) {
    companion object {
        /** Most `;` parameters read. */
        const val MAX_PARAMETERS = 16

        private const val MAX_PORT = 65_535
        private const val MAX_PORT_DIGITS = 5
        private val PROTOCOLS = setOf("RTP/AVP/UDP", "RTP/AVP")

        /**
         * Parses the first transport spec of [header] (01 §6.8.1). Media never goes to an address from it.
         *
         * @throws RtspException 400 when malformed, 461 when it asks for something other than UDP unicast
         *   in record mode.
         */
        fun parse(header: String): ClassicTransport {
            val spec = header.substringBefore(',')
            if (spec.count { it == ';' } > MAX_PARAMETERS) bad("Transport has more than $MAX_PARAMETERS parameters")
            val parts = spec.split(';')
            val protocol = parts[0].trim()
            if (protocol.isEmpty()) bad("Transport without a protocol")
            if (protocol.uppercase(Locale.ROOT) !in PROTOCOLS) unsupported("Transport is not RTP/AVP over UDP")
            val parameters = HashMap<String, String?>()
            for (index in 1 until parts.size) {
                val parameter = parts[index].trim()
                val name = parameter.substringBefore('=').trim().lowercase(Locale.ROOT)
                if (name.isEmpty()) bad("empty Transport parameter")
                val value = if ('=' in parameter) parameter.substringAfter('=').trim() else null
                if (parameters.containsKey(name)) bad("Transport repeats a parameter")
                parameters[name] = value
            }
            if ("multicast" in parameters) unsupported("multicast Transport is not supported")
            if (parameters.containsKey("mode")) {
                val mode = parameters["mode"].orEmpty().trim('"')
                if (!mode.equals("record", ignoreCase = true)) unsupported("Transport mode is not record")
            }
            return ClassicTransport(port(parameters["control_port"]), port(parameters["timing_port"]))
        }

        /** The reply's `Transport` value: the local [dataPort], [controlPort] and [timingPort] (01 §6.8.1). */
        fun reply(
            dataPort: Int,
            controlPort: Int,
            timingPort: Int,
        ): String =
            "RTP/AVP/UDP;unicast;mode=record;server_port=$dataPort;control_port=$controlPort;timing_port=$timingPort"

        private fun port(value: String?): Int {
            if (value == null || value.isEmpty() || value.length > MAX_PORT_DIGITS) return 0
            if (!value.all { it in '0'..'9' }) return 0
            return value.toInt().takeIf { it in 1..MAX_PORT } ?: 0
        }

        private fun bad(message: String): Nothing = throw RtspException(message)

        private fun unsupported(message: String): Nothing =
            throw RtspException(message, RtspStatus.UNSUPPORTED_TRANSPORT)
    }
}
