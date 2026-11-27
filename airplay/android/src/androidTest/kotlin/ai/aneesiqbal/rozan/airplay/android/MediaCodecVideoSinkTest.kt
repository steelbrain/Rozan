package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.debug.TestClip
import ai.aneesiqbal.rozan.airplay.android.video.AnnexB
import ai.aneesiqbal.rozan.airplay.android.video.MediaCodecVideoSink
import ai.aneesiqbal.rozan.airplay.android.video.SurfaceBridge
import ai.aneesiqbal.rozan.airplay.android.video.VideoDecoderFactory
import ai.aneesiqbal.rozan.airplay.android.video.VideoDecoders
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes the bundled H.264 clip through [MediaCodecVideoSink] into an
 * [ImageReader] surface standing in for the mirroring `SurfaceView`.
 *
 * The sink's own behaviour is tested with the platform's software decoder,
 * which every device ships, so the results do not depend on a device's
 * hardware decoder or an emulator's host-backed one (the API 31 x86 CI image's
 * goldfish decoder renders nothing into these surfaces).
 * [thePreferredDecoderDecodesTheClip] covers the production decoder choice.
 *
 * Decoders differ in speed by orders of magnitude (a two-core CI emulator
 * versus a TV), so the tests count decoder output (frames shown plus frames
 * skipped while catching up) and wait as long as a decoder 20 times slower
 * than real time needs. Only [aRealTimeStreamIsShownFrameForFrame] checks
 * timing, and it skips itself on a decoder that cannot keep up with the clip.
 */
@RunWith(AndroidJUnit4::class)
class MediaCodecVideoSinkTest {
    private val frameNanos = 1_000_000_000L / TestClip.FRAME_RATE
    private lateinit var readerThread: HandlerThread
    private lateinit var reader: ImageReader
    private val images = AtomicInteger()
    private lateinit var clip: TestClip

    /** Every decoder started in this test, in order. */
    private val started = CopyOnWriteArrayList<String>()

    /** The AOSP software H.264 decoders ("c2.android." from Android 10, "OMX.google." before). */
    private val software =
        RecordingDecoders(
            VideoDecoders.candidates(MediaFormat.MIMETYPE_VIDEO_AVC).filter {
                it.startsWith("c2.android.") || it.startsWith("OMX.google.")
            },
        )

    /** The production choice: hardware first. */
    private val preferred = RecordingDecoders(names = null)

    /** Long enough for a decoder 20 times slower than real time to get through the whole clip. */
    private val decodeTimeoutMillis: Long
        get() = clip.accessUnits.size * 1_000L / TestClip.FRAME_RATE * SLOWDOWN_ALLOWED + START_UP_MILLIS

    @Before
    fun setUp() {
        clip = TestClip.load(testContext)
        readerThread = HandlerThread("test-image-reader").apply { start() }
        reader = ImageReader.newInstance(clip.width, clip.height, ImageFormat.PRIVATE, 4)
        reader.setOnImageAvailableListener({ source ->
            // Drain everything queued: one callback can stand for several frames, and frames left
            // in the queue keep their buffers from the decoder, which then stops (seen on emulators).
            while (true) {
                val image = source.acquireNextImage() ?: break
                image.close()
                images.incrementAndGet()
            }
        }, Handler(readerThread.looper))
    }

    @After
    fun tearDown() {
        reader.close()
        readerThread.quitSafely()
    }

    @Test
    fun framesThatArriveBeforeTheSurfaceAreNotLost() {
        val bridge = SurfaceBridge()
        val sink = MediaCodecVideoSink(bridge, software)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime() + 100_000_000L
        // The stream starts while the activity is still coming up: frames with nowhere to go.
        for (i in 0 until BACKLOG) submit(sink, i, start)
        assertEquals(0L, sink.decoded())
        assertEquals(0L, sink.droppedUnits)

        bridge.surfaceAvailable(reader.surface)
        // The backlog goes into the decoder at once (all but the frames it holds back come out).
        expect(sink, "backlog decoding") { sink.decoded() >= BACKLOG - HELD_BACK }

        for (i in BACKLOG until clip.accessUnits.size) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        // Nothing vanishes: a unit is decoded, or counted as dropped (only when the decoder
        // takes no input for half a second).
        expect(sink, "every frame decoded or counted") { sink.accounted() >= clip.accessUnits.size - HELD_BACK }
        // Caught up: backlog frames older than its newest were skipped, the newest and the live ones shown.
        assertEquals("skipped backlog frames: ${state(sink)}", BACKLOG - 1L, sink.skippedFrames)
        expect(sink, "frames reached the surface") { images.get() >= 1 }
        report(sink, "pre-surface backlog")
        bridge.surfaceDestroyed()
        sink.close()
    }

