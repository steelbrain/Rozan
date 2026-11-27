package ai.aneesiqbal.rozan.airplay.receiver

/**
 * Diagnostics from the receiver: connection lifecycle, request methods and statuses, stream setup and
 * failures. Messages never contain codes, keys, nonces, sender names or IP addresses (connections are
 * numbered instead, docs/protocol/02-pairing.md §9.2), so they are safe to keep in release logs.
 *
 * Called from receiver threads; implementations must be thread-safe and must not block.
 */
public fun interface ReceiverLog {
    /** Records [message] at [level]. */
    public fun log(
        level: Level,
        message: String,
    )

    /** Severity of a message. */
    public enum class Level {
        /** Per-request detail. */
        DEBUG,

        /** Lifecycle: connections, sessions, streams. */
        INFO,

        /** Something a sender or the platform did wrong, or a recoverable failure. */
        WARN,
    }

    /** Ready-made logs. */
    public companion object {
        /** Discards everything. */
        public val NONE: ReceiverLog = ReceiverLog { _, _ -> }
    }
}
