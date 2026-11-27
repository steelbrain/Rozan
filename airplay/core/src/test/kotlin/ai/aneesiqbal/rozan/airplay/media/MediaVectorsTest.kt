package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 03d §A (keys), §H (codec configuration bytes) and §I (volume). */
class MediaVectorsTest {
    @Test
    fun `stream connection id constant is 0xC0FFEE1234567890`() {
        assertEquals("C0FFEE1234567890".toULong(16).toLong(), Vectors.STREAM_ID_A)
        assertEquals("13907095936298285200", Vectors.STREAM_ID_A.toULong().toString())
    }

    @Test
    fun `session key hashes the FairPlay key with the pair-verify secret`() {
        assertEquals(
            "43cc4f5b5ca43586ad422154f65b0e10",
            MediaKeys.sessionKey(Vectors.fairPlayKey, Vectors.ecdhSecret).toHex(),
        )
        assertEquals("00112233445566778899aabbccddeeff", MediaKeys.sessionKey(Vectors.fairPlayKey, null).toHex())
    }

    @Test
    fun `video keys read the stream connection id as unsigned`() {
        val paired = Vectors.sessionKey
        assertEquals("8bb49b485fce49813f8f44aea781cc0c", MediaKeys.videoKey(paired, Vectors.STREAM_ID_A).toHex())
        assertEquals("29a8a98d0a98fff567f7493e8a363eab", MediaKeys.videoIv(paired, Vectors.STREAM_ID_A).toHex())
        assertEquals("b2f2826031075f94c19a4d3c19809eba", MediaKeys.videoKey(paired, Vectors.STREAM_ID_B).toHex())
        assertEquals("a52fdee21b82b9c2b37c04d6d7a9dc33", MediaKeys.videoIv(paired, Vectors.STREAM_ID_B).toHex())
    }

    @Test
    fun `video keys without pairing use the FairPlay key`() {
        val unpaired = Vectors.fairPlayKey
        assertEquals("25196fd897a9542649376f356c82f61f", MediaKeys.videoKey(unpaired, Vectors.STREAM_ID_A).toHex())
        assertEquals("c0a42d423b234d9d6004147074258124", MediaKeys.videoIv(unpaired, Vectors.STREAM_ID_A).toHex())
        assertEquals("011f9c98cdcdeb1eab09aac65852c181", MediaKeys.videoKey(unpaired, Vectors.STREAM_ID_B).toHex())
        assertEquals("35bba2700b7d27bf711eb861870bc464", MediaKeys.videoIv(unpaired, Vectors.STREAM_ID_B).toHex())
    }

    @Test
    fun `ALAC cookie matches 03d H`() {
        assertEquals(
            "000001600010280a0e0200ff00000000000000000000ac44",
            AudioFormats.alacCookie(352, 44_100).toHex(),
        )
    }

    @Test
    fun `AAC-ELD config matches 03d H`() {
        assertContentEquals(hex("f8e85000"), AudioFormats.aacEldConfig())
    }

    @Test
    fun `sink formats follow the compression type`() {
        val alac = AudioFormats.formatFor(audioRequest(ct = 2, spf = 352))
        assertIs<AudioFormat.Alac>(alac)
        assertEquals(352, alac.framesPerPacket)
        assertEquals(2, alac.channels)
        assertEquals(44_100, alac.sampleRate)
        assertContentEquals(AudioFormats.alacCookie(352, 44_100), alac.magicCookie)
        val eld = AudioFormats.formatFor(audioRequest(ct = 8, spf = 480))
        assertIs<AudioFormat.Aac>(eld)
        assertTrue(eld.eld)
        assertContentEquals(hex("f8e85000"), eld.audioSpecificConfig)
        assertNull(AudioFormats.formatFor(audioRequest(ct = 1, spf = 352)))
        assertNull(AudioFormats.formatFor(audioRequest(ct = 4, spf = 1024)))
    }

    @Test
    fun `first-byte plausibility follows 03c 4_2`() {
        assertTrue(AudioFormats.isPlausibleFirstByte(2, 0x20))
        assertFalse(AudioFormats.isPlausibleFirstByte(2, 0x21))
        for (b in listOf(0x8C, 0x8D, 0x8E, 0x80, 0x81, 0x82)) assertTrue(AudioFormats.isPlausibleFirstByte(8, b))
        for (b in listOf(0x8F, 0x83, 0x20, 0x00)) assertFalse(AudioFormats.isPlausibleFirstByte(8, b))
    }

    @Test
    fun `volume mapping matches 03d I`() {
        val cases =
            listOf(
                0f to 1.000000f,
                -7.5f to 0.421697f,
                -15f to 0.177828f,
                -22.5f to 0.074989f,
                -29.9f to 0.031989f,
                -30f to 0f,
                -144f to 0f,
            )
        for ((db, linear) in cases) {
            val actual = Volume.linearFromDb(db)
            assertEquals(linear, actual ?: Float.NaN, 0.0000015f, "volume $db dB")
        }
    }

    @Test
    fun `volume clamps and ignores non-finite values`() {
        assertEquals(0f, Volume.normalizeDb(3f))
        assertEquals(-30f, Volume.normalizeDb(-50f))
        assertEquals(-144f, Volume.normalizeDb(-144f))
        assertEquals(-12f, Volume.normalizeDb(-12f))
        assertNull(Volume.normalizeDb(Float.NaN))
        assertNull(Volume.normalizeDb(Float.POSITIVE_INFINITY))
        assertNull(Volume.linearFromDb(Float.NEGATIVE_INFINITY))
        assertEquals(1f, Volume.linearFromDb(6f))
        assertEquals(0f, Volume.linearFromDb(-100f))
    }
}

/** A type-96 request with the usual values. */
internal fun audioRequest(
    ct: Int = 2,
    spf: Int = 352,
    controlPort: Int = 0,
    usingScreen: Boolean = false,
): AudioStreamRequest =
    AudioStreamRequest(
        compressionType = ct,
        framesPerPacket = spf,
        sampleRate = 44_100,
        clientControlPort = controlPort,
        latencyMinFrames = null,
        latencyMaxFrames = null,
        usingScreen = usingScreen,
    )
