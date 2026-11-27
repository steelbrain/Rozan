package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.audio.AacAudioSink
import ai.aneesiqbal.rozan.airplay.android.audio.AudioTrackPcmSink
import ai.aneesiqbal.rozan.airplay.android.audio.DecodingAudioSink
import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import android.media.AudioAttributes
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Decodes and plays fixtures through the audio sinks and their single AudioTrack. */
@RunWith(AndroidJUnit4::class)
class AudioSinkTest {
    private val music = DecodingAudioSink.ContentType { AudioAttributes.CONTENT_TYPE_MUSIC }

    @Test
    fun aacLcIsDecodedAndPlayed() = playAac("aac-lc.rzau", eld = false, framesPerPacket = 1024)

    @Test
    fun aacEldIsDecodedAndPlayed() = playAac("aac-eld.rzau", eld = true, framesPerPacket = 512)

    @Test
    fun pcmStaysInSyncWithItsPresentationTimes() {
        val rate = 44_100
        val frames = 352
        val sink = AudioTrackPcmSink(AudioFormat.Pcm(rate, 2, frames), music)
        val packet = ByteArray(frames * 4)
        val start = System.nanoTime() + 300_000_000L
        val packets = rate * STREAM_SECONDS / frames
        // Errors measured once the start-up offset has been corrected. One sample can catch an
        // output stall of the emulator that the clock is still correcting, so the test judges the
        // typical error, not the last.
        val errors = ArrayList<Long>(packets)
        val settled = start + SETTLE_NANOS
        for (p in 0 until packets) {
            fillSine(packet, p, frames, rate)
            val presentation = start + p.toLong() * frames * 1_000_000_000L / rate
            // Paced like a sender with a 1 s buffer (RAOP's default is about 2 s), so the queue
            // never holds the whole stream. The lead must exceed the output latency to be in
            // sync at all: a loaded API 31 emulator takes over 600 ms from write to speaker.
            sleepUntil(presentation - SENDER_LEAD_NANOS)
            sink.submit(packet, 0, packet.size, presentation)
            if (System.nanoTime() >= settled) errors += abs(sink.syncErrorNanos)
        }
        assertTrue("played (${sink.framesRendered})", eventually { sink.handled() >= packets * frames * 9L / 10 })
        errors.sort()
        val median = errors[errors.size / 2]
        val worst = errors.last()
        Log.i(
            TAG,
            "PCM sync error after settling: median ${median / 1_000} us, worst ${worst / 1_000} us " +
                "over ${errors.size} samples, skipped ${sink.framesSkipped} frames, dropped ${sink.droppedUnits} units",
        )
        sink.close()
        assertEquals(0L, sink.droppedUnits)
        assertTrue(errors.size > packets / 4)
        assertTrue(
            "median sync error ${median / 1_000} us (worst ${worst / 1_000} us)",
            median < MAX_SYNC_ERROR_NANOS,
        )
    }

    @Test
    fun pcmCatchesUpWithAMediumJumpWithinTwoSeconds() {
        val rate = 44_100
        val frames = 352
        val sink = AudioTrackPcmSink(AudioFormat.Pcm(rate, 2, frames), music)
        val packet = ByteArray(frames * 4)
        val start = System.nanoTime() + 300_000_000L
        val packets = rate * JUMP_STREAM_SECONDS / frames
        val jump = rate * JUMP_AT_SECONDS / frames
        val jumpNanos = start + jump.toLong() * frames * 1_000_000_000L / rate
        // From the jump on, the sender wants every frame 35 ms earlier (its clock mapping moved,
        // or the output stalled that long): the output is late by more than the tolerance and
        // less than the 40 ms that are corrected in one step, so the clock takes small steps.
        var seen = 0L
        var backInSync = 0L
        val after = ArrayList<Long>(packets)
        for (p in 0 until packets) {
            fillSine(packet, p, frames, rate)
            val nominal = start + p.toLong() * frames * 1_000_000_000L / rate
            sleepUntil(nominal - SENDER_LEAD_NANOS)
            val presentation = if (p >= jump) nominal - JUMP_NANOS else nominal
            sink.submit(packet, 0, packet.size, presentation)
            val now = System.nanoTime()
            val error = abs(sink.syncErrorNanos)
            // Convergence is the time from measuring the jump to the first measurement back inside
            // the tolerance. A later excursion (the emulator's output stalls under load) is not the
            // jump being corrected slowly; the median after settling covers those.
            if (p >= jump && seen == 0L && error >= TOLERANCE_NANOS) seen = now
            if (seen != 0L && backInSync == 0L && error < TOLERANCE_NANOS) backInSync = now
            if (now >= jumpNanos + JUMP_SETTLE_NANOS) after += error
        }
        sink.close()
        after.sort()
        val median = if (after.isEmpty()) Long.MAX_VALUE else after[after.size / 2]
        Log.i(
            TAG,
            "PCM 35 ms jump: seen ${(seen - jumpNanos) / 1_000_000} ms from its presentation time, " +
                "below 5 ms ${(backInSync - seen) / 1_000_000} ms later; median error after it ${median / 1_000} us " +
                "over ${after.size} samples, skipped ${sink.framesSkipped} frames",
        )
        assertTrue("the jump was never measured", seen != 0L)
        assertTrue("the jump was never corrected", backInSync != 0L)
        assertTrue(
            "back in sync only ${(backInSync - seen) / 1_000_000} ms after the jump",
            backInSync - seen <= JUMP_SETTLE_NANOS,
        )
        assertTrue(after.size > packets / 20)
        assertTrue("median error after the jump ${median / 1_000} us", median < TOLERANCE_NANOS)
    }

