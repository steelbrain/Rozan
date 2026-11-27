package ai.aneesiqbal.rozan.airplay.media

/** H.264 framing for the mirroring stream (03b §3.3, §3.4). */
internal object H264 {
    /** NAL unit type of an IDR slice. */
    const val NAL_IDR: Int = 5

    private const val START_CODE_BYTES = 4
    private const val AVCC_MIN_BYTES = 7
    private const val NAL_TYPE_MASK = 0x1F
    private const val FORBIDDEN_BIT = 0x80
    private const val LENGTH_SIZE_MASK = 0x03

    /**
     * Converts an avcC record (`AVCDecoderConfigurationRecord`) in `source[offset, offset + length)` to
     * Annex-B parameter sets: every SPS, then every PPS, each behind `00 00 00 01`. Applies every bound of
     * 03 §8.2.
     *
     * @return the parameter sets, or null when the record is invalid or exceeds a bound.
     */
    fun parseAvcC(
        source: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray? {
        val valid =
            length >= AVCC_MIN_BYTES &&
                source[offset].toInt() == 1 &&
                (source.u8(offset + 4) and LENGTH_SIZE_MASK) == LENGTH_SIZE_MASK
        if (!valid) return null
        val reader = AvcCReader(source, offset + AVCC_SPS_COUNT_OFFSET + 1, offset + length)
        val spsCount = source.u8(offset + AVCC_SPS_COUNT_OFFSET) and NAL_TYPE_MASK
        if (spsCount !in 1..MediaLimits.MAX_SPS_COUNT || !reader.copySets(spsCount)) return null
        val ppsCount = reader.nextByte()
        if (ppsCount !in 1..MediaLimits.MAX_PPS_COUNT || !reader.copySets(ppsCount)) return null
        // Bytes after the PPS list (the High-profile extension) are ignored.
        return reader.result()
    }

    /**
     * Rewrites the AVCC access unit `buffer[offset, offset + length)` (4-byte big-endian NAL lengths) to
     * Annex-B in place by replacing each length with `00 00 00 01`. Every length must be at least 1 and
     * inside the buffer, every NAL must have its forbidden bit clear, and the NALs must fill the range
     * exactly. Allocates nothing.
     *
     * @return a mask with bit `t` set for every NAL type `t` present, or 0 when the walk fails (the range
     *   is then partly rewritten and must be dropped). An empty range also gives 0.
     */
    fun avccToAnnexB(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var mask = 0
        var position = 0
        while (position < length) {
            if (length - position < START_CODE_BYTES + 1) return 0
            val nalLength = buffer.i32Be(offset + position)
            // A negative int is a length of 2^31 or more, which never fits.
            if (nalLength < 1 || nalLength > length - position - START_CODE_BYTES) return 0
            val header = buffer.u8(offset + position + START_CODE_BYTES)
            if ((header and FORBIDDEN_BIT) != 0) return 0
            writeStartCode(buffer, offset + position)
            mask = mask or (1 shl (header and NAL_TYPE_MASK))
            position += START_CODE_BYTES + nalLength
        }
        return mask
    }

    /** Whether `source[offset + 4, offset + 8)` is `"hvc1"`, the marker of an HEVC configuration. */
    fun isHevcConfig(
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean =
        length >= HVC1_OFFSET + HVC1.size &&
            HVC1.indices.all { source[offset + HVC1_OFFSET + it] == HVC1[it] }

    private fun writeStartCode(
        buffer: ByteArray,
        offset: Int,
    ) {
        buffer[offset] = 0
        buffer[offset + 1] = 0
        buffer[offset + 2] = 0
        buffer[offset + 3] = 1
    }

    private const val MAX_TOTAL_NALS = MediaLimits.MAX_SPS_COUNT + MediaLimits.MAX_PPS_COUNT
    private const val AVCC_SPS_COUNT_OFFSET = 5
    private const val HVC1_OFFSET = 4
    private val HVC1 = byteArrayOf(0x68, 0x76, 0x63, 0x31)

    /** Copies length-prefixed parameter sets out of an avcC record, enforcing the bounds. */
    private class AvcCReader(
        private val source: ByteArray,
        private var position: Int,
        private val end: Int,
    ) {
        private val out = ByteArray(MediaLimits.MAX_PARAMETER_SET_BYTES + START_CODE_BYTES * MAX_TOTAL_NALS)
        private var written = 0
        private var nalBytes = 0

        /** The next byte, or -1 at the end of the record. */
        fun nextByte(): Int = if (position < end) source.u8(position++) else -1

        /** Copies [count] `[u16be length][NAL]` entries; false when one is out of bounds. */
        fun copySets(count: Int): Boolean {
            repeat(count) {
                if (end - position < 2) return false
                val nalLength = source.u16Be(position)
                position += 2
                val fits =
                    nalLength in 1..MediaLimits.MAX_PARAMETER_SET_NAL_BYTES &&
                        nalLength <= end - position &&
                        nalBytes + nalLength <= MediaLimits.MAX_PARAMETER_SET_BYTES
                if (!fits) return false
                writeStartCode(out, written)
                source.copyInto(out, written + START_CODE_BYTES, position, position + nalLength)
                written += START_CODE_BYTES + nalLength
                nalBytes += nalLength
                position += nalLength
            }
            return true
        }

        /** The Annex-B parameter sets copied so far. */
        fun result(): ByteArray = out.copyOf(written)
    }
}
