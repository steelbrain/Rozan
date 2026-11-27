package ai.aneesiqbal.rozan.airplay.android.video

import ai.aneesiqbal.rozan.airplay.android.session.MediaCounters
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decodes one mirroring stream with [MediaCodec] straight into the mirroring
 * [Surface] (a `SurfaceView`, so frames go to a hardware overlay without
 * copies). Each frame is released with its presentation time, so the display
 * shows it at the vsync the sender intended.
 *
 * Start-up race: frames can arrive before the activity's surface exists. The
 * decoder is only ever configured with the real surface (changing the output
 * surface of a running codec is broken on several Fire TV models), so until
 * then the codec configuration and every access unit since the last key frame
 * wait in a bounded [PreSurfaceBuffer]. Once the surface exists, a decoder
 * starts and is fed that backlog; frames older than the newest buffered one
 * are decoded but not shown, so the picture jumps straight to the present.
 *
 * Threads: [configure], [submit] and [close] come from the receiver's
 * mirroring thread; surface changes come from the main thread; a dedicated
 * thread owns the decoder's lifecycle and its output. [submit] feeds the
 * decoder directly once the backlog is drained and allocates nothing. It waits
 * for a free decoder input buffer without holding the sink's lock, so a slow
 * or stalled decoder never holds up surface changes or [close].
 *
 * The video thread blocks in the decoder's output queue. While the decoder is
 * fed directly it waits up to [DIRECT_OUTPUT_TIMEOUT_US] (it wakes for every
 * frame anyway); a stop request cuts that wait short by queueing an
 * end-of-stream marker, which the decoder answers at once. Only while the
 * backlog is fed does it poll every [OUTPUT_TIMEOUT_US], because input buffers
 * come free without any output to wake it.
 */