    @Test
    fun aResolutionChangeMidStreamReconfiguresTheDecoder() {
        val bridge = SurfaceBridge()
        val sizes = LinkedSizes()
        instrumentation.runOnMainSync { bridge.setVideoSizeListener(sizes) }
        bridge.surfaceAvailable(reader.surface)
        val sink = MediaCodecVideoSink(bridge, software)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime() + 50_000_000L
        for (i in 0 until 30) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        expect(sink, "720p frames decoded") { sink.accounted() >= 30 - HELD_BACK }
        expect(sink, "720p frames shown") { sink.renderedFrames >= 1 }
        assertTrue("720p reported", sizes.await(1280, 720))
        val accountedBefore = sink.accounted()
        val shownBefore = sink.renderedFrames

        val small = TestClip.parse(testContext.assets.open("clip-360p.h264").use { it.readBytes() }, 640, 360)
        sink.configure(VideoCodec.H264, small.parameterSets, small.width, small.height)
        val restart = System.nanoTime() + 50_000_000L
        small.accessUnits.forEachIndexed { i, unit ->
            sleepUntil(restart + i * frameNanos - 20_000_000L)
            sink.submit(unit, 0, unit.size, restart + i * frameNanos)
        }
        expect(sink, "360p frames decoded") { sink.accounted() >= accountedBefore + small.accessUnits.size - HELD_BACK }
        expect(sink, "360p frames shown") { sink.renderedFrames > shownBefore }
        assertTrue("360p reported", sizes.await(640, 360))
        expect(sink, "a new decoder for the new format") { started.size >= 2 }
        report(sink, "resolution change")
        bridge.surfaceDestroyed()
        sink.close()
    }

    @Test
    fun losingTheSurfaceReleasesTheDecoderAndResumesAtTheNextKeyFrame() {
        val lost = 20
        val nextKeyFrame = clip.accessUnits.indices.first { it >= lost && isKeyFrame(clip.accessUnits[it]) }
        val bridge = SurfaceBridge()
        bridge.surfaceAvailable(reader.surface)
        val sink = MediaCodecVideoSink(bridge, software)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime() + 50_000_000L
        for (i in 0 until lost) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        expect(sink, "frames before the loss decoded") { sink.accounted() >= lost - HELD_BACK }
        expect(sink, "frames before the loss shown") { sink.renderedFrames >= 1 }
        // Returns only once the decoder no longer renders into the surface.
        bridge.surfaceDestroyed()
        val decodedAtLoss = sink.decoded()
        val droppedAtLoss = sink.droppedUnits
        val shownAtLoss = sink.renderedFrames
        bridge.surfaceAvailable(reader.surface)
        for (i in lost until clip.accessUnits.size) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        val sent = clip.accessUnits.size - lost
        expect(sink, "every frame after the loss decoded or dropped") {
            sink.accounted() >= decodedAtLoss + droppedAtLoss + sent - HELD_BACK
        }
        // Frames up to the next key frame refer to the lost decoder state: dropped, never decoded.
        expect(sink, "frames before key frame $nextKeyFrame dropped") {
            sink.droppedUnits - droppedAtLoss >= nextKeyFrame - lost
        }
        val fromKeyFrame = (clip.accessUnits.size - nextKeyFrame).toLong()
        expect(sink, "decoding resumed at key frame $nextKeyFrame") {
            sink.decoded() - decodedAtLoss in 1L..fromKeyFrame
        }
        expect(sink, "frames after the key frame shown") { sink.renderedFrames > shownAtLoss }
        report(sink, "surface loss")
        bridge.surfaceDestroyed()
        sink.close()
    }

