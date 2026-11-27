package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Protocol versions accepted on the control channel.
 *
 * AirPlay senders use `RTSP/1.0` for session control and `HTTP/1.1` for requests such as `GET /info` and
 * the pairing endpoints, on the same connection. Anything else, including `HTTP/1.0`, is rejected with
 * 505: no AirPlay sender uses it, and accepting it would mean supporting its close-after-response
 * semantics.
 *
 * @property token the version as written on the wire, e.g. `RTSP/1.0`.
 */
public enum class RtspProtocol(
    public val token: String,
) {
    /** `RTSP/1.0` (RFC 2326). */
    RTSP_1_0("RTSP/1.0"),

    /** `HTTP/1.1` (RFC 9112). */
    HTTP_1_1("HTTP/1.1"),
    ;

    /** Returns [token]. */
    override fun toString(): String = token

    /** Lookup by wire token. */
    public companion object {
        /** Returns the protocol whose [token] is exactly [token] (case-sensitive), or null. */
        public fun fromToken(token: String): RtspProtocol? = entries.firstOrNull { it.token == token }
    }
}
