package ai.aneesiqbal.rozan.airplay.alac

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Hand-built packets: uncompressed elements at every bit depth, element handling and header checks. */
class AlacDecoderPacketTest {
    private fun config(
        bitDepth: Int,
        channels: Int,
        frameLength: Int = 16,
    ) = AlacConfig.fromFmtp("96 $frameLength 0 $bitDepth 40 10 14 $channels 255 0 0 44100")

    /** [count] samples covering the extremes and random values of [bitDepth] bits. */
    private fun samples(
        bitDepth: Int,
        count: Int,
        seed: Int,
    ): IntArray {
        val max = if (bitDepth == 32) Int.MAX_VALUE else (1 shl (bitDepth - 1)) - 1
        val min = -max - 1
        val random = Random(seed)
        val fixed = intArrayOf(min, max, 0, -1, 1, min + 1, max - 1)
        return IntArray(count) { if (it < fixed.size) fixed[it] else random.nextInt(min, max) + random.nextInt(2) }
    }

    private fun decodeInts(
        config: AlacConfig,
        packet: ByteArray,
    ): Pair<Int, IntArray> {
        val decoder = AlacDecoder(config)
        val out = IntArray(decoder.minOutputSize)
        return decoder.decode(packet, 0, packet.size, out) to out
    }

    @ParameterizedTest
    @ValueSource(ints = [16, 20, 24, 32])
    fun `uncompressed mono and stereo elements round-trip at every bit depth`(bitDepth: Int) {
        val left = samples(bitDepth, 16, bitDepth)
        val right = samples(bitDepth, 16, bitDepth + 1)

        val mono = BitWriter().uncompressedElement(Element.SCE, bitDepth, listOf(left)).end().toByteArray()
        val (monoFrames, monoOut) = decodeInts(config(bitDepth, 1), mono)
        assertEquals(16, monoFrames)
        assertContentEquals(left, monoOut.copyOf(16))

        val stereo = BitWriter().uncompressedElement(Element.CPE, bitDepth, listOf(left, right)).end().toByteArray()
        val (stereoFrames, stereoOut) = decodeInts(config(bitDepth, 2), stereo)
        assertEquals(16, stereoFrames)
        assertContentEquals(IntArray(32) { if (it % 2 == 0) left[it / 2] else right[it / 2] }, stereoOut)
    }

    @Test
    fun `16-bit uncompressed elements decode to shorts`() {
        val left = samples(16, 16, 1)
        val right = samples(16, 16, 2)
        val packet = BitWriter().uncompressedElement(Element.CPE, 16, listOf(left, right)).end().toByteArray()
        val decoder = AlacDecoder(config(16, 2))
        val out = ShortArray(32)
        assertEquals(16, decoder.decode(packet, 0, packet.size, out))
        assertContentEquals(ShortArray(32) { (if (it % 2 == 0) left[it / 2] else right[it / 2]).toShort() }, out)
    }

    @Test
    fun `5_1 layout interleaves elements in stream order`() {
        // C (SCE), L R (CPE), Ls Rs (CPE), LFE: the MPEG 5.1 D layout ALAC uses for six channels.
        val ch = List(6) { samples(24, 8, 100 + it) }
        val packet =
            BitWriter()
                .uncompressedElement(Element.SCE, 24, listOf(ch[0]), frameCount = 8)
                .uncompressedElement(Element.CPE, 24, listOf(ch[1], ch[2]))
                .uncompressedElement(Element.CPE, 24, listOf(ch[3], ch[4]), frameCount = 8)
                .uncompressedElement(Element.LFE, 24, listOf(ch[5]))
                .end()
                .toByteArray()
        val (frames, out) = decodeInts(config(24, 6), packet)
        assertEquals(8, frames)
        assertContentEquals(IntArray(48) { ch[it % 6][it / 6] }, out.copyOf(48))
    }

    @Test
    fun `a partial frame count shorter than frameLength is honoured and carried to later elements`() {
        val c = samples(16, 5, 1)
        val l = samples(16, 5, 2)
        val r = samples(16, 5, 3)
        val packet =
            BitWriter()
                .uncompressedElement(Element.SCE, 16, listOf(c), frameCount = 5)
                .uncompressedElement(Element.CPE, 16, listOf(l, r)) // no count: uses 5, as in the reference
                .end()
                .toByteArray()
        val (frames, out) = decodeInts(config(16, 3), packet)
        assertEquals(5, frames)
        assertContentEquals(IntArray(15) { listOf(c, l, r)[it % 3][it / 3] }, out.copyOf(15))
    }