    /**
     * A sender that outruns the decoder keeps [MediaCodecVideoSink.submit] waiting for decoder input
     * buffers. That wait must not hold up the main thread's surface loss, which returns only once the
     * decoder has let go of the surface, nor [MediaCodecVideoSink.close].
     */
    @Test
    fun aSurfaceLossIsNotHeldUpByASubmitWaitingForTheDecoder() {
        val bridge = SurfaceBridge()
        bridge.surfaceAvailable(reader.surface)
        val sink = MediaCodecVideoSink(bridge, software)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime() + 50_000_000L
        for (i in 0 until 10) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        expect(sink, "decoder running") { sink.decoded() >= 1 }
        // The clip again and again, as fast as the sink takes it: always faster than the decoder.
        val flooding = AtomicBoolean(true)
        val flood =
            Thread {
                var i = 0L
                while (flooding.get()) {
                    val unit = clip.accessUnits[(i % clip.accessUnits.size).toInt()]
                    sink.submit(unit, 0, unit.size, start + (10 + i) * frameNanos)
                    i++
                }
            }.apply { start() }
        val losses = LongArray(SURFACE_LOSSES)
        for (round in 0 until SURFACE_LOSSES) {
            Thread.sleep(FLOOD_MILLIS)
            val lostAt = System.nanoTime()
            bridge.surfaceDestroyed()
            losses[round] = (System.nanoTime() - lostAt) / 1_000_000L
            bridge.surfaceAvailable(reader.surface)
        }
        flooding.set(false)
        val closeAt = System.nanoTime()
        sink.close()
        val closeMillis = (System.nanoTime() - closeAt) / 1_000_000L
        flood.join()
        Log.i(TAG, "surface loss under load: ${losses.joinToString()} ms; close $closeMillis ms; ${state(sink)}")
        assertTrue("surface loss took ${losses.max()} ms", losses.max() <= MAX_SURFACE_LOSS_MILLIS)
    }

    @Test
    fun closingWithoutEverHavingASurfaceIsClean() {
        val sink = MediaCodecVideoSink(SurfaceBridge(), software)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime()
        for (i in clip.accessUnits.indices) submit(sink, i, start)
        sink.close()
        assertEquals(0L, sink.decoded())
        assertTrue("no decoder without a surface", started.isEmpty())
    }

    /**
     * Steady-state cost of [MediaCodecVideoSink.submit] on the calling thread,
     * in objects allocated per frame. Our code allocates nothing; what remains
     * is MediaCodec's own bookkeeping in `getInputBuffer` (a ByteBuffer view
     * and a map entry per call), which no caller can avoid. Independent of
     * decoder speed: a slow decoder only makes `submit` wait.
     */
    @Test
    @Suppress("DEPRECATION") // Allocation counting is deprecated but still works on ART; there is no replacement.
    fun submittingFramesAllocatesAlmostNothing() {
        val bridge = SurfaceBridge()
        bridge.surfaceAvailable(reader.surface)
        val sink = MediaCodecVideoSink(bridge, software)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime() + 50_000_000L
        for (i in 0 until 10) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        // Measure the steady state: the decoder runs and has taken the key frame.
        expect(sink, "decoder running") { sink.decoded() >= 1 }
        val measured = clip.accessUnits.size - 10
        Debug.startAllocCounting()
        Debug.resetThreadAllocCount()
        for (i in 10 until clip.accessUnits.size) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        val objects = Debug.getThreadAllocCount()
        val bytes = Debug.getThreadAllocSize()
        Debug.stopAllocCounting()
        Log.i(TAG, "submit: ${objects.toDouble() / measured} objects, ${bytes.toDouble() / measured} bytes per frame")
        bridge.surfaceDestroyed()
        sink.close()
        assertTrue(
            "objects per frame: ${objects.toDouble() / measured}",
            objects <= measured * MAX_FRAMEWORK_OBJECTS_PER_FRAME,
        )
    }

