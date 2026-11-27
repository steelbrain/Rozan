/*
 * Ported from Apple's ALAC reference decoder, Copyright (c) 2011 Apple Inc., Apache License 2.0.
 *
 * Source: unpc_block and sign_of_int in codec/dp_dec.c (https://github.com/macosforge/alac).
 * Modified: translated to Kotlin; the reference's hand-unrolled loops for 4 and 8 coefficients are replaced by
 * its general loop, which computes the same values; the warm-up loop stops at the end of the block.
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
 * The adaptive FIR predictor that turns residuals back into samples.
 *
 * Arithmetic wraps at 32 bits as the reference's `int32_t` code does on every platform it ships on, and coefficients
 * wrap at 16 bits as its `int16_t` coefficients do.
 */
internal object DynamicPredictor {
    /** Coefficient count that selects a first-order integrator instead of the adaptive filter. */
    const val INTEGRATOR: Int = 31

    /**
     * Reconstructs [num] samples of [chanBits] bits from the residuals in [pc] into [out] (unpc_block). [pc] and
     * [out] may be the same array only for [numActive] 0 or [INTEGRATOR]. Adapts the first [numActive] entries of
     * [coefs] in place.
     */
    fun unpcBlock(
        pc: IntArray,
        out: IntArray,
        num: Int,
        coefs: ShortArray,
        numActive: Int,
        chanBits: Int,
        denShift: Int,
    ) {
        val chanShift = Int.SIZE_BITS - chanBits
        out[0] = pc[0]
        when (numActive) {
            0 -> if (num > 1 && pc !== out) System.arraycopy(pc, 1, out, 1, num - 1)
            INTEGRATOR -> integrate(pc, out, num, chanShift)
            else -> predict(pc, out, num, coefs, numActive, chanShift, denShift)
        }
    }

    /** First-order integrator; works in place. */
    private fun integrate(
        pc: IntArray,
        out: IntArray,
        num: Int,
        chanShift: Int,
    ) {
        var prev = out[0]
        for (j in 1 until num) {
            prev = ((pc[j] + prev) shl chanShift) shr chanShift
            out[j] = prev
        }
    }

    private fun predict(
        pc: IntArray,
        out: IntArray,
        num: Int,
        coefs: ShortArray,
        numActive: Int,
        chanShift: Int,
        denShift: Int,
    ) {
        // For denShift 0 the reference evaluates 1 << -1, which is undefined in C; on ARM and x86 it yields
        // 1 << 31, and so does masking the distance to five bits here.
        val denHalf = 1 shl ((denShift - 1) and 31)

        // The reference runs this to numActive even when the block is shorter, writing past it; the samples
        // inside the block are the same either way.
        val warmUp = minOf(numActive, num - 1)
        for (j in 1..warmUp) {
            out[j] = ((pc[j] + out[j - 1]) shl chanShift) shr chanShift
        }

        val lim = numActive + 1
        for (j in lim until num) {
            val top = out[j - lim]
            val last = j - 1
            var sum = 0
            for (k in 0 until numActive) {
                sum += coefs[k] * (out[last - k] - top)
            }
            val del = pc[j]
            out[j] = ((del + top + ((sum + denHalf) shr denShift)) shl chanShift) shr chanShift
            if (del > 0) {
                adaptAfterPositive(out, last, top, coefs, numActive, del, denShift)
            } else if (del < 0) {
                adaptAfterNegative(out, last, top, coefs, numActive, del, denShift)
            }
        }
    }

    /** Coefficient update after a positive residual [del0], from the oldest tap to the newest. */
    private fun adaptAfterPositive(
        out: IntArray,
        last: Int,
        top: Int,
        coefs: ShortArray,
        numActive: Int,
        del0: Int,
        denShift: Int,
    ) {
        var remaining = del0
        for (k in numActive - 1 downTo 0) {
            val dd = top - out[last - k]
            val sgn = Integer.signum(dd)
            coefs[k] = (coefs[k] - sgn).toShort()
            remaining -= (numActive - k) * ((sgn * dd) shr denShift)
            if (remaining <= 0) return
        }
    }

    /** Coefficient update after a negative residual [del0], from the oldest tap to the newest. */
    private fun adaptAfterNegative(
        out: IntArray,
        last: Int,
        top: Int,
        coefs: ShortArray,
        numActive: Int,
        del0: Int,
        denShift: Int,
    ) {
        var remaining = del0
        for (k in numActive - 1 downTo 0) {
            val dd = top - out[last - k]
            val sgn = Integer.signum(dd)
            coefs[k] = (coefs[k] + sgn).toShort()
            remaining -= (numActive - k) * ((-sgn * dd) shr denShift)
            if (remaining >= 0) return
        }
    }
}
