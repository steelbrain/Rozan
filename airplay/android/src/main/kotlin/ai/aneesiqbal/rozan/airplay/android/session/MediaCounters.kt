package ai.aneesiqbal.rozan.airplay.android.session

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide totals of what the media sinks put out, across all sessions. Debug builds report them to
 * instrumented tests, which run in another process. Updating them is lock-free and allocates nothing.
 */
internal object MediaCounters {
    /** Video frames released to the mirroring surface for display. */
    val videoFramesRendered = AtomicLong()

    /** PCM frames written to an `AudioTrack`. */
    val audioFramesWritten = AtomicLong()
}
