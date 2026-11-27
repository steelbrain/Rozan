package ai.aneesiqbal.rozan.airplay.android.audio

import kotlin.math.abs

/**
 * Keeps the audio output aligned with the presentation times it was given.
 *
 * The sink tells the clock where each written chunk landed in the output
 * ([onWrite]: output frame → wanted presentation time) and, whenever
 * [timestampDue] asks for it, when the output says a frame was heard
 * ([onTimestamp], from `AudioTrack.getTimestamp`). The clock compares the two
 * and hands out frames to leave out of the next chunk ([takeDrop]: we are
 * late) or silence to write before it ([takePad]: we are early).
 *
 * The error is measured against the mapping of the latest write, extrapolated
 * back to the heard frame: it is the error the frames written next will have.
 * A drop or pad shifts that mapping as soon as it is taken, so every
 * correction already written but not yet heard is part of the next
 * measurement. Corrections can therefore follow each other without waiting
 * for the earlier ones to reach the speaker, and no error is corrected twice.
 * (Subtracting the unheard corrections once more would count them twice:
 * the correction would stop half way and resume once they were heard.)
 *
 * How much is corrected:
 * - Until the output first lands inside [toleranceNanos] (the start-up offset:
 *   output latency, scheduling of `play()`; half a second on a loaded
 *   emulator), and again after an error of [largeErrorNanos] or more (an
 *   output stall), the whole error, up to [largeStepFrames], goes in one step:
 *   one gap instead of several.
 * - Otherwise errors inside [toleranceNanos] are left alone: timestamps jitter
 *   by a millisecond or so. A larger error starts a correction that goes on
 *   until the error is below [targetNanos], so jitter of up to
 *   ±([toleranceNanos] − [targetNanos]) / 2 never restarts it. Each
 *   measurement corrects half the error it saw, at most [maxStepNanos]. Half,
 *   so that the jitter of one timestamp moves the output by half as much and
 *   the error does not overshoot; capped, so each drop or pad stays short.
 *   Every step is a discontinuity in the sound whatever its length, so the
 *   cap is set to take few steps: a 35 ms error takes about 17 steps of at most
 *   2 ms. While correcting, the output is read every 1/20 s of written audio,
 *   which spreads those steps over about 0.85 s.
 * - Steady drift (sender and output clocks differ by tens to hundreds of ppm)
 *   shows up as an error that slowly passes [toleranceNanos] and is taken back
 *   below [targetNanos] in a few steps.
 *
 * Nothing here allocates; the sink calls it for every chunk.
 */
internal class AudioClock(
    /** Output sample rate in Hz. */
    val sampleRate: Int,
    private val toleranceNanos: Long = 5_000_000L,
    private val targetNanos: Long = 2_000_000L,
    private val largeErrorNanos: Long = 40_000_000L,
    private val maxStepNanos: Long = 2_000_000L,
    private val largeStepFrames: Int = sampleRate,
) {
    private var anchorFrame = NONE
    private var anchorNanos = 0L
    private var locked = false
    private var correcting = true
    private var nextPollFrame = 0L
    private var lastHeardFrame = 0L

    /** Correction not taken yet: frames to drop (> 0) or to pad (< 0). */
    private var pendingFrames = 0

    /** Error of the last measurement in nanoseconds (heard − wanted); for logs and tests. */
    var lastErrorNanos: Long = 0L
        private set

    /** True until the output has been in sync once, and while an error is being corrected. */
    val converging: Boolean get() = !locked || correcting

    /** Forgets all mappings and measurements (new output, or after a flush). */
    fun reset() {
        anchorFrame = NONE
        anchorNanos = 0L
        locked = false
        correcting = true
        nextPollFrame = 0L
        lastHeardFrame = 0L
        pendingFrames = 0
        lastErrorNanos = 0L
    }

    /** Output frame [frame] should be heard at [presentationNanos]. */
    fun onWrite(
        frame: Long,
        presentationNanos: Long,
    ) {
        anchorFrame = frame
        anchorNanos = presentationNanos
    }

    /** When output frame [frame] should be heard, extrapolated from the last write. */
    fun wantedNanos(frame: Long): Long = anchorNanos + (frame - anchorFrame) * NANOS_PER_SECOND / sampleRate

    /**
     * True when the output's timestamp should be read before writing output
     * frame [nextWriteFrame]; schedules the read after it. Often while
     * settling or correcting, rarely while in sync.
     */
    fun timestampDue(nextWriteFrame: Long): Boolean {
        if (nextWriteFrame < nextPollFrame) return false
        schedulePoll(nextWriteFrame)
        return true
    }

    /**
     * The output reports that frame [heardFrame] was heard at [heardNanos]
     * while [nextWriteFrame] is the next frame to be written. Replaces any
     * correction not taken yet with the one this measurement asks for.
     */
    fun onTimestamp(
        heardFrame: Long,
        heardNanos: Long,
        nextWriteFrame: Long,
    ) {
        // Until the output has really started, and while it stalls (underrun), the
        // position stands still and the timestamp says nothing about sync.
        val running = heardFrame > lastHeardFrame && lastHeardFrame > 0
        lastHeardFrame = heardFrame
        if (!running || anchorFrame == NONE) return
        pendingFrames = correction(heardNanos - wantedNanos(heardFrame))
        schedulePoll(nextWriteFrame)
    }

    /**
     * Frames to leave out at the start of the next chunk of [frames] frames;
     * the sink must drop exactly that many. A drop longer than the chunk
     * continues in the following chunks.
     */
    fun takeDrop(frames: Int): Int {
        if (pendingFrames <= 0 || frames <= 0) return 0
        val taken = minOf(pendingFrames, frames)
        pendingFrames -= taken
        // The frames written next carry content meant for later.
        anchorNanos += taken * NANOS_PER_SECOND / sampleRate
        return taken
    }

    /** Frames of silence to write before the next chunk; the sink must write exactly that many. */
    fun takePad(): Int {
        if (pendingFrames >= 0) return 0
        val taken = -pendingFrames
        pendingFrames = 0
        // The content written next lands that many frames later in the output.
        anchorFrame += taken
        return taken
    }

    /** Frames to drop (> 0) or pad (< 0) for a measured [error] (heard − wanted). */
    private fun correction(error: Long): Int {
        lastErrorNanos = error
        val size = abs(error)
        if (size >= largeErrorNanos) locked = false
        if (!locked && size >= toleranceNanos) {
            correcting = true
            return framesFor(error)
        }
        locked = true
        if (size >= toleranceNanos) {
            correcting = true
        } else if (size < targetNanos) {
            correcting = false
        }
        if (!correcting) return 0
        return framesFor((error / 2).coerceIn(-maxStepNanos, maxStepNanos))
    }

    /** [nanos] as frames, at most [largeStepFrames] either way (also when a timestamp is absurd). */
    private fun framesFor(nanos: Long): Int {
        val bound = largeStepFrames * NANOS_PER_SECOND / sampleRate
        return (nanos.coerceIn(-bound, bound) * sampleRate / NANOS_PER_SECOND).toInt()
    }

    private fun schedulePoll(nextWriteFrame: Long) {
        val settling = nextWriteFrame < sampleRate.toLong() * SETTLE_SECONDS
        val perSecond = if (settling || converging) FAST_POLLS_PER_SECOND else SLOW_POLLS_PER_SECOND
        nextPollFrame = nextWriteFrame + sampleRate / perSecond
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val SETTLE_SECONDS = 5
        const val FAST_POLLS_PER_SECOND = 20
        const val SLOW_POLLS_PER_SECOND = 2
    }
}