    @Test
    fun flushDropsQueuedAudioAndPlaybackResumes() {
        val fixture = AudioFixture.load("aac-lc.rzau")
        val sink =
            AacAudioSink(AudioFormat.Aac(fixture.sampleRate, fixture.channels, 1024, false, fixture.config), music)
        val frameNanos = 1024 * 1_000_000_000L / fixture.sampleRate
        val start = System.nanoTime() + 200_000_000L
        fixture.units.take(20).forEachIndexed { i, unit -> sink.submit(unit, 0, unit.size, start + i * frameNanos) }
        sink.flush()
        val restart = System.nanoTime() + 200_000_000L
        fixture.units.forEachIndexed { i, unit -> sink.submit(unit, 0, unit.size, restart + i * frameNanos) }
        assertTrue(eventually { sink.handled() >= fixture.units.size * 1024L * 8 / 10 })
        sink.close()
    }

    private fun playAac(
        asset: String,
        eld: Boolean,
        framesPerPacket: Int,
    ) {
        val fixture = AudioFixture.load(asset)
        val format = AudioFormat.Aac(fixture.sampleRate, fixture.channels, framesPerPacket, eld, fixture.config)
        val sink = AacAudioSink(format, music)
        val frameNanos = framesPerPacket * 1_000_000_000L / fixture.sampleRate
        val start = System.nanoTime() + 300_000_000L
        // Everything at once, presentation times in the future: the sink must wait for them.
        fixture.units.forEachIndexed { i, unit -> sink.submit(unit, 0, unit.size, start + i * frameNanos) }
        val expected = fixture.units.size.toLong() * framesPerPacket
        assertTrue(
            "$asset played (${sink.framesRendered}/$expected)",
            eventually {
                sink.handled() >= expected * 8 / 10
            },
        )
        Log.i(
            TAG,
            "$asset: ${sink.framesRendered} frames played, ${sink.framesSkipped} skipped, " +
                "sync error ${sink.syncErrorNanos / 1_000} us",
        )
        assertEquals(0L, sink.droppedUnits)
        sink.close()
    }

    /** Fills [packet] with packet [p] of a 440 Hz tone, big-endian stereo. */
    private fun fillSine(
        packet: ByteArray,
        p: Int,
        frames: Int,
        rate: Int,
    ) {
        for (i in 0 until frames) {
            val sample = (3000 * sin(2 * PI * 440 * (p * frames + i) / rate)).toInt()
            for (c in 0 until 2) {
                packet[(i * 2 + c) * 2] = (sample shr 8).toByte()
                packet[(i * 2 + c) * 2 + 1] = sample.toByte()
            }
        }
    }

    /** Frames played or deliberately skipped to catch up with the presentation times. */
    private fun DecodingAudioSink.handled() = framesRendered + framesSkipped

    private companion object {
        const val TAG = "AudioSinkTest"
        const val SENDER_LEAD_NANOS = 1_000_000_000L
        const val STREAM_SECONDS = 5

        /** The sender lead plus time to correct the start-up offset; measured from the first presentation. */
        const val SETTLE_NANOS = 1_500_000_000L

        /** The clock's tolerance: errors below it are left alone. */
        const val TOLERANCE_NANOS = 5_000_000L

        /**
         * The typical error must be inside the clock's tolerance. API 31 and 36 emulators measure
         * tens of microseconds, also under load; an output stall of 35 ms in the window is gone
         * about a second after it is measured and leaves the median inside.
         */
        const val MAX_SYNC_ERROR_NANOS = TOLERANCE_NANOS
        const val JUMP_NANOS = 35_000_000L

        /** After the settling phase, when the clock reads the output only twice a second. */
        const val JUMP_AT_SECONDS = 6
        const val JUMP_STREAM_SECONDS = 10
        const val JUMP_SETTLE_NANOS = 2_000_000_000L
    }
}
