package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverEvents
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.fail

/** A [MediaClock] that only moves when told to. Thread-safe. */
internal class ManualClock(
    start: Long,
) : MediaClock {
    private val now = AtomicLong(start)

    override fun nanoTime(): Long = now.get()

    fun set(value: Long) = now.set(value)

    fun advance(nanos: Long): Long = now.addAndGet(nanos)
}

/** A [ClockSync] the test sets directly. */
internal class FakeClockSync(
    @Volatile override var offsetNanos: Long = 0L,
    @Volatile override var roundTripNanos: Long = 0L,
    @Volatile override var isSynced: Boolean = true,
) : ClockSync

/** Waits up to [timeoutMillis] for [condition], failing with [message]. */
internal fun eventually(
    message: String,
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        if (System.nanoTime() - deadline > 0) fail("timed out: $message")
        Thread.sleep(2)
    }
}

/** One [VideoSink.configure] call. */
internal class VideoConfig(
    val codec: VideoCodec,
    val parameterSets: ByteArray,
    val width: Int,
    val height: Int,
)

/** One submitted access unit, copied. */
internal class SubmittedUnit(
    val bytes: ByteArray,
    val presentationNanos: Long,
    val thread: String,
)

/** Records every call; thread-safe. */
internal class RecordingVideoSink : VideoSink {
    val configs = CopyOnWriteArrayList<VideoConfig>()
    val frames = CopyOnWriteArrayList<SubmittedUnit>()

    @Volatile var closed = false

    @Volatile var failOnSubmit: RuntimeException? = null

    override fun configure(
        codec: VideoCodec,
        parameterSets: ByteArray,
        width: Int,
        height: Int,
    ) {
        configs += VideoConfig(codec, parameterSets.copyOf(), width, height)
    }

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        failOnSubmit?.let { throw it }
        frames +=
            SubmittedUnit(
                accessUnit.copyOfRange(offset, offset + length),
                presentationNanos,
                Thread.currentThread().name,
            )
    }

    override fun close() {
        closed = true
    }
}

/** Records every call; thread-safe. */
internal class RecordingAudioSink(
    val format: AudioFormat? = null,
) : AudioSink {
    val units = CopyOnWriteArrayList<SubmittedUnit>()
    val volumes = CopyOnWriteArrayList<Float>()

    @Volatile var flushes = 0

    @Volatile var closed = false

    @Volatile var failOnSubmit: RuntimeException? = null

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        failOnSubmit?.let { throw it }
        units +=
            SubmittedUnit(
                accessUnit.copyOfRange(offset, offset + length),
                presentationNanos,
                Thread.currentThread().name,
            )
    }

    override fun setVolume(linear: Float) {
        volumes += linear
    }

    override fun flush() {
        flushes++
    }

    override fun close() {
        closed = true
    }
}

/** Records listener callbacks; thread-safe. */
internal class RecordingListener : MediaListener {
    val events = CopyOnWriteArrayList<String>()

    override fun onMirroringConnected() {
        events += "connected"
    }

    override fun onMirroringDisconnected(reason: String) {
        events += "disconnected: $reason"
    }

    override fun onAudioFailed(reason: String) {
        events += "audio failed: $reason"
    }
}

/** Hands out recording sinks; optionally fails to create them. */
internal class FakePlatform : ReceiverPlatform {
    val videoSinks = CopyOnWriteArrayList<RecordingVideoSink>()
    val audioSinks = CopyOnWriteArrayList<RecordingAudioSink>()

    @Volatile var failSinks = false

    override fun publish(records: List<ServiceRecord>) = Unit

    override fun unpublish() = Unit

    override fun createVideoSink(): VideoSink {
        if (failSinks) throw IllegalStateException("no decoder")
        return RecordingVideoSink().also { videoSinks += it }
    }

    override fun createAudioSink(format: AudioFormat): AudioSink {
        if (failSinks) throw IllegalStateException("no audio output")
        return RecordingAudioSink(format).also { audioSinks += it }
    }

    override val events: ReceiverEvents =
        object : ReceiverEvents {
            override fun onConnectionStarted(client: ClientInfo) = Unit

            override fun onPinRequired(
                pin: String,
                client: ClientInfo,
            ) = Unit

            override fun onPinFinished(
                client: ClientInfo,
                result: PinResult,
            ) = Unit

            override fun onMirroringStarted(client: ClientInfo) = Unit

            override fun onMirroringStopped(client: ClientInfo) = Unit

            override fun onAudioStarted(client: ClientInfo) = Unit

            override fun onAudioStopped(client: ClientInfo) = Unit

            override fun onNowPlaying(
                client: ClientInfo,
                nowPlaying: NowPlaying?,
            ) = Unit
        }
}

/** A test configuration: short waits, an optional manual clock, logs collected. */
internal fun testConfig(
    clock: MediaClock = MediaClock.SYSTEM,
    syncWaitNanos: Long = 1_000_000_000L,
    firstConnectionTimeoutMillis: Int = 5_000,
    readTimeoutMillis: Int = 5_000,
    log: MutableList<String> = CopyOnWriteArrayList(),
): MediaConfig =
    MediaConfig(
        clock = clock,
        syncWaitNanos = syncWaitNanos,
        firstConnectionTimeoutMillis = firstConnectionTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        playoutPollNanos = 1_000_000L,
        log = { log += it },
    )

/** 03d §A inputs. */
internal object Vectors {
    val fairPlayKey =
        ai.aneesiqbal.rozan.airplay
            .hex("00112233445566778899aabbccddeeff")
    val ecdhSecret =
        ai.aneesiqbal.rozan.airplay
            .hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
    val audioIv =
        ai.aneesiqbal.rozan.airplay
            .hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    val sessionKey =
        ai.aneesiqbal.rozan.airplay
            .hex("43cc4f5b5ca43586ad422154f65b0e10")
    const val STREAM_ID_A: Long = -0x3F0011EDCBA98770L // 0xC0FFEE1234567890
    const val STREAM_ID_B: Long = 4964383553955644435L
}
