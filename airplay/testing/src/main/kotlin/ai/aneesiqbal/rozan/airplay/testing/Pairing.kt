package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.AesCtr
import ai.aneesiqbal.rozan.airplay.crypto.AesGcm
import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.crypto.CryptoException
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.crypto.X25519KeyPair
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistException
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import java.math.BigInteger

/*
 * The sender's half of docs/protocol/02-pairing.md §4 and §5, written from the spec. These classes build
 * request bodies and check response bodies; AirPlayTestSender does the I/O.
 */

/**
 * The SRP-6a client of 02 §5.2: RFC 5054's 2048-bit group, SHA-1, the 40-byte session key and Apple's
 * M1 (H(g) of the single byte 0x02; s, A and B unpadded).
 */
internal class SrpClient(
    private val username: ByteArray,
    private val password: ByteArray,
    private val a: BigInteger = BigInteger(1, Crypto.randomBytes(32)),
) {
    /** A = g^a mod N, minimal big-endian. */
    val publicKey: ByteArray = unsigned(G.modPow(a, N))

    /** K (40 bytes), after [proof]. */
    var sessionKey: ByteArray? = null
        private set

    private var m1: ByteArray? = null

    /** M1 for the receiver's [salt] and [serverKey] B. */
    fun proof(
        salt: ByteArray,
        serverKey: ByteArray,
    ): ByteArray {
        val b = BigInteger(1, serverKey)
        check(b.mod(N).signum() != 0) { "B is 0 mod N" }
        val k = BigInteger(1, sha1(pad(N), pad(G)))
        val u = BigInteger(1, sha1(pad(BigInteger(1, publicKey)), pad(b)))
        check(u.signum() != 0) { "u is 0" }
        val x = BigInteger(1, sha1(salt, sha1(username, ":".toByteArray(), password)))
        val base = b.subtract(k.multiply(G.modPow(x, N))).mod(N)
        val s = unsigned(base.modPow(a.add(u.multiply(x)), N))
        val key = sha1(s, byteArrayOf(0, 0, 0, 0)) + sha1(s, byteArrayOf(0, 0, 0, 1))
        sessionKey = key
        val hn = sha1(unsigned(N))
        val hg = sha1(unsigned(G))
        val proof = sha1(xor(hn, hg), sha1(username), salt, publicKey, unsigned(b), key)
        m1 = proof
        return proof
    }

    /** Whether [serverProof] is M2 = H(A ‖ M1 ‖ K). */
    fun verifyServer(serverProof: ByteArray): Boolean {
        val expected = sha1(publicKey, checkNotNull(m1), checkNotNull(sessionKey))
        return Crypto.constantTimeEquals(expected, serverProof)
    }

    companion object {
        val N =
            BigInteger(
                "ac6bdb41324a9a9bf166de5e1389582faf72b6651987ee07fc3192943db56050" +
                    "a37329cbb4a099ed8193e0757767a13dd52312ab4b03310dcd7f48a9da04fd50" +
                    "e8083969edb767b0cf6095179a163ab3661a05fbd5faaae82918a9962f0b93b8" +
                    "55f97993ec975eeaa80d740adbf4ff747359d041d5c33ea71d281e446b14773b" +
                    "ca97b43a23fb801676bd207a436c6481f1d2b9078717461a5b9d32e688f87748" +
                    "544523b524b0d57d5ea77a2775d2ecfa032cfbdbf52fb3786160279004e57ae6" +
                    "af874e7303ce53299ccc041c7bc308d82a5698f3a8d0c38271ae35f8e9dbfbb6" +
                    "94b5c803d89f7ae435de236d525f54759b65e372fcd68ef20fa7111f9e4aff73",
                16,
            )
        val G: BigInteger = BigInteger.valueOf(2)
        private const val GROUP_BYTES = 256

        fun sha1(vararg parts: ByteArray): ByteArray = Crypto.digest("SHA-1", *parts)

        /** Minimal big-endian bytes of a non-negative [value] (one 0 byte for zero). */
        fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        /** [value] left-padded with zeros to the group size. */
        fun pad(value: BigInteger): ByteArray {
            val bytes = unsigned(value)
            return ByteArray(GROUP_BYTES - bytes.size) + bytes
        }
    }
}

/**
 * Legacy PIN pairing, the sender side of 02 §5.3-§5.5. Call [step1], [step2], [step3] and [receiverKey]
 * in order; each throws [SenderException] when the receiver's answer is not what a sender accepts.
 */
