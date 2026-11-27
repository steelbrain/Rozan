/*
 * Ported from Apple's ALAC reference decoder, Copyright (c) 2011 Apple Inc., Apache License 2.0.
 *
 * Source: unmix16/20/24/32 and copyPredictorTo20/24/24Shift/32/32Shift in codec/matrix_dec.c
 * (https://github.com/macosforge/alac).
 * Modified: translated to Kotlin; each routine is split into unmixing, merging shifted-off bits and writing
 * interleaved samples, with output as 16-bit shorts or sign-extended ints instead of packed bytes.
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
 * Stereo unmixing and output of decoded channels.
 *
 * The encoder's generalized mid-side transform is
 * `u = floor((r * L + (m - r) * R) / m)`, `v = L - R` with `m = 2^mixBits` and `r = mixRes`; its lossless inverse is
 * `L = u + v - floor(r * v / m)`, `R = L - v`.
 */
internal object Matrix {
    /** Replaces [u] with the left channel and [v] with the right channel for the first [num] samples. */
    fun unmix(
        u: IntArray,
        v: IntArray,
        num: Int,
        mixBits: Int,
        mixRes: Int,
    ) {
        for (j in 0 until num) {
            val side = v[j]
            val left = u[j] + side - ((mixRes * side) shr mixBits)
            u[j] = left
            v[j] = left - side
        }
    }

    /**
     * Appends the low bits that the encoder shifted off before compressing: sample `j` becomes
     * `(src[j] << shiftBits) | shifted[first + j * stride]`, with the shifted value unsigned.
     */
    fun mergeShifted(
        src: IntArray,
        num: Int,
        shifted: ShortArray,
        first: Int,
        stride: Int,
        shiftBits: Int,
    ) {
        var s = first
        for (j in 0 until num) {
            src[j] = (src[j] shl shiftBits) or (shifted[s].toInt() and 0xffff)
            s += stride
        }
    }

    /** Writes [num] samples of [src] to every [stride]th element of [out] from [first], truncated to 16 bits. */
    fun write16(
        src: IntArray,
        out: ShortArray,
        first: Int,
        stride: Int,
        num: Int,
    ) {
        var o = first
        for (j in 0 until num) {
            out[o] = src[j].toShort()
            o += stride
        }
    }

    /**
     * Writes [num] samples of [src] to every [stride]th element of [out] from [first], keeping the low [bitDepth]
     * bits sign-extended (the bits the reference packs into its 16-, 24- or 32-bit output).
     */
    fun writeInt(
        src: IntArray,
        out: IntArray,
        first: Int,
        stride: Int,
        num: Int,
        bitDepth: Int,
    ) {
        val extend = Int.SIZE_BITS - bitDepth
        var o = first
        for (j in 0 until num) {
            out[o] = (src[j] shl extend) shr extend
            o += stride
        }
    }
}
