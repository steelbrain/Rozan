package ai.aneesiqbal.rozan.airplay

import java.io.IOException

/**
 * Base class for errors caused by input the receiver will not accept: malformed, truncated or oversized
 * protocol data, or cryptographic material that fails validation.
 *
 * It extends [IOException] so that connection code can handle it like any other I/O failure: drop the
 * connection and carry on. It never signals a bug in the receiver itself.
 *
 * @param message what was wrong with the input. Must not contain secrets or raw attacker-controlled bytes.
 * @param cause the underlying failure, if any.
 */
public abstract class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
