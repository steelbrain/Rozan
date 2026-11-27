package ai.aneesiqbal.rozan.airplay.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * [AudioClock] keeping a simulated stream in sync: a sender, the sink's write
 * loop and an output on one simulated clock (see [Playback]).
 */
class AudioClockSimulationTest {
    @Test
    fun aMediumStallAfterLockIsGoneWithinTwoSecondsInSmallSteps() {
        // Device-like output (50 ms) and a loaded emulator (600 ms from write to speaker): the
        // deeper the output, the more corrections are written but not heard when the next is made.
        for (latency in longArrayOf(50 * MS, 600 * MS)) {
            for (shift in longArrayOf(35 * MS, -35 * MS)) {
                val playback = Playback(latencyNanos = latency)
                playback.stallAt(STALL_FRAME, shift)
                playback.run(seconds = 30)
                val what = "latency ${latency / MS} ms, shift ${shift / MS} ms"
                val stall = playback.heardNanos(STALL_FRAME)
                assertTrue(what, playback.maxError(playback.startNanos + 2 * SECOND, stall) < 3 * MS)
                val converged = playback.settleTime(stall, 5 * MS)
                val close = playback.settleTime(stall, 3 * MS)
                val steps = playback.correctionsBetween(stall, Long.MAX_VALUE)
                report("$what: below 5 ms after ${converged / MS} ms, below 3 ms after ${close / MS} ms")
                report("$what: ${steps.size} steps")
                assertTrue("$what: converged after ${converged / MS} ms", converged <= 2 * SECOND)
                assertTrue("$what: within 3 ms after ${close / MS} ms", close <= 2 * SECOND)
                // Small steps, all in the same direction: no overshoot, no back and forth.
                assertTrue(what, steps.all { abs(it.frames) <= MAX_STEP_FRAMES })
                assertTrue(what, steps.all { it.frames * shift > 0 })
                // Corrected once: the steps add up to the shift (give or take the error left before
                // and after, each under 3 ms), and the error never crosses to the other side.
                val corrected = steps.sumOf { it.frames.toLong() } * SECOND / RATE
                assertTrue("$what: corrected ${corrected / 1_000} us", abs(corrected - shift) < 6 * MS)
                val direction = if (shift > 0) 1 else -1
                val after = playback.samplesBetween(stall, Long.MAX_VALUE)
                assertTrue(what, after.all { it.errorNanos * direction > -1 * MS })
                // Done: nothing more for the rest of the run.
                assertTrue(what, playback.correctionsBetween(stall + 3 * SECOND, Long.MAX_VALUE).isEmpty())
            }
        }
    }

    @Test
    fun timestampJitterInsideTheToleranceMakesNoCorrections() {
        for (seed in 1..5) {
            val playback = Playback(timestampJitterNanos = 1 * MS, seed = seed)
            playback.run(seconds = 60)
            // Start-up: the output latency in one step, and maybe a step or two towards zero.
            val startUp = playback.correctionsBetween(0, playback.startNanos + SECOND)
            assertTrue("seed $seed: ${startUp.size} start-up steps", startUp.size in 1..3)
            val later = playback.correctionsBetween(playback.startNanos + SECOND, Long.MAX_VALUE)
            assertEquals("seed $seed: corrections after start-up", 0, later.size)
            assertTrue(playback.maxError(playback.startNanos + SECOND, Long.MAX_VALUE) < 3 * MS)
        }
    }

    @Test
    fun steadyDriftIsFollowedInSmallStepsInOneDirection() {
        for (ppm in intArrayOf(50, -50, 300, -300)) {
            val playback = Playback(ppm = ppm.toDouble())
            playback.run(seconds = 120)
            val from = playback.startNanos + 2 * SECOND
            val steps = playback.correctionsBetween(from, Long.MAX_VALUE)
            val worst = playback.maxError(from, Long.MAX_VALUE)
            report("$ppm ppm: ${steps.size} steps, worst error ${worst / 1_000} us")
            // The output runs fast (ppm > 0): it gets early and is padded; slow: dropped.
            assertTrue("$ppm ppm", steps.all { (it.frames > 0) == (ppm < 0) })
            assertTrue("$ppm ppm", steps.all { abs(it.frames) <= MAX_STEP_FRAMES })
            // Kept near the tolerance (5 ms) plus timestamp jitter (1 ms).
            assertTrue("$ppm ppm: worst ${worst / 1_000} us", worst < 7 * MS)
            // Each step takes the error from beyond 5 ms back below the 2 ms target, so the steps
            // add up to the drift with few of them: no chatter.
            val drift = abs(ppm) * 120 * SECOND / 1_000_000
            val corrected = abs(steps.sumOf { it.frames.toLong() }) * SECOND / RATE
            assertTrue(
                "$ppm ppm: corrected ${corrected / 1_000} us of ${drift / 1_000}",
                abs(corrected - drift) < 7 * MS,
            )
            assertTrue("$ppm ppm: ${steps.size} steps", steps.size <= drift / MS + 4)
        }
    }

