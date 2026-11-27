package ai.aneesiqbal.rozan.airplay.android.debug

import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.sin

/**
 * Streams the bundled test clip into the platform's video sink in a loop, at
 * the clip's frame rate, like a mirroring sender would. Debug builds only.
 */
internal class FakeMirrorSession(
    private val platform: ReceiverPlatform,
    private val clip: TestClip,
) {
    @Volatile private var running = true
    private val thread = Thread({ run() }, "airplay-debug-mirror")

    fun start() = thread.start()

    fun stop() {
        running = false
        LockSupport.unpark(thread)
        thread.join()
    }

    private fun run() {
        val sink: VideoSink = platform.createVideoSink()
        sink.configure(VideoCodec.H264, clip.parameterSets, clip.width, clip.height)
        val frameNanos = NANOS_PER_SECOND / TestClip.FRAME_RATE
        val start = System.nanoTime() + LEAD_NANOS
        var index = 0L
        try {
            while (running) {
                val unit = clip.accessUnits[(index % clip.accessUnits.size).toInt()]
                val presentation = start + index * frameNanos
                // A test source pacing itself like a live sender: each frame arrives shortly before it is due.
                waitUntil(presentation - LEAD_NANOS)
                if (!running) break
                sink.submit(unit, 0, unit.size, presentation)
                index++
            }
        } finally {
            sink.close()
        }
    }

    private fun waitUntil(nanos: Long) {
        var remaining = nanos - System.nanoTime()
        while (running && remaining > 0) {
            LockSupport.parkNanos(remaining)
            remaining = nanos - System.nanoTime()
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val LEAD_NANOS = 40_000_000L
    }
}

/**
 * Plays a quiet 440 Hz tone through the platform's PCM sink, in AirPlay-sized
 * packets (352 frames, 44.1 kHz stereo, big-endian), with presentation times
 * half a second ahead like a buffered audio sender. Debug builds only.
 */
internal class FakeAudioSession(
    private val platform: ReceiverPlatform,
) {
    @Volatile private var running = true
    private val thread = Thread({ run() }, "airplay-debug-audio")

    fun start() = thread.start()

    fun stop() {
        running = false
        LockSupport.unpark(thread)
        thread.join()
    }

    private fun run() {
        val sink: AudioSink = platform.createAudioSink(AudioFormat.Pcm(RATE, CHANNELS, FRAMES))
        val packet = ByteArray(FRAMES * CHANNELS * 2)
        val start = System.nanoTime() + LATENCY_NANOS
        var frame = 0L
        try {
            while (running) {
                for (i in 0 until FRAMES) {
                    val sample = (AMPLITUDE * sin(2 * PI * TONE_HZ * (frame + i) / RATE)).toInt()
                    for (c in 0 until CHANNELS) {
                        val at = (i * CHANNELS + c) * 2
                        packet[at] = (sample shr 8).toByte()
                        packet[at + 1] = sample.toByte()
                    }
                }
                val presentation = start + frame * NANOS_PER_SECOND / RATE
                // Stay about one latency ahead of the presentation time, like a sender with a jitter buffer.
                var remaining = presentation - LATENCY_NANOS - System.nanoTime()
                while (running && remaining > 0) {
                    LockSupport.parkNanos(remaining)
                    remaining = presentation - LATENCY_NANOS - System.nanoTime()
                }
                if (!running) break
                sink.submit(packet, 0, packet.size, presentation)
                frame += FRAMES
            }
        } finally {
            sink.close()
        }
    }

    companion object {
        private const val RATE = 44_100
        private const val CHANNELS = 2
        private const val FRAMES = 352
        private const val TONE_HZ = 440.0
        private const val AMPLITUDE = 3000.0
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val LATENCY_NANOS = 500_000_000L

        /** A plain square "cover" in the accent colour with a darker disc, as JPEG bytes. */
        fun artwork(): ByteArray {
            val size = 600
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(0x2A, 0x4A, 0x6B))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(0x7D, 0xC0, 0xFF)
            canvas.drawCircle(size / 2f, size / 2f, size * 0.32f, paint)
            paint.color = Color.rgb(0x0F, 0x10, 0x13)
            canvas.drawCircle(size / 2f, size / 2f, size * 0.06f, paint)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            return out.toByteArray()
        }
    }
}
