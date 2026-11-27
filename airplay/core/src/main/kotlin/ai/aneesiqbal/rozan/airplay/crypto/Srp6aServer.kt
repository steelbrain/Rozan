package ai.aneesiqbal.rozan.airplay.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Server side of one SRP-6a exchange (RFC 5054, RFC 2945).
 *
 * Usage: create one with [fromPassword] or [fromVerifier]; send [salt] and [publicKey] (B) to the client;
 * when the client answers with its public key A and proof M1, call [verifyClient], which returns the
 * server proof M2 to send back. After that, [sessionKey] holds K. An instance handles exactly one attempt:
 * after [verifyClient] has been called once, whether it succeeded or not, later calls fail, so a client
 * cannot retry guesses against the same B.
 *
 * The factories pick b so that B is exactly [SrpGroup.byteLength] bytes long without padding, which leaves
 * only one possible encoding of B on the wire (docs/protocol/02-pairing.md section 5.2).
 *
 * Not thread-safe.
 */
public class Srp6aServer internal constructor(
    private val parameters: SrpParameters,
    username: ByteArray,
    salt: ByteArray,
    private val verifier: BigInteger,
    private val privateKey: BigInteger,
) {
    private val group = parameters.group
    private val usernameBytes = username.copyOf()
    private val saltBytes = salt.copyOf()
    private val serverPublic: BigInteger
    private val serverPublicBytes: ByteArray
    private var used = false
    private var verifiedKey: ByteArray? = null

    init {
        require(verifier.signum() > 0 && verifier < group.prime) { "verifier must be in [1, N - 1]" }
        require(privateKey.signum() > 0) { "private key must be positive" }
        serverPublic = Srp6a.serverPublicKey(parameters, verifier, privateKey)
        // Clients must reject B = 0 (RFC 5054 section 2.5.3); about one b in N produces it.
        require(serverPublic.signum() != 0) { "private key produced B = 0" }
        serverPublicBytes = serverPublic.toUnsignedBytes()
    }

    /** The salt s to send to the client. */
    public val salt: ByteArray get() = saltBytes.copyOf()

    /** The server public key B, big-endian without leading zero bytes. */
    public val publicKey: ByteArray get() = serverPublicBytes.copyOf()

    /**
     * The session key K. Available only after [verifyClient] succeeded.
     *
     * @throws IllegalStateException before a successful [verifyClient].
     */
    public val sessionKey: ByteArray
        get() = checkNotNull(verifiedKey) { "SRP client has not been verified" }.copyOf()

    /**
     * Checks the client's public key A and proof M1 and, if both are valid, returns the server proof M2.
     *
     * A is used padded to the group size in u and without leading zero bytes in the proofs (RFC 5054
     * section 2.1 conversions).
     *
     * @param clientPublicKey A as a big-endian unsigned integer of 1 to [SrpGroup.byteLength] bytes,
     *   padded or not.
     * @param clientProof M1 as sent by the client.
     * @throws CryptoException if A is invalid (zero modulo N, not below N, empty or too long), if u is
     *   zero, if M1 does not match, or if this instance has already been used.
     */
    public fun verifyClient(
        clientPublicKey: ByteArray,
        clientProof: ByteArray,
    ): ByteArray {
        checkInput(!used) { "SRP exchange already used" }
        used = true
        checkInput(clientPublicKey.isNotEmpty() && clientPublicKey.size <= group.byteLength) {
            "SRP client public key has invalid length ${clientPublicKey.size}"
        }
        val clientPublic = BigInteger(1, clientPublicKey)
        checkInput(clientPublic.mod(group.prime).signum() != 0) { "SRP client public key is 0 mod N" }
        checkInput(clientPublic < group.prime) { "SRP client public key is not below N" }
        val u = Srp6a.scrambler(parameters, clientPublic, serverPublic)
        checkInput(u.signum() != 0) { "SRP scrambling parameter is 0" }

        val premaster =
            clientPublic
                .multiply(verifier.modPow(u, group.prime))
                .mod(group.prime)
                .modPow(privateKey, group.prime)
        val key = parameters.keyDerivation.deriveKey(parameters, premaster.toUnsignedBytes())
        val transcript =
            SrpTranscript(parameters, usernameBytes, saltBytes, clientPublic.toUnsignedBytes(), serverPublicBytes, key)
        val expected = parameters.proofs.clientProof(transcript)
        checkInput(Crypto.constantTimeEquals(expected, clientProof)) { "SRP client proof does not match" }
        val serverProof = parameters.proofs.serverProof(transcript, expected)
        verifiedKey = key
        return serverProof
    }

    /** Factories. */
    public companion object {
        /** Salt length used by [fromPassword] when no salt is given. */
        public const val DEFAULT_SALT_SIZE: Int = 16

        /** RFC 5054 asks for at least 256 bits; larger exponents only slow down weak TV CPUs. */
        private const val PRIVATE_KEY_BYTES = 32

        /**
         * Creates a server that knows the password itself (as with a PIN shown on the receiver's screen):
         * computes `x = H(s | H(I | ":" | P))` and `v = g^x mod N`.
         *
         * @param parameters group, hash and proof variant.
         * @param username the username I.
         * @param password the password P (for PIN pairing, the PIN's ASCII bytes).
         * @param salt the salt s. The default is [DEFAULT_SALT_SIZE] random bytes whose first byte is not
         *   zero, so that the salt has only one encoding.
         * @param random source of the ephemeral private key b.
         */
        public fun fromPassword(
            parameters: SrpParameters,
            username: ByteArray,
            password: ByteArray,
            salt: ByteArray = randomSalt(Crypto.secureRandom),
            random: SecureRandom = Crypto.secureRandom,
        ): Srp6aServer =
            create(parameters, username, salt, Srp6a.verifier(parameters, username, password, salt), random)

        /**
         * Creates a server from a stored verifier `v = g^x mod N`.
         *
         * @param parameters group, hash and proof variant.
         * @param username the username I.
         * @param salt the salt s the verifier was computed with.
         * @param verifier v as a big-endian unsigned integer.
         * @param random source of the ephemeral private key b.
         * @throws IllegalArgumentException if v is not in [1, N - 1].
         */
        public fun fromVerifier(
            parameters: SrpParameters,
            username: ByteArray,
            salt: ByteArray,
            verifier: ByteArray,
            random: SecureRandom = Crypto.secureRandom,
        ): Srp6aServer = create(parameters, username, salt, BigInteger(1, verifier), random)

        private fun create(
            parameters: SrpParameters,
            username: ByteArray,
            salt: ByteArray,
            verifier: BigInteger,
            random: SecureRandom,
        ): Srp6aServer {
            val bytes = ByteArray(PRIVATE_KEY_BYTES)
            while (true) {
                random.nextBytes(bytes)
                val privateKey = BigInteger(1, bytes)
                if (privateKey.signum() == 0) continue
                val server = Srp6aServer(parameters, username, salt, verifier, privateKey)
                // About one b in 256 gives a B with a leading zero byte; pick again instead.
                if (server.serverPublicBytes.size == parameters.group.byteLength) return server
            }
        }

        private fun randomSalt(random: SecureRandom): ByteArray {
            val salt = ByteArray(DEFAULT_SALT_SIZE)
            do random.nextBytes(salt) while (salt[0] == 0.toByte())
            return salt
        }
    }
}