    @Test
    fun aSlowOutputStartIsCaughtUpInOneStep() {
        val playback = Playback(latencyNanos = 600 * MS)
        playback.run(seconds = 10)
        val first = playback.corrections.first()
        assertTrue("first step ${first.frames}", first.frames > 590 * RATE / 1_000)
        assertEquals(1, playback.corrections.count { abs(it.frames) > MAX_STEP_FRAMES })
        val heard = playback.heardNanos(0)
        val converged = playback.settleTime(heard, 5 * MS)
        report("600 ms start-up offset: below 5 ms after ${converged / MS} ms")
        assertTrue("converged after ${converged / MS} ms", converged <= 1_500 * MS)
    }

    @Test
    fun aLargeStallIsCorrectedInOneStep() {
        val playback = Playback()
        playback.stallAt(STALL_FRAME, 200 * MS)
        playback.run(seconds = 20)
        val stall = playback.heardNanos(STALL_FRAME)
        val steps = playback.correctionsBetween(stall, Long.MAX_VALUE)
        assertTrue("first step ${steps.first().frames}", steps.first().frames > 195 * RATE / 1_000)
        assertTrue(steps.drop(1).all { abs(it.frames) <= MAX_STEP_FRAMES })
        assertTrue(playback.settleTime(stall, 5 * MS) <= SECOND)
    }

    @Test
    fun otherRatesAndChunkSizesConvergeAlike() {
        // AAC-LC (1024 frames) and AAC-ELD (480) at 48 kHz.
        for (chunk in intArrayOf(1024, 480)) {
            val playback = Playback(rate = 48_000, chunkFrames = chunk)
            playback.stallAt(480_000, 35 * MS)
            playback.run(seconds = 30)
            val stall = playback.heardNanos(480_000)
            val steps = playback.correctionsBetween(stall, Long.MAX_VALUE)
            assertTrue("chunk $chunk", playback.settleTime(stall, 5 * MS) <= 2 * SECOND)
            assertTrue("chunk $chunk", steps.all { it.frames in 1..96 })
        }
    }

    private fun report(line: String) = println("AudioClockSimulationTest: $line")

    private companion object {
        const val RATE = 44_100
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
        const val STALL_FRAME = 10L * RATE

        /** 2 ms at 44.1 kHz. */
        const val MAX_STEP_FRAMES = 88
    }
}

/**
 * A sender, the sink's write loop and an output, on a simulated clock.
 *
 * - Sender: chunks of [chunkFrames] frames, each submitted between 1 and 2 s
 *   (at random) before its presentation time, as AirPlay senders queue that
 *   much ahead.
 * - Sink: the calls `DecodingAudioSink.render` makes. The first chunk is
 *   written and the output started at its presentation time; then, per chunk:
 *   read a timestamp when the clock asks, drop or pad what it hands out, map
 *   and write, blocking while the output buffer (1/10 s) is full.
 * - Output: takes frames from the buffer at the sender's rate × (1 + [ppm]
 *   / 10⁶) and plays them [latencyNanos] later. From [stallAt]'s frame on
 *   everything is heard that much later (a stall) or earlier (skipped
 *   frames). Its timestamps report the last frame heard, the time off by up
 *   to ±[timestampJitterNanos].
 *
 * Records the error of every written chunk when heard (exact, against the
 * sender's timeline) and every correction.
 */
