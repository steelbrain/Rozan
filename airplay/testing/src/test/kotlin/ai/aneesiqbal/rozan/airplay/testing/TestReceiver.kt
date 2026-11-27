package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.alac.AlacConfig
import ai.aneesiqbal.rozan.airplay.alac.AlacDecoder
import ai.aneesiqbal.rozan.airplay.fairplay.FairPlayException
import ai.aneesiqbal.rozan.airplay.fairplay.FairPlaySession
import ai.aneesiqbal.rozan.airplay.receiver.AirPlayReceiver
import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverEvents
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.fail

/**
 * The real receiver, through its public API only, on an ephemeral loopback port: real pairing (Mode P),
 * real FairPlay from `:airplay:fairplay`, real media layer. The platform records events and decodes
 * what reaches the sinks.
 */
internal class TestReceiver(
    mode: PinMode = PinMode.PIN_PAIRING,
) : AutoCloseable {
    val platform = RecordingPlatform()
    val logs = CopyOnWriteArrayList<String>()
    val receiver =
        AirPlayReceiver(
            ReceiverConfig(
                name = "Test Receiver",
                deviceId = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55),
                identitySeed = ByteArray(32) { it.toByte() },
                display = DisplayInfo(1920, 1080, 60),
                pinMode = mode,
                controlPort = 0,
            ),
            platform,
            { RealFairPlay() },
            { level, message -> logs += "$level $message" },
        ).also { it.start() }

    /** A sender pointed at this receiver. */
    fun sender(identity: SenderIdentity = SenderIdentity()): AirPlayTestSender =
        AirPlayTestSender(InetAddress.getLoopbackAddress(), receiver.controlPort, identity)

    override fun close() = receiver.stop()

    private class RealFairPlay : FairPlayHandler {
        private val session = FairPlaySession()

        override fun setup(request: ByteArray): ByteArray = adapt { session.setup(request) }

        override fun handshake(request: ByteArray): ByteArray = adapt { session.handshake(request) }

        override fun decryptKey(encryptedKey: ByteArray): ByteArray = adapt { session.decryptKey(encryptedKey) }

        private inline fun adapt(step: () -> ByteArray): ByteArray =
            try {
                step()
            } catch (e: FairPlayException) {
                throw FairPlayFailure(e.message ?: "FairPlay failed", e)
            }
    }
}

/** Events as short strings, and sinks that keep (and for ALAC, decode) what they are given. */
internal class RecordingPlatform : ReceiverPlatform {
    val eventLog = LinkedBlockingQueue<String>()
    val videoSinks = CopyOnWriteArrayList<VideoRecorder>()
    val audioSinks = CopyOnWriteArrayList<AudioRecorder>()
    val nowPlaying = CopyOnWriteArrayList<NowPlaying?>()

    override fun publish(records: List<ServiceRecord>) = Unit

    override fun unpublish() = Unit

    override fun createVideoSink(): VideoSink = VideoRecorder().also { videoSinks += it }

    override fun createAudioSink(format: AudioFormat): AudioSink = AudioRecorder(format).also { audioSinks += it }

    override val events: ReceiverEvents =
        object : ReceiverEvents {
            override fun onConnectionStarted(client: ClientInfo) = record("connection")

            override fun onConnectionEnded(client: ClientInfo) = record("connectionEnded")

            override fun onPinRequired(
                pin: String,
                client: ClientInfo,
            ) = record("pin $pin")

            override fun onPinFinished(
                client: ClientInfo,
                result: PinResult,
            ) = record("pinFinished $result")

            override fun onMirroringStarted(client: ClientInfo) = record("mirroringStarted")

            override fun onMirroringStopped(client: ClientInfo) = record("mirroringStopped")

            override fun onAudioStarted(client: ClientInfo) = record("audioStarted")

            override fun onAudioStopped(client: ClientInfo) = record("audioStopped")

            override fun onNowPlaying(
                client: ClientInfo,
                nowPlaying: NowPlaying?,
            ) {
                this@RecordingPlatform.nowPlaying += nowPlaying
                record("nowPlaying")
            }
        }

    private fun record(event: String) {
        eventLog += event
    }

    /** Waits for the next event matching [predicate], skipping others. */
    fun awaitEvent(predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val left = deadline - System.nanoTime()
            val event = eventLog.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: fail("no matching event")
            if (predicate(event)) return event
        }
    }

    /** The PIN the receiver shows next: what the user would read off the screen. */
    fun awaitPin(): String = awaitEvent { it.startsWith("pin ") }.removePrefix("pin ")
}

internal class VideoRecorder : VideoSink {
    class Config(
        val codec: VideoCodec,
        val parameterSets: ByteArray,
        val width: Int,
        val height: Int,
    )

    class Frame(
        val bytes: ByteArray,
        val presentationNanos: Long,
    )

    val configs = CopyOnWriteArrayList<Config>()
    val frames = CopyOnWriteArrayList<Frame>()

    @Volatile var closed = false

    override fun configure(
        codec: VideoCodec,
        parameterSets: ByteArray,
        width: Int,
        height: Int,
    ) {
        configs += Config(codec, parameterSets.copyOf(), width, height)
    }

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        frames += Frame(accessUnit.copyOfRange(offset, offset + length), presentationNanos)
    }

    override fun close() {
        closed = true
    }
}

internal class AudioRecorder(
    val format: AudioFormat,
) : AudioSink {
    class Decoded(
        val pcm: ShortArray,
        val presentationNanos: Long,
    )

    private val decoder = (format as? AudioFormat.Alac)?.let { AlacDecoder(AlacConfig.fromMagicCookie(it.magicCookie)) }
    val units = CopyOnWriteArrayList<Decoded>()
    val volumes = CopyOnWriteArrayList<Float>()

    @Volatile var closed = false

    override fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    ) {
        val alac = checkNotNull(decoder) { "the test sender sends ALAC" }
        val pcm = ShortArray(alac.minOutputSize)
        val frames = alac.decode(accessUnit, offset, length, pcm)
        units += Decoded(pcm.copyOf(frames * format.channels), presentationNanos)
    }

    override fun setVolume(linear: Float) {
        volumes += linear
    }

    override fun flush() = Unit

    override fun close() {
        closed = true
    }
}

/** Waits up to [timeoutMillis] for [condition]. */
internal fun eventually(
    what: String,
    timeoutMillis: Long = 10_000,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("timed out waiting for $what")
        LockSupport.parkNanos(10_000_000)
    }
}
