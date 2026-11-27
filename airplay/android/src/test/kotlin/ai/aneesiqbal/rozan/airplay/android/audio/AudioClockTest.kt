package ai.aneesiqbal.rozan.airplay.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs

/** Single decisions of [AudioClock]; [AudioClockSimulationTest] plays whole streams. */
class AudioClockTest {
    private val rate = 44_100
    private val msNanos = 1_000_000L

    private companion object {
        const val MAX_ROUNDS = 20
        const val QUIET_ROUNDS = 3
        const val CHUNKS_PER_ROUND = 10_000
    }

    /** Nanoseconds for [frames] frames at [rate]. */
    private fun nanos(frames: Long) = frames * 1_000_000_000L / rate

    /** A clock that has a mapping (frame 0 at time 0) and has seen the output start. */
    private fun runningClock(): AudioClock {
        val clock = AudioClock(rate)
        clock.onWrite(0, 0)
        clock.onTimestamp(1, nanos(1), 4410)
        return clock
    }

    /** A running clock that has been in sync once, i.e. is past start-up. */
    private fun lockedClock(): AudioClock {
        val clock = runningClock()
        assertEquals(0, clock.measure(4410, 0))
        return clock
    }

    /**
     * Reports that output frame [heardFrame] was heard [errorNanos] after the current mapping wants
     * it, and takes the correction: frames dropped (> 0) or padded (< 0).
     */
    private fun AudioClock.measure(
        heardFrame: Long,
        errorNanos: Long,
    ): Int {
        onTimestamp(heardFrame, wantedNanos(heardFrame) + errorNanos, heardFrame + 8820)
        return takeDrop(Int.MAX_VALUE) - takePad()
    }

    /** Equal up to the nanosecond rounding of frame/time conversions. */
    private fun assertNear(
        expected: Long,
        actual: Long,
    ) = assertTrue("expected $expected, got $actual", abs(expected - actual) <= 2)

    @Test
    fun noCorrectionBeforeAnyWrite() {
        val clock = AudioClock(rate)
        clock.onTimestamp(100, 123, 1000)
        clock.onTimestamp(200, 456_000_000, 1000)
        assertEquals(0, clock.takeDrop(352))
        assertEquals(0, clock.takePad())
    }

    @Test
    fun theOutputMustBeAdvancingBeforeItsTimestampsCount() {
        val clock = AudioClock(rate)
        clock.onWrite(0, 0)
        // First position seen, and one that stands still (not started, or an underrun): no measurement.
        clock.onTimestamp(4410, nanos(4410) + 60 * msNanos, 8820)
        clock.onTimestamp(4410, nanos(4410) + 90 * msNanos, 8820)
        assertEquals(0, clock.takeDrop(Int.MAX_VALUE))
        // Moving again: measured.
        clock.onTimestamp(8820, nanos(8820) + 60 * msNanos, 13_230)
        assertEquals(2646, clock.takeDrop(Int.MAX_VALUE))
    }

    @Test
    fun withinToleranceNothingHappens() {
        val clock = lockedClock()
        // 4 ms late, then 4 ms early: inside the 5 ms tolerance.
        assertEquals(0, clock.measure(8820, 4 * msNanos))
        assertEquals(0, clock.measure(13_230, -4 * msNanos))
        assertFalse(clock.converging)
    }

    @Test
    fun startUpOffsetIsCorrectedAtOnce() {
        val clock = runningClock()
        // 10 ms late before ever being in sync: the whole error goes in one step.
        assertEquals(441, clock.measure(4410, 10 * msNanos))
        assertTrue(clock.converging)
    }

    @Test
    fun onceLockedMediumErrorsAreCorrectedInCappedSteps() {
        val clock = lockedClock()
        // 30 ms late: beyond tolerance, below the large-error threshold. Half of it is 15 ms;
        // the step is capped at 2 ms (88 frames).
        assertEquals(88, clock.measure(8820, 30 * msNanos))
        assertTrue(clock.converging)
    }

