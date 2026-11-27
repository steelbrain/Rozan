package ai.aneesiqbal.rozan.airplay.rtsp

/**
 * Chooses the largest body a request may carry, from its request line and header fields, before any body
 * byte is read: for example a larger cap for artwork than for everything else. The result is further
 * capped by [RtspLimits.maxBodyBytes]; a larger `Content-Length` fails with 413.
 */
public fun interface RtspBodyLimit {
    /** Largest body in bytes for a request of [method] on [target] with [headers]. */
    public fun maxBodyBytes(
        method: String,
        target: String,
        headers: RtspHeaders,
    ): Int
}
