package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.alac.AlacConfig
import ai.aneesiqbal.rozan.airplay.alac.AlacDecoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the sender puts on the wire, checked with real decoders and the bundled clip. */
class MediaFormatsTest {
    /** The cookie a receiver builds for AirPlay ALAC (03c §4.8.1). */
    private val cookie = hex("00000160001028" + "0a0e0200ff00000000000000000000ac44")

    @Test
    fun `escape frames decode to the samples they carry`() {
        val decoder = AlacDecoder(AlacConfig.fromMagicCookie(cookie))
        val out = ShortArray(decoder.minOutputSize)
        val pcm = AlacFrames.tone(12_345, 352)
        val frame = AlacFrames.encode(pcm, 352, 352)
        assertEquals(0x20, frame.u8(0), "a channel pair element, instance 0")
        assertEquals(AlacFrames.size(352), frame.size)
        assertEquals(352, decoder.decode(frame, 0, frame.size, out))
        assertContentEquals(pcm, out.copyOf(704))

        val partial = AlacFrames.encode(pcm, 100, 352)
        assertEquals(AlacFrames.size(100, partial = true), partial.size)
        assertEquals(100, decoder.decode(partial, 0, partial.size, out))
        assertContentEquals(pcm.copyOf(200), out.copyOf(200))
    }

    @Test
    fun `the bundled 720p clip splits into 60 frames with an IDR every second`() {
        val clip = H264Clip.parse(File(System.getProperty("rozan.testing.clip")).readBytes())
        assertEquals(1280, clip.width)
        assertEquals(720, clip.height)
        assertEquals(60, clip.frames.size)
        assertEquals(listOf(0, 30), clip.frames.indices.filter { clip.frames[it].isIdr })
        assertEquals(7, clip.sps[0].toInt() and 0x1F)
        assertEquals(8, clip.pps[0].toInt() and 0x1F)
        for (frame in clip.frames) {
            val types = frame.nalUnits.map { it[0].toInt() and 0x1F }
            assertTrue(types.none { it == 7 || it == 8 || it == 9 }, "parameter sets and delimiters stay out: $types")
            assertTrue(types.any { it == 1 || it == 5 }, "every frame has a slice: $types")
        }
    }

    @Test
    fun `access units split at slices that start a picture, and SPS sizes honour cropping`() {
        // libx264 output: high profile 1920x1080 (68 macroblock rows cropped by 8 lines, emulation
        // prevention bytes inside) and baseline 1280x720.
        val sps = hex("67640028acd940780227e5c044000003000400000300f03c60c658")
        assertEquals(1920 to 1080, SpsSize.parse(sps))
        assertEquals(1280 to 720, SpsSize.parse(hex("6742c01fd9005005bb0110000003001000000303c0f1832480")))
        val pps = hex("68ebe3cb22c0")
        val idr = hex("6588840021")
        val slice2 = hex("650088840021") // first_mb_in_slice != 0: same picture
        val p1 = hex("419a0021")
        val sei = hex("0605ff")
        val p2 = hex("419a0042")
        val stream =
            listOf(sps, pps, idr, slice2, p1, sei, p2).fold(ByteArray(0)) { acc, nal ->
                acc + byteArrayOf(0, 0, 0, 1) + nal
            }
        val clip = H264Clip.parse(stream)
        assertEquals(3, clip.frames.size)
        assertEquals(listOf(true, false, false), clip.frames.map { it.isIdr })
        assertEquals(2, clip.frames[0].nalUnits.size)
        assertContentEquals(sei, clip.frames[2].nalUnits[0])
        assertEquals(1920, clip.width)
    }

    @Test
    fun `the codec record is an avcC with 4-byte lengths`() {
        val sps = hex("6742c01e95a0f0")
        val pps = hex("68ce3880")
        assertContentEquals(
            hex("0142c01effe10007" + "6742c01e95a0f0" + "010004" + "68ce3880"),
            MirroringSender.avcC(sps, pps),
        )
        assertContentEquals(
            hex("00000002" + "6501" + "00000001" + "41"),
            MirroringSender.avcc(listOf(hex("6501"), hex("41"))),
        )
    }
}