private class Playback(
    val rate: Int = 44_100,
    val chunkFrames: Int = 352,
    ppm: Double = 0.0,
    val latencyNanos: Long = 50_000_000L,
    val timestampJitterNanos: Long = 1_000_000L,
    seed: Int = 1,
) {
    /** A drop (> 0) or pad (< 0) of [frames] frames at output frame [frame], heard from [heardNanos] on. */
    class Correction(
        val frame: Long,
        val heardNanos: Long,
        val frames: Int,
    )

    /** A chunk's first frame is heard at [heardNanos], [errorNanos] after the sender wanted it. */
    class Sample(
        val heardNanos: Long,
        val errorNanos: Long,
    )

    val clock = AudioClock(rate)
    val corrections = ArrayList<Correction>()
    val samples = ArrayList<Sample>()

    /** When the output started playing. */
    var startNanos = 0L
        private set

    private val random = Random(seed)
    private val outputRate = rate * (1 + ppm / 1e6)
    private val bufferFrames = rate / 10L
    private var stallFrame = Long.MAX_VALUE
    private var stallNanos = 0L
    private var now = 0L
    private var written = 0L

    /** From output frame [frame] on, everything is heard [shiftNanos] later (negative: earlier). */
    fun stallAt(
        frame: Long,
        shiftNanos: Long,
    ) {
        stallFrame = frame
        stallNanos = shiftNanos
    }

    /** When output frame [frame] leaves the buffer. */
    private fun leaveNanos(frame: Long): Long {
        val plain = startNanos + (frame * 1e9 / outputRate).toLong()
        return if (frame >= stallFrame) plain + stallNanos else plain
    }

    /** When output frame [frame] is heard. */
    fun heardNanos(frame: Long): Long = leaveNanos(frame) + latencyNanos

    /** Frames that have left the buffer by [now]. */
    private fun consumed(now: Long): Long {
        if (now < startNanos) return 0
        val plain = ((now - startNanos) * outputRate / 1e9).toLong() + 1
        if (plain <= stallFrame) return plain
        return maxOf(stallFrame, ((now - startNanos - stallNanos) * outputRate / 1e9).toLong() + 1)
    }

    /** Streams [seconds] of audio. */
    fun run(seconds: Int) {
        val chunks = seconds.toLong() * rate / chunkFrames
        var submitted = 0L
        for (k in 0 until chunks) {
            val presentation = FIRST_PRESENTATION + k * chunkFrames * SECOND / rate
            submitted = maxOf(submitted, presentation - SECOND - random.nextLong(SECOND))
            now = maxOf(now, submitted)
            if (k == 0L) start(presentation) else render(presentation)
        }
    }

    /** `DecodingAudioSink.start`: write the first chunk, wait for its time, start the output. */
    private fun start(presentation: Long) {
        clock.reset()
        clock.onWrite(0, presentation)
        written = chunkFrames.toLong()
        now = maxOf(now, presentation)
        startNanos = now
        samples += Sample(heardNanos(0), heardNanos(0) - presentation)
    }

    /** `DecodingAudioSink.render` for one chunk. */
    private fun render(presentation: Long) {
        if (clock.timestampDue(written)) readTimestamp()
        val first = clock.takeDrop(chunkFrames)
        if (first > 0) recordDrop(first)
        if (first == chunkFrames) return
        val pad = clock.takePad()
        if (pad > 0) {
            corrections += Correction(written, heardNanos(written), -pad)
            write(pad)
        }
        val wanted = presentation + first * SECOND / rate
        clock.onWrite(written, wanted)
        samples += Sample(heardNanos(written), heardNanos(written) - wanted)
        write(chunkFrames - first)
    }

    /** A drop longer than a chunk goes on in the next chunks, at the same output frame: one step. */
    private fun recordDrop(frames: Int) {
        val previous = corrections.lastOrNull()
        if (previous != null && previous.frame == written && previous.frames > 0) {
            corrections[corrections.size - 1] = Correction(written, previous.heardNanos, previous.frames + frames)
        } else {
            corrections += Correction(written, heardNanos(written), frames)
        }
    }

    private fun readTimestamp() {
        val last = consumed(now - latencyNanos) - 1
        if (last < 0) return // Nothing heard yet: getTimestamp fails.
        val jitter = random.nextLong(-timestampJitterNanos, timestampJitterNanos + 1)
        clock.onTimestamp(last, heardNanos(last) + jitter, written)
    }

    /** A blocking write of [frames] frames: returns once they fit the buffer. */
    private fun write(frames: Int) {
        check(consumed(now) <= written) { "the output ran dry at ${now / MS} ms; the scenario is broken" }
        val mustHaveLeft = written + frames - bufferFrames
        if (mustHaveLeft > 0) now = maxOf(now, leaveNanos(mustHaveLeft - 1))
        written += frames
    }

    fun correctionsBetween(
        fromNanos: Long,
        untilNanos: Long,
    ): List<Correction> = corrections.filter { it.heardNanos in fromNanos until untilNanos }

    fun samplesBetween(
        fromNanos: Long,
        untilNanos: Long,
    ): List<Sample> = samples.filter { it.heardNanos in fromNanos until untilNanos }

    /** Largest error heard between the two times. */
    fun maxError(
        fromNanos: Long,
        untilNanos: Long,
    ): Long = samplesBetween(fromNanos, untilNanos).maxOf { abs(it.errorNanos) }

    /** How long after [fromNanos] the heard error is last [boundNanos] or more (0: never). */
    fun settleTime(
        fromNanos: Long,
        boundNanos: Long,
    ): Long {
        val last = samples.lastOrNull { it.heardNanos >= fromNanos && abs(it.errorNanos) >= boundNanos }
        return if (last == null) 0 else last.heardNanos - fromNanos
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
        const val FIRST_PRESENTATION = 10 * SECOND
    }
}