    /**
     * The decoder production picks (hardware first) decodes the clip. Skipped
     * only when that decoder is an emulator's host-backed one and it decoded
     * less than the clip, which says something about the emulator image, not
     * about devices.
     */
    @Test
    fun thePreferredDecoderDecodesTheClip() {
        val bridge = SurfaceBridge()
        bridge.surfaceAvailable(reader.surface)
        val sink = MediaCodecVideoSink(bridge, preferred)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime() + 50_000_000L
        for (i in clip.accessUnits.indices) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        val complete = eventually(decodeTimeoutMillis) { sink.accounted() >= clip.accessUnits.size - HELD_BACK }
        val outcome = state(sink)
        report(sink, "preferred decoder")
        bridge.surfaceDestroyed()
        sink.close()
        val name = started.lastOrNull().orEmpty()
        assumeFalse("emulator decoder $name did not decode the clip: $outcome", !complete && ".goldfish." in name)
        assertTrue("preferred decoder $name: $outcome\n${codecLog()}", complete)
        assertTrue("frames shown: $outcome", sink.renderedFrames >= 1)
    }

    /**
     * Timing: a stream paced at its frame rate is shown frame for frame, with
     * nothing skipped or dropped. Only meaningful when the decoder is clearly
     * faster than real time, so it measures that first and skips otherwise.
     */
    @Test
    fun aRealTimeStreamIsShownFrameForFrame() {
        val rate = measureDecodeRate(preferred)
        assumeTrue(
            "decoder ${started.lastOrNull()} runs at ${rate.fixed(1)} fps, below $REAL_TIME_FPS fps",
            rate >= REAL_TIME_FPS,
        )
        images.set(0)
        val bridge = SurfaceBridge()
        bridge.surfaceAvailable(reader.surface)
        val sink = MediaCodecVideoSink(bridge, preferred)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val start = System.nanoTime() + 100_000_000L
        for (i in clip.accessUnits.indices) {
            sleepUntil(start + i * frameNanos - 20_000_000L)
            submit(sink, i, start)
        }
        expect(sink, "every frame shown", REAL_TIME_TIMEOUT_MILLIS) {
            sink.renderedFrames >= clip.accessUnits.size - HELD_BACK
        }
        expect(sink, "frames reached the surface", REAL_TIME_TIMEOUT_MILLIS) {
            images.get() >= clip.accessUnits.size / 2
        }
        report(sink, "real time")
        bridge.surfaceDestroyed()
        sink.close()
        assertEquals(0L, sink.skippedFrames)
        assertEquals(0L, sink.droppedUnits)
    }

    /**
     * Decodes the clip's first group of pictures as fast as [decoders] take it
     * and returns frames decoded per second. One group only: the emulators'
     * goldfish decoder stops for good when a second key frame arrives while
     * earlier frames are still queued, which a live stream never does.
     */
    private fun measureDecodeRate(decoders: VideoDecoderFactory): Double {
        val group = clip.accessUnits.indices.firstOrNull { it > 0 && isKeyFrame(clip.accessUnits[it]) }
        val frames = group ?: clip.accessUnits.size
        val bridge = SurfaceBridge()
        bridge.surfaceAvailable(reader.surface)
        val sink = MediaCodecVideoSink(bridge, decoders)
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val begin = System.nanoTime()
        for (i in 0 until frames) submit(sink, i, begin)
        eventually(decodeTimeoutMillis) { sink.decoded() >= frames - HELD_BACK }
        val seconds = (System.nanoTime() - begin) / 1e9
        val rate = sink.decoded() / seconds
        Log.i(TAG, "decoder ${started.lastOrNull()}: ${sink.decoded()} of $frames frames in ${seconds.fixed(2)} s")
        Log.i(TAG, "decode rate ${rate.fixed(1)} fps; the clip plays at ${TestClip.FRAME_RATE} fps")
        bridge.surfaceDestroyed()
        sink.close()
        return rate
    }

    private fun submit(
        sink: MediaCodecVideoSink,
        index: Int,
        start: Long,
    ) {
        val unit = clip.accessUnits[index]
        sink.submit(unit, 0, unit.size, start + index * frameNanos)
    }

    private fun isKeyFrame(unit: ByteArray) = AnnexB.isKeyFrame(VideoCodec.H264, unit, 0, unit.size)

    /** Frames that came out of the decoder: shown, or skipped while catching up. */
    private fun MediaCodecVideoSink.decoded() = renderedFrames + skippedFrames

    /** Access units the sink is done with: decoded or dropped. */
    private fun MediaCodecVideoSink.accounted() = decoded() + droppedUnits

