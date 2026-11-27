package ai.aneesiqbal.rozan.airplay.alac

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlacConfigTest {
    /** The ALACSpecificConfig ffmpeg writes for 16-bit stereo, 44.1 kHz. */
    private val bareCookie =
        hex("00001000 00 10 28 0a 0e 02 0000 00004004 00158880 0000ac44")

    private val expected =
        AlacConfig(
            frameLength = 4096,
            compatibleVersion = 0,
            bitDepth = 16,
            pb = 40,
            mb = 10,
            kb = 14,
            numChannels = 2,
            maxRun = 0,
            maxFrameBytes = 16388,
            avgBitRate = 1_411_200,
            sampleRate = 44100,
        )

    private val frmaAtom = hex("0000000c 66726d61 616c6163") // size 12, 'frma', 'alac'
    private val alacAtom = hex("00000024 616c6163 00000000") // size 36, 'alac', version/flags
    private val channelLayout = hex("00000018 6368616e 00000000 00650002 00000000 00000000") // 'chan', stereo

    @Test
    fun `cookie from a CAF fixture has the frma and alac atoms and parses`() {
        val cookie = Fixtures.caf("sweep-stereo16").cookie
        assertEquals(48, cookie.size)
        assertEquals(expected, AlacConfig.fromMagicCookie(cookie))
    }

    @Test
    fun `cookie is accepted bare, with either atom header and with channel layout info`() {
        assertEquals(expected, AlacConfig.fromMagicCookie(bareCookie))
        assertEquals(expected, AlacConfig.fromMagicCookie(alacAtom + bareCookie))
        assertEquals(expected, AlacConfig.fromMagicCookie(frmaAtom + bareCookie))
        assertEquals(expected, AlacConfig.fromMagicCookie(frmaAtom + alacAtom + bareCookie))
        assertEquals(expected, AlacConfig.fromMagicCookie(bareCookie + channelLayout))
        assertEquals(expected, AlacConfig.fromMagicCookie(frmaAtom + alacAtom + bareCookie + channelLayout))
    }

    @Test
    fun `every truncation of a cookie is rejected`() {
        for (cookie in listOf(bareCookie, alacAtom + bareCookie, frmaAtom + alacAtom + bareCookie)) {
            for (length in 0 until cookie.size) {
                assertFailsWith<AlacException>("length $length of ${cookie.size}") {
                    AlacConfig.fromMagicCookie(cookie.copyOf(length))
                }
            }
        }
    }

    @Test
    fun `cookie fields wider than their range are rejected`() {
        fun withByte(
            index: Int,
            value: Int,
        ) = bareCookie.copyOf().also { it[index] = value.toByte() }
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(0, 0x80)) } // frameLength >= 2^31
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(1, 0x01)) } // frameLength 65536 + 4096
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(4, 1)) } // compatibleVersion
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(5, 8)) } // bitDepth
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(8, 32)) } // kb
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(9, 0)) } // numChannels
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(9, 9)) } // numChannels
        assertFailsWith<AlacException> { AlacConfig.fromMagicCookie(withByte(20, 0xff)) } // sampleRate
    }

    @Test
    fun `random cookies either parse to a valid config or throw AlacException`() {
        val random = Random(1)
        val wrappings = listOf(bareCookie, alacAtom + bareCookie, frmaAtom + alacAtom + bareCookie)
        var parsed = 0
        repeat(20_000) {
            val cookie =
                if (random.nextBoolean()) {
                    random.nextBytes(random.nextInt(0, 80))
                } else {
                    // A valid cookie with a few random bytes and a random tail, so that some of them parse.
                    val base = wrappings[random.nextInt(wrappings.size)] + random.nextBytes(random.nextInt(0, 30))
                    repeat(random.nextInt(0, 3)) { base[random.nextInt(base.size)] = random.nextInt().toByte() }
                    base
                }
            try {
                val config = AlacConfig.fromMagicCookie(cookie)
                AlacDecoder(config) // must be constructible from any accepted config
                parsed++
            } catch (expected: AlacException) {
                assertEquals(AlacException::class, expected::class)
            }
        }
        assertTrue(parsed in 1000..19_000, "parsed $parsed of 20000")
    }

    @Test
    fun `AirPlay fmtp parses`() {
        val config = AlacConfig.fromFmtp("96 352 0 16 40 10 14 2 255 0 0 44100")
        assertEquals(
            AlacConfig(
                frameLength = 352,
                compatibleVersion = 0,
                bitDepth = 16,
                pb = 40,
                mb = 10,
                kb = 14,
                numChannels = 2,
                maxRun = 255,
                maxFrameBytes = 0,
                avgBitRate = 0,
                sampleRate = 44100,
            ),
            config,
        )
    }

    @Test
    fun `fmtp tolerates surrounding and repeated whitespace and the full uint32 range`() {
        val config = AlacConfig.fromFmtp("\t 96  4096 0 24 40 10 14 8 65535 4294967295 4294967295 384000 \r\n")
        assertEquals(4096, config.frameLength)
        assertEquals(24, config.bitDepth)
        assertEquals(8, config.numChannels)
        assertEquals(65535, config.maxRun)
        assertEquals(0xffff_ffffL, config.maxFrameBytes)
        assertEquals(0xffff_ffffL, config.avgBitRate)
        assertEquals(384_000, config.sampleRate)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "96 352 0 16 40 10 14 2 255 0 0", // 11 fields
            "96 352 0 16 40 10 14 2 255 0 0 44100 1", // 13 fields
            "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100",
            "96 352 0 16 40 10 14 2 255 0 0 44100.0",
            "96 -352 0 16 40 10 14 2 255 0 0 44100",
            "96 +352 0 16 40 10 14 2 255 0 0 44100",
            "96 0x160 0 16 40 10 14 2 255 0 0 44100",
            "96 352 0 16 40 10 14 2 255 4294967296 0 44100", // maxFrameBytes > 32 bits
            "96 352 0 16 40 10 14 2 255 0 0 99999999999", // 11 digits
            "128 352 0 16 40 10 14 2 255 0 0 44100", // payload type > 127
            "96 0 0 16 40 10 14 2 255 0 0 44100", // frameLength 0
            "96 16385 0 16 40 10 14 2 255 0 0 44100", // frameLength > 16384
            "96 352 1 16 40 10 14 2 255 0 0 44100", // compatibleVersion
            "96 352 0 8 40 10 14 2 255 0 0 44100", // bitDepth
            "96 352 0 18 40 10 14 2 255 0 0 44100", // bitDepth
            "96 352 0 16 256 10 14 2 255 0 0 44100", // pb
            "96 352 0 16 40 256 14 2 255 0 0 44100", // mb
            "96 352 0 16 40 10 32 2 255 0 0 44100", // kb
            "96 352 0 16 40 10 14 0 255 0 0 44100", // numChannels
            "96 352 0 16 40 10 14 9 255 0 0 44100", // numChannels
            "96 352 0 16 40 10 14 2 65536 0 0 44100", // maxRun
            "96 352 0 16 40 10 14 2 255 0 0 0", // sampleRate
            "96 352 0 16 40 10 14 2 255 0 0 384001", // sampleRate
            "96 352 0 16 40 10 14 2 255 0 0 4294967295", // sampleRate
            "96 352 0 16 40 10 14 2 255 0 0 44100\n96", // a newline inside is not a separator
        ],
    )
    fun `malformed fmtp is rejected`(fmtp: String) {
        assertFailsWith<AlacException> { AlacConfig.fromFmtp(fmtp) }
    }

    @Test
    fun `overlong fmtp is rejected before parsing`() {
        assertFailsWith<AlacException> { AlacConfig.fromFmtp("96 352 0 16 40 10 14 2 255 0 0 44100" + " ".repeat(300)) }
    }

    @Test
    fun `random fmtp strings either parse or throw AlacException`() {
        val random = Random(2)
        val alphabet = "0123456789 \t-+x."
        repeat(20_000) {
            val text = String(CharArray(random.nextInt(0, 60)) { alphabet[random.nextInt(alphabet.length)] })
            try {
                AlacConfig.fromFmtp(text)
            } catch (expected: AlacException) {
                assertEquals(AlacException::class, expected::class)
            }
        }
    }

    @Test
    fun `constructor and copy validate every field`() {
        assertFailsWith<AlacException> { expected.copy(frameLength = 0) }
        assertFailsWith<AlacException> { expected.copy(frameLength = 16385) }
        assertFailsWith<AlacException> { expected.copy(compatibleVersion = -1) }
        assertFailsWith<AlacException> { expected.copy(bitDepth = 0) }
        assertFailsWith<AlacException> { expected.copy(pb = -1) }
        assertFailsWith<AlacException> { expected.copy(mb = 256) }
        assertFailsWith<AlacException> { expected.copy(kb = -1) }
        assertFailsWith<AlacException> { expected.copy(numChannels = 9) }
        assertFailsWith<AlacException> { expected.copy(maxRun = -1) }
        assertFailsWith<AlacException> { expected.copy(maxFrameBytes = -1) }
        assertFailsWith<AlacException> { expected.copy(avgBitRate = 0x1_0000_0000L) }
        assertFailsWith<AlacException> { expected.copy(sampleRate = 0) }
        for (depth in listOf(16, 20, 24, 32)) assertEquals(depth, expected.copy(bitDepth = depth).bitDepth)
        assertEquals(16384, expected.copy(frameLength = 16384).frameLength)
        assertEquals(0, expected.copy(kb = 0, pb = 0, mb = 0).kb) // ffmpeg's uncompressed streams
    }

    private fun hex(text: String): ByteArray {
        val digits = text.filter { !it.isWhitespace() }
        return ByteArray(digits.length / 2) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
