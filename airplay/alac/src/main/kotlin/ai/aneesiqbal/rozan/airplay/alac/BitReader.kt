/*
 * Ported from Apple's ALAC reference decoder, Copyright (c) 2011 Apple Inc., Apache License 2.0.
 *
 * Source: the reading half of BitBuffer in codec/ALACBitUtilities.c and read32bit/getstreambits in codec/ag_dec.c
 * from https://github.com/macosforge/alac.
 * Modified: translated to Kotlin; reads never leave the packet (missing bytes read as zero).
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
 * MSB-first bit reader over one packet.
 *
 * The reference reads up to five bytes past its current position without bounds checks and only compares its
 * position with the end of the buffer at a few points. Here every byte past the end of the packet reads as zero, so
 * reads are always memory-safe and the bits a well-formed packet uses are unchanged. Callers compare [position] with
 * [limit] (see [checkWithin]) where the reference checks for overruns, and before loops whose length comes from the
 * stream.
 */
internal class BitReader {
    private var data: ByteArray = EMPTY
    private var base = 0
    private var byteLength = 0

    /** Bit position from the start of the packet. It may pass [limit]; see [checkWithin]. */
    var position: Int = 0

    /** Packet length in bits. */
    var limit: Int = 0
        private set

    /** Bits left before [limit]; negative once a read has run past the end. */
    val remaining: Int
        get() = limit - position

    /** Starts reading the [length] bytes at [offset] in [data]. The caller has checked the bounds. */
    fun reset(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        this.data = data
        base = offset
        byteLength = length
        position = 0
        limit = length shl 3
    }

    /** Drops the reference to the last packet. */
    fun release() {
        reset(EMPTY, 0, 0)
    }

    /** Reads [count] bits (1..32) as an unsigned value; 32-bit values may come back negative. */
    fun read(count: Int): Int {
        val value = peek(position, count)
        position += count
        return value
    }

    /** The [count] bits (1..32) at [bitPos], as an unsigned value. Same result as the reference's getstreambits. */
    fun peek(
        bitPos: Int,
        count: Int,
    ): Int = window(bitPos) ushr (Int.SIZE_BITS - count)

    /** The 32 bits starting at bit [bitPos]. */
    private fun window(bitPos: Int): Int {
        val byteIndex = bitPos ushr 3
        val shift = bitPos and 7
        val word = word(byteIndex)
        return if (shift == 0) word else (word shl shift) or (byteAt(byteIndex + 4) ushr (8 - shift))
    }

    /** The four bytes at [byteIndex] as a big-endian word: the reference's read32bit. */
    fun word(byteIndex: Int): Int {
        if (byteIndex < byteLength - 3) {
            val p = base + byteIndex
            return (data[p].toInt() shl 24) or
                ((data[p + 1].toInt() and 0xff) shl 16) or
                ((data[p + 2].toInt() and 0xff) shl 8) or
                (data[p + 3].toInt() and 0xff)
        }
        return (byteAt(byteIndex) shl 24) or
            (byteAt(byteIndex + 1) shl 16) or
            (byteAt(byteIndex + 2) shl 8) or
            byteAt(byteIndex + 3)
    }

    private fun byteAt(index: Int): Int = if (index < byteLength) data[base + index].toInt() and 0xff else 0

    /** Skips [count] bits (BitBufferAdvance). */
    fun advance(count: Int) {
        position += count
    }

    /** Moves to the next byte boundary unless already on one (BitBufferByteAlign without writing). */
    fun byteAlign() {
        position = (position + 7) and 7.inv()
    }

    /** Throws if reading [what] took the position past the end of the packet. */
    fun checkWithin(what: String) {
        if (position > limit) throw AlacException("$what runs past the end of the packet")
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