    @Test
    fun `elements that disagree on the frame count are rejected`() {
        val packet =
            BitWriter()
                .uncompressedElement(Element.SCE, 16, listOf(samples(16, 5, 1)), frameCount = 5)
                .uncompressedElement(Element.CPE, 16, listOf(samples(16, 6, 2), samples(16, 6, 3)), frameCount = 6)
                .end()
                .toByteArray()
        assertFailsWith<AlacException> { decodeInts(config(16, 3), packet) }
    }

    @Test
    fun `channels missing from a packet are filled with silence`() {
        val mono = samples(16, 16, 1)
        val packet = BitWriter().uncompressedElement(Element.SCE, 16, listOf(mono)).end().toByteArray()
        val decoder = AlacDecoder(config(16, 2))
        val out = ShortArray(32) { 7 }
        assertEquals(16, decoder.decode(packet, 0, packet.size, out))
        assertContentEquals(ShortArray(32) { if (it % 2 == 0) mono[it / 2].toShort() else 0 }, out)
    }

    @Test
    fun `a channel pair that does not fit ends the packet`() {
        val mono = samples(16, 16, 1)
        val pair = listOf(samples(16, 16, 2), samples(16, 16, 3))
        // Two channels configured: the SCE fills channel 0 and the CPE would need channels 1 and 2.
        val packet =
            BitWriter()
                .uncompressedElement(Element.SCE, 16, listOf(mono))
                .uncompressedElement(Element.CPE, 16, pair)
                .end()
                .toByteArray()
        val (frames, out) = decodeInts(config(16, 2), packet)
        assertEquals(16, frames)
        assertContentEquals(IntArray(32) { if (it % 2 == 0) mono[it / 2] else 0 }, out)

        // With nothing decoded before it, the packet does not match the configuration at all.
        val pairOnly = BitWriter().uncompressedElement(Element.CPE, 16, pair).end().toByteArray()
        assertFailsWith<AlacException> { decodeInts(config(16, 1), pairOnly) }
    }

    @Test
    fun `fill and data stream elements are skipped`() {
        val left = samples(16, 16, 1)
        val right = samples(16, 16, 2)
        val plain = BitWriter().uncompressedElement(Element.CPE, 16, listOf(left, right)).end().toByteArray()
        val padded =
            BitWriter()
                .write(Element.FIL, 3)
                .write(3, 4) // 3 bytes
                .write(0x123456, 24)
                .write(Element.FIL, 3)
                .write(15, 4)
                .write(2, 8) // 15 + 2 - 1 = 16 bytes
                .copyBits(ByteArray(16) { 0x55 }, 0, 128)
                .write(Element.DSE, 3)
                .write(0, 4) // instance tag
                .write(1, 1) // byte-align before the data
                .write(255, 8)
                .write(3, 8) // 255 + 3 = 258 bytes
                .let { it.write(0, (8 - it.bitCount % 8) % 8) }
                .copyBits(ByteArray(258) { 0x33 }, 0, 258 * 8)
                .uncompressedElement(Element.CPE, 16, listOf(left, right))
                .end()
                .toByteArray()
        assertContentEquals(decodeInts(config(16, 2), plain).second, decodeInts(config(16, 2), padded).second)
    }

    @Test
    fun `bytes after the last channel are ignored`() {
        val packet = Fixtures.airplayPacket(0)
        val config = AlacConfig.fromFmtp(Fixtures.AIRPLAY_FMTP)
        val withTrailer = packet + Random(5).nextBytes(100)
        assertContentEquals(decodeInts(config, packet).second, decodeInts(config, withTrailer).second)
    }

    @Test
    fun `empty, END-only and unsupported-element packets are rejected`() {
        val config = config(16, 2)
        assertFailsWith<AlacException> { decodeInts(config, ByteArray(0)) }
        assertFailsWith<AlacException> { decodeInts(config, BitWriter().end().toByteArray()) }
        for (tag in listOf(Element.CCE, Element.PCE)) {
            assertFailsWith<AlacException> { decodeInts(config, BitWriter().write(tag, 3).write(0, 29).toByteArray()) }
        }
        val fillPastEnd =
            BitWriter()
                .write(Element.FIL, 3)
                .write(15, 4)
                .write(255, 8)
                .toByteArray()
        assertFailsWith<AlacException> { decodeInts(config, fillPastEnd) }
        val dataPastEnd =
            BitWriter()
                .write(Element.DSE, 3)
                .write(0, 5)
                .write(200, 8)
                .toByteArray()
        assertFailsWith<AlacException> { decodeInts(config, dataPastEnd) }
    }

