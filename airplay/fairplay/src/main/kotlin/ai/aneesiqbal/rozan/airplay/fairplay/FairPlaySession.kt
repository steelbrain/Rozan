package ai.aneesiqbal.rozan.airplay.fairplay

import java.security.SecureRandom

/**
 * One FairPlay exchange; one instance per control connection. Not thread-safe.
 *
 * The client drives three steps (spec `docs/protocol/04-fairplay.md` section 1):
 * [setup] and [handshake] answer the two `/fp-setup` POSTs, then [decryptKey]
 * turns each FairPlay-wrapped media key (`ekey` or `fpaeskey`) into an AES key.
 *
 * What a failed, repeated or out-of-order message does to the session follows
 * spec section 1.6:
 * - [setup] always discards the whole current session first, so a failed setup
 *   leaves no session.
 * - A v2 [handshake] needs a v2 setup. It drops any earlier handshake when it
 *   starts and keeps the setup when it fails, so the client may retry; a second
 *   successful handshake replaces the first.
 * - A v3 [handshake] needs no setup and need not repeat the setup's mode. It
 *   replaces the whole session, including a v2 one.
 * - Any other handshake fails and discards the session.
 * - [decryptKey] never changes the session: a v2 blob that fails to
 *   authenticate fails only that call. The reference would carry on with an
 *   all-zero key; here the call throws, so the caller rejects the SETUP that
 *   carried the blob.
 *
 * Before any of that, a message of the wrong size or with a version byte other
 * than `02`/`03` throws without touching the session, as the reference's HTTP
 * layer rejects those before FairPlay sees them (section 1.6).
 *
 * Every failure throws [FairPlayException].
 */
