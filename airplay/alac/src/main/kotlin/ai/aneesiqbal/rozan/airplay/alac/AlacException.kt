package ai.aneesiqbal.rozan.airplay.alac

import java.io.IOException

/**
 * Thrown when an ALAC configuration (magic cookie, SDP `fmtp` value or constructor arguments) or an ALAC packet is
 * malformed or uses a feature this decoder does not support.
 *
 * A decoder keeps no state between packets, so after this exception the same [AlacDecoder] can decode the next
 * packet.
 */
public class AlacException(
    message: String,
) : IOException(message)
