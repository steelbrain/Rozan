package ai.aneesiqbal.rozan.airplay.plist

import ai.aneesiqbal.rozan.airplay.ProtocolException

/**
 * A property list could not be read or written: it is malformed, truncated, exceeds a limit, or holds a
 * value the format cannot represent.
 *
 * @param message what was wrong.
 * @param cause the underlying failure, if any.
 */
public class PlistException(
    message: String,
    cause: Throwable? = null,
) : ProtocolException(message, cause)

/** Throws a [PlistException] for malformed or unacceptable input. Gives the parsers a single throw site. */
internal fun malformed(message: String): Nothing = throw PlistException(message)