public class FairPlaySession internal constructor(
    private val randomBytes: (ByteArray) -> Unit,
) {
    /** Creates a session whose FairPlay v2 receiver SAP comes from [SecureRandom]. */
    public constructor() : this(SystemRandom::nextBytes)

    private var state: State = State.Empty

    /**
     * Answers the 16-byte `/fp-setup` [request] with the 142-byte reply. Discards
     * any previous session first, even when the request then fails validation.
     *
     * @throws FairPlayException if the request is malformed or names an unsupported version or mode.
     */
    public fun setup(request: ByteArray): ByteArray {
        checkFraming(request, SETUP_REQUEST_SIZE)
        discard()
        return when (request.u8(VERSION_OFFSET)) {
            VERSION_2 -> setupV2(request)
            else -> setupV3(request)
        }
    }

    /**
     * Answers the 164-byte `/fp-setup` [request] with the 32-byte reply. A v2
     * handshake needs a preceding v2 [setup]; a v3 handshake does not.
     *
     * @throws FairPlayException if the request is malformed, arrives out of order, or (v2) fails authentication.
     */
    public fun handshake(request: ByteArray): ByteArray {
        checkFraming(request, HANDSHAKE_REQUEST_SIZE)
        val current = state
        val version = request.u8(VERSION_OFFSET)
        return when {
            version == VERSION_2 && current is State.V2 -> {
                handshakeV2(current, request)
            }

            version == VERSION_3 && request.u8(HANDSHAKE_MODE_OFFSET) < MODES -> {
                handshakeV3(request)
            }

            else -> {
                discard()
                fail(if (version == VERSION_2) "v2 handshake without a v2 setup" else "unsupported FairPlay mode")
            }
        }
    }

    /**
     * Recovers the 16-byte AES media key from the 72-byte FairPlay-wrapped
     * [encryptedKey] (`ekey` or `fpaeskey`). Needs a completed [handshake]; may
     * be called repeatedly. Never changes the session.
     *
     * @throws FairPlayException if the blob is malformed, arrives out of order, or (v2) fails authentication.
     */
    public fun decryptKey(encryptedKey: ByteArray): ByteArray {
        if (encryptedKey.size != KEY_BLOB_SIZE) fail("encrypted key must be $KEY_BLOB_SIZE bytes")
        val current = state
        val handshake = (current as? State.V2)?.handshake
        return when {
            handshake != null -> decryptKeyV2(handshake, encryptedKey)
            current is State.V3Ready -> decryptKeyV3(current, encryptedKey)
            else -> fail("key decrypt before a completed handshake")
        }
    }

    // The checks the reference's HTTP layer makes before the FairPlay state is touched.
    private fun checkFraming(
        request: ByteArray,
        size: Int,
    ) {
        if (request.size != size) fail("/fp-setup message must be $size bytes here")
        val version = request.u8(VERSION_OFFSET)
        if (version != VERSION_2 && version != VERSION_3) fail("unsupported FairPlay version")
    }

    // Sections 1.1 and 3.3.
    private fun setupV2(request: ByteArray): ByteArray {
        if (!request.startsWithAt(0, V2_SETUP_HEADER) || request.u8(15) != SETUP_TRAILER) {
            fail("malformed v2 setup request")
        }
        val mode = mode(request, SETUP_MODE_OFFSET)
        val receiverSap = ByteArray(FairPlayV2.SAP_SIZE)
        val random = ByteArray(FairPlayV2.SAP_SIZE - 2)
        randomBytes(random)
        receiverSap[1] = 0x01
        random.copyInto(receiverSap, 2)
        random.fill(0)
        val reply = ByteArray(SETUP_REPLY_SIZE)
        V2_SETUP_REPLY_PREFIX.copyInto(reply)
        reply[SETUP_REPLY_PREFIX_SIZE - 1] = mode.toByte()
        FairPlayV2.sapEncrypt(mode, receiverSap).copyInto(reply, SETUP_REPLY_PREFIX_SIZE)
        state = State.V2(mode, receiverSap)
        return reply
    }

    // Sections 1.1 and 4.1: only the version and mode are checked.
    private fun setupV3(request: ByteArray): ByteArray {
        val reply = FairPlayV3.setupReply(mode(request, SETUP_MODE_OFFSET))
        state = State.V3Setup
        return reply
    }

    // Sections 1.3, 1.6 and 3.4: any earlier handshake is dropped first; the setup survives a failure.
    private fun handshakeV2(
        current: State.V2,
        request: ByteArray,
    ): ByteArray {
        current.clearHandshake()
        if (!request.startsWithAt(0, V2_HANDSHAKE_HEADER) ||
            request.u8(HANDSHAKE_MODE_OFFSET) != current.mode ||
            !request.startsWithAt(HANDSHAKE_MODE_OFFSET + 1, V2_HANDSHAKE_FIXED)
        ) {
            fail("malformed v2 handshake request")
        }
        val senderSap = FairPlayV2.sapDecrypt(current.mode, request, HANDSHAKE_SAP_OFFSET)
        val macKey = FairPlayV2.macKey(senderSap, current.receiverSap)
        val wrappingKey = FairPlayV2.wrappingKey(senderSap, current.receiverSap)
        senderSap.fill(0)
        val confirmation = FairPlayV2.confirmation(macKey)
        if (!constantTimeEquals(confirmation, 0, request, HANDSHAKE_TAG_OFFSET, FairPlayV2.TAG_SIZE)) {
            macKey.fill(0)
            wrappingKey.fill(0)
            fail("v2 handshake confirmation mismatch")
        }
        current.handshake = State.V2.Handshake(macKey, wrappingKey)
        val reply = V2_HANDSHAKE_REPLY_HEADER.copyOf(HANDSHAKE_REPLY_SIZE)
        confirmation.copyInto(reply, V2_HANDSHAKE_REPLY_HEADER.size)
        return reply
    }

    // Sections 1.6 and 4.2: replaces any session; the request is kept for the key decrypt.
    private fun handshakeV3(request: ByteArray): ByteArray {
        discard()
        state = State.V3Ready(request.copyOf())
        val reply = FairPlayV3.HANDSHAKE_REPLY_HEADER.copyOf(HANDSHAKE_REPLY_SIZE)
        request.copyInto(reply, FairPlayV3.HANDSHAKE_REPLY_HEADER.size, HANDSHAKE_TAG_OFFSET, HANDSHAKE_REQUEST_SIZE)
        return reply
    }

    // Sections 1.5 and 3.5. A failure here fails only this call.
    private fun decryptKeyV2(
        handshake: State.V2.Handshake,
        blob: ByteArray,
    ): ByteArray {
        if (!hasV2KeyBlobFraming(blob)) fail("malformed v2 encrypted key")
        // authenticated = blob[0..35] || recovered, where recovered = AES-ECB-decrypt(wrapped) ^ blind.
        val authenticated = ByteArray(KEY_BLOB_TAG_OFFSET + MEDIA_KEY_SIZE)
        blob.copyInto(authenticated, 0, 0, KEY_BLOB_TAG_OFFSET)
        Aes.ecbDecrypt(handshake.wrappingKey, blob, KEY_BLOB_WRAPPED_OFFSET, authenticated, KEY_BLOB_TAG_OFFSET)
        authenticated.xorInPlace(KEY_BLOB_TAG_OFFSET, blob, KEY_BLOB_BLIND_OFFSET, MEDIA_KEY_SIZE)
        val expected = FairPlayV2.hmacSha1(handshake.macKey, authenticated, 0, authenticated.size)
        val recovered = authenticated.copyOfRange(KEY_BLOB_TAG_OFFSET, authenticated.size)
        authenticated.fill(0)
        if (!constantTimeEquals(expected, 0, blob, KEY_BLOB_TAG_OFFSET, FairPlayV2.TAG_SIZE)) {
            recovered.fill(0)
            fail("v2 encrypted key authentication failed")
        }
        return recovered
    }

    // Section 1.5: header, be32 60, be32 0, and be32 16 before the tag.
    private fun hasV2KeyBlobFraming(blob: ByteArray): Boolean =
        blob.startsWithAt(0, KEY_BLOB_HEADER) &&
            blob.be32(KEY_BLOB_LENGTH_OFFSET) == KEY_BLOB_LENGTH &&
            blob.be32(KEY_BLOB_LENGTH_OFFSET + 4) == 0 &&
            blob.be32(KEY_BLOB_KEY_LENGTH_OFFSET) == MEDIA_KEY_SIZE

    // Section 4.3: the framing is ignored; the AES key is derived once per handshake.
    private fun decryptKeyV3(
        current: State.V3Ready,
        blob: ByteArray,
    ): ByteArray {
        val tables = V3Tables.instance
        val aesKey =
            current.aesKey ?: run {
                val sapKey = FairPlayV3.sessionKey(current.message3, tables)
                val key = FairPlayV3.aesKey(sapKey, tables)
                sapKey.fill(0)
                current.message3.fill(0)
                current.aesKey = key
                key
            }
        return FairPlayV3.mediaKey(aesKey, blob, tables)
    }

    private fun mode(
        request: ByteArray,
        offset: Int,
    ): Int {
        val mode = request.u8(offset)
        if (mode >= MODES) fail("unsupported FairPlay mode")
        return mode
    }

    private fun discard() {
        state.wipe()
        state = State.Empty
    }

    private sealed interface State {
        fun wipe() {}

        data object Empty : State

        /** A successful v2 setup, plus the current handshake if one succeeded since. */
        class V2(
            val mode: Int,
            val receiverSap: ByteArray,
        ) : State {
            var handshake: Handshake? = null

            fun clearHandshake() {
                handshake?.wipe()
                handshake = null
            }

            override fun wipe() {
                receiverSap.fill(0)
                clearHandshake()
            }

            /** The keys derived from a handshake's sender SAP. */
            class Handshake(
                val macKey: ByteArray,
                val wrappingKey: ByteArray,
            ) {
                fun wipe() {
                    macKey.fill(0)
                    wrappingKey.fill(0)
                }
            }
        }

        /** A v3 setup with no handshake since. */
        data object V3Setup : State

        class V3Ready(
            val message3: ByteArray,
        ) : State {
            var aesKey: ByteArray? = null

            override fun wipe() {
                message3.fill(0)
                aesKey?.fill(0)
            }
        }
    }

    private object SystemRandom {
        private val random = SecureRandom()

        fun nextBytes(bytes: ByteArray) = random.nextBytes(bytes)
    }

    private companion object {
        const val MODES = 4
        const val VERSION_OFFSET = 4
        const val VERSION_2 = 2
        const val VERSION_3 = 3

        const val SETUP_REQUEST_SIZE = 16
        const val SETUP_MODE_OFFSET = 14
        const val SETUP_TRAILER = 0xBB
        const val SETUP_REPLY_SIZE = 142
        const val SETUP_REPLY_PREFIX_SIZE = 14

        const val HANDSHAKE_REQUEST_SIZE = 164
        const val HANDSHAKE_MODE_OFFSET = 12
        const val HANDSHAKE_SAP_OFFSET = 16
        const val HANDSHAKE_TAG_OFFSET = 144
        const val HANDSHAKE_REPLY_SIZE = 32

        const val KEY_BLOB_SIZE = 72
        const val MEDIA_KEY_SIZE = 16
        const val KEY_BLOB_LENGTH_OFFSET = 8
        const val KEY_BLOB_LENGTH = 60
        const val KEY_BLOB_BLIND_OFFSET = 16
        const val KEY_BLOB_KEY_LENGTH_OFFSET = 32
        const val KEY_BLOB_TAG_OFFSET = 36
        const val KEY_BLOB_WRAPPED_OFFSET = 56

        /** `"FPLY" 02 01 01 00`, be32 length 4, `02 00` (section 1.1); then mode, then `bb`. */
        val V2_SETUP_HEADER = hex("46504c5902010100000000040200")

        /** Section 3.3: the mode byte at offset 13 is filled in. */
        val V2_SETUP_REPLY_PREFIX = hex("46504c5902010200000000820200")

        /** `"FPLY" 02 01 03 00`, be32 length 152 (section 1.3); then mode, then [V2_HANDSHAKE_FIXED]. */
        val V2_HANDSHAKE_HEADER = hex("46504c590201030000000098")
        val V2_HANDSHAKE_FIXED = hex("8f1a9c")

        /** Section 3.4 step 5. */
        val V2_HANDSHAKE_REPLY_HEADER = hex("46504c590201040000000014")

        /** `"FPLY" 01 02 01 00` (section 1.5). */
        val KEY_BLOB_HEADER = hex("46504c5901020100")
    }
}

private fun fail(message: String): Nothing = throw FairPlayException(message)
