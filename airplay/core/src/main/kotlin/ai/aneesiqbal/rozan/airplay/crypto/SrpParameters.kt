package ai.aneesiqbal.rozan.airplay.crypto

/**
 * Everything that differs between SRP-6a deployments: the group, the hash, how the session key K is
 * derived from the premaster secret S, and how the proofs M1 and M2 are computed.
 *
 * The multiplier `k = H(N | PAD(g))`, the scrambler `u = H(PAD(A) | PAD(B))` and the private key
 * `x = H(s | H(I | ":" | P))` are fixed by RFC 5054 and are not configurable.
 *
 * @property group the group (N, g).
 * @property hashAlgorithm the [java.security.MessageDigest] algorithm H, for example `"SHA-512"`.
 * @property keyDerivation how K is derived from S.
 * @property proofs how M1 and M2 are computed.
 */
public class SrpParameters(
    public val group: SrpGroup,
    public val hashAlgorithm: String,
    public val keyDerivation: SrpKeyDerivation = SrpKeyDerivation.HASH,
    public val proofs: SrpProofs = SrpProofs.RFC2945,
) {
    init {
        // Fail at construction, not halfway through a handshake, if the platform lacks the digest.
        Crypto.messageDigest(hashAlgorithm)
    }

    /** H applied to the concatenation of [parts]. */
    public fun hash(vararg parts: ByteArray): ByteArray = Crypto.digest(hashAlgorithm, parts)

    /** Presets. */
    public companion object {
        /**
         * HomeKit-style pair-setup: the RFC 5054 3072-bit group, SHA-512, `K = H(S)`, and the RFC 2945
         * proofs `M1 = H(H(N) xor H(g) | H(I) | s | A | B | K)` and `M2 = H(A | M1 | K)`.
         */
        public val HOMEKIT: SrpParameters =
            SrpParameters(SrpGroup.RFC5054_3072, Crypto.SHA_512, SrpKeyDerivation.HASH, SrpProofs.RFC2945)

        /**
         * AirPlay legacy PIN pairing (`pair-setup-pin`, docs/protocol/02-pairing.md section 5.2): the RFC
         * 5054 2048-bit group, SHA-1, the 40-byte `K = H(S | 00000000) | H(S | 00000001)`
         * ([SrpKeyDerivation.HASH_WITH_COUNTER]) and the RFC 2945 proofs over that K, with H(g) over the
         * single byte 0x02 and s, A and B unpadded.
         */
        public val LEGACY_PIN: SrpParameters =
            SrpParameters(SrpGroup.RFC5054_2048, "SHA-1", SrpKeyDerivation.HASH_WITH_COUNTER, SrpProofs.RFC2945)
    }
}

/** Derives the session key K from the premaster secret S. */
public fun interface SrpKeyDerivation {
    /**
     * Returns K for [premasterSecret], which is S as a big-endian integer without leading zero bytes.
     * Use [SrpGroup.pad] on it first if a variant hashes the padded form.
     */
    public fun deriveKey(
        parameters: SrpParameters,
        premasterSecret: ByteArray,
    ): ByteArray

    /** Standard derivations. */
    public companion object {
        /** `K = H(S)`, as used by HomeKit pairing. */
        public val HASH: SrpKeyDerivation = SrpKeyDerivation { parameters, s -> parameters.hash(s) }

        /**
         * `K = H(S | 00 00 00 00) | H(S | 00 00 00 01)`: two digests of S with a 4-byte big-endian counter
         * appended, twice the digest length (40 bytes with SHA-1). Used by AirPlay legacy PIN pairing.
         */
        public val HASH_WITH_COUNTER: SrpKeyDerivation =
            SrpKeyDerivation { parameters, s ->
                parameters.hash(s, byteArrayOf(0, 0, 0, 0)) + parameters.hash(s, byteArrayOf(0, 0, 0, 1))
            }

        /**
         * `K = SHA_Interleave(S)` from RFC 2945 section 3.1, generalised to the configured hash: drop
         * leading zero bytes (and one more byte if the length is odd), hash the even- and odd-indexed bytes
         * separately and interleave the two digests. K is twice the digest length.
         */
        public val INTERLEAVED: SrpKeyDerivation =
            SrpKeyDerivation { parameters, s ->
                var start = 0
                while (start < s.size && s[start] == 0.toByte()) start++
                if ((s.size - start) % 2 == 1) start++
                val half = (s.size - start) / 2
                val even = ByteArray(half) { s[start + 2 * it] }
                val odd = ByteArray(half) { s[start + 2 * it + 1] }
                val g = parameters.hash(even)
                val h = parameters.hash(odd)
                ByteArray(g.size * 2) { if (it % 2 == 0) g[it / 2] else h[it / 2] }
            }
    }
}

/** Computes the client proof M1 and the server proof M2 from the values both sides share. */
public interface SrpProofs {
    /** Returns M1, the proof the client sends to show it knows K. */
    public fun clientProof(transcript: SrpTranscript): ByteArray

    /** Returns M2, the proof the server sends back once [clientProof] (M1) has been verified. */
    public fun serverProof(
        transcript: SrpTranscript,
        clientProof: ByteArray,
    ): ByteArray

    /** Standard proofs. */
    public companion object {
        /**
         * RFC 2945 section 3: `M1 = H(H(N) xor H(g) | H(I) | s | A | B | K)` and `M2 = H(A | M1 | K)`, with
         * N, g, A and B converted to bytes without padding.
         */
        public val RFC2945: SrpProofs =
            object : SrpProofs {
                override fun clientProof(transcript: SrpTranscript): ByteArray {
                    val group = transcript.parameters.group
                    val hn = transcript.hash(group.prime.toUnsignedBytes())
                    val hg = transcript.hash(group.generator.toUnsignedBytes())
                    for (i in hn.indices) hn[i] = (hn[i].toInt() xor hg[i].toInt()).toByte()
                    return transcript.hash(
                        hn,
                        transcript.hash(transcript.username),
                        transcript.salt,
                        transcript.clientPublicKey,
                        transcript.serverPublicKey,
                        transcript.sessionKey,
                    )
                }

                override fun serverProof(
                    transcript: SrpTranscript,
                    clientProof: ByteArray,
                ): ByteArray = transcript.hash(transcript.clientPublicKey, clientProof, transcript.sessionKey)
            }
    }
}

/**
 * The values of one SRP exchange that [SrpProofs] may use. Byte arrays are returned as copies.
 *
 * @property parameters the parameters of the exchange.
 */
public class SrpTranscript internal constructor(
    public val parameters: SrpParameters,
    private val usernameBytes: ByteArray,
    private val saltBytes: ByteArray,
    private val clientPublicKeyBytes: ByteArray,
    private val serverPublicKeyBytes: ByteArray,
    private val sessionKeyBytes: ByteArray,
) {
    /** The username I. */
    public val username: ByteArray get() = usernameBytes.copyOf()

    /** The salt s. */
    public val salt: ByteArray get() = saltBytes.copyOf()

    /** The client public key A, big-endian without leading zero bytes. */
    public val clientPublicKey: ByteArray get() = clientPublicKeyBytes.copyOf()

    /** The server public key B, big-endian without leading zero bytes. */
    public val serverPublicKey: ByteArray get() = serverPublicKeyBytes.copyOf()

    /** The session key K. */
    public val sessionKey: ByteArray get() = sessionKeyBytes.copyOf()

    /** H applied to the concatenation of [parts]. */
    public fun hash(vararg parts: ByteArray): ByteArray = Crypto.digest(parameters.hashAlgorithm, parts)
}
