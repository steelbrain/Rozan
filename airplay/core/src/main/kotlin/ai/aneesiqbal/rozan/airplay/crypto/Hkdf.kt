package ai.aneesiqbal.rozan.airplay.crypto

import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF (RFC 5869): HMAC-based extract-and-expand key derivation. */
public object Hkdf {
    /** The JCA name of HMAC-SHA512, the MAC HomeKit-style pairing uses. */
    public const val HMAC_SHA512: String = "HmacSHA512"

    private const val MAX_BLOCKS = 255

    /**
     * HKDF-SHA512: derives [length] bytes from [inputKeyMaterial], [salt] and [info].
     *
     * @throws IllegalArgumentException if [length] is not in `1..16320` (255 blocks of 64 bytes).
     */
    public fun sha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray = derive(HMAC_SHA512, inputKeyMaterial, salt, info, length)

    /**
     * HKDF with the HMAC named [macAlgorithm] (a JCA [Mac] name such as `"HmacSHA256"`). An empty [salt]
     * means "not provided" and is replaced by a string of zeros as long as the hash output (RFC 5869
     * section 2.2).
     *
     * @throws IllegalArgumentException if the platform has no such MAC or [length] is not in
     *   `1..255 * hashLength`.
     */
    public fun derive(
        macAlgorithm: String,
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac =
            try {
                Mac.getInstance(macAlgorithm)
            } catch (e: NoSuchAlgorithmException) {
                throw IllegalArgumentException("unsupported MAC $macAlgorithm", e)
            }
        val hashLength = mac.macLength
        require(length in 1..MAX_BLOCKS * hashLength) { "HKDF length must be in 1..${MAX_BLOCKS * hashLength}" }
        return wrapCryptoErrors("HKDF") {
            // Extract: PRK = HMAC(salt, IKM). A zero-length HMAC key equals HashLen zero bytes after padding,
            // but SecretKeySpec rejects empty keys, so use the zeros explicitly.
            mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(hashLength) else salt, macAlgorithm))
            val prk = mac.doFinal(inputKeyMaterial)

            // Expand: T(i) = HMAC(PRK, T(i - 1) | info | i), OKM = first length bytes of T(1) | T(2) | ...
            mac.init(SecretKeySpec(prk, macAlgorithm))
            val okm = ByteArray(length)
            var block = ByteArray(0)
            var position = 0
            var counter = 1
            while (position < length) {
                mac.update(block)
                mac.update(info)
                mac.update(counter.toByte())
                block = mac.doFinal()
                val n = minOf(block.size, length - position)
                block.copyInto(okm, position, 0, n)
                position += n
                counter++
            }
            prk.fill(0)
            okm
        }
    }
}
