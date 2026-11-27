package ai.aneesiqbal.rozan.airplay.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// The stream ciphers (CTR, CBC) go through JCA so that Android uses Conscrypt, which runs on the CPU's AES
// instructions. GCM only protects a few small pairing messages and needs a 16-byte IV that Conscrypt may
// not accept, so it uses BouncyCastle's lightweight implementation instead.

/** AES block size in bytes. */
private const val BLOCK = 16

private fun checkAesKey(key: ByteArray) {
    checkInput(key.size == 16 || key.size == 24 || key.size == 32) {
        "AES key must be 16, 24 or 32 bytes, got ${key.size}"
    }
}

private fun checkBlock(
    what: String,
    value: ByteArray,
) {
    checkInput(value.size == BLOCK) { "AES $what must be $BLOCK bytes, got ${value.size}" }
}

/**
 * Creates and initialises a JCA cipher: the only place this module calls `Cipher.getInstance` and
 * `Cipher.init`.
 */
internal fun jcaCipher(
    transformation: String,
    mode: Int,
    key: SecretKeySpec,
    parameters: AlgorithmParameterSpec?,
): Cipher =
    wrapCryptoErrors(transformation) {
        val cipher = Cipher.getInstance(transformation)
        if (parameters == null) cipher.init(mode, key) else cipher.init(mode, key, parameters)
        cipher
    }

private fun checkWritten(
    written: Int,
    expected: Int,
) {
    // Both modes below only ever pass whole blocks, which every provider processes immediately.
    checkInput(written == expected) { "AES provider returned $written of $expected bytes" }
}

/**
 * AES in counter mode as one continuous keystream: each call continues where the previous one stopped, also
 * in the middle of a block, so a stream split into packets of any size decrypts as if it were one buffer
 * (docs/protocol/03-media-streams.md section 2.3). Encryption and decryption are the same operation.
 *
 * The 16-byte counter block starts at `initialCounter` and is incremented as one 128-bit big-endian integer,
 * wrapping to zero after all ones (NIST SP 800-38A).
 *
 * The provider only ever sees whole blocks; the rest of a block that a call ends in is kept here and used
 * by the next call, so correctness does not depend on how a provider handles partial blocks. Allocates
 * nothing per call. Not thread-safe.
 *
 * @param key AES key, 16, 24 or 32 bytes.
 * @param initialCounter the first 16-byte counter block.
 * @throws CryptoException if a size is wrong.
 */
public class AesCtr(
    key: ByteArray,
    initialCounter: ByteArray,
) {
    private val cipher: Cipher =
        wrapCryptoErrors("AES-CTR setup") {
            checkAesKey(key)
            checkBlock("counter", initialCounter)
            jcaCipher(
                "AES/CTR/NoPadding",
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(initialCounter),
            )
        }
    private val zeros = ByteArray(BLOCK)
    private val leftover = ByteArray(BLOCK)
    private var leftoverPosition = BLOCK

    /** XORs the next [length] keystream bytes into [buffer] starting at [offset], in place. */
    public fun process(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ) {
        process(buffer, offset, length, buffer, offset)
    }

    /**
     * XORs the next [length] keystream bytes with `input[inputOffset ..]` and writes the result to
     * `output[outputOffset ..]`. The two ranges may be the same range of the same array.
     */
    public fun process(
        input: ByteArray,
        inputOffset: Int,
        length: Int,
        output: ByteArray,
        outputOffset: Int,
    ) {
        requireRange(input, inputOffset, length)
        requireRange(output, outputOffset, length)
        var inPosition = inputOffset
        var outPosition = outputOffset
        var remaining = length

        // 1. Finish the block an earlier call started.
        while (remaining > 0 && leftoverPosition < BLOCK) {
            output[outPosition++] = (input[inPosition++].toInt() xor leftover[leftoverPosition++].toInt()).toByte()
            remaining--
        }

        // 2. Whole blocks go straight through the provider.
        val whole = remaining - remaining % BLOCK
        if (whole > 0) {
            checkWritten(
                wrapCryptoErrors("AES-CTR") { cipher.update(input, inPosition, whole, output, outPosition) },
                whole,
            )
            inPosition += whole
            outPosition += whole
            remaining -= whole
        }

        // 3. Start the next block for the tail and keep the rest of its keystream for the next call.
        if (remaining > 0) {
            checkWritten(wrapCryptoErrors("AES-CTR") { cipher.update(zeros, 0, BLOCK, leftover, 0) }, BLOCK)
            for (i in 0 until remaining) {
                output[outPosition + i] = (input[inPosition + i].toInt() xor leftover[i].toInt()).toByte()
            }
            leftoverPosition = remaining
        }
    }
}

/**
 * AES in CBC mode without padding.
 *
 * [decryptWholeBlocks] is the allocation-free path for audio packets (docs/protocol/03-media-streams.md
 * section 2.2). Not thread-safe.
 *
 * @param key AES key, 16, 24 or 32 bytes.
 * @throws CryptoException if the key size is wrong.
 */
