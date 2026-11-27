package ai.aneesiqbal.rozan.airplay.fairplay

/**
 * A FairPlay message was malformed, arrived out of order or failed
 * authentication, or the platform could not run a required cipher. The
 * [FairPlaySession] has been reset; the connection that sent the message should
 * be closed.
 */
public class FairPlayException(
    message: String,
) : Exception(message) {
    internal constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
