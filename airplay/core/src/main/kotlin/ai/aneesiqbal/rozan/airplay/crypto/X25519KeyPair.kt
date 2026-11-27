package ai.aneesiqbal.rozan.airplay.crypto

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * An X25519 (RFC 7748) key pair for Diffie-Hellman, backed by BouncyCastle's lightweight implementation.
 *
 * Instances are immutable and thread-safe.
 */
public class X25519KeyPair private constructor(
    private val privateKey: ByteArray,
) {
    private val publicKeyBytes = ByteArray(KEY_SIZE).also { X25519.generatePublicKey(privateKey, 0, it, 0) }

    /** The 32-byte public key (u-coordinate, little-endian). */
    public val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    /**
     * Computes the shared secret with [peerPublicKey].
     *
     * @throws CryptoException if [peerPublicKey] is not 32 bytes, or if the result is all zeros (the peer
     *   sent a small-order point, RFC 7748 section 6.1).
     */
    public fun sharedSecret(peerPublicKey: ByteArray): ByteArray {
        if (peerPublicKey.size != KEY_SIZE) {
            throw CryptoException("X25519 public key must be $KEY_SIZE bytes, got ${peerPublicKey.size}")
        }
        val out = ByteArray(KEY_SIZE)
        // calculateAgreement returns false when the output is all zeros, checked without branching on secrets.
        if (!X25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, out, 0)) {
            throw CryptoException("X25519 shared secret is all zeros")
        }
        return out
    }

    /** Factories and sizes. */
    public companion object {
        /** Size of private keys, public keys and shared secrets, in bytes. */
        public const val KEY_SIZE: Int = 32

        /** Generates a new key pair from [random]. */
        public fun generate(random: SecureRandom = Crypto.secureRandom): X25519KeyPair {
            val privateKey = ByteArray(KEY_SIZE)
            X25519.generatePrivateKey(random, privateKey)
            return X25519KeyPair(privateKey)
        }

        /**
         * Rebuilds a key pair from a 32-byte private key (scalar). Clamping happens inside X25519.
         *
         * @throws CryptoException if [privateKey] is not 32 bytes.
         */
        public fun fromPrivateKey(privateKey: ByteArray): X25519KeyPair {
            if (privateKey.size != KEY_SIZE) {
                throw CryptoException("X25519 private key must be $KEY_SIZE bytes, got ${privateKey.size}")
            }
            return X25519KeyPair(privateKey.copyOf())
        }

        /** The raw X25519(k, u) function of RFC 7748 section 5, for test vectors. */
        internal fun scalarMult(
            k: ByteArray,
            u: ByteArray,
        ): ByteArray = ByteArray(KEY_SIZE).also { X25519.scalarMult(k, 0, u, 0, it, 0) }
    }
}
