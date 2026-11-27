package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.AesCtr
import ai.aneesiqbal.rozan.airplay.crypto.AesGcm
import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.crypto.SrpParameters
import ai.aneesiqbal.rozan.airplay.crypto.SrpTestClient
import ai.aneesiqbal.rozan.airplay.crypto.X25519KeyPair
import ai.aneesiqbal.rozan.airplay.crypto.toUnsignedBytes
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertIs

/*
 * Sender-side halves of docs/protocol/02-pairing.md §4-§6, written from the spec independently of the
 * receiver code, for unit tests here and for end-to-end tests that drive a receiver over RTSP.
 *
 * Each client builds request bodies and checks response bodies; it does no I/O. A typical PIN flow:
 *
 *     val pin = PinPairingClient(pin = displayedCode, identity = senderIdentity)
 *     val r1 = post("/pair-setup-pin", pin.step1())     // after POST /pair-pin-start
 *     val r2 = post("/pair-setup-pin", pin.step2(r1))
 *     val r3 = post("/pair-setup-pin", pin.step3(r2))
 *     val receiverKey = pin.receiverKey(r3)              // equals TXT/`/info` pk
 *     val verify = PairVerifyClient(senderIdentity)
 *     val v1 = post("/pair-verify", verify.step1())
 *     post("/pair-verify", verify.step2(v1, receiverKey)) // empty 200
 *     verify.sharedSecret                                // the receiver's verifiedSharedSecret
 *
 * And for Mode D: `DigestClient.authorization(code, DigestClient.nonce(wwwAuthenticate), uri)` as the
 * `Authorization` header of the repeated SETUP.
 */

/**
 * The sender side of legacy PIN pairing (02 §5.3-§5.5): SRP-6a with [SrpParameters.LEGACY_PIN], then the
 * AES-128-GCM exchange of long-term keys. Bodies are binary plists. Call [step1], [step2], [step3] and
 * [receiverKey] in that order; each throws [AssertionError] (or [IllegalStateException]) when the receiver's
 * answer is wrong.
 *
 * @param pin the code the user would type.
 * @param user the `user` value (a sender device id).
 * @param identity the sender's long-term Ed25519 key, sent in step 3.
 * @param privateKey SRP a; random by default, fixed for test vectors.
 */
class PinPairingClient(
    pin: String,
    val user: String = "AA:BB:CC:DD:EE:FF",
    val identity: Ed25519KeyPair = Ed25519KeyPair.generate(),
    privateKey: BigInteger = BigInteger(1, Crypto.randomBytes(32)),
) {
    private val srp =
        SrpTestClient(
            SrpParameters.LEGACY_PIN,
            user.toByteArray(Charsets.UTF_8),
            pin.toByteArray(Charsets.US_ASCII),
            privateKey,
        )
    private var clientProof: ByteArray? = null
    private var gcmKey: ByteArray? = null
    private var iv0: ByteArray? = null

    /** A, as sent in step 2. */
    val publicKey: ByteArray get() = srp.publicKey().toUnsignedBytes()

    /** K (40 bytes), after [step3]. */
    var sessionKey: ByteArray? = null
        private set

    /** Step 1 request: `{method: "pin", user}`. */
    fun step1(): ByteArray =
        BinaryPlist.encode(
            buildPlistDict {
                put("method", "pin")
                put("user", user)
            },
        )

    /** Step 2 request `{pk: A, proof: M1}`, from the step 1 response `{pk: B, salt}`. */
    fun step2(step1Response: ByteArray): ByteArray {
        val reply = decode(step1Response)
        val salt = checkNotNull(reply.data("salt")) { "step 1 response has no salt" }
        val serverKey = checkNotNull(reply.data("pk")) { "step 1 response has no pk" }
        assertEquals(16, salt.size, "salt size")
        assertEquals(256, serverKey.size, "B size")
        val proof = srp.clientProof(salt, serverKey)
        clientProof = proof
        return BinaryPlist.encode(
            buildPlistDict {
                put("pk", publicKey)
                put("proof", proof)
            },
        )
    }

    /** Step 3 request `{epk, authTag}` (the sender key under GCM with IVc), after checking M2. */
    fun step3(step2Response: ByteArray): ByteArray {
        val serverProof = checkNotNull(decode(step2Response).data("proof")) { "step 2 response has no proof" }
        check(srp.verifyServer(checkNotNull(clientProof), serverProof)) { "M2 does not match" }
        val key = srp.sessionKey()
        sessionKey = key
        val aesKey = Crypto.sha512("Pair-Setup-AES-Key".toByteArray(), key).copyOf(16)
        val iv = Crypto.sha512("Pair-Setup-AES-IV".toByteArray(), key).copyOf(16)
        gcmKey = aesKey
        iv0 = iv
        val sealed = AesGcm.encrypt(aesKey, pinIv(iv, 1), identity.publicKey)
        return BinaryPlist.encode(
            buildPlistDict {
                put("epk", sealed.copyOfRange(0, 32))
                put("authTag", sealed.copyOfRange(32, 48))
            },
        )
    }

    /** The receiver's Ed25519 public key, decrypted from the step 3 response with IVr. */
    fun receiverKey(step3Response: ByteArray): ByteArray {
        val reply = decode(step3Response)
        val epk = checkNotNull(reply.data("epk")) { "step 3 response has no epk" }
        val tag = checkNotNull(reply.data("authTag")) { "step 3 response has no authTag" }
        return AesGcm.decrypt(checkNotNull(gcmKey), pinIv(checkNotNull(iv0), 2), epk + tag)
    }

    /** IV0 for this exchange, after [step3]. */
    val initialIv: ByteArray? get() = iv0?.copyOf()

    companion object {
        /** IV0 with [amount] added to byte 15 alone, wrapping without carry (02 §5.5). */
        fun pinIv(
            iv0: ByteArray,
            amount: Int,
        ): ByteArray = iv0.copyOf().also { it[15] = ((it[15].toInt() and 0xFF) + amount).toByte() }

        private fun decode(body: ByteArray): PlistDict = assertIs<PlistDict>(BinaryPlist.decode(body))
    }
}

