/*
 * Ported from Apple's ALAC reference decoder, Copyright (c) 2011 Apple Inc., Apache License 2.0.
 *
 * Source: dyn_decomp, dyn_get, dyn_get_32bit and lg3a in codec/ag_dec.c, constants from codec/aglib.h
 * (https://github.com/macosforge/alac).
 * Modified: translated to Kotlin with the reference's unsigned 32-bit arithmetic; the position is checked
 * against the true end of the packet; a wrapped history value is rejected instead of shifting by more than 31.
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
 * Adaptive Golomb decoding of prediction residuals.
 *
 * The reference does this arithmetic in `uint32_t`. Kotlin's [Int] wraps the same way for `+`, `-` and `*`; unsigned
 * shifts and comparisons are written with `ushr`.
 */
internal object AdaptiveGolomb {
    private const val QBSHIFT = 9
    private const val MMULSHIFT = 2
    private const val MDENSHIFT = QBSHIFT - MMULSHIFT - 1
    private const val MOFF = 1 shl (MDENSHIFT - 2)
    private const val BITOFF = 24
    private const val MAX_PREFIX_16 = 9
    private const val MAX_PREFIX_32 = 9
    private const val MAX_DATATYPE_BITS_16 = 16
    private const val N_MEAN_CLAMP_VAL = 0xffff
    private const val MAX_ZERO_RUN = 65535

    /** Largest history value that takes the zero-run path without 32-bit wraparound of `mb << MMULSHIFT`. */
    private const val ZERO_RUN_MAX_MB = (1 shl (QBSHIFT - MMULSHIFT)) - 1

    /**
     * Decodes [numSamples] signed residuals into [out] (dyn_decomp).
     *
     * @param maxSize bits in a residual sent without Golomb coding (the channel's sample size).
     * @param mb0 initial history (`mb` from the config).
     * @param pb history multiplier (`pb` from the config, scaled by the channel's pbFactor / 4).
     * @param kb limit on the Golomb parameter (`kb` from the config, 1..31).
     */
    fun decompress(
        bits: BitReader,
        out: IntArray,
        numSamples: Int,
        maxSize: Int,
        mb0: Int,
        pb: Int,
        kb: Int,
    ) {
        val wb = (1 shl kb) - 1
        val limit = bits.limit
        var mb = mb0
        var zmode = 0
        var c = 0
        while (c < numSamples) {
            // Every value takes at least one bit, so this sample would end past the packet.
            if (bits.position >= limit) throw AlacException("adaptive Golomb data runs past the end of the packet")
            val k = minOf(lg3a(mb ushr QBSHIFT), kb)
            val n = readValue(bits, (1 shl k) - 1, k, maxSize)

            // The least significant bit is the sign.
            val ndecode = n + zmode
            val multiplier = -(ndecode and 1) or 1
            out[c++] = ((ndecode + 1) ushr 1) * multiplier

            mb = pb * (n + zmode) + mb - ((pb * mb) ushr QBSHIFT)
            if (n ushr 16 != 0) mb = N_MEAN_CLAMP_VAL // n > N_MAX_MEAN_CLAMP (0xffff)
            zmode = 0

            // (mb << MMULSHIFT) < QB, as uint32_t.
            if ((mb shl MMULSHIFT) ushr QBSHIFT == 0 && c < numSamples) {
                val run = readZeroRun(bits, mb, wb)
                if (run > numSamples - c) throw AlacException("zero run of $run is longer than the rest of the frame")
                out.fill(0, c, c + run)
                c += run
                zmode = if (run >= MAX_ZERO_RUN) 0 else 1
                mb = 0
            }
        }
        bits.checkWithin("adaptive Golomb data")
    }

    /** Reads the length of a run of zero residuals (the zero-run half of dyn_decomp, then dyn_get). */
    private fun readZeroRun(
        bits: BitReader,
        mb: Int,
        wb: Int,
    ): Int {
        // Only a history that wrapped past 2^30 gets here with mb > 127; the reference would then shift by more
        // than 31 bits (undefined behaviour). A valid stream never does this.
        if (mb ushr 7 != 0) throw AlacException("adaptive Golomb history overflowed")
        val k = mb.countLeadingZeroBits() - BITOFF + ((mb + MOFF) ushr MDENSHIFT)
        val m = ((1 shl k) - 1) and wb

        var pos = bits.position
        val stream = bits.word(pos ushr 3) shl (pos and 7)
        val pre = stream.inv().countLeadingZeroBits()
        val result: Int
        if (pre >= MAX_PREFIX_16) {
            result = (stream shl MAX_PREFIX_16) ushr (Int.SIZE_BITS - MAX_DATATYPE_BITS_16)
            pos += MAX_PREFIX_16 + MAX_DATATYPE_BITS_16
        } else {
            // All of the bits fit in the word loaded above: pre + 1 + k <= 8 + 1 + 8.
            val v = (stream shl (pre + 1)) ushr (Int.SIZE_BITS - k)
            pos += pre + 1 + k
            var r = pre * m + v - 1
            if (v < 2) {
                r -= v - 1
                pos -= 1
            }
            result = r
        }
        bits.position = pos
        return result
    }

    /**
     * Reads one Golomb-coded value with parameter [k] and modulus [m] (dyn_get_32bit). A prefix of [MAX_PREFIX_32]
     * ones is followed by the value in [maxBits] plain bits.
     *
     * The code bits come from a 32-bit word loaded at the current byte and shifted left by the bit offset, exactly
     * as in the reference (so with `kb` above 16 the low bits can read as zero there too).
     */
    private fun readValue(
        bits: BitReader,
        m: Int,
        k: Int,
        maxBits: Int,
    ): Int {
        var pos = bits.position
        val stream = bits.word(pos ushr 3) shl (pos and 7)
        var result = stream.inv().countLeadingZeroBits()
        if (result >= MAX_PREFIX_32) {
            result = bits.peek(pos + MAX_PREFIX_32, maxBits)
            pos += MAX_PREFIX_32 + maxBits
        } else {
            pos += result + 1
            if (k != 1) {
                val v = (stream shl (result + 1)) ushr (Int.SIZE_BITS - k)
                pos += k - 1
                result *= m
                if (v >= 2) {
                    result += v - 1
                    pos += 1
                }
            }
        }
        bits.position = pos
        return result
    }

    /** floor(log2(x + 3)) for x >= 0. */
    private fun lg3a(x: Int): Int = 31 - (x + 3).countLeadingZeroBits()
}
