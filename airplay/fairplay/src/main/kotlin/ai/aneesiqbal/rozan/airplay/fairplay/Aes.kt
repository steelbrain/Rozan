package ai.aneesiqbal.rozan.airplay.fairplay

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 (FIPS-197) pieces used by FairPlay.
 *
 * [encryptBlock] and [decryptBlock] run the standard round function with eleven
 * caller-supplied round keys, because FairPlay v2's SAP cipher uses round keys
 * that are not an AES key schedule (spec sections 2 and 3.2), so a platform
 * cipher cannot be used there. Ordinary single-key AES goes through the
 * platform ([ecbDecrypt]).
 *
 * The state is the standard column-major layout: byte `4 * column + row`.
 */
internal object Aes {
    const val BLOCK_SIZE = 16
    const val ROUNDS = 10

    /** Round keys for [encryptBlock]/[decryptBlock]: 11 keys of 16 bytes. */
    const val ROUND_KEYS_SIZE = (ROUNDS + 1) * BLOCK_SIZE

    /** The AES S-box, derived from its definition (inverse in GF(2^8), then the affine map). */
    val SBOX: IntArray

    /** Inverse of [SBOX]. */
    val INV_SBOX: IntArray

    init {
        val exp = IntArray(255)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x xor xtime(x) // multiply by the generator 0x03
        }
        val sbox = IntArray(256)
        val inverse = IntArray(256)
        for (a in 0 until 256) {
            val inv = if (a == 0) 0 else exp[(255 - log[a]) % 255]
            var s = inv
            for (shift in 1..4) s = s xor (((inv shl shift) or (inv ushr (8 - shift))) and 0xFF)
            s = s xor 0x63
            sbox[a] = s
            inverse[s] = a
        }
        SBOX = sbox
        INV_SBOX = inverse
    }

    /** Encrypts the 16 bytes of [block] at [offset] in place with [roundKeys] (`keys[0..10]`). */
    fun encryptBlock(
        block: ByteArray,
        offset: Int,
        roundKeys: ByteArray,
    ) {
        val state = IntArray(BLOCK_SIZE) { block.u8(offset + it) }
        addRoundKey(state, roundKeys, 0)
        for (round in 1 until ROUNDS) {
            subBytes(state, SBOX)
            shiftRows(state, inverse = false)
            mixColumns(state)
            addRoundKey(state, roundKeys, round)
        }
        subBytes(state, SBOX)
        shiftRows(state, inverse = false)
        addRoundKey(state, roundKeys, ROUNDS)
        for (i in 0 until BLOCK_SIZE) block[offset + i] = state[i].toByte()
        state.fill(0)
    }

    /** Exact inverse of [encryptBlock]. */
    fun decryptBlock(
        block: ByteArray,
        offset: Int,
        roundKeys: ByteArray,
    ) {
        val state = IntArray(BLOCK_SIZE) { block.u8(offset + it) }
        addRoundKey(state, roundKeys, ROUNDS)
        for (round in ROUNDS - 1 downTo 1) {
            shiftRows(state, inverse = true)
            subBytes(state, INV_SBOX)
            addRoundKey(state, roundKeys, round)
            invMixColumns(state)
        }
        shiftRows(state, inverse = true)
        subBytes(state, INV_SBOX)
        addRoundKey(state, roundKeys, 0)
        for (i in 0 until BLOCK_SIZE) block[offset + i] = state[i].toByte()
        state.fill(0)
    }

    /**
     * Standard AES-128 decryption of one block with the platform cipher: the 16
     * bytes of [input] at [inputOffset] are decrypted with [key] into [output] at
     * [outputOffset].
     *
     * Lint's `GetInstance` check is suppressed: it warns that ECB leaks patterns
     * across blocks, but this is the raw single-block AES the protocol specifies
     * (spec sections 3.5 and 4.3), one block with no chaining.
     */
    @Suppress("GetInstance")
    fun ecbDecrypt(
        key: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val written = cipher.doFinal(input, inputOffset, BLOCK_SIZE, output, outputOffset)
            if (written != BLOCK_SIZE) throw FairPlayException("AES returned $written bytes")
        } catch (e: GeneralSecurityException) {
            throw FairPlayException("platform AES failed", e)
        }
    }

    private fun xtime(a: Int): Int {
        val shifted = a shl 1
        return if (shifted and 0x100 != 0) (shifted xor 0x11B) else shifted
    }

    /** Multiplication in GF(2^8) with the AES polynomial. */
    private fun mul(
        a: Int,
        b: Int,
    ): Int {
        var result = 0
        var x = a
        var y = b
        while (y != 0) {
            if (y and 1 != 0) result = result xor x
            x = xtime(x)
            y = y ushr 1
        }
        return result
    }

    private fun addRoundKey(
        state: IntArray,
        roundKeys: ByteArray,
        round: Int,
    ) {
        for (i in 0 until BLOCK_SIZE) state[i] = state[i] xor roundKeys.u8(round * BLOCK_SIZE + i)
    }

    private fun subBytes(
        state: IntArray,
        box: IntArray,
    ) {
        for (i in 0 until BLOCK_SIZE) state[i] = box[state[i]]
    }

    /** Row `r` rotates left by `r` columns (right for the inverse). */
    private fun shiftRows(
        state: IntArray,
        inverse: Boolean,
    ) {
        val copy = state.copyOf()
        for (column in 0 until 4) {
            for (row in 1 until 4) {
                val source = if (inverse) (column - row + 4) % 4 else (column + row) % 4
                state[4 * column + row] = copy[4 * source + row]
            }
        }
        copy.fill(0)
    }

    private fun mixColumns(state: IntArray) {
        for (c in 0 until 4) {
            val a0 = state[4 * c]
            val a1 = state[4 * c + 1]
            val a2 = state[4 * c + 2]
            val a3 = state[4 * c + 3]
            state[4 * c] = mul(a0, 2) xor mul(a1, 3) xor a2 xor a3
            state[4 * c + 1] = a0 xor mul(a1, 2) xor mul(a2, 3) xor a3
            state[4 * c + 2] = a0 xor a1 xor mul(a2, 2) xor mul(a3, 3)
            state[4 * c + 3] = mul(a0, 3) xor a1 xor a2 xor mul(a3, 2)
        }
    }

    private fun invMixColumns(state: IntArray) {
        for (c in 0 until 4) {
            val a0 = state[4 * c]
            val a1 = state[4 * c + 1]
            val a2 = state[4 * c + 2]
            val a3 = state[4 * c + 3]
            state[4 * c] = mul(a0, 0x0E) xor mul(a1, 0x0B) xor mul(a2, 0x0D) xor mul(a3, 0x09)
            state[4 * c + 1] = mul(a0, 0x09) xor mul(a1, 0x0E) xor mul(a2, 0x0B) xor mul(a3, 0x0D)
            state[4 * c + 2] = mul(a0, 0x0D) xor mul(a1, 0x09) xor mul(a2, 0x0E) xor mul(a3, 0x0B)
            state[4 * c + 3] = mul(a0, 0x0B) xor mul(a1, 0x0D) xor mul(a2, 0x09) xor mul(a3, 0x0E)
        }
    }
}
