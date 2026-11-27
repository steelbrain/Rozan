/*
 * Ported from Apple's ALAC reference decoder, Copyright (c) 2011 Apple Inc., Apache License 2.0.
 *
 * Source: codec/ALACAudioTypes.h (ALACSpecificConfig) and the magic cookie parsing in
 * codec/ALACDecoder.cpp (ALACDecoder::Init) from https://github.com/macosforge/alac.
 * Modified: translated to Kotlin; every field is range-checked; added the AirPlay SDP fmtp form.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.aneesiqbal.rozan.airplay.alac

/**
 * The `ALACSpecificConfig` that describes an Apple Lossless stream: the 24 bytes at the start of an ALAC magic cookie,
 * or the numbers in an AirPlay (RAOP) SDP `fmtp` attribute.
 *
 * Every field is range-checked when the object is created (including through [copy]); a value outside its range
 * throws [AlacException]. The decoder only uses [frameLength], [bitDepth], [pb], [mb], [kb] and [numChannels]; the
 * other fields are carried for completeness.
 *
 * @property frameLength Frames per packet for packets that do not state their own count, 1..[MAX_FRAME_LENGTH].
 *   Encoders default to 4096; AirPlay uses 352.
 * @property compatibleVersion Bitstream version. Only 0 exists.
 * @property bitDepth Bits per sample: 16, 20, 24 or 32.
 * @property pb Adaptive Golomb history multiplier, 0..255. Encoders write 40.
 * @property mb Initial adaptive Golomb history, 0..255. Encoders write 10.
 * @property kb Adaptive Golomb parameter limit, 0..31. Encoders write 14; ffmpeg writes 0 (with [pb] and [mb] 0)
 *   for streams it stores uncompressed. Compressed packets need at least 1.
 * @property numChannels Channel count, 1..[MAX_CHANNELS].
 * @property maxRun Unused by the decoder, 0..65535. Apple's encoder writes 255.
 * @property maxFrameBytes Largest packet in bytes, or 0 if unknown; 0..4294967295.
 * @property avgBitRate Average bit rate in bits per second, or 0 if unknown; 0..4294967295.
 * @property sampleRate Sample rate in Hz, 1..[MAX_SAMPLE_RATE].
 * @throws AlacException if a field is out of range.
 */
