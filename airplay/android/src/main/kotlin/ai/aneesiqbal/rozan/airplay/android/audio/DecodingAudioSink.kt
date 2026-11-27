package ai.aneesiqbal.rozan.airplay.android.audio

import ai.aneesiqbal.rozan.airplay.android.session.MediaCounters
import ai.aneesiqbal.rozan.airplay.android.session.PacketRing
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import android.media.AudioAttributes
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import android.media.AudioFormat as PlatformAudioFormat

/**
 * Plays one audio stream through a single 16-bit PCM [AudioTrack].
 *
 * The receiver's thread hands over encoded units with [submit]; they wait in a
 * bounded queue and a dedicated audio-priority thread decodes them
 * ([decode], implemented per codec) and writes the PCM out, so the network
 * thread never blocks on audio output.
 *
 * Timing: the track starts playing when the first unit's presentation time is
 * reached. From then on [AudioClock] compares where frames should be heard
 * with where `AudioTrack.getTimestamp` says they were heard, and the sink
 * drops or pads a few frames to stay in sync with the sender (and with the
 * video when mirroring).
 *
 * Nothing on the per-unit path allocates: units, PCM and silence live in
 * buffers allocated once per output configuration.
 */
internal abstract class DecodingAudioSink(
    sampleRate: Int,
    channels: Int,
    private val contentType: ContentType,
    threadName: String,
) : AudioSink {
    /** Picks the output's content type when a track is created. */
    fun interface ContentType {
        /** `AudioAttributes.CONTENT_TYPE_*` for the current session. */
        fun current(): Int
    }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val queue = PacketRing(QUEUE_BYTES, QUEUE_UNITS)

    // Guarded by lock.
    private var closed = false
    private var flushRequested = false
    private var threadStarted = false
    private var volume = 1f
    private var track: AudioTrack? = null

    /** Set by [flush] and [close] so the audio thread stops writing at once. */
    @Volatile private var interrupted = false

    // Audio thread only.
    private val unit = ByteArray(MAX_UNIT_BYTES)
    private var unitLength = 0
    private var unitPresentationNanos = 0L

    /** Output sample rate; a decoder may change it when its output format changes. */
    protected var outputRate: Int = sampleRate.coerceIn(MIN_RATE, MAX_RATE)
        private set

    /** Output channel count; see [outputRate]. */
    protected var outputChannels: Int = channels.coerceIn(1, MAX_CHANNELS)
        private set

    private var started = false
    private var framesWritten = 0L
    private var clock = AudioClock(outputRate)
    private var silence = ShortArray(0)
    private val timestamp = AudioTimestamp()

    /** PCM frames written to the output so far (all tracks of this sink). */
    @Volatile
    var framesRendered: Long = 0L
        private set

    /** Decoded frames left out to catch up with the sender's clock. */
    @Volatile
    var framesSkipped: Long = 0L
        private set

    /** Units dropped: queue full, too large, or undecodable. */
    @Volatile
    var droppedUnits: Long = 0L
        private set

    /** Last measured sync error in nanoseconds (heard − wanted). */
    val syncErrorNanos: Long get() = clock.lastErrorNanos

    private val thread = Thread({ runAudioThread() }, threadName)

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        lock.withLock {
            if (closed) return
            if (!threadStarted) {
                threadStarted = true
                thread.start()
            }
            val fits = length in 1..MAX_UNIT_BYTES && offset >= 0 && offset + length <= accessUnit.size
            if (fits && queue.offer(accessUnit, offset, length, presentationNanos)) {
                wake.signal()
            } else {
                droppedUnits++
            }
        }
    }

    override fun setVolume(linear: Float) {
        lock.withLock {
            volume = if (linear.isNaN()) 0f else linear.coerceIn(0f, 1f)
            track?.setVolume(volume)
        }
    }

    override fun flush() {
        lock.withLock {
            queue.clear()
            flushRequested = true
            interrupted = true
            pauseTrackLocked()
            wake.signal()
        }
    }

    override fun close() {
        val join =
            lock.withLock {
                if (closed) return
                closed = true
                interrupted = true
                pauseTrackLocked()
                wake.signal()
                threadStarted
            }
        if (join) thread.join()
    }

    /** Decodes one unit and passes its PCM to [render]. Runs on the audio thread. */
    protected abstract fun decode(
        unit: ByteArray,
        length: Int,
        presentationNanos: Long,
    )

    /** Drops decoder state after a flush. Runs on the audio thread. */
    protected open fun onFlush() = Unit

    /** Releases decoder resources. Runs on the audio thread, last. */
    protected open fun onRelease() = Unit

    /** The decoder's output format changed; the next [render] uses a new track. */
    protected fun changeOutputFormat(
        sampleRate: Int,
        channels: Int,
    ) {
        val rate = sampleRate.coerceIn(MIN_RATE, MAX_RATE)
        val count = channels.coerceIn(1, MAX_CHANNELS)
        if (rate == outputRate && count == outputChannels) return
        outputRate = rate
        outputChannels = count
        releaseTrack()
    }

    /**
     * Plays [frames] interleaved frames from [samples] that should be heard
     * from [presentationNanos] on. Runs on the audio thread.
     */
    protected fun render(
        samples: ShortArray,
        frames: Int,
        presentationNanos: Long,
    ) {
        if (frames <= 0 || interrupted) return
        val output = track ?: createTrack() ?: return
        if (!started) {
            start(output, samples, frames, presentationNanos)
            return
        }
        measure(output)
        val first = clock.takeDrop(frames)
        if (first > 0) {
            framesSkipped += first
            if (first == frames) return
        }
        val pad = clock.takePad()
        if (pad > 0) writeSilence(output, pad)
        clock.onWrite(framesWritten, presentationNanos + first * NANOS_PER_SECOND / outputRate)
        write(output, samples, first, frames - first)
    }

    private fun start(
        output: AudioTrack,
        samples: ShortArray,
        frames: Int,
        presentationNanos: Long,
    ) {
        clock.reset()
        clock.onWrite(0L, presentationNanos)
        // Not playing yet: a blocking write would wait forever if the unit exceeded the buffer.
        write(output, samples, 0, frames, AudioTrack.WRITE_NON_BLOCKING)
        if (!waitUntil(presentationNanos)) return
        lock.withLock {
            if (interrupted || track !== output) return
            output.play()
        }
        started = true
    }

    /** Sleeps until [presentationNanos] (bounded); false if flushed or closed meanwhile. */
    private fun waitUntil(presentationNanos: Long): Boolean =
        lock.withLock {
            val now = System.nanoTime()
            var remaining = (presentationNanos - now).coerceAtMost(MAX_START_WAIT_NANOS)
            while (remaining > 0 && !interrupted) remaining = wake.awaitNanos(remaining)
            !interrupted
        }

    /** Reads the output position when the clock asks for it, so it can schedule a drop or pad. */
    private fun measure(output: AudioTrack) {
        if (!clock.timestampDue(framesWritten) || !output.getTimestamp(timestamp)) return
        clock.onTimestamp(timestamp.framePosition, timestamp.nanoTime, framesWritten)
    }

    private fun write(
        output: AudioTrack,
        samples: ShortArray,
        firstFrame: Int,
        frames: Int,
        mode: Int = AudioTrack.WRITE_BLOCKING,
    ) {
        if (interrupted) return
        val written = output.write(samples, firstFrame * outputChannels, frames * outputChannels, mode)
        if (written < 0) {
            Log.w(TAG, "AudioTrack write failed ($written); recreating the output")
            releaseTrack()
            return
        }
        val framesOut = written / outputChannels
        framesWritten += framesOut
        framesRendered += framesOut
        MediaCounters.audioFramesWritten.addAndGet(framesOut.toLong())
    }

    private fun writeSilence(
        output: AudioTrack,
        frames: Int,
    ) {
        var left = frames
        val chunk = silence.size / outputChannels
        while (left > 0 && chunk > 0 && !interrupted) {
            val n = minOf(left, chunk)
            write(output, silence, 0, n)
            left -= n
        }
    }

    private fun createTrack(): AudioTrack? {
        val mask = channelMask(outputChannels) ?: return null
        val created = buildTrack(mask) ?: return null
        val accepted =
            lock.withLock {
                if (!closed) {
                    created.setVolume(volume)
                    track = created
                }
                !closed
            }
        if (!accepted) {
            created.release()
            return null
        }
        started = false
        framesWritten = 0L
        if (clock.sampleRate != outputRate) clock = AudioClock(outputRate)
        val silenceSamples = outputRate / BUFFER_DIVISOR * outputChannels
        if (silence.size != silenceSamples) silence = ShortArray(silenceSamples)
        return created
    }

    private fun buildTrack(mask: Int): AudioTrack? {
        val frameBytes = outputChannels * BYTES_PER_SAMPLE
        val minBytes = AudioTrack.getMinBufferSize(outputRate, mask, PlatformAudioFormat.ENCODING_PCM_16BIT)
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(contentType.current())
                .build()
        val format =
            PlatformAudioFormat
                .Builder()
                .setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(outputRate)
                .setChannelMask(mask)
                .build()
        val built =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBytes, outputRate / BUFFER_DIVISOR * frameBytes))
                    .build()
            } catch (e: UnsupportedOperationException) {
                Log.e(TAG, "cannot create AudioTrack ${outputRate}Hz x$outputChannels", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "cannot create AudioTrack ${outputRate}Hz x$outputChannels", e)
                null
            }
        if (built != null && built.state != AudioTrack.STATE_INITIALIZED) {
            built.release()
            return null
        }
        return built
    }

    private fun releaseTrack() {
        val old =
            lock.withLock {
                val current = track
                track = null
                current
            }
        old?.release()
        started = false
    }

    private fun pauseTrackLocked() {
        try {
            track?.pause()
        } catch (ignored: IllegalStateException) {
            // Not initialised or already released: nothing is playing, nothing to interrupt.
        }
    }

    private fun runAudioThread() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot raise audio thread priority", e)
        }
        try {
            while (takeUnit()) {
                if (unitLength > 0) decodeSafely()
            }
        } finally {
            releaseTrack()
            onRelease()
        }
    }

    /** Waits for the next unit and copies it to [unit]; handles flushes; false once closed. */
    private fun takeUnit(): Boolean {
        var flush = false
        val open =
            lock.withLock {
                while (!closed && !flushRequested && queue.isEmpty) wake.await()
                unitLength = 0
                if (!closed && flushRequested) {
                    flushRequested = false
                    interrupted = false
                    flush = true
                } else if (!closed) {
                    unitLength = queue.copyPeek(unit)
                    unitPresentationNanos = queue.peekTimestamp()
                    queue.poll()
                }
                !closed
            }
        if (open && flush) {
            releaseTrack()
            onFlush()
        }
        return open
    }

    // Units come from the network: a malformed one must cost that unit, not the process.
    @Suppress("TooGenericExceptionCaught")
    private fun decodeSafely() {
        try {
            decode(unit, unitLength, unitPresentationNanos)
        } catch (e: IOException) {
            dropUndecodable(e)
        } catch (e: RuntimeException) {
            dropUndecodable(e)
        }
    }

    private fun dropUndecodable(cause: Exception) {
        if (droppedUnits == 0L) Log.w(TAG, "dropping undecodable audio unit", cause)
        droppedUnits++
    }

    companion object {
        private const val TAG = "AirPlayAudio"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BYTES_PER_SAMPLE = 2
        private const val MIN_RATE = 8_000
        private const val MAX_RATE = 192_000
        private const val MAX_CHANNELS = 8

        /** Largest encoded unit accepted (ALAC/PCM packets of 352–4096 frames are far smaller). */
        const val MAX_UNIT_BYTES = 64 * 1024
        private const val QUEUE_BYTES = 1024 * 1024
        private const val QUEUE_UNITS = 1024

        /** Output buffer of 1/10 s (100 ms) at least. */
        private const val BUFFER_DIVISOR = 10

        /** Never wait longer than this for the first unit's time (a broken clock mapping). */
        private const val MAX_START_WAIT_NANOS = 5_000_000_000L

        /** Output channel mask for [channels], or null when unsupported. */
        fun channelMask(channels: Int): Int? =
            when (channels) {
                1 -> PlatformAudioFormat.CHANNEL_OUT_MONO
                2 -> PlatformAudioFormat.CHANNEL_OUT_STEREO
                4 -> PlatformAudioFormat.CHANNEL_OUT_QUAD
                6 -> PlatformAudioFormat.CHANNEL_OUT_5POINT1
                8 -> PlatformAudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> null
            }

        /** Content type for audio-only streams vs. the audio of a mirroring session. */
        fun contentTypeFor(mirroring: Boolean): Int =
            if (mirroring) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC
    }
}
