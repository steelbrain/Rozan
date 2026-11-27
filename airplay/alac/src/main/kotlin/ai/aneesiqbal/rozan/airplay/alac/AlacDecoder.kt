/*
 * Ported from Apple's ALAC reference decoder, Copyright (c) 2011 Apple Inc., Apache License 2.0.
 *
 * Source: ALACDecoder::Decode, FillElement and DataStreamElement in codec/ALACDecoder.cpp, element IDs from
 * codec/ALACBitUtilities.h (https://github.com/macosforge/alac).
 * Modified: translated to Kotlin; every count and position from the stream is checked before use; output is
 * 16-bit shorts or sign-extended ints.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.aneesiqbal.rozan.airplay.alac

/**
 * Decodes Apple Lossless (ALAC) packets into interleaved PCM.
 *
 * Every buffer is allocated here, sized from [config] (256 KiB for 16384-frame packets, 5.5 KiB for AirPlay's
 * 352-frame packets); [decode] does not allocate. Each packet is decoded on its own, so packets may be skipped or
 * reordered, and the decoder keeps working after an [AlacException]. The decoder is not thread-safe; use one per
 * stream.
 *
 * Packets come from the network and are treated as hostile: a malformed packet throws [AlacException] after work
 * bounded by the packet length and [AlacConfig.frameLength] × [AlacConfig.numChannels], and never reads or writes
 * outside the given arrays.
 *
 * @property config The stream configuration the packets were encoded with.
 */
