package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto

/** The media key derivations of docs/protocol/03-media-streams.md §2, from the sender's side. */
public object MediaKeys {
    /**
     * `session_key` (03 §2.1): SHA-512 of the FairPlay key and the pair-verify secret, first 16 bytes; the
     * FairPlay key itself when the connection did not verify ([sharedSecret] null).
     */
    public fun sessionKey(
        fairPlayKey: ByteArray,
        sharedSecret: ByteArray?,
    ): ByteArray {
        if (sharedSecret == null) return fairPlayKey.copyOf()
        return Crypto.sha512(fairPlayKey, sharedSecret).copyOf(16)
    }

    /** `video_key` of one type-110 stream (03 §2.3). */
    public fun videoKey(
        sessionKey: ByteArray,
        streamConnectionId: Long,
    ): ByteArray = derive("AirPlayStreamKey", sessionKey, streamConnectionId)

    /** `video_iv` of one type-110 stream (03 §2.3), the initial AES-CTR counter block. */
    public fun videoIv(
        sessionKey: ByteArray,
        streamConnectionId: Long,
    ): ByteArray = derive("AirPlayStreamIV", sessionKey, streamConnectionId)

    private fun derive(
        label: String,
        sessionKey: ByteArray,
        streamConnectionId: Long,
    ): ByteArray =
        Crypto
            .sha512(
                label.toByteArray(Charsets.US_ASCII),
                java.lang.Long
                    .toUnsignedString(streamConnectionId)
                    .toByteArray(Charsets.US_ASCII),
                sessionKey,
            ).copyOf(16)
}