    @Test
    fun earlyOutputPads() {
        val clock = lockedClock()
        assertEquals(-88, clock.measure(8820, -30 * msNanos))
    }

    @Test
    fun correctionTakesHalfTheErrorAndStopsBelowTheTarget() {
        val clock = lockedClock()
        // 6 ms starts a correction: 2 ms (capped half).
        assertEquals(88, clock.measure(8820, 6 * msNanos))
        // 4 ms is inside the tolerance but the correction goes on: half of it.
        assertEquals(88, clock.measure(13_230, 4 * msNanos))
        assertEquals(44, clock.measure(17_640, 2 * msNanos))
        // Below the 2 ms target: done.
        assertEquals(0, clock.measure(22_050, 1 * msNanos))
        assertFalse(clock.converging)
        // In sync again, 4 ms is jitter, not a reason to correct.
        assertEquals(0, clock.measure(26_460, 4 * msNanos))
    }

    @Test
    fun aLargeErrorUnlocksForFastCorrection() {
        val clock = lockedClock()
        assertEquals(2646, clock.measure(8820, 60 * msNanos))
        // Still unlocked: a medium error right after is corrected in full too.
        assertEquals(882, clock.measure(13_230, 20 * msNanos))
        // In tolerance: locked again, the rest goes in small steps.
        assertEquals(66, clock.measure(17_640, 3 * msNanos))
    }

    @Test
    fun largeErrorsAreCorrectedFastButBounded() {
        // A slow output start (500 ms) is caught up in one step.
        assertEquals(22_050, runningClock().measure(4410, 500 * msNanos))
        // Capped at one second, so a broken timestamp cannot throw away more.
        assertEquals(44_100, runningClock().measure(4410, 5_000 * msNanos))
        assertEquals(-44_100, runningClock().measure(4410, -5_000 * msNanos))
        assertEquals(44_100, runningClock().measure(4410, Long.MAX_VALUE / 2))
    }

    @Test
    fun aTakenCorrectionIsPartOfTheNextMeasurement() {
        val clock = lockedClock()
        // Frame 8820 heard 30 ms late. The 2 ms drop is taken but not heard yet.
        val heard = clock.wantedNanos(8820) + 30 * msNanos
        clock.onTimestamp(8820, heard, 13_230)
        assertEquals(88, clock.takeDrop(352))
        clock.onWrite(13_230, clock.wantedNanos(13_230))
        // The output reports the same old frame again (its timestamp is not newer): that frame
        // still played 30 ms late, but the frames written next will be 28 ms late, and that is
        // what is measured and corrected now.
        clock.onTimestamp(8821, heard + nanos(1), 13_582)
        assertNear(30 * msNanos - nanos(88), clock.lastErrorNanos)
        // And a pad shifts the mapping the other way.
        val early = lockedClock()
        val before = early.wantedNanos(8820)
        early.onTimestamp(8820, before - 30 * msNanos, 13_230)
        assertEquals(88, early.takePad())
        assertNear(before - nanos(88), early.wantedNanos(8820))
    }

    @Test
    fun aDropLongerThanAChunkContinuesInTheNextChunks() {
        val clock = runningClock()
        clock.onTimestamp(4410, nanos(4410) + 20 * msNanos, 8820)
        assertEquals(352, clock.takeDrop(352))
        assertEquals(352, clock.takeDrop(352))
        assertEquals(178, clock.takeDrop(352))
        assertEquals(0, clock.takeDrop(352))
        assertEquals(0, clock.takePad())
    }

    @Test
    fun aNewMeasurementReplacesACorrectionNotTakenYet() {
        val clock = runningClock()
        clock.onTimestamp(4410, nanos(4410) + 20 * msNanos, 8820)
        // Measured again before anything was taken: the same error, not twice the drop.
        clock.onTimestamp(4411, nanos(4411) + 20 * msNanos, 8820)
        assertEquals(882, clock.takeDrop(Int.MAX_VALUE))
    }