public data class AlacConfig(
    public val frameLength: Int,
    public val compatibleVersion: Int,
    public val bitDepth: Int,
    public val pb: Int,
    public val mb: Int,
    public val kb: Int,
    public val numChannels: Int,
    public val maxRun: Int,
    public val maxFrameBytes: Long,
    public val avgBitRate: Long,
    public val sampleRate: Int,
) {
    init {
        requireRange("frameLength", frameLength.toLong(), 1, MAX_FRAME_LENGTH.toLong())
        // The reference decoder rejects compatibleVersion > kALACVersion (0).
        requireRange("compatibleVersion", compatibleVersion.toLong(), 0, 0)
        when (bitDepth) {
            16, 20, 24, 32 -> Unit
            else -> invalid("bitDepth $bitDepth is not 16, 20, 24 or 32")
        }
        requireRange("pb", pb.toLong(), 0, U8_MAX)
        requireRange("mb", mb.toLong(), 0, U8_MAX)
        // 32 and up would make (1 << kb) undefined in the reference. 0 is only valid for uncompressed streams; the
        // decoder rejects compressed packets then.
        requireRange("kb", kb.toLong(), 0, MAX_KB)
        requireRange("numChannels", numChannels.toLong(), 1, MAX_CHANNELS.toLong())
        requireRange("maxRun", maxRun.toLong(), 0, U16_MAX)
        requireRange("maxFrameBytes", maxFrameBytes, 0, U32_MAX)
        requireRange("avgBitRate", avgBitRate, 0, U32_MAX)
        requireRange("sampleRate", sampleRate.toLong(), 1, MAX_SAMPLE_RATE.toLong())
    }

    /** Factories and limits. */
    public companion object {
        /** Largest [frameLength] the format supports. */
        public const val MAX_FRAME_LENGTH: Int = 16384

        /** Largest [numChannels] the format supports. */
        public const val MAX_CHANNELS: Int = 8

        /** Largest [sampleRate] the format documents. */
        public const val MAX_SAMPLE_RATE: Int = 384_000

        /** Size of the serialized `ALACSpecificConfig` in bytes. */
        public const val SIZE_BYTES: Int = 24

        private const val ATOM_HEADER_BYTES = 12
        private const val FMTP_FIELDS = 12
        private const val MAX_FMTP_LENGTH = 256
        private const val MAX_U32_DIGITS = 10
        private const val MAX_PAYLOAD_TYPE = 127L
        private const val MAX_KB = 31L
        private const val U8_MAX = 0xffL
        private const val U16_MAX = 0xffffL
        private const val U32_MAX = 0xffff_ffffL

        /**
         * Parses an ALAC magic cookie, as stored in a CAF `kuki` chunk or an MP4 `alac` sample entry.
         *
         * Like Apple's decoder, this accepts the bare 24-byte `ALACSpecificConfig` as well as one preceded by a
         * 12-byte `frma` atom and/or a 12-byte `alac` atom header. Bytes after the config (the optional channel
         * layout info) are ignored.
         *
         * @throws AlacException if the cookie is too short or a field is out of range.
         */
        public fun fromMagicCookie(cookie: ByteArray): AlacConfig {
            var pos = 0
            if (hasAtomType(cookie, pos, "frma")) pos += ATOM_HEADER_BYTES
            if (hasAtomType(cookie, pos, "alac")) pos += ATOM_HEADER_BYTES
            if (cookie.size - pos < SIZE_BYTES) {
                invalid("magic cookie has ${cookie.size} bytes, too few for a $SIZE_BYTES-byte ALAC config")
            }
            return AlacConfig(
                frameLength = intField("frameLength", u32(cookie, pos)),
                compatibleVersion = u8(cookie, pos + 4),
                bitDepth = u8(cookie, pos + 5),
                pb = u8(cookie, pos + 6),
                mb = u8(cookie, pos + 7),
                kb = u8(cookie, pos + 8),
                numChannels = u8(cookie, pos + 9),
                maxRun = (u8(cookie, pos + 10) shl 8) or u8(cookie, pos + 11),
                maxFrameBytes = u32(cookie, pos + 12),
                avgBitRate = u32(cookie, pos + 16),
                sampleRate = intField("sampleRate", u32(cookie, pos + 20)),
            )
        }

        /**
         * Parses the value of an AirPlay (RAOP) SDP `a=fmtp` attribute for ALAC, without the `a=fmtp:` prefix:
         * twelve unsigned decimal numbers separated by spaces or tabs, for example
         * `"96 352 0 16 40 10 14 2 255 0 0 44100"`.
         *
         * The numbers are, in order: RTP payload type (0..127, checked and discarded), [frameLength],
         * [compatibleVersion], [bitDepth], [pb], [mb], [kb], [numChannels], [maxRun], [maxFrameBytes], [avgBitRate]
         * and [sampleRate].
         *
         * @throws AlacException if the value is not twelve such numbers or a field is out of range.
         */
        public fun fromFmtp(fmtp: String): AlacConfig {
            if (fmtp.length > MAX_FMTP_LENGTH) invalid("fmtp value is longer than $MAX_FMTP_LENGTH characters")
            val fields = fmtp.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (fields.size != FMTP_FIELDS) invalid("fmtp value has ${fields.size} fields, expected $FMTP_FIELDS")
            val values = LongArray(FMTP_FIELDS) { parseU32(fields[it], it) }
            requireRange("payload type", values[0], 0, MAX_PAYLOAD_TYPE)
            return AlacConfig(
                frameLength = intField("frameLength", values[1]),
                compatibleVersion = intField("compatibleVersion", values[2]),
                bitDepth = intField("bitDepth", values[3]),
                pb = intField("pb", values[4]),
                mb = intField("mb", values[5]),
                kb = intField("kb", values[6]),
                numChannels = intField("numChannels", values[7]),
                maxRun = intField("maxRun", values[8]),
                maxFrameBytes = values[9],
                avgBitRate = values[10],
                sampleRate = intField("sampleRate", values[11]),
            )
        }

        private fun hasAtomType(
            bytes: ByteArray,
            pos: Int,
            type: String,
        ): Boolean {
            if (bytes.size - pos < ATOM_HEADER_BYTES) return false
            for (i in type.indices) {
                if (bytes[pos + 4 + i].toInt() != type[i].code) return false
            }
            return true
        }

        private fun u8(
            bytes: ByteArray,
            pos: Int,
        ): Int = bytes[pos].toInt() and 0xff

        private fun u32(
            bytes: ByteArray,
            pos: Int,
        ): Long =
            (u8(bytes, pos).toLong() shl 24) or (u8(bytes, pos + 1).toLong() shl 16) or
                (u8(bytes, pos + 2).toLong() shl 8) or u8(bytes, pos + 3).toLong()

        /** Parses an unsigned 32-bit decimal number; [index] (0-based) is only used in the error message. */
        private fun parseU32(
            token: String,
            index: Int,
        ): Long {
            if (token.length > MAX_U32_DIGITS || token.any { it !in '0'..'9' }) {
                invalid("fmtp field ${index + 1} is not an unsigned decimal number of at most $MAX_U32_DIGITS digits")
            }
            val value = token.toLong()
            if (value > U32_MAX) invalid("fmtp field ${index + 1} does not fit in 32 bits")
            return value
        }

        /** Narrows a field that is wider on the wire than in [AlacConfig]; the constructor checks the exact range. */
        private fun intField(
            name: String,
            value: Long,
        ): Int {
            if (value > Int.MAX_VALUE) invalid("$name $value is out of range")
            return value.toInt()
        }

        private fun requireRange(
            name: String,
            value: Long,
            min: Long,
            max: Long,
        ) {
            if (value < min || value > max) invalid("$name $value is outside $min..$max")
        }

        private fun invalid(message: String): Nothing = throw AlacException(message)
    }
}