internal class PinPairingClient(
    pin: String,
    private val user: String,
    private val identity: Ed25519KeyPair,
    srpPrivateKey: BigInteger = BigInteger(1, Crypto.randomBytes(32)),
) {
    private val srp = SrpClient(user.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.US_ASCII), srpPrivateKey)
    private var gcmKey: ByteArray? = null
    private var iv0: ByteArray? = null

    fun step1(): ByteArray =
        BinaryPlist.encode(
            buildPlistDict {
                put("method", "pin")
                put("user", user)
            },
        )

    fun step2(step1Response: ByteArray): ByteArray {
        val reply = dict(step1Response, "step 1")
        val salt = required(reply.data("salt"), "PIN step 1 response has no salt")
        val serverKey = required(reply.data("pk"), "PIN step 1 response has no pk")
        if (salt.size != SALT_BYTES || serverKey.size > MAX_KEY_BYTES) {
            throw SenderException("PIN step 1 response has a ${salt.size}-byte salt and a ${serverKey.size}-byte B")
        }
        val proof = srp.proof(salt, serverKey)
        return BinaryPlist.encode(
            buildPlistDict {
                put("pk", srp.publicKey)
                put("proof", proof)
            },
        )
    }

    fun step3(step2Response: ByteArray): ByteArray {
        val serverProof = dict(step2Response, "step 2").data("proof")
        if (serverProof == null || !srp.verifyServer(serverProof)) {
            throw SenderException("the receiver's PIN proof M2 does not verify")
        }
        val key = checkNotNull(srp.sessionKey)
        val aesKey = Crypto.sha512("Pair-Setup-AES-Key".toByteArray(), key).copyOf(16)
        val iv = Crypto.sha512("Pair-Setup-AES-IV".toByteArray(), key).copyOf(16)
        gcmKey = aesKey
        iv0 = iv
        val sealed = AesGcm.encrypt(aesKey, ivPlus(iv, 1), identity.publicKey)
        return BinaryPlist.encode(
            buildPlistDict {
                put("epk", sealed.copyOfRange(0, 32))
                put("authTag", sealed.copyOfRange(32, 48))
            },
        )
    }

    /** The receiver's Ed25519 public key from the step 3 response. */
    fun receiverKey(step3Response: ByteArray): ByteArray {
        val reply = dict(step3Response, "step 3")
        val epk = required(reply.data("epk"), "PIN step 3 response has no epk")
        val tag = required(reply.data("authTag"), "PIN step 3 response has no authTag")
        return try {
            AesGcm.decrypt(checkNotNull(gcmKey), ivPlus(checkNotNull(iv0), 2), epk + tag)
        } catch (e: CryptoException) {
            throw SenderException("the receiver's key in PIN step 3 does not authenticate", cause = e)
        }
    }

    private companion object {
        const val SALT_BYTES = 16
        const val MAX_KEY_BYTES = 256

        /** IV0 with [amount] added to byte 15 alone, wrapping without carry (02 §5.5). */
        fun ivPlus(
            iv0: ByteArray,
            amount: Int,
        ): ByteArray = iv0.copyOf().also { it[15] = (it.u8(15) + amount).toByte() }
    }
}

/**
 * Legacy pair-verify, the sender side of 02 §4: X25519 agreement, then both signatures under one AES-CTR
 * keystream (the receiver's in bytes 0..63, the sender's in 64..127).
 */
internal class PairVerifyClient(
    private val identity: Ed25519KeyPair,
    private val ephemeral: X25519KeyPair = X25519KeyPair.generate(),
) {
    /** The agreed secret, after [step2]. */
    var sharedSecret: ByteArray? = null
        private set

    /** `01 00 00 00 ‖ ephemeral ‖ identity`. */
    fun step1(): ByteArray = byteArrayOf(1, 0, 0, 0) + ephemeral.publicKey + identity.publicKey

    /** The step 2 request, after checking the receiver's signature against [receiverKey]. */
    fun step2(
        step1Response: ByteArray,
        receiverKey: ByteArray,
    ): ByteArray {
        if (step1Response.size != STEP1_RESPONSE_BYTES) {
            throw SenderException("pair-verify step 1 response is ${step1Response.size} bytes")
        }
        val receiverEphemeral = step1Response.copyOfRange(0, 32)
        val signature = step1Response.copyOfRange(32, STEP1_RESPONSE_BYTES)
        val shared = agree(receiverEphemeral)
        val cipher =
            AesCtr(
                Crypto.sha512("Pair-Verify-AES-Key".toByteArray(), shared).copyOf(16),
                Crypto.sha512("Pair-Verify-AES-IV".toByteArray(), shared).copyOf(16),
            )
        cipher.process(signature)
        if (!Ed25519.verify(receiverKey, receiverEphemeral + ephemeral.publicKey, signature)) {
            throw SenderException("the receiver's pair-verify signature does not match its key")
        }
        val own = identity.sign(ephemeral.publicKey + receiverEphemeral)
        cipher.process(own)
        sharedSecret = shared
        return byteArrayOf(0, 0, 0, 0) + own
    }

    private fun agree(receiverEphemeral: ByteArray): ByteArray =
        try {
            ephemeral.sharedSecret(receiverEphemeral)
        } catch (e: CryptoException) {
            throw SenderException("the receiver's ephemeral key is unusable", cause = e)
        }

    private companion object {
        const val STEP1_RESPONSE_BYTES = 96
    }
}

/** [value], or a [SenderException] with [message] when the receiver left it out. */
internal fun <T : Any> required(
    value: T?,
    message: String,
): T = value ?: throw SenderException(message)

/** [body] as a binary plist dictionary, or a [SenderException] naming [step]. */
internal fun dict(
    body: ByteArray,
    step: String,
): PlistDict =
    try {
        BinaryPlist.decode(body) as? PlistDict
    } catch (e: PlistException) {
        throw SenderException("$step response is not a binary plist", cause = e)
    } ?: throw SenderException("$step response is not a dictionary")