    @Test
    fun mappingFollowsTheLatestWrite() {
        val clock = runningClock()
        // A later chunk was written with a jump in presentation time (sender skipped 1 s).
        clock.onWrite(44_100, 2_000 * msNanos)
        assertEquals(2_000 * msNanos - nanos(4410), clock.wantedNanos(44_100 - 4410))
        assertEquals(0, clock.measure(44_100 - 4410, 0))
    }

    @Test
    fun steadyDriftIsTrackedAndReported() {
        val clock = lockedClock()
        assertEquals(88, clock.measure(441_000, 7 * msNanos))
        assertEquals(7 * msNanos, clock.lastErrorNanos)
    }

    @Test
    fun readsTheOutputOftenWhileSettlingOrCorrectingAndRarelyInSync() {
        val clock = AudioClock(rate)
        clock.onWrite(0, 0)
        // First five seconds: every 1/20 s of written audio.
        assertTrue(clock.timestampDue(352))
        assertFalse(clock.timestampDue(352 + 2204))
        assertTrue(clock.timestampDue(352 + 2205))
        // In sync after that: every 1/2 s.
        clock.onTimestamp(220_000, nanos(220_000), 230_000)
        clock.onTimestamp(221_000, nanos(221_000), 230_000)
        assertFalse(clock.converging)
        assertFalse(clock.timestampDue(230_000 + 22_049))
        assertTrue(clock.timestampDue(230_000 + 22_050))
        // An error found by that read brings the next one forward.
        clock.onTimestamp(240_000, nanos(240_000) + 10 * msNanos, 252_050)
        assertTrue(clock.converging)
        assertFalse(clock.timestampDue(252_050 + 2204))
        assertTrue(clock.timestampDue(252_050 + 2205))
    }

    @Test
    fun thePerChunkCallsAllocateNothing() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("needs HotSpot's per-thread allocation counter", threads != null)
        val counter = threads ?: return
        val clock = AudioClock(rate)
        clock.onWrite(0, 0)
        // Rounds of chunks, every read 10 ms off one way or the other so drops and pads are taken.
        // The first rounds may include class loading and JIT bookkeeping; a per-chunk allocation
        // would show in every round.
        val allocations = LongArray(MAX_ROUNDS)
        var quiet = 0
        var round = 0
        while (round < MAX_ROUNDS && quiet < QUIET_ROUNDS) {
            val before = counter.currentThreadAllocatedBytes
            playChunks(clock, round)
            allocations[round] = counter.currentThreadAllocatedBytes - before
            quiet = if (allocations[round] == 0L) quiet + 1 else 0
            round++
        }
        assertEquals("bytes per round: ${allocations.toList()}", QUIET_ROUNDS, quiet)
    }

    private fun playChunks(
        clock: AudioClock,
        round: Int,
    ) {
        var frame = round.toLong() * CHUNKS_PER_ROUND * 352
        for (i in 0 until CHUNKS_PER_ROUND) {
            if (clock.timestampDue(frame)) {
                val heard = frame - 8820
                val off = if ((frame / 2205) % 2 == 0L) 10 * msNanos else -10 * msNanos
                clock.onTimestamp(heard, clock.wantedNanos(heard) + off, frame)
            }
            val first = clock.takeDrop(352)
            frame += clock.takePad()
            clock.onWrite(frame, clock.wantedNanos(frame) + nanos(first.toLong()))
            frame += 352 - first
        }
    }

    @Test
    fun resetForgetsMapping() {
        val clock = runningClock()
        clock.onTimestamp(4410, nanos(4410) + 20 * msNanos, 8820)
        clock.reset()
        assertEquals(0, clock.takeDrop(Int.MAX_VALUE))
        clock.onTimestamp(4410, Long.MAX_VALUE / 2, 8820)
        clock.onTimestamp(8820, Long.MAX_VALUE / 2, 13_230)
        assertEquals(0, clock.takeDrop(Int.MAX_VALUE))
        assertTrue(clock.timestampDue(0))
    }
}
