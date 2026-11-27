package ai.aneesiqbal.rozan.airplay.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.modes.ChaCha20Poly1305 as BcChaCha20Poly1305

/**
 * The ChaCha20-Poly1305 AEAD of RFC 8439 with 32-byte keys, 12-byte nonces and 16-byte tags, backed by
 * BouncyCastle's lightweight implementation. Also the nonce layouts HomeKit-style pairing uses.
 */
public object ChaCha20Poly1305 {
    /** Key size in bytes. */
    public const val KEY_SIZE: Int = 32

    /** Nonce size in bytes. */
    public const val NONCE_SIZE: Int = 12

    /** Authentication tag size in bytes. */
    public const val TAG_SIZE: Int = 16

    private const val COUNTER_OFFSET = NONCE_SIZE - Long.SIZE_BYTES
    private const val ASCII_LIMIT = 0x80

    /**
     * Encrypts `plaintext[offset, offset + length)` and returns the ciphertext followed by the tag.
     *
     * @throws CryptoException if the key or nonce size is wrong.
     */
    public fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
        offset: Int = 0,
        length: Int = plaintext.size - offset,
    ): ByteArray {
        requireRange(plaintext, offset, length)
        val cipher = cipher(forEncryption = true, key, nonce, aad)
        val out = ByteArray(length + TAG_SIZE)
        val written = cipher.processBytes(plaintext, offset, length, out, 0)
        finish(cipher, out, written)
        return out
    }

    /**
     * Checks the tag at the end of `ciphertextAndTag[offset, offset + length)` and returns the plaintext.
     * Nothing is returned unless the tag verifies.
     *
     * @throws CryptoException if the tag does not verify, the input is shorter than the tag, or the key or
     *   nonce size is wrong.
     */
    public fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
        offset: Int = 0,
        length: Int = ciphertextAndTag.size - offset,
    ): ByteArray {
        requireRange(ciphertextAndTag, offset, length)
        if (length < TAG_SIZE) throw CryptoException("ChaCha20-Poly1305 input is shorter than the tag")
        val cipher = cipher(forEncryption = false, key, nonce, aad)
        val out = ByteArray(length - TAG_SIZE)
        val written = cipher.processBytes(ciphertextAndTag, offset, length, out, 0)
        finish(cipher, out, written)
        return out
    }

    /**
     * The 12-byte nonce for message number [counter]: four zero bytes followed by the counter as an 8-byte
     * little-endian integer (treated as unsigned).
     */
    public fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        for (i in 0 until Long.SIZE_BYTES) {
            nonce[COUNTER_OFFSET + i] = (counter ushr (Byte.SIZE_BITS * i)).toByte()
        }
        return nonce
    }

    /**
     * The 12-byte nonce for an ASCII [label] such as `"PV-Msg02"`: the label's bytes right-aligned, with zero
     * bytes on the left.
     *
     * @throws IllegalArgumentException if [label] is longer than 12 characters or not ASCII.
     */
    public fun labelNonce(label: String): ByteArray {
        require(label.length <= NONCE_SIZE) { "nonce label longer than $NONCE_SIZE bytes" }
        require(label.all { it.code < ASCII_LIMIT }) { "nonce label must be ASCII" }
        val nonce = ByteArray(NONCE_SIZE)
        val start = NONCE_SIZE - label.length
        for (i in label.indices) nonce[start + i] = label[i].code.toByte()
        return nonce
    }

    private fun cipher(
        forEncryption: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): BcChaCha20Poly1305 {
        if (key.size !=
            KEY_SIZE
        ) {
            throw CryptoException("ChaCha20-Poly1305 key must be $KEY_SIZE bytes, got ${key.size}")
        }
        if (nonce.size != NONCE_SIZE) {
            throw CryptoException("ChaCha20-Poly1305 nonce must be $NONCE_SIZE bytes, got ${nonce.size}")
        }
        return BcChaCha20Poly1305().apply {
            init(forEncryption, AEADParameters(KeyParameter(key), TAG_SIZE * Byte.SIZE_BITS, nonce, aad))
        }
    }

    private fun finish(
        cipher: BcChaCha20Poly1305,
        out: ByteArray,
        written: Int,
    ) {
        try {
            cipher.doFinal(out, written)
        } catch (e: InvalidCipherTextException) {
            out.fill(0)
            throw CryptoException("ChaCha20-Poly1305 tag does not verify", e)
        }
    }
}