/**
 * The sender side of legacy pair-verify (02 §4): X25519 agreement, then signatures under one AES-CTR
 * keystream (receiver bytes 0..63, sender bytes 64..127).
 *
 * @param identity the sender's long-term Ed25519 key, announced in step 1.
 * @param ephemeral the sender's X25519 key; random by default.
 */
class PairVerifyClient(
    val identity: Ed25519KeyPair,
    val ephemeral: X25519KeyPair = X25519KeyPair.generate(),
) {
    /** The agreed secret, after [step2]; the receiver's `verifiedSharedSecret` must equal it. */
    var sharedSecret: ByteArray? = null
        private set

    /** Step 1 request (68 bytes): `01 00 00 00 ‖ ephemeral ‖ identity`. */
    fun step1(): ByteArray = byteArrayOf(1, 0, 0, 0) + ephemeral.publicKey + identity.publicKey

    /**
     * Step 2 request (68 bytes) from the 96-byte step 1 response, after checking the receiver's signature
     * against [receiverKey] (throws [IllegalStateException] if it does not verify).
     */
    fun step2(
        step1Response: ByteArray,
        receiverKey: ByteArray,
    ): ByteArray {
        check(step1Response.size == 96) { "step 1 response is ${step1Response.size} bytes" }
        val receiverEphemeral = step1Response.copyOfRange(0, 32)
        val signature = step1Response.copyOfRange(32, 96)
        val shared = ephemeral.sharedSecret(receiverEphemeral)
        sharedSecret = shared
        val cipher =
            AesCtr(
                Crypto.sha512("Pair-Verify-AES-Key".toByteArray(), shared).copyOf(16),
                Crypto.sha512("Pair-Verify-AES-IV".toByteArray(), shared).copyOf(16),
            )
        cipher.process(signature)
        check(Ed25519.verify(receiverKey, receiverEphemeral + ephemeral.publicKey, signature)) {
            "receiver signature does not verify"
        }
        val own = identity.sign(ephemeral.publicKey + receiverEphemeral)
        cipher.process(own)
        return byteArrayOf(0, 0, 0, 0) + own
    }
}

/** The sender side of the Mode D Digest exchange (02 §6, RFC 2617), with its own MD5. */
object DigestClient {
    /** The `nonce` of a `WWW-Authenticate: Digest ...` value. */
    fun nonce(challenge: String): String =
        checkNotNull(Regex("nonce=\"([^\"]*)\"").find(challenge)) { "no nonce in challenge" }.groupValues[1]

    /**
     * An `Authorization` header answering [nonce] with [code]. With [qop] set, adds `qop`, `nc` and
     * `cnonce` and uses the RFC 2617 qop formula.
     */
    fun authorization(
        code: String,
        nonce: String,
        uri: String,
        username: String = "AirPlay",
        method: String = "SETUP",
        realm: String = "raop",
        qop: String? = null,
        nc: String = "00000001",
        cnonce: String = "0a4f113b",
    ): String {
        val response = response(code, nonce, uri, username, method, realm, qop, nc, cnonce)
        val base = "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\""
        return if (qop == null) {
            "$base, response=\"$response\""
        } else {
            "$base, qop=$qop, nc=$nc, cnonce=\"$cnonce\", response=\"$response\""
        }
    }

    /** The `response` value for these parameters. */
    fun response(
        code: String,
        nonce: String,
        uri: String,
        username: String = "AirPlay",
        method: String = "SETUP",
        realm: String = "raop",
        qop: String? = null,
        nc: String = "00000001",
        cnonce: String = "0a4f113b",
    ): String {
        val ha1 = md5("$username:$realm:$code")
        val ha2 = md5("$method:$uri")
        return if (qop == null) md5("$ha1:$nonce:$ha2") else md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
}

/** Runs the 3 PIN steps on this connection (after `pinStart`) and returns the receiver key from step 3. */
internal fun ConnectionPairing.pinPair(client: PinPairingClient): ByteArray {
    val r1 = assertIs<PairingReply.Ok>(pairSetupPin(client.step1()), "step 1")
    assertEquals("application/x-apple-binary-plist", r1.contentType)
    val r2 = assertIs<PairingReply.Ok>(pairSetupPin(client.step2(r1.body)), "step 2")
    val r3 = assertIs<PairingReply.Ok>(pairSetupPin(client.step3(r2.body)), "step 3")
    return client.receiverKey(r3.body)
}

/** Runs pair-verify on this connection against [receiverKey] and returns the agreed secret. */
internal fun ConnectionPairing.verify(
    client: PairVerifyClient,
    receiverKey: ByteArray,
): ByteArray {
    val r1 = assertIs<PairingReply.Ok>(pairVerify(client.step1()), "verify step 1")
    assertEquals("application/octet-stream", r1.contentType)
    val r2 = assertIs<PairingReply.Ok>(pairVerify(client.step2(r1.body, receiverKey)), "verify step 2")
    assertEquals(0, r2.body.size)
    return checkNotNull(client.sharedSecret)
}

/** Asserts that [reply] is an error with [status] and [close]. */
internal fun assertPairingError(
    status: Int,
    close: Boolean,
    reply: PairingReply,
    message: String? = null,
) {
    val error = assertIs<PairingReply.Error>(reply, message)
    assertEquals(status, error.status, message)
    assertEquals(close, error.close, message)
}
