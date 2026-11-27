package ai.aneesiqbal.rozan.airplay.fairplay

/**
 * `modified_md5(block, key)` of spec section 4.7: the MD5 compression function
 * with [key] as the chaining state, big-endian message words, a message-word
 * permutation after step 31, and a Davies-Meyer feed-forward.
 */
internal object ModifiedMd5 {
    const val BLOCK_SIZE = 64
    const val KEY_SIZE = 16

    /** MD5's additive constants, `floor(2^32 * abs(sin(i + 1)))` (RFC 1321). */
    val K: IntArray =
        intArrayOf(
            0xd76aa478.toInt(),
            0xe8c7b756.toInt(),
            0x242070db,
            0xc1bdceee.toInt(),
            0xf57c0faf.toInt(),
            0x4787c62a,
            0xa8304613.toInt(),
            0xfd469501.toInt(),
            0x698098d8,
            0x8b44f7af.toInt(),
            0xffff5bb1.toInt(),
            0x895cd7be.toInt(),
            0x6b901122,
            0xfd987193.toInt(),
            0xa679438e.toInt(),
            0x49b40821,
            0xf61e2562.toInt(),
            0xc040b340.toInt(),
            0x265e5a51,
            0xe9b6c7aa.toInt(),
            0xd62f105d.toInt(),
            0x02441453,
            0xd8a1e681.toInt(),
            0xe7d3fbc8.toInt(),
            0x21e1cde6,
            0xc33707d6.toInt(),
            0xf4d50d87.toInt(),
            0x455a14ed,
            0xa9e3e905.toInt(),
            0xfcefa3f8.toInt(),
            0x676f02d9,
            0x8d2a4c8a.toInt(),
            0xfffa3942.toInt(),
            0x8771f681.toInt(),
            0x6d9d6122,
            0xfde5380c.toInt(),
            0xa4beea44.toInt(),
            0x4bdecfa9,
            0xf6bb4b60.toInt(),
            0xbebfbc70.toInt(),
            0x289b7ec6,
            0xeaa127fa.toInt(),
            0xd4ef3085.toInt(),
            0x04881d05,
            0xd9d4d039.toInt(),
            0xe6db99e5.toInt(),
            0x1fa27cf8,
            0xc4ac5665.toInt(),
            0xf4292244.toInt(),
            0x432aff97,
            0xab9423a7.toInt(),
            0xfc93a039.toInt(),
            0x655b59c3,
            0x8f0ccc92.toInt(),
            0xffeff47d.toInt(),
            0x85845dd1.toInt(),
            0x6fa87e4f,
            0xfe2ce6e0.toInt(),
            0xa3014314.toInt(),
            0x4e0811a1,
            0xf7537e82.toInt(),
            0xbd3af235.toInt(),
            0x2ad7d2bb,
            0xeb86d391.toInt(),
        )

    /** Per-step rotate amounts: four per round, indexed `4 * (i / 16) + i % 4`. */
    private val SHIFTS = intArrayOf(7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21)

    private const val PERMUTATION_STEP = 31

    /** Compresses the 64 bytes of [block] at [offset] with the 16-byte [key]; returns 16 bytes. */
    fun compress(
        block: ByteArray,
        offset: Int,
        key: ByteArray,
    ): ByteArray {
        val m = IntArray(16) { block.be32(offset + 4 * it) }
        val a0 = key.le32(0)
        val b0 = key.le32(4)
        val c0 = key.le32(8)
        val d0 = key.le32(12)
        var a = a0
        var b = b0
        var c = c0
        var d = d0
        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when (i / 16) {
                0 -> {
                    f = (b and c) or (b.inv() and d)
                    g = i
                }

                1 -> {
                    f = (b and d) or (c and d.inv())
                    g = (5 * i + 1) % 16
                }

                2 -> {
                    f = b xor c xor d
                    g = (3 * i + 5) % 16
                }

                else -> {
                    f = c xor (b or d.inv())
                    g = (7 * i) % 16
                }
            }
            val z = Integer.rotateLeft(a + m[g] + K[i] + f, SHIFTS[4 * (i / 16) + i % 4]) + b
            a = d
            d = c
            c = b
            b = z
            if (i == PERMUTATION_STEP) {
                swap(m, a and 15, b and 15)
                swap(m, c and 15, d and 15)
                swap(m, (a ushr 4) and 15, (b ushr 4) and 15)
                swap(m, (a ushr 8) and 15, (b ushr 8) and 15)
                swap(m, (a ushr 12) and 15, (b ushr 12) and 15)
            }
        }
        m.fill(0)
        val out = ByteArray(KEY_SIZE)
        out.putLe32(0, a0 + a)
        out.putLe32(4, b0 + b)
        out.putLe32(8, c0 + c)
        out.putLe32(12, d0 + d)
        return out
    }

    private fun swap(
        words: IntArray,
        i: Int,
        j: Int,
    ) {
        val t = words[i]
        words[i] = words[j]
        words[j] = t
    }
}