internal class MediaCodecVideoSink(
    private val surfaces: SurfaceBridge,
    private val decoders: VideoDecoderFactory = VideoDecoders,
) : VideoSink,
    SurfaceBridge.Consumer {
    private class StreamFormat(
        val codec: VideoCodec,
        val parameterSets: ByteArray,
        val width: Int,
        val height: Int,
    ) {
        fun sameAs(other: StreamFormat): Boolean =
            codec == other.codec &&
                width == other.width &&
                height == other.height &&
                parameterSets.contentEquals(other.parameterSets)
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Guarded by lock.
    private var format: StreamFormat? = null
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var failedFormat: StreamFormat? = null
    private var failedSurface: Surface? = null
    private var closed = false
    private val pending = PreSurfaceBuffer()

    // Written under lock, read without it by the video thread's output loop.
    @Volatile private var direct = false

    @Volatile private var stopRequested = false

    // Video thread only.
    private val info = MediaCodec.BufferInfo()
    private var catchUpUntilUs = Long.MIN_VALUE

    /** Frames released to the surface for display. */
    @Volatile
    var renderedFrames: Long = 0L
        private set

    /** Frames decoded but skipped while catching up after start. */
    @Volatile
    var skippedFrames: Long = 0L
        private set

    /** Access units that never reached the decoder. */
    @Volatile
    var droppedUnits: Long = 0L
        private set

    private val thread = Thread({ runVideoThread() }, "airplay-video")

    init {
        thread.start()
        surfaces.register(this)
    }

    override fun configure(
        codec: VideoCodec,
        parameterSets: ByteArray,
        width: Int,
        height: Int,
    ) {
        val next = StreamFormat(codec, parameterSets.copyOf(), width, height)
        lock.withLock {
            val current = format
            if (closed || (current != null && current.sameAs(next))) return
            format = next
            // Frames from here on belong to the new configuration and start at its key frame.
            pending.reset(awaitKeyFrame = true)
            direct = false
            if (this.codec != null) requestStopLocked()
            changed.signalAll()
        }
    }

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        if (length <= 0 || offset < 0 || offset + length > accessUnit.size) return
        var attempts = 0
        while (attempts < MAX_INPUT_ATTEMPTS) {
            // Buffered or dropped under the lock, or the decoder to feed: its input is waited for without it.
            val running = lock.withLock { routeLocked(accessUnit, offset, length, presentationNanos) } ?: return
            var failure: IllegalStateException? = null
            val index =
                try {
                    running.dequeueInputBuffer(INPUT_TIMEOUT_US)
                } catch (e: IllegalStateException) {
                    failure = e
                    MediaCodec.INFO_TRY_AGAIN_LATER
                }
            val outcome =
                lock.withLock {
                    feedLocked(
                        running,
                        index,
                        failure,
                        accessUnit,
                        offset,
                        length,
                        presentationNanos,
                    )
                }
            if (outcome == FED) return
            if (outcome == TIMED_OUT) attempts++
        }
        // The decoder took no input for a while (stalled output); drop rather than stall the sender forever.
        lock.withLock { droppedUnits++ }
    }

    /**
     * After waiting for input of [running] without the lock: feeds the unit into [index] and returns
     * [FED], or [TIMED_OUT] when no buffer came free, or [REROUTE] when the decoder was stopped,
     * replaced or switched to the backlog meanwhile (the unit then goes the way [routeLocked] says).
     * A decoder that failed is restarted; its unit counts as fed, like everything it held.
     */
    private fun feedLocked(
        running: MediaCodec,
        index: Int,
        failure: IllegalStateException?,
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ): Int {
        if (closed || codec !== running || !direct) return REROUTE
        if (failure != null) {
            restartLocked(failure)
            return FED
        }
        if (index < 0) return TIMED_OUT
        try {
            queueInput(running, index, accessUnit, offset, length, presentationNanos)
        } catch (e: IllegalStateException) {
            restartLocked(e)
        }
        return FED
    }

    /**
     * Buffers or drops the unit and returns null, or returns the decoder to feed it to directly.
     */
    private fun routeLocked(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ): MediaCodec? {
        val current = format
        val running = codec
        if (closed || current == null) {
            droppedUnits++
            return null
        }
        if (!direct || running == null) {
            val keyFrame = AnnexB.isKeyFrame(current.codec, accessUnit, offset, length)
            if (!pending.add(accessUnit, offset, length, presentationNanos, keyFrame)) droppedUnits++
            return null
        }
        return running
    }

    private fun queueInput(
        decoder: MediaCodec,
        index: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        val buffer = decoder.getInputBuffer(index)
        if (buffer == null || length > buffer.capacity()) {
            decoder.queueInputBuffer(index, 0, 0, 0L, 0)
            droppedUnits++
            return
        }
        buffer.clear()
        buffer.put(data, offset, length)
        decoder.queueInputBuffer(index, 0, length, presentationNanos / NANOS_PER_MICRO, 0)
    }

    override fun attachSurface(surface: Surface) {
        lock.withLock {
            if (closed || this.surface === surface) return
            this.surface = surface
            if (codec != null) requestStopLocked()
            changed.signalAll()
        }
    }

    override fun detachSurface() {
        lock.withLock {
            surface = null
            if (codec == null) return
            requestStopLocked()
            direct = false
            // Without the surface the decoder's state is lost; resume at the next key frame.
            pending.reset(awaitKeyFrame = true)
            changed.signalAll()
            while (codec != null) changed.await()
        }
    }

    override fun close() {
        surfaces.unregister(this)
        lock.withLock {
            closed = true
            requestStopLocked()
            changed.signalAll()
        }
        thread.join()
    }

    private fun restartLocked(cause: Exception) {
        Log.w(TAG, "decoder failed; restarting at the next key frame", cause)
        direct = false
        pending.reset(awaitKeyFrame = true)
        stopRequested = true
    }

    /**
     * Asks the video thread to stop the running decoder, and cuts its output wait short: an end of
     * stream queued now comes out of the decoder at once (after the frames it holds), while the
     * thread would otherwise sleep until the wait times out. Units from here on wait in the backlog:
     * nothing may follow the end of stream into this decoder.
     */
    private fun requestStopLocked() {
        stopRequested = true
        direct = false
        val running = codec ?: return
        try {
            val index = running.dequeueInputBuffer(0L)
            if (index >= 0) running.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (ignored: IllegalStateException) {
            // Already stopping or failed, or the sender is waiting for input: the decoder is busy and
            // its next output (or the wait's time-out) wakes the thread.
        }
    }

    private fun runVideoThread() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (true) {
            val running = awaitStartableCodec() ?: break
            try {
                pump(running)
            } catch (e: IllegalStateException) {
                lock.withLock { restartLocked(e) }
            }
            lock.withLock {
                releaseCodecLocked(running)
                changed.signalAll()
            }
        }
    }

    /** Blocks until a decoder can run (format and surface known), starts it; null once closed. */
    private fun awaitStartableCodec(): MediaCodec? =
        lock.withLock {
            var started: MediaCodec? = null
            while (!closed && started == null) {
                started = tryStartLocked()
                if (started == null) changed.await()
            }
            started
        }

    private fun tryStartLocked(): MediaCodec? {
        val current = format
        val target = surface
        val blocked = codec != null || (failedFormat === current && failedSurface === target)
        if (blocked || current == null || target == null) return null
        val mediaFormat = VideoDecoders.format(current.codec, current.parameterSets, current.width, current.height)
        val started = decoders.start(current.codec, mediaFormat, target)
        if (started == null) {
            Log.e(TAG, "no decoder accepts this ${current.codec} stream")
            failedFormat = current
            failedSurface = target
            return null
        }
        codec = started
        stopRequested = false
        direct = false
        catchUpUntilUs = if (pending.isEmpty) Long.MIN_VALUE else pending.lastPresentationNanos() / NANOS_PER_MICRO
        return started
    }

    private fun releaseCodecLocked(running: MediaCodec) {
        try {
            running.stop()
        } catch (ignored: IllegalStateException) {
            // Already in the error state; release() below still frees it.
        }
        running.release()
        codec = null
        direct = false
    }

    /** Feeds the backlog and renders output until asked to stop. */
    private fun pump(running: MediaCodec) {
        while (!stopRequested) {
            val feeding = !direct
            val fed = feeding && feedPending(running)
            val timeout =
                when {
                    fed -> 0L
                    feeding -> OUTPUT_TIMEOUT_US
                    else -> DIRECT_OUTPUT_TIMEOUT_US
                }
            val index = running.dequeueOutputBuffer(info, timeout)
            if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // The marker of a stop request: nothing to show.
                    running.releaseOutputBuffer(index, false)
                } else {
                    releaseOutput(running, index)
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                reportSize(running.outputFormat)
            }
        }
    }

    /**
     * Moves one buffered unit into the decoder; switches to direct feeding once
     * the backlog is empty and a key frame has gone in.
     */
    private fun feedPending(running: MediaCodec): Boolean =
        lock.withLock {
            if (stopRequested || direct) return@withLock false
            if (pending.isEmpty) {
                // A fresh decoder (after a surface loss, a new format or an error) cannot use
                // frames that refer to an earlier key frame. Until the next key frame they keep
                // going through the buffer, which drops them.
                if (!pending.awaitingKeyFrame) direct = true
                return@withLock false
            }
            val index = running.dequeueInputBuffer(0L)
            if (index < 0) return@withLock false
            val buffer = running.getInputBuffer(index)
            val length = pending.peekLength()
            if (buffer == null || length > buffer.capacity()) {
                running.queueInputBuffer(index, 0, 0, 0L, 0)
                droppedUnits++
            } else {
                buffer.clear()
                pending.copyPeek(buffer)
                val flags = if (pending.peekKeyFrame()) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                running.queueInputBuffer(index, 0, length, pending.peekPresentationNanos() / NANOS_PER_MICRO, flags)
            }
            pending.poll()
            true
        }

    private fun releaseOutput(
        running: MediaCodec,
        index: Int,
    ) {
        if (info.presentationTimeUs >= catchUpUntilUs) {
            running.releaseOutputBuffer(index, info.presentationTimeUs * NANOS_PER_MICRO)
            renderedFrames++
            MediaCounters.videoFramesRendered.incrementAndGet()
        } else {
            running.releaseOutputBuffer(index, false)
            skippedFrames++
        }
    }

    private fun reportSize(output: MediaFormat) {
        var width = output.getInteger(MediaFormat.KEY_WIDTH)
        var height = output.getInteger(MediaFormat.KEY_HEIGHT)
        if (output.containsKey(KEY_CROP_RIGHT) && output.containsKey(KEY_CROP_LEFT)) {
            width = output.getInteger(KEY_CROP_RIGHT) - output.getInteger(KEY_CROP_LEFT) + 1
        }
        if (output.containsKey(KEY_CROP_BOTTOM) && output.containsKey(KEY_CROP_TOP)) {
            height = output.getInteger(KEY_CROP_BOTTOM) - output.getInteger(KEY_CROP_TOP) + 1
        }
        surfaces.onVideoSize(width, height)
    }

    private companion object {
        const val TAG = "AirPlayVideo"
        const val NANOS_PER_MICRO = 1_000L

        /** One wait for a free input buffer; [submit] retries up to [MAX_INPUT_ATTEMPTS] times. */
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_INPUT_ATTEMPTS = 50

        /** Outcomes of [feedLocked]. */
        const val FED = 0
        const val TIMED_OUT = 1
        const val REROUTE = 2

        /** One output wait while the backlog is fed: free input buffers are noticed this often. */
        const val OUTPUT_TIMEOUT_US = 10_000L

        /**
         * Upper bound on one output wait while fed directly. Frames and stop requests (through the
         * end-of-stream marker) end it early; it only runs out on a decoder that takes no input.
         */
        const val DIRECT_OUTPUT_TIMEOUT_US = 100_000L

        // MediaFormat.KEY_CROP_* constants exist only from API 33; the keys are older.
        const val KEY_CROP_LEFT = "crop-left"
        const val KEY_CROP_RIGHT = "crop-right"
        const val KEY_CROP_TOP = "crop-top"
        const val KEY_CROP_BOTTOM = "crop-bottom"
    }
}