public class AlacDecoder(
    public val config: AlacConfig,
) {
    /** Smallest output array [decode] accepts: [AlacConfig.frameLength] × [AlacConfig.numChannels] samples. */
    public val minOutputSize: Int = config.frameLength * config.numChannels

    private val frameLength = config.frameLength
    private val numChannels = config.numChannels
    private val bitDepth = config.bitDepth
    private val bits = BitReader()
    private val mixU = IntArray(frameLength)
    private val mixV = IntArray(frameLength)
    private val predictor = IntArray(frameLength)
    private val shiftBuffer = ShortArray(frameLength * 2)
    private val paramsU = ChannelParams()
    private val paramsV = ChannelParams()

    // State of the packet being decoded.
    private var out16: ShortArray? = null
    private var out32: IntArray? = null
    private var frames = 0
    private var bytesShifted = 0
    private var escape = false

    /**
     * Decodes one packet of a 16-bit stream.
     *
     * @param packet array holding the packet.
     * @param offset index of the packet's first byte in [packet].
     * @param length packet length in bytes.
     * @param output receives `frames × numChannels` interleaved samples from index 0, channels in stream order
     *   (for example C, L, R for three channels). Must hold at least [minOutputSize] samples. Its contents are
     *   unspecified after an exception.
     * @return frames per channel decoded, 1..[AlacConfig.frameLength].
     * @throws AlacException if the packet is malformed or uses a feature this decoder does not support.
     * @throws IllegalArgumentException if the stream is not 16-bit, [offset] and [length] do not describe a range
     *   of [packet], or [output] is smaller than [minOutputSize].
     */
    public fun decode(
        packet: ByteArray,
        offset: Int,
        length: Int,
        output: ShortArray,
    ): Int {
        require(bitDepth == 16) { "A $bitDepth-bit stream needs the IntArray output" }
        require(output.size >= minOutputSize) { "output holds ${output.size} samples, needs $minOutputSize" }
        out16 = output
        try {
            return decodePacket(packet, offset, length)
        } finally {
            out16 = null
        }
    }

    /**
     * Decodes one packet of a stream of any bit depth into ints.
     *
     * Each sample is the signed value at the stream's [AlacConfig.bitDepth], right-aligned: -32768..32767 for 16-bit,
     * -524288..524287 for 20-bit, -8388608..8388607 for 24-bit and the full [Int] range for 32-bit.
     *
     * @param packet array holding the packet.
     * @param offset index of the packet's first byte in [packet].
     * @param length packet length in bytes.
     * @param output receives `frames × numChannels` interleaved samples from index 0, channels in stream order
     *   (for example C, L, R for three channels). Must hold at least [minOutputSize] samples. Its contents are
     *   unspecified after an exception.
     * @return frames per channel decoded, 1..[AlacConfig.frameLength].
     * @throws AlacException if the packet is malformed or uses a feature this decoder does not support.
     * @throws IllegalArgumentException if [offset] and [length] do not describe a range of [packet], or [output] is
     *   smaller than [minOutputSize].
     */
    public fun decode(
        packet: ByteArray,
        offset: Int,
        length: Int,
        output: IntArray,
    ): Int {
        require(output.size >= minOutputSize) { "output holds ${output.size} samples, needs $minOutputSize" }
        out32 = output
        try {
            return decodePacket(packet, offset, length)
        } finally {
            out32 = null
        }
    }

    private fun decodePacket(
        packet: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && length <= packet.size - offset) {
            "offset $offset and length $length are outside an array of ${packet.size}"
        }
        if (length > MAX_PACKET_BYTES) fail("packet of $length bytes is larger than $MAX_PACKET_BYTES")
        bits.reset(packet, offset, length)
        try {
            return decodeElements()
        } finally {
            bits.release()
        }
    }

    /** The element loop of ALACDecoder::Decode. Returns the frames per channel. */
    private fun decodeElements(): Int {
        frames = frameLength
        var channel = 0
        while (channel < numChannels) {
            val next = decodeElement(channel)
            if (next == END_OF_PACKET) break
            channel = next
        }
        // The reference fills channels the packet did not carry with silence. A packet with no channel at all
        // does not match the configuration, so it is rejected instead.
        if (channel == 0) fail("packet has no audio element that fits $numChannels channel(s)")
        while (channel < numChannels) {
            fillSilence(channel)
            channel++
        }
        return frames
    }

    /** Decodes the next element; returns the next channel index, or [END_OF_PACKET]. */
    private fun decodeElement(channel: Int): Int {
        if (bits.position >= bits.limit) fail("packet ended after $channel of $numChannels channel(s)")
        return when (val tag = bits.read(TAG_BITS)) {
            ID_SCE, ID_LFE -> {
                decodeSingle(channel)
                channel + 1
            }

            // A pair that would exceed the configured channels ends the packet, as in the reference.
            ID_CPE -> {
                if (channel + 2 > numChannels) return END_OF_PACKET
                decodePair(channel)
                channel + 2
            }

            ID_DSE -> {
                skipDataStreamElement()
                channel
            }

            ID_FIL -> {
                skipFillElement()
                channel
            }

            ID_END -> {
                END_OF_PACKET
            }

            else -> {
                fail("unsupported element type $tag")
            } // coupling channel or program config element
        }
    }

    /** A single channel or LFE element. */
    private fun decodeSingle(channel: Int) {
        readElementHeader(channel)
        val n = frames
        val shift = bytesShifted * 8
        if (escape) {
            readUncompressed(mixU, null, n)
        } else {
            bits.advance(MIX_HEADER_BITS) // mixBits and mixRes: nothing to unmix in one channel
            paramsU.read(bits)
            val shiftStart = skipShiftedBits(shift * n)
            decodeChannel(paramsU, mixU, n, bitDepth - shift)
            if (shift != 0) readShiftedBits(shiftStart, n, shift)
        }
        writeChannel(mixU, channel, 0, 1)
    }

    /** A channel pair element. */
    private fun decodePair(channel: Int) {
        readElementHeader(channel)
        val n = frames
        val shift = bytesShifted * 8
        var mixBits = 0
        var mixRes = 0
        if (escape) {
            readUncompressed(mixU, mixV, n)
        } else {
            // Mid-side coding adds a bit; 32-bit pairs must shift bytes off to stay within 32.
            val chanBits = bitDepth - shift + 1
            if (chanBits > Int.SIZE_BITS) fail("32-bit channel pair without shifted-off bytes")
            mixBits = bits.read(8)
            mixRes = bits.read(8).toByte().toInt()
            if (mixRes != 0 && mixBits >= Int.SIZE_BITS) fail("mixBits $mixBits is too large")
            paramsU.read(bits)
            paramsV.read(bits)
            val shiftStart = skipShiftedBits(shift * 2 * n)
            decodeChannel(paramsU, mixU, n, chanBits)
            decodeChannel(paramsV, mixV, n, chanBits)
            if (shift != 0) readShiftedBits(shiftStart, 2 * n, shift)
        }
        if (mixRes != 0) Matrix.unmix(mixU, mixV, n, mixBits, mixRes)
        writeChannel(mixU, channel, 0, 2)
        writeChannel(mixV, channel + 1, 1, 2)
    }

    /**
     * Reads the element header after the tag: instance tag, 12 reserved bits, flags (partial frame, bytes shifted
     * off, escape) and the optional frame count. Sets [bytesShifted], [escape] and [frames].
     */
    private fun readElementHeader(channel: Int) {
        bits.advance(INSTANCE_TAG_BITS) // the reference ignores the element instance tag
        if (bits.read(RESERVED_HEADER_BITS) != 0) fail("reserved element header bits are not zero")
        val flags = bits.read(FLAG_BITS)
        val shifted = (flags ushr 1) and 3
        val uncompressed = (flags and 1) != 0
        if (shifted == 3) fail("element shifts off 3 bytes")
        // The reference only restores shifted-off bytes for 24- and 32-bit output, and asserts that uncompressed
        // elements shift nothing off. Encoders follow both rules.
        if (shifted != 0 && bitDepth < 24) fail("$bitDepth-bit element shifts off bytes")
        if (shifted != 0 && uncompressed) fail("uncompressed element shifts off bytes")
        bytesShifted = shifted
        escape = uncompressed
        if (flags ushr 3 != 0) {
            val count = bits.read(32)
            if (count < 1 || count > frameLength) fail("element has ${count.toUInt()} frames, limit $frameLength")
            // The reference lets a later element change the count mid-packet, leaving earlier channels mismatched.
            if (channel > 0 && count != frames) fail("elements in one packet disagree on the frame count")
            frames = count
        }
    }

    /** Decodes residuals and runs the predictor(s) for one channel into [out]. */
    private fun decodeChannel(
        params: ChannelParams,
        out: IntArray,
        n: Int,
        chanBits: Int,
    ) {
        // With kb 0 the reference shifts a 32-bit value by 32 (undefined). ffmpeg writes kb 0 only for streams it
        // stores uncompressed, so a compressed packet in such a stream is malformed.
        if (config.kb == 0) fail("compressed element in a stream whose kb is 0")
        val pb = (config.pb * params.pbFactor) / 4
        AdaptiveGolomb.decompress(bits, predictor, n, chanBits, config.mb, pb, config.kb)
        // Any nonzero mode first runs the integrator in place.
        if (params.mode != 0) {
            DynamicPredictor.unpcBlock(predictor, predictor, n, params.coefs, DynamicPredictor.INTEGRATOR, chanBits, 0)
        }
        DynamicPredictor.unpcBlock(predictor, out, n, params.coefs, params.numCoefs, chanBits, params.denShift)
    }

    /** Reads [n] uncompressed samples (interleaved with [v] when it is not null) of [bitDepth] bits. */
    private fun readUncompressed(
        u: IntArray,
        v: IntArray?,
        n: Int,
    ) {
        val channels = if (v == null) 1 else 2
        if (bits.remaining < n * bitDepth * channels) fail("uncompressed samples run past the end of the packet")
        val extend = Int.SIZE_BITS - bitDepth
        for (i in 0 until n) {
            u[i] = (bits.read(bitDepth) shl extend) shr extend
            if (v != null) v[i] = (bits.read(bitDepth) shl extend) shr extend
        }
    }

    /** Skips the shifted-off low bits, which precede the compressed data; returns where they start. */
    private fun skipShiftedBits(count: Int): Int {
        val start = bits.position
        bits.advance(count)
        bits.checkWithin("shifted-off sample bits")
        return start
    }

    /** Reads [count] values of [shift] bits from [start] into [shiftBuffer], keeping the current position. */
    private fun readShiftedBits(
        start: Int,
        count: Int,
        shift: Int,
    ) {
        val resume = bits.position
        bits.position = start
        for (i in 0 until count) {
            shiftBuffer[i] = bits.read(shift).toShort()
        }
        bits.position = resume
    }

    /** Writes a decoded channel to the output; for a pair, [shiftFirst] and [shiftStride] pick its shifted bits. */
    private fun writeChannel(
        src: IntArray,
        channel: Int,
        shiftFirst: Int,
        shiftStride: Int,
    ) {
        val n = frames
        val shorts = out16
        val ints = out32
        if (shorts != null) {
            Matrix.write16(src, shorts, channel, numChannels, n)
        } else if (ints != null) {
            if (bytesShifted != 0) {
                Matrix.mergeShifted(src, n, shiftBuffer, shiftFirst, shiftStride, bytesShifted * 8)
            }
            Matrix.writeInt(src, ints, channel, numChannels, n, bitDepth)
        }
    }

    private fun fillSilence(channel: Int) {
        val shorts = out16
        val ints = out32
        var o = channel
        for (i in 0 until frames) {
            if (shorts != null) shorts[o] = 0
            if (ints != null) ints[o] = 0
            o += numChannels
        }
    }

    /** Skips a fill element (FillElement). */
    private fun skipFillElement() {
        var count = bits.read(4)
        if (count == 15) count += bits.read(8) - 1
        bits.advance(count * 8)
        bits.checkWithin("fill element")
    }

    /** Skips a data stream element (DataStreamElement). */
    private fun skipDataStreamElement() {
        bits.advance(INSTANCE_TAG_BITS)
        val byteAligned = bits.read(1) != 0
        var count = bits.read(8)
        if (count == 255) count += bits.read(8)
        if (byteAligned) bits.byteAlign()
        bits.advance(count * 8)
        bits.checkWithin("data stream element")
    }

    /** Prediction parameters of one channel, read from an element header. */
    private class ChannelParams {
        var mode = 0
        var denShift = 0
        var pbFactor = 0
        var numCoefs = 0
        val coefs = ShortArray(MAX_COEFS)

        fun read(bits: BitReader) {
            val modeByte = bits.read(8)
            mode = modeByte ushr 4
            denShift = modeByte and 0xf
            val countByte = bits.read(8)
            pbFactor = countByte ushr 5
            numCoefs = countByte and 0x1f
            for (i in 0 until numCoefs) {
                coefs[i] = bits.read(16).toShort()
            }
        }
    }

    private companion object {
        // Element types (ELEMENT_TYPE in ALACBitUtilities.h).
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_LFE = 3
        const val ID_DSE = 4
        const val ID_FIL = 6
        const val ID_END = 7

        const val TAG_BITS = 3
        const val INSTANCE_TAG_BITS = 4
        const val RESERVED_HEADER_BITS = 12
        const val FLAG_BITS = 4
        const val MIX_HEADER_BITS = 16

        /** Coefficient count is a 5-bit field. */
        const val MAX_COEFS = 32

        /** Returned by [decodeElement] when no more channels follow. */
        const val END_OF_PACKET = -1

        /**
         * Keeps bit positions within an [Int]. The largest packet a valid stream can hold (8 uncompressed 32-bit
         * channels of 16384 frames) is about 512 KiB.
         */
        const val MAX_PACKET_BYTES = 1 shl 24
    }
}

private fun fail(message: String): Nothing = throw AlacException(message)
