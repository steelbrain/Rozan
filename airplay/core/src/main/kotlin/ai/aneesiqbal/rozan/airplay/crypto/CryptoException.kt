package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.ProtocolException

/**
 * A cryptographic operation failed on the input it was given: an authentication tag did not verify, a peer
 * key or SRP value is invalid, or a key or nonce has the wrong size.
 *
 * Messages never include key material.
 *
 * @param message what was wrong.
 * @param cause the underlying failure, if any.
 */
public class CryptoException(
    message: String,
    cause: Throwable? = null,
) : ProtocolException(message, cause)
