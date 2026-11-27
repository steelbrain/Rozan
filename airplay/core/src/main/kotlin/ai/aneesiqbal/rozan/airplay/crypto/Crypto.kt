package ai.aneesiqbal.rozan.airplay.crypto

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom

/** Small helpers shared by the pairing and stream code: comparisons, random bytes and hashing. */
public object Crypto {
    /** Process-wide source of randomness. [SecureRandom] is thread-safe. */
    internal val secureRandom: SecureRandom = newSecureRandom()

    private fun newSecureRandom(): SecureRandom = SecureRandom()

    /**
     * Returns whether [a] and [b] hold the same bytes. The time taken depends only on the lengths, never
     * on where the arrays differ, so it is safe for comparing MACs, proofs and other secrets.
     */
    public fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    /** Returns [size] bytes from a [SecureRandom]. */
    public fun randomBytes(size: Int): ByteArray {
        require(size >= 0) { "size must not be negative" }
        return ByteArray(size).also { secureRandom.nextBytes(it) }
    }

    /** SHA-512 of the concatenation of [parts]. */
    public fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA_512, parts)

    /**
     * Hash of the concatenation of [parts] with the [MessageDigest] named [algorithm] (for example
     * `"SHA-1"` or `"SHA-512"`).
     *
     * @throws IllegalArgumentException if the platform has no such digest.
     */
    public fun digest(
        algorithm: String,
        vararg parts: ByteArray,
    ): ByteArray = digest(algorithm, parts)

    internal fun digest(
        algorithm: String,
        parts: Array<out ByteArray>,
    ): ByteArray {
        val md = messageDigest(algorithm)
        for (part in parts) md.update(part)
        return md.digest()
    }

    internal fun messageDigest(algorithm: String): MessageDigest =
        try {
            MessageDigest.getInstance(algorithm)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalArgumentException("unsupported digest $algorithm", e)
        }

    internal const val SHA_512: String = "SHA-512"
}

/** Big-endian bytes of a non-negative integer with no leading zero bytes (one zero byte for zero). */
internal fun BigInteger.toUnsignedBytes(): ByteArray {
    val bytes = toByteArray()
    return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
}

/** Runs a JCA or BouncyCastle call and reports any security-provider failure as a [CryptoException]. */
internal inline fun <T> wrapCryptoErrors(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: GeneralSecurityException) {
        throw CryptoException("$what failed", e)
    }

/** Throws a [CryptoException] with [message] if [value] is false: the input-validation twin of `require`. */
internal inline fun checkInput(
    value: Boolean,
    message: () -> String,
) {
    if (!value) throw CryptoException(message())
}

internal fun requireRange(
    array: ByteArray,
    offset: Int,
    length: Int,
) {
    require(offset >= 0 && length >= 0 && offset <= array.size - length) {
        "range [$offset, $offset + $length) is outside an array of ${array.size} bytes"
    }
}