public class AesCbc(
    key: ByteArray,
) {
    private val keySpec =
        key.let {
            checkAesKey(it)
            SecretKeySpec(it, "AES")
        }

    // ECB is only the raw block function here, not the mode: CBC chaining is done explicitly in
    // decryptWholeBlocks so that a new IV per packet needs no Cipher.init (and no IvParameterSpec).
    private val blockDecrypt: Cipher = jcaCipher("AES/ECB/NoPadding", Cipher.DECRYPT_MODE, keySpec, null)
    private val scratch = ByteArray(CHUNK)
    private val previous = ByteArray(BLOCK)

    /**
     * Encrypts [length] bytes of [input] from [offset] with [iv].
     *
     * @throws CryptoException if [iv] is not 16 bytes or [length] is not a multiple of 16.
     */
    public fun encrypt(
        iv: ByteArray,
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ): ByteArray = oneShot(Cipher.ENCRYPT_MODE, iv, input, offset, length)

    /**
     * Decrypts [length] bytes of [input] from [offset] with [iv].
     *
     * @throws CryptoException if [iv] is not 16 bytes or [length] is not a multiple of 16.
     */
    public fun decrypt(
        iv: ByteArray,
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ): ByteArray = oneShot(Cipher.DECRYPT_MODE, iv, input, offset, length)

    /**
     * Decrypts, in place, the whole 16-byte blocks of `buffer[offset, offset + length)` with [iv] and leaves
     * a trailing partial block (fewer than 16 bytes), which senders transmit in clear, untouched. Each call
     * starts a new chain from [iv]. Allocates nothing.
     *
     * @return the number of bytes decrypted, `length` rounded down to a multiple of 16.
     * @throws CryptoException if [iv] is not 16 bytes.
     */
    public fun decryptWholeBlocks(
        iv: ByteArray,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        checkBlock("IV", iv)
        requireRange(buffer, offset, length)
        val whole = length - length % BLOCK
        iv.copyInto(previous)
        var position = offset
        val end = offset + whole
        while (position < end) {
            val n = minOf(CHUNK, end - position)
            buffer.copyInto(scratch, 0, position, position + n)
            checkWritten(wrapCryptoErrors("AES-CBC") { blockDecrypt.update(scratch, 0, n, buffer, position) }, n)
            for (i in 0 until n) {
                val chain = if (i < BLOCK) previous[i] else scratch[i - BLOCK]
                buffer[position + i] = (buffer[position + i].toInt() xor chain.toInt()).toByte()
            }
            scratch.copyInto(previous, 0, n - BLOCK, n)
            position += n
        }
        return whole
    }

    private fun oneShot(
        mode: Int,
        iv: ByteArray,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        checkBlock("IV", iv)
        requireRange(input, offset, length)
        checkInput(length % BLOCK == 0) { "AES-CBC input must be a multiple of $BLOCK bytes, got $length" }
        val cipher = jcaCipher("AES/CBC/NoPadding", mode, keySpec, IvParameterSpec(iv))
        return wrapCryptoErrors("AES-CBC") { cipher.doFinal(input, offset, length) }
    }

    private companion object {
        /** Bytes decrypted per provider call; a multiple of the block size. */
        const val CHUNK = 4096
    }
}

/**
 * AES-GCM AEAD with 16-byte tags (NIST SP 800-38D), backed by BouncyCastle's lightweight implementation.
 *
 * Nonces may be 12 bytes (the standard size) or 16 bytes, which AirPlay legacy PIN pairing uses
 * (docs/protocol/02-pairing.md section 5.5); a 16-byte nonce is hashed into the initial counter with GHASH
 * as the standard specifies.
 */
public object AesGcm {
    /** Standard nonce size in bytes. */
    public const val NONCE_SIZE: Int = 12

    /** The longer nonce size used by AirPlay legacy PIN pairing, in bytes. */
    public const val LONG_NONCE_SIZE: Int = 16

    /** Authentication tag size in bytes. */
    public const val TAG_SIZE: Int = 16

    /**
     * Encrypts [plaintext] and returns the ciphertext followed by the tag.
     *
     * @throws CryptoException if the key or nonce size is wrong.
     */
    public fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray = run(forEncryption = true, key, nonce, plaintext, aad)

    /**
     * Checks the tag at the end of [ciphertextAndTag] and returns the plaintext. Nothing is returned unless
     * the tag verifies.
     *
     * @throws CryptoException if the tag does not verify, the input is shorter than the tag, or the key or
     *   nonce size is wrong.
     */
    public fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        checkInput(ciphertextAndTag.size >= TAG_SIZE) { "AES-GCM input is shorter than the tag" }
        return run(forEncryption = false, key, nonce, ciphertextAndTag, aad)
    }

    private fun run(
        forEncryption: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        checkAesKey(key)
        checkInput(nonce.size == NONCE_SIZE || nonce.size == LONG_NONCE_SIZE) {
            "AES-GCM nonce must be $NONCE_SIZE or $LONG_NONCE_SIZE bytes, got ${nonce.size}"
        }
        val gcm = GCMBlockCipher.newInstance(AESEngine.newInstance())
        gcm.init(forEncryption, AEADParameters(KeyParameter(key), TAG_SIZE * Byte.SIZE_BITS, nonce, aad))
        val out = ByteArray(gcm.getOutputSize(input.size))
        val written = gcm.processBytes(input, 0, input.size, out, 0)
        try {
            gcm.doFinal(out, written)
        } catch (e: InvalidCipherTextException) {
            out.fill(0)
            throw CryptoException("AES-GCM tag does not verify", e)
        }
        return out
    }
}
