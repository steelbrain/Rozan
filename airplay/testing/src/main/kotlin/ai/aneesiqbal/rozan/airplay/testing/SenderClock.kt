package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp

/**
 * The sender's clock, as an Apple sender keeps it (docs/protocol/03b-mirroring-video.md §3.5): video
 * timestamps count the sender's uptime, and its NTP replies and audio sync packets use the same clock
 * plus 2,208,988,800 s.
 *
 * Runs on `System.nanoTime()`, so it never jumps. Thread-safe.
 *
 * @param uptimeAtStartNanos the uptime the clock reports when it is created; one hour by default, a
 *   plausible value that keeps every timestamp positive.
 */
public class SenderClock(
    uptimeAtStartNanos: Long = DEFAULT_UPTIME_NANOS,
) {
    private val base = System.nanoTime() - uptimeAtStartNanos

    /** The sender's uptime now. */
    public fun uptimeNanos(): Long = System.nanoTime() - base

    /** The sender's NTP-domain time now: uptime plus the NTP epoch offset. */
    public fun ntpNanos(): Long = uptimeNanos() + NTP_OFFSET_NANOS

    /** The NTP-domain time of the moment `System.nanoTime()` read [nanoTime]. */
    public fun ntpNanosAt(nanoTime: Long): Long = nanoTime - base + NTP_OFFSET_NANOS

    /** The uptime of the moment `System.nanoTime()` read [nanoTime]. */
    public fun uptimeNanosAt(nanoTime: Long): Long = nanoTime - base

    /** Conversions. */
    public companion object {
        /** One hour. */
        public const val DEFAULT_UPTIME_NANOS: Long = 3_600_000_000_000L

        /** 2,208,988,800 s in nanoseconds: what NTP-domain times add to the uptime. */
        public const val NTP_OFFSET_NANOS: Long = NtpTimestamp.UNIX_EPOCH_OFFSET_SECONDS * NtpTimestamp.NANOS_PER_SECOND

        /** [nanos] as Q32.32 fixed point, the wire format of every AirPlay timestamp. */
        public fun q32(nanos: Long): Long = NtpTimestamp.fromNanos(nanos).bits
    }
}
