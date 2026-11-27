package ai.aneesiqbal.rozan.airplay.crypto

import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc8032.Ed25519 as Bc

/**
 * An Ed25519 (RFC 8032, pure variant) signing key, backed by BouncyCastle's lightweight implementation.
 *
 * Instances are immutable and thread-safe.
 */
public class Ed25519KeyPair private constructor(
    private val seed: ByteArray,
) {
    private val publicKeyBytes = ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Bc.generatePublicKey(seed, 0, it, 0) }

    /** The 32-byte public key. */
    public val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    /** Signs [message] and returns the 64-byte signature. */
    public fun sign(message: ByteArray): ByteArray {
        val signature = ByteArray(Ed25519.SIGNATURE_SIZE)
        Bc.sign(seed, 0, publicKeyBytes, 0, message, 0, message.size, signature, 0)
        return signature
    }

    /** Factories and sizes. */
    public companion object {
        /** Size of the private key (the RFC 8032 secret seed), in bytes. */
        public const val SEED_SIZE: Int = 32

        /** Generates a new key pair from [random]. */
        public fun generate(random: SecureRandom = Crypto.secureRandom): Ed25519KeyPair {
            val seed = ByteArray(SEED_SIZE)
            Bc.generatePrivateKey(random, seed)
            return Ed25519KeyPair(seed)
        }

        /**
         * Rebuilds a key pair from its 32-byte secret seed.
         *
         * @throws CryptoException if [seed] is not 32 bytes.
         */
        public fun fromSeed(seed: ByteArray): Ed25519KeyPair {
            if (seed.size != SEED_SIZE) throw CryptoException("Ed25519 seed must be $SEED_SIZE bytes, got ${seed.size}")
            return Ed25519KeyPair(seed.copyOf())
        }
    }
}

/** Ed25519 signature verification and sizes. */
public object Ed25519 {
    /** Size of a public key, in bytes. */
    public const val PUBLIC_KEY_SIZE: Int = 32

    /** Size of a signature, in bytes. */
    public const val SIGNATURE_SIZE: Int = 64

    /**
     * Returns whether [signature] is a valid Ed25519 signature of [message] under [publicKey]. Inputs of the
     * wrong size, non-canonical encodings and invalid points all give false rather than an exception.
     */
    public fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return Bc.verify(signature, 0, publicKey, 0, message, 0, message.size)
    }
}
