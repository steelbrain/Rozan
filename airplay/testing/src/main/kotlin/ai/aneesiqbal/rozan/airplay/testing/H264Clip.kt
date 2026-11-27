package ai.aneesiqbal.rozan.airplay.testing

/**
 * An H.264 Annex-B elementary stream cut into what a mirroring sender sends: the parameter sets for the
 * codec record, and one access unit per frame (docs/protocol/03b-mirroring-video.md §3.3, §3.4).
 *
 * @property sps the first sequence parameter set NAL unit (no start code).
 * @property pps the first picture parameter set NAL unit (no start code).
 * @property frames the access units in decoding order.
 * @property width the coded width in pixels after cropping, from the SPS.
 * @property height the coded height in pixels after cropping, from the SPS.
 */
public class H264Clip(
    public val sps: ByteArray,
    public val pps: ByteArray,
    public val frames: List<Frame>,
    public val width: Int,
    public val height: Int,
) {
    /**
     * One access unit.
     *
     * @property nalUnits the NAL units without start codes, excluding parameter sets and delimiters.
     * @property isIdr whether it holds an IDR slice.
     */
    public class Frame(
        public val nalUnits: List<ByteArray>,
        public val isIdr: Boolean,
    )

    /** Parsing. */
    public companion object {
        /**
         * Splits an Annex-B stream into access units: a new one starts at an access unit delimiter, at a
         * parameter set or SEI after a slice, or at a slice whose `first_mb_in_slice` is 0 after a slice
         * (H.264 §7.4.1.2.3, enough for encoder output).
         *
         * @throws IllegalArgumentException if the stream has no SPS, PPS or slice.
         */
        public fun parse(annexB: ByteArray): H264Clip {
            val splitter = AccessUnitSplitter()
            for (nal in nalUnits(annexB)) splitter.add(nal)
            splitter.finishUnit()
            val sps = requireNotNull(splitter.sps) { "the stream has no SPS" }
            val pps = requireNotNull(splitter.pps) { "the stream has no PPS" }
            require(splitter.frames.isNotEmpty()) { "the stream has no slices" }
            val size = SpsSize.parse(sps)
            return H264Clip(sps, pps, splitter.frames, size.first, size.second)
        }

        /** The NAL units of [annexB], start codes and trailing zero bytes removed. */
        internal fun nalUnits(annexB: ByteArray): List<ByteArray> {
            val units = ArrayList<ByteArray>()
            var start = nextStart(annexB, 0)
            while (start >= 0) {
                val next = nextStart(annexB, start)
                var end = if (next < 0) annexB.size else next - 3
                while (end > start && annexB[end - 1].toInt() == 0) end--
                if (end > start) units += annexB.copyOfRange(start, end)
                start = next
            }
            return units
        }

        /** The index after the next `00 00 01` at or after [from], or -1. */
        private fun nextStart(
            data: ByteArray,
            from: Int,
        ): Int {
            var i = from
            while (i + 2 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i + 3
                i++
            }
            return -1
        }
    }
}

/** Groups NAL units into access units for [H264Clip.parse], keeping the first SPS and PPS aside. */
private class AccessUnitSplitter {
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    val frames = ArrayList<H264Clip.Frame>()
    private var current = ArrayList<ByteArray>()
    private var hasSlice = false
    private var idr = false

    fun add(nal: ByteArray) {
        when (val type = nal[0].toInt() and 0x1F) {
            NAL_AUD -> {
                finishUnit()
            }

            NAL_SPS -> {
                if (hasSlice) finishUnit()
                if (sps == null) sps = nal
            }

            NAL_PPS -> {
                if (hasSlice) finishUnit()
                if (pps == null) pps = nal
            }

            else -> {
                addToUnit(type, nal)
            }
        }
    }

    private fun addToUnit(
        type: Int,
        nal: ByteArray,
    ) {
        val slice = type == NAL_SLICE || type == NAL_IDR
        val startsPicture = slice && nal.size > 1 && (nal[1].toInt() and 0x80) != 0
        if (hasSlice && (type == NAL_SEI || startsPicture)) finishUnit()
        current += nal
        if (slice) hasSlice = true
        if (type == NAL_IDR) idr = true
    }

    fun finishUnit() {
        if (hasSlice) frames += H264Clip.Frame(current, idr)
        current = ArrayList()
        hasSlice = false
        idr = false
    }

    private companion object {
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SEI = 6
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val NAL_AUD = 9
    }
}

/** The picture size from an H.264 SPS (§7.3.2.1.1), after frame cropping. */
internal object SpsSize {
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    fun parse(sps: ByteArray): Pair<Int, Int> {
        val bits = ExpGolomb(unescape(sps), 8)
        val profile = bits.bits(8)
        bits.bits(16) // constraint flags, level
        bits.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = bits.ue()
            if (chromaFormat == 3) bits.bits(1) // separate_colour_plane_flag
            bits.ue() // bit_depth_luma_minus8
            bits.ue() // bit_depth_chroma_minus8
            bits.bits(1) // qpprime_y_zero_transform_bypass_flag
            if (bits.bits(1) == 1) skipScalingLists(bits, if (chromaFormat == 3) 12 else 8)
        }
        bits.ue() // log2_max_frame_num_minus4
        val pocType = bits.ue()
        if (pocType == 0) {
            bits.ue() // log2_max_pic_order_cnt_lsb_minus4
        } else if (pocType == 1) {
            bits.bits(1) // delta_pic_order_always_zero_flag
            bits.se() // offset_for_non_ref_pic
            bits.se() // offset_for_top_to_bottom_field
            repeat(bits.ue()) { bits.se() } // offset_for_ref_frame[]
        }
        bits.ue() // max_num_ref_frames
        bits.bits(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = bits.ue() + 1
        val heightMapUnits = bits.ue() + 1
        val frameMbsOnly = bits.bits(1)
        if (frameMbsOnly == 0) bits.bits(1) // mb_adaptive_frame_field_flag
        bits.bits(1) // direct_8x8_inference_flag
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightMapUnits * 16
        if (bits.bits(1) == 1) {
            val left = bits.ue()
            val right = bits.ue()
            val top = bits.ue()
            val bottom = bits.ue()
            val cropX = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
            val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            width -= (left + right) * cropX
            height -= (top + bottom) * cropY
        }
        return width to height
    }

    private fun skipScalingLists(
        bits: ExpGolomb,
        count: Int,
    ) {
        for (i in 0 until count) {
            if (bits.bits(1) == 0) continue
            val size = if (i < 6) 16 else 64
            var last = 8
            var next = 8
            for (j in 0 until size) {
                if (next != 0) next = (last + bits.se() + 256) % 256
                if (next != 0) last = next
            }
        }
    }

    /** Removes emulation prevention bytes (`00 00 03` -> `00 00`). */
    private fun unescape(nal: ByteArray): ByteArray {
        val out = ArrayList<Byte>(nal.size)
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            out += b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** An MSB-first bit reader with Exp-Golomb codes. */
    private class ExpGolomb(
        private val data: ByteArray,
        private var position: Int,
    ) {
        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                require(position < data.size * 8) { "the SPS ends early" }
                value = (value shl 1) or ((data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1)
                position++
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros++
                require(zeros < 32) { "Exp-Golomb code too long" }
            }
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
