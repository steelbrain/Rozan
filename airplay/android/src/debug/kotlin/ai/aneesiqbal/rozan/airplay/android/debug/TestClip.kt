package ai.aneesiqbal.rozan.airplay.android.debug

import ai.aneesiqbal.rozan.airplay.android.video.AnnexB
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import android.content.Context
import java.io.ByteArrayOutputStream

/**
 * An H.264 Annex-B clip split the way the receiver delivers mirroring video:
 * parameter sets on their own, then one access unit per frame (split at the
 * access unit delimiters the fixture script puts in). Debug and test use only.
 */
internal class TestClip(
    val parameterSets: ByteArray,
    val accessUnits: List<ByteArray>,
    val width: Int,
    val height: Int,
) {
    companion object {
        /** The bundled 1280×720, 30 fps, 2 s clip (debug assets). */
        const val ASSET_720P = "airplay/test-clip-720p.h264"
        const val FRAME_RATE = 30

        fun load(
            context: Context,
            asset: String = ASSET_720P,
            width: Int = 1280,
            height: Int = 720,
        ): TestClip = parse(context.assets.open(asset).use { it.readBytes() }, width, height)

        fun parse(
            data: ByteArray,
            width: Int,
            height: Int,
        ): TestClip {
            val parameterSets = ByteArrayOutputStream()
            val units = ArrayList<ByteArray>()
            var current = ByteArrayOutputStream()
            var nal = AnnexB.nextNalStart(data, 0, data.size)
            while (nal >= 0) {
                val next = AnnexB.nextNalStart(data, nal + 1, data.size)
                var end = if (next < 0) data.size else next - 3
                while (end > nal && data[end - 1].toInt() == 0) end--
                when (AnnexB.nalType(VideoCodec.H264, data, nal)) {
                    NAL_AUD -> {
                        if (current.size() > 0) units += current.toByteArray()
                        current = ByteArrayOutputStream()
                    }

                    NAL_SPS, NAL_PPS -> {
                        if (units.isEmpty()) append(parameterSets, data, nal, end)
                    }

                    else -> {
                        append(current, data, nal, end)
                    }
                }
                nal = next
            }
            if (current.size() > 0) units += current.toByteArray()
            return TestClip(parameterSets.toByteArray(), units, width, height)
        }

        private fun append(
            out: ByteArrayOutputStream,
            data: ByteArray,
            from: Int,
            to: Int,
        ) {
            out.write(START_CODE)
            out.write(data, from, to - from)
        }

        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_AUD = 9
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
