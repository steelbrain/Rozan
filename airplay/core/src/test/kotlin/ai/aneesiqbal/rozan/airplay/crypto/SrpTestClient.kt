package ai.aneesiqbal.rozan.airplay.crypto

import java.math.BigInteger

/** SRP-6a client (RFC 5054 section 2.6) used to exercise [Srp6aServer] end to end. */
class SrpTestClient(
    private val parameters: SrpParameters,
    private val username: ByteArray,
    private val password: ByteArray,
    private val privateKey: BigInteger = BigInteger(1, Crypto.randomBytes(32)),
) {
    /** A = g^a mod N. */
    fun publicKey(): BigInteger = parameters.group.generator.modPow(privateKey, parameters.group.prime)

    private var transcript: SrpTranscript? = null

    /** K, after [clientProof]. */
    fun sessionKey(): ByteArray = checkNotNull(transcript).sessionKey

    /** Returns M1 for the server's [salt] and [serverPublicKey]. */
    fun clientProof(
        salt: ByteArray,
        serverPublicKey: ByteArray,
    ): ByteArray {
        val n: BigInteger = parameters.group.prime
        val g: BigInteger = parameters.group.generator
        val b = BigInteger(1, serverPublicKey)
        require(b.mod(n).signum() != 0)
        val a: BigInteger = publicKey()
        val k = Srp6a.multiplier(parameters)
        val u = Srp6a.scrambler(parameters, a, b)
        val x = Srp6a.privateKey(parameters, username, password, salt)
        val base = b.subtract(k.multiply(g.modPow(x, n))).mod(n)
        val s = base.modPow(privateKey.add(u.multiply(x)), n)
        val key = parameters.keyDerivation.deriveKey(parameters, s.toUnsignedBytes())
        val t = SrpTranscript(parameters, username, salt, a.toUnsignedBytes(), b.toUnsignedBytes(), key)
        transcript = t
        return parameters.proofs.clientProof(t)
    }

    /** Whether [serverProof] is the M2 this client expects for its [clientProof]. */
    fun verifyServer(
        clientProof: ByteArray,
        serverProof: ByteArray,
    ): Boolean = parameters.proofs.serverProof(checkNotNull(transcript), clientProof).contentEquals(serverProof)
}