    /** Waits for [condition]; on timeout fails with the sink's counters and the codec log. */
    private fun expect(
        sink: MediaCodecVideoSink,
        what: String,
        timeoutMillis: Long = decodeTimeoutMillis,
        condition: () -> Boolean,
    ) {
        if (!eventually(timeoutMillis, condition)) {
            fail("$what (after $timeoutMillis ms): ${state(sink)}\n${codecLog()}")
        }
    }

    private fun Double.fixed(decimals: Int) = "%.${decimals}f".format(Locale.ROOT, this)

    private fun report(
        sink: MediaCodecVideoSink,
        label: String,
    ) = Log.i(TAG, "$label: ${state(sink)}")

    private fun state(sink: MediaCodecVideoSink) =
        "${sink.renderedFrames} shown, ${sink.skippedFrames} skipped, ${sink.droppedUnits} dropped, " +
            "${images.get()} images, decoders $started"

    /** The recent log lines of the decoder stack, for failures on devices we cannot inspect (CI). */
    private fun codecLog(): String =
        shell("logcat -d -t $LOG_LINES")
            .lineSequence()
            .filter { line -> CODEC_LOG_TAGS.any { line.contains(it) } }
            .toList()
            .takeLast(LOG_LINES_SHOWN)
            .joinToString("\n")

    /** Starts the first of [names] that works, or the production choice when null; records what it started. */
    private inner class RecordingDecoders(
        private val names: List<String>?,
    ) : VideoDecoderFactory {
        override fun start(
            codec: VideoCodec,
            format: MediaFormat,
            surface: Surface,
        ): MediaCodec? {
            val decoder =
                if (names == null) {
                    VideoDecoders.start(codec, format, surface)
                } else {
                    VideoDecoders.startFirst(names, format, surface)
                }
            return decoder?.also { started += it.name }
        }
    }

    private class LinkedSizes : SurfaceBridge.VideoSizeListener {
        private val seen = ArrayList<Pair<Int, Int>>()

        @Synchronized
        override fun onVideoSizeChanged(
            width: Int,
            height: Int,
        ) {
            seen += width to height
        }

        fun await(
            width: Int,
            height: Int,
        ): Boolean = eventually { synchronized(this) { seen.contains(width to height) } }
    }

    private companion object {
        const val TAG = "VideoSinkTest"

        /** Frames sent before the surface exists. */
        const val BACKLOG = 15

        /**
         * Frames a decoder may keep until more input pushes them out (its output delay; a mirroring
         * stream has no end-of-stream to flush them). c2.android.avc.decoder keeps 2, the emulators'
         * goldfish decoder none; generous for hardware decoders that keep a few reference frames.
         */
        const val HELD_BACK = 8

        /** How much slower than real time a decoder may be before the correctness tests time out. */
        const val SLOWDOWN_ALLOWED = 20

        /** Decoder creation and the first output, on top of decoding itself. */
        const val START_UP_MILLIS = 10_000L

        /** Decode rate below which [aRealTimeStreamIsShownFrameForFrame] is skipped: twice the clip's. */
        const val REAL_TIME_FPS = 2 * TestClip.FRAME_RATE

        /** The paced clip lasts 2 s; a decoder above [REAL_TIME_FPS] is done shortly after. */
        const val REAL_TIME_TIMEOUT_MILLIS = 10_000L

        /** Framework objects per queued input buffer (measured: see the log line); generous on purpose. */
        const val MAX_FRAMEWORK_OBJECTS_PER_FRAME = 8

        /** Surface losses while a flooding sender waits for decoder input, and the time between them. */
        const val SURFACE_LOSSES = 5
        const val FLOOD_MILLIS = 400L

        /**
         * A surface loss waits for the decoder to stop, never for a submit's input wait. Stopping the
         * OMX software decoder of CI's API 31 x86 image alone took 252 ms; a submit that held the lock
         * through its wait added up to 500 ms on top of that, which this bound still catches.
         */
        const val MAX_SURFACE_LOSS_MILLIS = 450L

        const val LOG_LINES = 3000
        const val LOG_LINES_SHOWN = 40
        val CODEC_LOG_TAGS = listOf("AirPlayVideo", "CCodec", "Codec2", "MediaCodec", "ACodec", "OMX", "goldfish")
    }
}
