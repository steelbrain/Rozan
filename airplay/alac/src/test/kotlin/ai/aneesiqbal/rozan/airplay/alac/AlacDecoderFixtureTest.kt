package ai.aneesiqbal.rozan.airplay.alac

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Bit-exact decoding of ffmpeg-encoded fixtures against the PCM they were encoded from. */
class AlacDecoderFixtureTest {
    @ParameterizedTest
    @MethodSource("pcm16Fixtures")
    fun `16-bit fixture decodes to its PCM byte for byte`(name: String) {
        val caf = Fixtures.caf(name)
        val config = AlacConfig.fromMagicCookie(caf.cookie)
        assertEquals("alac", caf.formatId)
        assertEquals(16, config.bitDepth)
        assertEquals(caf.channels, config.numChannels)
        assertEquals(caf.framesPerPacket, config.frameLength)
        val expected = Fixtures.bytes("${Fixtures.PCM16.getValue(name)}.raw")
        val decoder = AlacDecoder(config)

        Fixtures.assertPcmEquals(expected, Fixtures.decodeShorts(caf, decoder), caf.channels, 2)
        Fixtures.assertPcmEquals(expected, Fixtures.decodeInts(caf, decoder, 2), caf.channels, 2)
    }

    @ParameterizedTest
    @MethodSource("pcm24Fixtures")
    fun `24-bit fixture decodes to its PCM byte for byte`(name: String) {
        val caf = Fixtures.caf(name)
        val config = AlacConfig.fromMagicCookie(caf.cookie)
        assertEquals(24, config.bitDepth)
        assertEquals(caf.channels, config.numChannels)
        val expected = Fixtures.bytes("${Fixtures.PCM24.getValue(name)}.raw")
        val decoder = AlacDecoder(config)

        Fixtures.assertPcmEquals(expected, Fixtures.decodeInts(caf, decoder, 3), caf.channels, 3)
        val packet = caf.packets.first()
        assertFailsWith<IllegalArgumentException> {
            decoder.decode(packet, 0, packet.size, ShortArray(decoder.minOutputSize))
        }
    }

    @Test
    fun `AirPlay-shaped 352-frame packets decode with the RAOP fmtp config`() {
        val config = AlacConfig.fromFmtp(Fixtures.AIRPLAY_FMTP)
        val decoder = AlacDecoder(config)
        val out = ShortArray(decoder.minOutputSize)
        assertEquals(704, out.size)
        val asEncoded = ByteArrayOutputStream()
        val asAirPlay = ByteArrayOutputStream()
        for (i in 0 until Fixtures.AIRPLAY_PACKETS) {
            // ffmpeg always uses 4096-frame packets, so each 352-frame packet carries its own frame count.
            val packet = Fixtures.airplayPacket(i)
            assertEquals(352, decoder.decode(packet, 0, packet.size, out))
            appendS16(asEncoded, out, 704)

            // The same packet as a sender whose frameLength is 352 writes it: no partial-frame flag or count.
            val full = withoutFrameCount(packet)
            assertEquals(packet.size - 4, full.size)
            assertEquals(352, decoder.decode(full, 0, full.size, out))
            appendS16(asAirPlay, out, 704)
        }
        val expected = Fixtures.bytes("airplay352.raw")
        Fixtures.assertPcmEquals(expected, asEncoded.toByteArray(), 2, 2)
        Fixtures.assertPcmEquals(expected, asAirPlay.toByteArray(), 2, 2)
    }

    @Test
    fun `a packet inside a larger buffer decodes the same as on its own`() {
        val config = AlacConfig.fromFmtp(Fixtures.AIRPLAY_FMTP)
        val decoder = AlacDecoder(config)
        val packet = Fixtures.airplayPacket(3)
        val alone = ShortArray(decoder.minOutputSize)
        decoder.decode(packet, 0, packet.size, alone)

        val buffer = ByteArray(packet.size + 64) { 0x5a }
        packet.copyInto(buffer, 17)
        val embedded = ShortArray(decoder.minOutputSize + 10)
        assertEquals(352, decoder.decode(buffer, 17, packet.size, embedded))
        assertEquals(alone.toList(), embedded.copyOf(alone.size).toList())
    }

    @Test
    fun `fixtures cover every element header feature`() {
        val seen = HashSet<String>()
        for ((_, packet) in Fixtures.allPackets()) {
            val tag = bitsAt(packet, 0, 3)
            seen += if (tag == Element.CPE) "pair" else "single"
            val flags = bitsAt(packet, 19, 4)
            val partial = flags ushr 3 != 0
            if (partial) seen += "partial"
            if ((flags ushr 1) and 3 != 0) seen += "shifted"
            if (flags and 1 != 0) {
                seen += "uncompressed"
            } else {
                val mixStart = if (partial) 55 else 23
                if (tag == Element.CPE && bitsAt(packet, mixStart + 8, 8) != 0) seen += "mixed"
                seen +=
                    when (bitsAt(packet, mixStart + 24, 8) and 0x1f) {
                        4 -> "order 4"
                        8 -> "order 8"
                        else -> "other order"
                    }
            }
        }
        val expected =
            setOf("pair", "single", "partial", "shifted", "uncompressed", "mixed", "order 4", "order 8", "other order")
        assertEquals(expected, seen)
    }

    /**
     * Rewrites a packet whose header carries the partial-frame flag and count into the form an encoder whose
     * frameLength equals that count writes: flag clear, 32-bit count removed.
     */
    private fun withoutFrameCount(packet: ByteArray): ByteArray {
        assertEquals(1, bitAt(packet, 19), "partial-frame flag")
        assertEquals(352, bitsAt(packet, 23, 32), "frame count")
        return BitWriter()
            .copyBits(packet, 0, 19)
            .write(0, 1)
            .copyBits(packet, 20, 23)
            .copyBits(packet, 55, packet.size * 8)
            .toByteArray()
    }

    private fun appendS16(
        out: ByteArrayOutputStream,
        samples: ShortArray,
        count: Int,
    ) {
        for (i in 0 until count) {
            out.write(samples[i].toInt())
            out.write(samples[i].toInt() ushr 8)
        }
    }

    companion object {
        @JvmStatic
        fun pcm16Fixtures(): List<String> = Fixtures.PCM16.keys.toList()

        @JvmStatic
        fun pcm24Fixtures(): List<String> = Fixtures.PCM24.keys.toList()
    }
}
