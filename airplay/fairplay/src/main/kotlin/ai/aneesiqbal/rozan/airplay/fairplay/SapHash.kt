package ai.aneesiqbal.rozan.airplay.fairplay

/** `sap_hash(block)` of spec section 4.8. */
internal object SapHash {
    const val BLOCK_SIZE = 64
    const val OUT_SIZE = 16
    const val BUFFER0_SIZE = 20
    const val BUFFER1_SIZE = 210
    const val BUFFER2_SIZE = 35
    const val BUFFER3_SIZE = 132
    const val BUFFER4_SIZE = 21

    private const val SCRAMBLE_ITERATIONS = 840
    private const val FINAL_PASSES = 16

    /** Hashes the 64 bytes of [block] at [offset]; returns 16 bytes. */
    fun hash(
        block: ByteArray,
        offset: Int,
        tables: V3Tables,
    ): ByteArray {
        val buffers = Buffers.seeded(loadAndScramble(block, offset), tables)
        buffers.garble()
        val out = combine(buffers, tables)
        buffers.wipe()
        return out
    }

    /** Steps 1 and 2: `buffer1` loaded from the 64 bytes of [block] at [offset], then scrambled. */
    fun loadAndScramble(
        block: ByteArray,
        offset: Int,
    ): ByteArray {
        // Byte-swapped per 32-bit word and repeated with period 64.
        val b1 = ByteArray(BUFFER1_SIZE) { i -> block[offset + 4 * ((i % 64) / 4) + (3 - i % 4)] }
        // The index offsets wrap modulo 2^32 before the modulo 210 (section 6a).
        for (i in 0 until SCRAMBLE_ITERATIONS) {
            val x = b1.u8(Integer.remainderUnsigned(i - 155, BUFFER1_SIZE))
            val y = b1.u8(Integer.remainderUnsigned(i - 57, BUFFER1_SIZE))
            val z = b1.u8(Integer.remainderUnsigned(i - 13, BUFFER1_SIZE))
            val target = Integer.remainderUnsigned(i, BUFFER1_SIZE)
            val w = b1.u8(target)
            b1[target] = (rol8(y, 5) + (rol8(z, 3) xor w) - rol8(x, 7)).toByte()
        }
        return b1
    }

    /** Steps 4 and 5: folds the garbled [buffers] into the 16-byte output. */
    fun combine(
        buffers: Buffers,
        tables: V3Tables,
    ): ByteArray {
        val out = IntArray(OUT_SIZE) { 0xE1 }
        for (i in 0 until tables.i0Index.size) {
            out[i] = if (i == 3) 0x3D else (out[i] + buffers.b3.u8(tables.i0Index.u8(i) * 4)) and 0xFF
        }
        for (buffer in arrayOf(buffers.b0, buffers.b2, buffers.b1)) {
            for (i in buffer.indices) out[i % OUT_SIZE] = out[i % OUT_SIZE] xor buffer.u8(i)
        }
        // In-place reverse scramble; `and 15` is the unsigned modulo 16.
        repeat(FINAL_PASSES) {
            for (i in 0 until OUT_SIZE) {
                val x = out[(i - 7) and 15]
                val y = out[i]
                val z = out[(i - 37) and 15]
                val w = out[(i - 177) and 15]
                out[i] = rol8(x, 1) xor y xor rol8(z, 6) xor rol8(w, 5)
            }
        }
        val result = ByteArray(OUT_SIZE) { out[it].toByte() }
        out.fill(0)
        return result
    }

    /** The five `sap_hash` buffers that [Garble] rewrites. */
    class Buffers(
        val b0: ByteArray,
        val b1: ByteArray,
        val b2: ByteArray,
        val b3: ByteArray,
        val b4: ByteArray,
    ) {
        /** Step 3. */
        fun garble(trace: GarbleTrace? = null) = Garble(b0, b1, b2, b3, b4, trace).run()

        fun wipe() {
            for (buffer in arrayOf(b0, b1, b2, b3, b4)) buffer.fill(0)
        }

        companion object {
            /**
             * The buffers before [garble]: the seeds, [b1], and a zero `buffer3`
             * (its initial content does not affect the result, section 6i).
             */
            fun seeded(
                b1: ByteArray,
                tables: V3Tables,
            ) = Buffers(
                tables.buffer0Seed.copyOf(),
                b1,
                tables.buffer2Seed.copyOf(),
                ByteArray(BUFFER3_SIZE),
                tables.buffer4Seed.copyOf(),
            )
        }
    }

    /** 8-bit left rotate of the byte [v] by [c] (1..7). */
    private fun rol8(
        v: Int,
        c: Int,
    ): Int = ((v shl c) and 0xFF) or ((v and 0xFF) ushr (8 - c))
}
