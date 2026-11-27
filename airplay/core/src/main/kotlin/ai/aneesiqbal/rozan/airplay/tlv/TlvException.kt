package ai.aneesiqbal.rozan.airplay.tlv

import ai.aneesiqbal.rozan.airplay.ProtocolException

/**
 * A TLV8 message could not be decoded or encoded: it is truncated, exceeds a limit, or is missing a
 * required item.
 *
 * @param message what was wrong.
 * @param cause the underlying failure, if any.
 */
public class TlvException(
    message: String,
    cause: Throwable? = null,
) : ProtocolException(message, cause)
