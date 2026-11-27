package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.crypto.Crypto

/** Media key derivation (docs/protocol/03-media-streams.md §2). */
internal object MediaKeys {
    /** Size of every media AES key and IV. */
    const val KEY_BYTES: Int = 16

    private val STREAM_KEY_LABEL = "AirPlayStreamKey".toByteArray(Charsets.US_ASCII)
    private val STREAM_IV_LABEL = "AirPlayStreamIV".toByteArray(Charsets.US_ASCII)

    /**
     * `session_key` (§2.1): `SHA-512(fairplay_key ‖ ecdh_secret)[0..16)` when pair-verify produced
     * [ecdhSecret] on this connection, else [fairPlayKey] itself (copied).
     */
    fun sessionKey(
        fairPlayKey: ByteArray,
        ecdhSecret: ByteArray?,
    ): ByteArray {
        require(fairPlayKey.size == KEY_BYTES) { "FairPlay key must be $KEY_BYTES bytes" }
        if (ecdhSecret == null) return fairPlayKey.copyOf()
        val digest = Crypto.sha512(fairPlayKey, ecdhSecret)
        return digest.copyOf(KEY_BYTES).also { digest.fill(0) }
    }

    /** `video_key` (§2.3) for [streamConnectionId], read as unsigned 64 bits. */
    fun videoKey(
        sessionKey: ByteArray,
        streamConnectionId: Long,
    ): ByteArray = streamHash(STREAM_KEY_LABEL, sessionKey, streamConnectionId)

    /** `video_iv` (§2.3) for [streamConnectionId], read as unsigned 64 bits. */
    fun videoIv(
        sessionKey: ByteArray,
        streamConnectionId: Long,
    ): ByteArray = streamHash(STREAM_IV_LABEL, sessionKey, streamConnectionId)

    private fun streamHash(
        label: ByteArray,
        sessionKey: ByteArray,
        streamConnectionId: Long,
    ): ByteArray {
        // Unsigned decimal: a signed one gives the wrong key for half of all sessions (§2.3).
        val id = streamConnectionId.toULong().toString().toByteArray(Charsets.US_ASCII)
        val digest = Crypto.sha512(label, id, sessionKey)
        return digest.copyOf(KEY_BYTES).also { digest.fill(0) }
    }
}
