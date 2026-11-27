package ai.aneesiqbal.rozan.airplay.alac

import org.junit.jupiter.api.Timeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hostile input: every packet must either decode or throw [AlacException]; nothing else may escape, and the work
 * per packet stays bounded (the timeouts catch loops).
 */
@Timeout(120)
class AlacDecoderFuzzTest {
    /** Decoder plus reusable output buffers; [decode] returns frames, or null after an [AlacException]. */
    private class Harness(
        val config: AlacConfig,
    ) {
        val decoder = AlacDecoder(config)
        val shorts = ShortArray(decoder.minOutputSize)
        val ints = IntArray(decoder.minOutputSize)
        private var useShorts = false

        fun decode(
            packet: ByteArray,
            length: Int = packet.size,
        ): Int? {
            // 16-bit streams alternate between the two output APIs.
            useShorts = config.bitDepth == 16 && !useShorts
            val frames =
                try {
                    if (useShorts) {
                        decoder.decode(packet, 0, length, shorts)
                    } else {
                        decoder.decode(packet, 0, length, ints)
                    }
                } catch (rejected: AlacException) {
                    assertTrue(rejected.message.orEmpty().isNotEmpty())
                    return null
                }
            assertTrue(frames in 1..config.frameLength, "frames $frames")
            return frames
        }

        /** Decoded samples of the last successful [decode], as ints. */
        fun samples(frames: Int): List<Int> {
            val count = frames * config.numChannels
            return if (useShorts) shorts.take(count).map { it.toInt() } else ints.take(count)
        }
    }

    private val configs =
        listOf(
            AlacConfig.fromFmtp(Fixtures.AIRPLAY_FMTP),
            AlacConfig.fromFmtp("96 4096 0 16 40 10 14 1 255 0 0 44100"),
            AlacConfig.fromFmtp("96 4096 0 24 40 10 14 2 255 0 0 96000"),
            AlacConfig.fromFmtp("96 1024 0 32 40 10 14 8 255 0 0 48000"),
            AlacConfig.fromFmtp("96 256 0 20 40 10 14 3 255 0 0 48000"),
            AlacConfig.fromFmtp("96 4096 0 16 0 0 0 2 0 0 0 44100"), // ffmpeg's uncompressed streams
            AlacConfig.fromFmtp("96 352 0 16 255 255 31 2 255 0 0 44100"), // extreme tuning values
        )

    @Test
    fun `every truncation of every fixture packet decodes or throws`() {
        for ((config, packet) in Fixtures.allPackets()) {
            val harness = Harness(config)
            assertTrue(harness.decode(packet) != null)
            for (length in 0 until packet.size) {
                val frames = harness.decode(packet, length)
                // Audio data ends in the last two bytes (then the 3-bit END tag and padding), so any shorter
                // packet has lost audio and must be rejected.
                if (length <= packet.size - 2) assertNull(frames, "length $length of ${packet.size} decoded")
            }
        }
    }

    @Test
    fun `random bytes decode or throw`() {
        val random = Random(10)
        for (config in configs) {
            val harness = Harness(config)
            repeat(5_000) {
                harness.decode(random.nextBytes(random.nextInt(0, 1500)))
            }
        }
    }

    @Test
    fun `random data after a valid element header decodes or throws, deterministically`() {
        val random = Random(11)
        var decoded = 0
        for (config in configs) {
            val harness = Harness(config)
            repeat(4_000) {
                val packet = randomElement(random, config)
                val first = harness.decode(packet)
                val firstSamples = first?.let { harness.samples(it) }
                // Decode something else in between: no state may leak from one packet into the next.
                harness.decode(randomElement(random, config))
                val again = harness.decode(packet)
                assertEquals(first, again)
                if (again != null) {
                    assertEquals(firstSamples, harness.samples(again))
                    decoded++
                }
            }
        }
        assertTrue(decoded > 100, "only $decoded random packets decoded; the fuzzer is not reaching the codec")
    }

    @Test
    fun `bit flips and byte overwrites in fixture packets decode or throw`() {
        val random = Random(12)
        for ((config, packet) in Fixtures.allPackets()) {
            val harness = Harness(config)
            repeat(400) {
                val damaged = packet.copyOf()
                repeat(random.nextInt(1, 4)) {
                    val bit = random.nextInt(damaged.size * 8)
                    damaged[bit ushr 3] = (damaged[bit ushr 3].toInt() xor (0x80 ushr (bit and 7))).toByte()
                }
                if (random.nextInt(4) == 0) damaged[random.nextInt(damaged.size)] = random.nextInt().toByte()
                harness.decode(damaged)
            }
        }
    }

    /**
     * A single or pair element (or a fill/data element first) with a well-formed start: zero reserved bits,
     * random flags, a plausible frame count and random data after that.
     */
    private fun randomElement(
        random: Random,
        config: AlacConfig,
    ): ByteArray {
        val writer = BitWriter()
        if (random.nextInt(8) == 0) writer.write(Element.FIL, 3).write(random.nextInt(4), 4)
        val tag =
            when {
                config.numChannels >= 2 && random.nextBoolean() -> Element.CPE
                random.nextBoolean() -> Element.SCE
                else -> Element.LFE
            }
        writer.write(tag, 3).write(random.nextInt(16), 4).write(0, 12)
        val partial = random.nextBoolean()
        val shifted = if (config.bitDepth >= 24) random.nextInt(3) else 0
        val escape = random.nextInt(4) == 0 && shifted == 0
        writer.write(((if (partial) 1 else 0) shl 3) or (shifted shl 1) or (if (escape) 1 else 0), 4)
        if (partial) writer.write(random.nextInt(1, config.frameLength + 1), 32)
        // Random bits up to a byte boundary, then random bytes: the same as random bits all the way.
        writer.write(random.nextInt(), (8 - writer.bitCount % 8) % 8)
        val body = random.nextBytes(random.nextInt(0, config.frameLength * config.numChannels * 3 + 64))
        return writer.toByteArray() + body
    }
}
