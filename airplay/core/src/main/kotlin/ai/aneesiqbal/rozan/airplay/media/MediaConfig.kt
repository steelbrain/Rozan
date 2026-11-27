package ai.aneesiqbal.rozan.airplay.media

/** The local monotonic clock, in nanoseconds; `System.nanoTime()` in production, injectable for tests. */
internal fun interface MediaClock {
    /** Current time in nanoseconds on the clock the sinks schedule against. */
    fun nanoTime(): Long

    /** Clocks. */
    companion object {
        /** `System.nanoTime()`, the clock `AudioTrack` and `MediaCodec` render times use. */
        val SYSTEM: MediaClock = MediaClock { System.nanoTime() }
    }
}

/**
 * Tunables of the media layer. Defaults follow docs/protocol/03-media-streams.md and 03c; tests shorten the
 * waits.
 *
 * @property clock the local clock for timestamps and playout decisions.
 * @property audioOnlyExtraNanos receiver latency added to audio-only playout (03c §4.7, `Audio-Latency`).
 * @property mirroringAudioExtraNanos latency added to mirroring (AAC-ELD) audio presentation times.
 * @property syncWaitNanos how long ALAC is held waiting for the first sync packet and clock sync before
 *   falling back to arrival-time playout.
 * @property fallbackDelayNanos the fixed jitter delay of arrival-time playout (03c §4.7 "Fallback").
 * @property firstConnectionTimeoutMillis how long a mirroring stream waits for its data connection (03 §8.2).
 * @property readTimeoutMillis longest stall inside a mirroring packet (03 §8.2).
 * @property timingBurstCount timing requests sent at the burst interval before the slow interval.
 * @property timingBurstIntervalMillis interval between burst timing requests.
 * @property timingIntervalMillis interval between timing requests after the burst.
 * @property playoutPollNanos longest the playout thread sleeps before rechecking its buffer.
 * @property joinTimeoutMillis how long stopping a stream waits for each of its threads to finish.
 * @property log where rare diagnostic messages go (wrong key, stream resets).
 */
internal class MediaConfig(
    val clock: MediaClock = MediaClock.SYSTEM,
    val audioOnlyExtraNanos: Long = 250_000_000L,
    val mirroringAudioExtraNanos: Long = 0L,
    val syncWaitNanos: Long = 1_000_000_000L,
    val fallbackDelayNanos: Long = 250_000_000L,
    val firstConnectionTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 10_000,
    val timingBurstCount: Int = 8,
    val timingBurstIntervalMillis: Long = 250L,
    val timingIntervalMillis: Long = 3_000L,
    val playoutPollNanos: Long = 5_000_000L,
    val joinTimeoutMillis: Long = 2_000L,
    val log: MediaLog = MediaLog.NONE,
)

/** A sink for rare diagnostic messages of the media layer. Called on media threads; must not block. */
internal fun interface MediaLog {
    /** Records [message]. */
    fun log(message: String)

    /** Logs. */
    companion object {
        /** Discards everything. */
        val NONE: MediaLog = MediaLog { }
    }
}

/** A media stream must stop: the sender broke a bound or the stream cannot continue. */
internal class MediaStreamException(
    message: String,
) : Exception(message)