/** The fixed SRP-6a computations of RFC 5054 sections 2.5 and 2.6, shared with the tests' client. */
internal object Srp6a {
    /** `k = H(N | PAD(g))`. */
    fun multiplier(parameters: SrpParameters): BigInteger {
        val group = parameters.group
        return BigInteger(1, parameters.hash(group.prime.toUnsignedBytes(), group.pad(group.generator)))
    }

    /** `B = (k * v + g^b) mod N`. */
    fun serverPublicKey(
        parameters: SrpParameters,
        verifier: BigInteger,
        privateKey: BigInteger,
    ): BigInteger {
        val group = parameters.group
        return multiplier(parameters)
            .multiply(verifier)
            .add(group.generator.modPow(privateKey, group.prime))
            .mod(group.prime)
    }

    /** `u = H(PAD(A) | PAD(B))`. */
    fun scrambler(
        parameters: SrpParameters,
        clientPublic: BigInteger,
        serverPublic: BigInteger,
    ): BigInteger {
        val group = parameters.group
        return BigInteger(1, parameters.hash(group.pad(clientPublic), group.pad(serverPublic)))
    }

    /** `x = H(s | H(I | ":" | P))`. */
    fun privateKey(
        parameters: SrpParameters,
        username: ByteArray,
        password: ByteArray,
        salt: ByteArray,
    ): BigInteger {
        val inner = parameters.hash(username, byteArrayOf(':'.code.toByte()), password)
        return BigInteger(1, parameters.hash(salt, inner))
    }

    /** `v = g^x mod N`. */
    fun verifier(
        parameters: SrpParameters,
        username: ByteArray,
        password: ByteArray,
        salt: ByteArray,
    ): BigInteger {
        val group = parameters.group
        return group.generator.modPow(privateKey(parameters, username, password, salt), group.prime)
    }
}
