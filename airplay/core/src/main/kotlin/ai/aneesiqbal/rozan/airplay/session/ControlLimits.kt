package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.rtsp.RtspLimits
import java.util.concurrent.TimeUnit

/**
 * Bounds of the control server (docs/protocol/01 §3.1-§3.2, 02 §9.1, 03 §6.4, §8.1). Tests shrink the
 * timeouts and caps; production uses the defaults.
 *
 * Memory budget of the control layer: at most [maxConnections] connections, each with one read buffer of
 * [READ_BUFFER_BYTES], a header section of at most [RtspLimits.maxHeaderBytes] and one body. Bodies are
 * capped per request ([RequestBodyLimits]): 64 KiB before a connection is authenticated, 1 MiB after,
 * 8 KiB for a classic `ANNOUNCE` either way, and 8 MiB only for artwork on the connection that owns the
 * streaming session. The worst case is about
 * `11 x 1 MiB + 8 MiB` plus per-connection buffers.
 *
 * @property maxConnections concurrent control connections (02 §9.1). When the table is
 *   full, a new connection takes the place of an unauthenticated one (01 §3.1); with none to replace, it
 *   is closed on accept.
 * @property maxConnectionsPerAddress concurrent connections from one IP address (02 §9.1). An address at
 *   the cap replaces its own oldest unauthenticated connection, or is refused.
 * @property idleTimeoutMillis a connection that sends nothing for this long is closed (01 §3.1: 60 s).
 * @property requestTimeoutMillis until a connection is authenticated, each request must arrive whole
 *   (headers and body) within this long of its first byte (01 §3.1).
 * @property authTimeoutMillis a connection must authenticate within this long of opening, or of its last
 *   whole-session TEARDOWN (01 §3.1): long enough for a person to read a code and type it, with one code
 *   expiring (02 §5.1) on the way.
 * @property sessionStartTimeoutMillis an authenticated connection without a session (it has not opened
 *   one yet, or the media layer refused it) is closed after this long (01 §3.1): senders send the session
 *   SETUP right after pair-verify, and Digest authenticates the SETUP itself.
 * @property feedbackTimeoutNanos a streaming session without `/feedback` for this long is torn down
 *   (03 §6.4: 15 s).
 * @property housekeepingIntervalMillis how often the feedback timeout, the connection deadlines and pairing
 *   expiries are checked, while a connection is open or pairing state can expire (never while idle).
 * @property infoRequestsPerSecond `GET /info` requests allowed per IP per second (02 §9.1); more get 503.
 */
internal class ControlLimits(
    val maxConnections: Int = 12,
    val maxConnectionsPerAddress: Int = 4,
    val idleTimeoutMillis: Int = 60_000,
    val requestTimeoutMillis: Int = 10_000,
    val authTimeoutMillis: Int = 180_000,
    val sessionStartTimeoutMillis: Int = 30_000,
    val feedbackTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(15),
    val housekeepingIntervalMillis: Long = 1_000,
    val infoRequestsPerSecond: Int = 10,
) {
    init {
        require(maxConnections > 0 && maxConnectionsPerAddress > 0) { "connection caps must be positive" }
        require(idleTimeoutMillis > 0 && feedbackTimeoutNanos > 0 && housekeepingIntervalMillis > 0) {
            "timeouts must be positive"
        }
        require(requestTimeoutMillis > 0 && authTimeoutMillis > 0 && sessionStartTimeoutMillis > 0) {
            "deadlines must be positive"
        }
        require(infoRequestsPerSecond > 0) { "infoRequestsPerSecond must be positive" }
    }

    companion object {
        /** Most header fields (01 §3.2). */
        const val MAX_HEADER_COUNT = 32

        /** Longest header name (01 §3.2). */
        const val MAX_HEADER_NAME_BYTES = 64

        /** Longest header value (01 §3.2). */
        const val MAX_HEADER_VALUE_BYTES = 1024

        /** Longest request line (01 §3.2). */
        const val MAX_REQUEST_LINE_BYTES = 2048

        /** Read chunk per connection. */
        const val READ_BUFFER_BYTES = 8 * 1024

        /** Parser limits for every control connection. */
        val RTSP: RtspLimits =
            RtspLimits(
                maxStartLineBytes = MAX_REQUEST_LINE_BYTES,
                maxHeaderBytes = MAX_HEADER_COUNT * (MAX_HEADER_NAME_BYTES + MAX_HEADER_VALUE_BYTES + 4),
                maxHeaderCount = MAX_HEADER_COUNT,
                maxBodyBytes = RequestBodyLimits.ARTWORK_BYTES,
                maxHeaderNameBytes = MAX_HEADER_NAME_BYTES,
                maxHeaderValueBytes = MAX_HEADER_VALUE_BYTES,
            )
    }
}

/** Per-request body caps (01 §3.2, 03 §8.1). Over the cap the request gets 413 and the connection closes. */
internal object RequestBodyLimits {
    /** Any request before the connection is authenticated: pairing, fp-setup, the first SETUP. */
    const val UNAUTHENTICATED_BYTES = 64 * 1024

    /** SETUP, TEARDOWN and `text/parameters` SET_PARAMETER (03 §8.1). */
    const val CONTROL_BYTES = 64 * 1024

    /** DMAP metadata (03 §8.1). */
    const val DMAP_BYTES = Dmap.MAX_BODY_BYTES

    /** Everything else after authentication (01 §3.2). */
    const val DEFAULT_BYTES = 1024 * 1024

    /** Artwork, for the connection that owns the streaming session (01 §3.2, 03 §8.1). */
    const val ARTWORK_BYTES = 8 * 1024 * 1024

    /** A classic `ANNOUNCE` (01 §3.2, §6.17), before or after authentication. */
    const val ANNOUNCE_BYTES = SessionDescription.MAX_BYTES

    /**
     * The cap for a request of [method] with media type [mediaType], on a connection that is
     * [authenticated] and does or does not [ownSession].
     */
    fun forRequest(
        method: String,
        mediaType: String?,
        authenticated: Boolean,
        ownSession: Boolean,
    ): Int =
        when {
            method == "ANNOUNCE" -> ANNOUNCE_BYTES
            !authenticated -> UNAUTHENTICATED_BYTES
            method == "SETUP" || method == "TEARDOWN" -> CONTROL_BYTES
            method != "SET_PARAMETER" -> DEFAULT_BYTES
            mediaType == ContentTypes.DMAP -> DMAP_BYTES
            mediaType != null && mediaType.startsWith("image/") -> if (ownSession) ARTWORK_BYTES else DEFAULT_BYTES
            else -> CONTROL_BYTES
        }
}