    @Test
    fun `malformed element headers are rejected`() {
        fun header(
            config: AlacConfig,
            tag: Int,
            reserved: Int,
            flags: Int,
            count: Int? = null,
        ): ByteArray {
            val writer =
                BitWriter()
                    .write(tag, 3)
                    .write(0, 4)
                    .write(reserved, 12)
                    .write(flags, 4)
            if (count != null) writer.write(count, 32)
            // Enough plausible data behind the header that only the header can be the reason for rejecting it.
            val body = ByteArray(config.frameLength * config.numChannels * 4 + 64)
            return writer.copyBits(body, 0, body.size * 8).toByteArray()
        }
        val c16 = config(16, 2)
        val c24 = config(24, 2)
        val c32 = config(32, 2)
        assertEquals(16, decodeInts(c16, header(c16, Element.CPE, 0, 0b0001)).first) // sanity: uncompressed zeros
        assertEquals(16, decodeInts(c16, header(c16, Element.CPE, 0, 0b0000)).first) // sanity: compressed zeros

        assertFailsWith<AlacException>("reserved bits") { decodeInts(c16, header(c16, Element.CPE, 1, 0)) }
        assertFailsWith<AlacException>("3 bytes shifted") { decodeInts(c24, header(c24, Element.CPE, 0, 0b0110)) }
        assertFailsWith<AlacException>("16-bit shifted") { decodeInts(c16, header(c16, Element.CPE, 0, 0b0010)) }
        assertFailsWith<AlacException>("uncompressed shifted") { decodeInts(c24, header(c24, Element.SCE, 0, 0b0011)) }
        assertFailsWith<AlacException>("32-bit pair unshifted") { decodeInts(c32, header(c32, Element.CPE, 0, 0)) }
        for (count in listOf(0, 17, -1, Int.MIN_VALUE)) {
            assertFailsWith<AlacException>("count $count") {
                decodeInts(c16, header(c16, Element.CPE, 0, 0b1000, count))
            }
        }
        assertEquals(17, decodeInts(config(16, 2, 17), header(c16, Element.CPE, 0, 0b1000, 17)).first)
    }

    @Test
    fun `mixBits of 32 or more is rejected when mixing is on`() {
        val config = config(16, 2)

        fun pair(
            mixBits: Int,
            mixRes: Int,
        ) = BitWriter()
            .write(Element.CPE, 3)
            .write(0, 4 + 12 + 4)
            .write(mixBits, 8)
            .write(mixRes, 8)
            .copyBits(ByteArray(256), 0, 2048)
            .toByteArray()
        assertEquals(16, decodeInts(config, pair(31, 1)).first)
        assertEquals(16, decodeInts(config, pair(200, 0)).first) // unused without mixing
        assertFailsWith<AlacException> { decodeInts(config, pair(32, 1)) }
    }

    @Test
    fun `compressed elements in a stream with kb 0 are rejected`() {
        val config = AlacConfig.fromFmtp("96 16 0 16 0 0 0 1 0 0 0 44100")
        val compressed =
            BitWriter()
                .write(Element.SCE, 3)
                .write(0, 20)
                .copyBits(ByteArray(64), 0, 512)
                .toByteArray()
        assertFailsWith<AlacException> { decodeInts(config, compressed) }
        val uncompressed = BitWriter().uncompressedElement(Element.SCE, 16, listOf(samples(16, 16, 1))).end()
        assertEquals(16, decodeInts(config, uncompressed.toByteArray()).first)
    }

    @Test
    fun `API misuse throws IllegalArgumentException`() {
        val decoder = AlacDecoder(config(16, 2))
        val packet = BitWriter().uncompressedElement(Element.CPE, 16, listOf(IntArray(16), IntArray(16))).end()
        val bytes = packet.toByteArray()
        val out = ShortArray(32)
        assertFailsWith<IllegalArgumentException> { decoder.decode(bytes, -1, bytes.size, out) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(bytes, 1, bytes.size, out) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(bytes, 0, -1, out) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(bytes, Int.MAX_VALUE, 2, out) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(bytes, 0, bytes.size, ShortArray(31)) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(bytes, 0, bytes.size, IntArray(31)) }
        assertEquals(16, decoder.decode(bytes, 0, bytes.size, out))
    }

    @Test
    fun `packets over 16 MiB are rejected without being read`() {
        val decoder = AlacDecoder(config(16, 2))
        val huge = ByteArray((1 shl 24) + 1)
        assertFailsWith<AlacException> { decoder.decode(huge, 0, huge.size, ShortArray(32)) }
    }
}
