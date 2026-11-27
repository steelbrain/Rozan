package ai.aneesiqbal.rozan.airplay.android.video

import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec

/**
 * Minimal Annex-B helpers: locate NAL units by start code and read their type.
 * Inputs come from the network, so every index is bounds-checked and nothing
 * allocates on the per-frame paths ([isKeyFrame], [nextNalStart]).
 */
internal object AnnexB {
    private const val AVC_TYPE_MASK = 0x1F
    private const val AVC_IDR = 5
    private const val AVC_SLICE = 1
    private const val AVC_SPS = 7
    private const val AVC_PPS = 8
    private const val HEVC_FIRST_IRAP = 16
    private const val HEVC_LAST_IRAP = 23
    private const val HEVC_FIRST_NON_VCL = 32

    /**
     * Index of the first NAL payload byte after the next `00 00 01` start code
     * at or after [from], or -1 when there is none before [end].
     */
    fun nextNalStart(
        data: ByteArray,
        from: Int,
        end: Int,
    ): Int {
        var i = from.coerceAtLeast(0)
        val limit = minOf(end, data.size) - 2
        while (i < limit) {
            if ((data[i + 2].toInt() and 0xFF) > 1) {
                i += 3
            } else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                return i + 3
            } else {
                i++
            }
        }
        return -1
    }

    /** NAL unit type of the NAL whose first byte is at [index]. */
    fun nalType(
        codec: VideoCodec,
        data: ByteArray,
        index: Int,
    ): Int {
        val header = data[index].toInt() and 0xFF
        return when (codec) {
            VideoCodec.H264 -> header and AVC_TYPE_MASK
            VideoCodec.HEVC -> (header ushr 1) and 0x3F
        }
    }

    /**
     * Whether the access unit starts a new coded video sequence (H.264 IDR, or
     * an HEVC IRAP picture), i.e. decoding can start from it. Stops at the
     * first slice, so the cost is independent of the frame size.
     */
    fun isKeyFrame(
        codec: VideoCodec,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        val end = offset + length
        var nal = nextNalStart(data, offset, end)
        while (nal in 0 until end) {
            val type = nalType(codec, data, nal)
            when (codec) {
                VideoCodec.H264 -> if (type == AVC_IDR || type == AVC_SLICE) return type == AVC_IDR
                VideoCodec.HEVC -> if (type < HEVC_FIRST_NON_VCL) return type in HEVC_FIRST_IRAP..HEVC_LAST_IRAP
            }
            nal = nextNalStart(data, nal + 1, end)
        }
        return false
    }

    /**
     * Splits H.264 parameter sets into `csd-0` (SPS) and `csd-1` (PPS), each
     * in Annex-B form with 4-byte start codes. Returns null unless both exist.
     * Runs once per (re)configuration, so allocating here is fine.
     */
    fun splitAvcParameterSets(parameterSets: ByteArray): Pair<ByteArray, ByteArray>? {
        val sps = java.io.ByteArrayOutputStream()
        val pps = java.io.ByteArrayOutputStream()
        var nal = nextNalStart(parameterSets, 0, parameterSets.size)
        while (nal >= 0 && nal < parameterSets.size) {
            val next = nextNalStart(parameterSets, nal + 1, parameterSets.size)
            var nalEnd = if (next < 0) parameterSets.size else next - 3
            // A 4-byte start code leaves a zero byte that belongs to the next code, not this NAL.
            while (nalEnd > nal && parameterSets[nalEnd - 1].toInt() == 0) nalEnd--
            val target =
                when (nalType(VideoCodec.H264, parameterSets, nal)) {
                    AVC_SPS -> sps
                    AVC_PPS -> pps
                    else -> null
                }
            target?.write(START_CODE)
            target?.write(parameterSets, nal, nalEnd - nal)
            nal = next
        }
        if (sps.size() == 0 || pps.size() == 0) return null
        return sps.toByteArray() to pps.toByteArray()
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
}
