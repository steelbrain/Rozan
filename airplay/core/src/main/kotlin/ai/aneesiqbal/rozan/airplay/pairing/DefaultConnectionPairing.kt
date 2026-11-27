package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.AesCtr
import ai.aneesiqbal.rozan.airplay.crypto.AesGcm
import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.crypto.CryptoException
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519
import ai.aneesiqbal.rozan.airplay.crypto.Srp6aServer
import ai.aneesiqbal.rozan.airplay.crypto.SrpParameters
import ai.aneesiqbal.rozan.airplay.crypto.X25519KeyPair
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistException
import ai.aneesiqbal.rozan.airplay.plist.PlistLimits
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import java.net.InetAddress

/**
 * Pairing state of one control connection (docs/protocol/02-pairing.md §3-§6). Created by
 * [DefaultPairingService.openConnection]; used from the connection's thread only.
 *
 * States, as in 01 §8: nothing yet; `SETUP_DONE` after `/pair-setup`; `PIN_PAIRED` after PIN step 3;
 * `VERIFIED` after pair-verify step 2; Digest-authenticated after a good SETUP `Authorization`.
 */
internal class DefaultConnectionPairing(
    private val service: DefaultPairingService,
    private val client: InetAddress,
) : ConnectionPairing {
    private var closed = false

    /** Sender key announced by `/pair-setup` (02 §3). */
    private var setupKey: ByteArray? = null

    // PIN pairing (02 §5): the code and SRP exchange after step 1, then K after step 2, then the sender key.
    private var pinCode: DefaultPairingService.IssuedCode? = null
    private var srp: Srp6aServer? = null
    private var sessionKey: ByteArray? = null
    private var pinKey: ByteArray? = null

    // Pair-verify (02 §4).
    private var verify: PendingVerify? = null
    private var sharedSecret: ByteArray? = null
    private var verifiedWithPinKey = false

    // Digest (02 §6).
    private var nonce: String? = null
    private var digestAuthenticated = false

    private class PendingVerify(
        val senderEphemeral: ByteArray,
        val senderKey: ByteArray,
        val receiverEphemeral: ByteArray,
        val shared: ByteArray,
        val cipher: AesCtr,
        val pinAuthenticated: Boolean,
    )

    override val isAuthenticated: Boolean
        get() = !closed && (digestAuthenticated || (sharedSecret != null && verifiedWithPinKey))

    override val verifiedSharedSecret: ByteArray? get() = sharedSecret?.copyOf()

    override fun pairSetup(body: ByteArray): PairingReply {
        if (closed) return closedReply()
        if (body.size != Ed25519.PUBLIC_KEY_SIZE) return error(PairingStatus.BAD_REQUEST)
        setupKey = body.copyOf()
        return PairingReply.Ok(service.identity().publicKey, OCTET_STREAM)
    }

    override fun pairSetupPin(body: ByteArray): PairingReply {
        if (closed) return closedReply()
        if (body.size > MAX_PIN_BODY) return error(PairingStatus.BAD_REQUEST)
        val dict =
            try {
                BinaryPlist.decode(body, PLIST_LIMITS) as? PlistDict
            } catch (ignored: PlistException) {
                null
            } ?: return error(PairingStatus.BAD_REQUEST)
        val method = dict.string("method")
        val user = dict.string("user")
        val clientKey = dict.data("pk")
        val proof = dict.data("proof")
        val encryptedKey = dict.data("epk")
        val tag = dict.data("authTag")
        return when {
            method != null && user != null -> pinStep1(method, user)
            clientKey != null && proof != null -> pinStep2(clientKey, proof)
            encryptedKey != null && tag != null -> pinStep3(encryptedKey, tag)
            else -> error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED)
        }
    }

    /** Step 1 (02 §5.3): `{method: "pin", user}` → `{pk: B, salt}`, against the live code for this client. */
    private fun pinStep1(
        method: String,
        user: String,
    ): PairingReply {
        clearPinState()
        val username = user.toByteArray(Charsets.UTF_8)
        if (method != PIN_METHOD || username.size > MAX_USERNAME_BYTES) {
            return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED)
        }
        val code = service.codeForPinStart(client) ?: return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED)
        val random = service.random
        val salt = ByteArray(Srp6aServer.DEFAULT_SALT_SIZE)
        do random.nextBytes(salt) while (salt[0] == 0.toByte())
        val password = code.code.toByteArray(Charsets.US_ASCII)
        val server = Srp6aServer.fromPassword(SrpParameters.LEGACY_PIN, username, password, salt, random)
        password.fill(0)
        pinCode = code
        srp = server
        val reply =
            buildPlistDict {
                put("pk", server.publicKey)
                put("salt", salt)
            }
        return PairingReply.Ok(BinaryPlist.encode(reply), BINARY_PLIST)
    }

    /** Step 2 (02 §5.4): `{pk: A, proof: M1}` → `{proof: M2}`; a wrong proof is a counted failure. */
    private fun pinStep2(
        clientKey: ByteArray,
        proof: ByteArray,
    ): PairingReply {
        val server = srp
        val code = pinCode
        srp = null
        val wellFormed = proof.size == SRP_PROOF_SIZE && clientKey.size in 1..SRP_KEY_MAX
        if (server == null || code == null || !wellFormed) {
            clearPinState()
            return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED)
        }
        if (!service.reservePinProof(client, code)) {
            clearPinState()
            return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
        }
        val serverProof =
            try {
                server.verifyClient(clientKey, proof)
            } catch (ignored: CryptoException) {
                clearPinState()
                service.pinProofFailed(client, code)
                return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
            }
        sessionKey = server.sessionKey
        return PairingReply.Ok(BinaryPlist.encode(buildPlistDict { put("proof", serverProof) }), BINARY_PLIST)
    }

    /**
     * Step 3 (02 §5.5): `{epk, authTag}` carries the sender's Ed25519 key under AES-128-GCM keyed from K;
     * the reply carries the receiver's. The IVs add 1 and 2 to byte 15 of IV0 without carrying into byte 14.
     */
    private fun pinStep3(
        encryptedKey: ByteArray,
        tag: ByteArray,
    ): PairingReply {
        val key = sessionKey
        val code = pinCode
        sessionKey = null
        pinCode = null
        val wellFormed = encryptedKey.size == Ed25519.PUBLIC_KEY_SIZE && tag.size == AesGcm.TAG_SIZE
        if (key == null || code == null || !wellFormed) {
            key?.fill(0)
            return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED)
        }
        val gcmKey = Crypto.sha512(GCM_KEY_LABEL, key).copyOf(AES_KEY_SIZE)
        val iv0 = Crypto.sha512(GCM_IV_LABEL, key).copyOf(AES_KEY_SIZE)
        key.fill(0)
        val senderKey =
            try {
                AesGcm.decrypt(gcmKey, ivPlus(iv0, 1), encryptedKey + tag)
            } catch (ignored: CryptoException) {
                gcmKey.fill(0)
                return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
            }
        // The code may have been used up, superseded or expired since step 2 (02 §9.2).
        if (!service.pinSucceeded(client, code, senderKey)) {
            gcmKey.fill(0)
            return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
        }
        val sealed = AesGcm.encrypt(gcmKey, ivPlus(iv0, 2), service.identity().publicKey)
        gcmKey.fill(0)
        pinKey = senderKey
        val reply =
            buildPlistDict {
                put("epk", sealed.copyOfRange(0, Ed25519.PUBLIC_KEY_SIZE))
                put("authTag", sealed.copyOfRange(Ed25519.PUBLIC_KEY_SIZE, sealed.size))
            }
        return PairingReply.Ok(BinaryPlist.encode(reply), BINARY_PLIST)
    }

    override fun pairVerify(body: ByteArray): PairingReply {
        if (closed) return closedReply()
        if (body.size != VERIFY_MESSAGE_SIZE) return error(PairingStatus.BAD_REQUEST)
        // Byte 0 selects the step; bytes 1-3 are zero (02 §4.1).
        val padded = body[1] == ZERO && body[2] == ZERO && body[3] == ZERO
        return when {
            !padded -> error(PairingStatus.BAD_REQUEST)
            body[0] == VERIFY_STEP_1 -> verifyStep1(body)
            body[0] == VERIFY_STEP_2 -> verifyStep2(body)
            else -> error(PairingStatus.BAD_REQUEST)
        }
    }

    /** Step 1 (02 §4.3): agree an X25519 secret and sign `receiverEph ‖ senderEph` with the identity. */
    private fun verifyStep1(body: ByteArray): PairingReply {
        resetVerify()
        val senderEphemeral = body.copyOfRange(VERIFY_HEADER, VERIFY_HEADER + X25519KeyPair.KEY_SIZE)
        val senderKey = body.copyOfRange(VERIFY_HEADER + X25519KeyPair.KEY_SIZE, VERIFY_MESSAGE_SIZE)
        val modeP = service.pinMode == PinMode.PIN_PAIRING
        // (a) PIN-paired on this connection with this key, or (b) in the Mode P session trust.
        val pinAuthenticated = pinKey?.contentEquals(senderKey) == true || (modeP && service.isTrusted(senderKey))
        // After `/pair-setup` the key must be the one announced there (02 §4.3 rule 2).
        val matchesSetup = setupKey?.contentEquals(senderKey) ?: true
        if (!matchesSetup || (modeP && !pinAuthenticated)) {
            return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
        }
        val ephemeral = X25519KeyPair.generate(service.random)
        val shared =
            try {
                ephemeral.sharedSecret(senderEphemeral)
            } catch (ignored: CryptoException) {
                return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
            }
        val aesKey = Crypto.sha512(VERIFY_KEY_LABEL, shared).copyOf(AES_KEY_SIZE)
        val aesIv = Crypto.sha512(VERIFY_IV_LABEL, shared).copyOf(AES_KEY_SIZE)
        val cipher = AesCtr(aesKey, aesIv)
        aesKey.fill(0)
        val receiverEphemeral = ephemeral.publicKey
        val signature = service.identity().sign(receiverEphemeral + senderEphemeral)
        cipher.process(signature)
        verify = PendingVerify(senderEphemeral, senderKey, receiverEphemeral, shared, cipher, pinAuthenticated)
        return PairingReply.Ok(receiverEphemeral + signature, OCTET_STREAM)
    }

    /** Step 2 (02 §4.3): decrypt the sender's signature with keystream bytes 64..127 and check it. */
    private fun verifyStep2(body: ByteArray): PairingReply {
        val pending = verify ?: return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED)
        verify = null
        val signature = body.copyOfRange(VERIFY_HEADER, VERIFY_MESSAGE_SIZE)
        pending.cipher.process(signature)
        if (!Ed25519.verify(pending.senderKey, pending.senderEphemeral + pending.receiverEphemeral, signature)) {
            pending.shared.fill(0)
            return error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
        }
        sharedSecret = pending.shared
        verifiedWithPinKey = pending.pinAuthenticated
        return PairingReply.Ok(ByteArray(0), OCTET_STREAM)
    }

    override fun authorizeSetup(
        method: String,
        uri: String,
        authorization: String?,
        senderDeviceId: String?,
        senderName: String?,
    ): SetupAuthorization =
        when {
            closed -> {
                SetupAuthorization.Refused(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)
            }

            isAuthenticated -> {
                SetupAuthorization.Authorized
            }

            else -> {
                when (checkAnswer(method, authorization, senderDeviceId)) {
                    DefaultPairingService.DigestCheck.ACCEPTED -> {
                        digestAuthenticated = true
                        nonce = null
                        SetupAuthorization.Authorized
                    }

                    DefaultPairingService.DigestCheck.LOCKED -> {
                        lockedOut()
                    }

                    // No usable answer, or a wrong one: challenge again with a fresh nonce (02 §6.1, §6.2).
                    DefaultPairingService.DigestCheck.WRONG, DefaultPairingService.DigestCheck.NO_CODE, null -> {
                        challenge(senderDeviceId, senderName)
                    }
                }
            }
        }

    /**
     * Makes sure a code is on screen and issues a new nonce; a plain 401 when no code can be shown, and 409
     * (then close) while another client's code is on screen.
     */
    private fun challenge(
        senderDeviceId: String?,
        senderName: String?,
    ): SetupAuthorization =
        when (service.ensureDigestCode(this, client, senderDeviceId, senderName)) {
            DefaultPairingService.DigestPrompt.REFUSED -> {
                lockedOut()
            }

            DefaultPairingService.DigestPrompt.BUSY -> {
                nonce = null
                SetupAuthorization.Refused(PairingStatus.CONFLICT, close = true)
            }

            DefaultPairingService.DigestPrompt.SHOWN -> {
                val fresh = Digest.toHex(ByteArray(Digest.NONCE_LENGTH / 2).also(service.random::nextBytes))
                nonce = fresh
                SetupAuthorization.Challenge(Digest.challenge(fresh))
            }
        }

    override fun endAuthentication() {
        clearPinState()
        resetVerify()
        sharedSecret?.fill(0)
        sharedSecret = null
        verifiedWithPinKey = false
        pinKey = null
        setupKey = null
        digestAuthenticated = false
        nonce = null
    }

    override fun close() {
        if (closed) return
        closed = true
        clearPinState()
        resetVerify()
        sharedSecret?.fill(0)
        sharedSecret = null
        nonce = null
        service.connectionClosed(this, client)
    }

    /**
     * Checks an `Authorization` header against the latest nonce and the code; null when there is no answer
     * to check (no header, not Digest, the wrong realm, or not the latest nonce).
     */
    private fun checkAnswer(
        method: String,
        authorization: String?,
        senderDeviceId: String?,
    ): DefaultPairingService.DigestCheck? {
        val parameters = authorization?.let(Digest::parse) ?: return null
        val issued = nonce ?: return null
        if (parameters["realm"] != Digest.REALM || parameters["nonce"] != issued) return null
        return service.checkDigest(this, client, senderDeviceId) { Digest.matches(parameters, it, method) }
    }

    private fun lockedOut(): SetupAuthorization {
        nonce = null
        return SetupAuthorization.Refused(PairingStatus.UNAUTHORIZED, close = false)
    }

    private fun clearPinState() {
        pinCode = null
        srp = null
        sessionKey?.fill(0)
        sessionKey = null
    }

    private fun resetVerify() {
        verify?.shared?.fill(0)
        verify = null
    }

    private fun closedReply() = error(PairingStatus.CONNECTION_AUTHORIZATION_REQUIRED, close = true)

    private fun error(
        status: Int,
        close: Boolean = false,
    ) = PairingReply.Error(status, close)

    /** [iv] with [amount] added to byte 15 only: the increment wraps within the byte (02 §5.5). */
    private fun ivPlus(
        iv: ByteArray,
        amount: Int,
    ): ByteArray = iv.copyOf().also { it[LAST_IV_BYTE] = (it[LAST_IV_BYTE] + amount).toByte() }

    private companion object {
        const val PIN_METHOD = "pin"
        const val MAX_USERNAME_BYTES = 64
        const val MAX_PIN_BODY = 4096
        const val SRP_PROOF_SIZE = 20
        const val SRP_KEY_MAX = 256
        const val AES_KEY_SIZE = 16
        const val LAST_IV_BYTE = 15
        const val VERIFY_MESSAGE_SIZE = 68
        const val VERIFY_HEADER = 4
        const val VERIFY_STEP_1: Byte = 1
        const val VERIFY_STEP_2: Byte = 0
        const val ZERO: Byte = 0
        const val BINARY_PLIST = "application/x-apple-binary-plist"
        const val OCTET_STREAM = "application/octet-stream"

        val PLIST_LIMITS = PlistLimits(maxObjects = 32, maxDepth = 2)
        val GCM_KEY_LABEL = "Pair-Setup-AES-Key".toByteArray(Charsets.US_ASCII)
        val GCM_IV_LABEL = "Pair-Setup-AES-IV".toByteArray(Charsets.US_ASCII)
        val VERIFY_KEY_LABEL = "Pair-Verify-AES-Key".toByteArray(Charsets.US_ASCII)
        val VERIFY_IV_LABEL = "Pair-Verify-AES-IV".toByteArray(Charsets.US_ASCII)
    }
}
