package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.media.AudioPorts
import ai.aneesiqbal.rozan.airplay.media.AudioStreamRequest
import ai.aneesiqbal.rozan.airplay.media.MediaListener
import ai.aneesiqbal.rozan.airplay.media.MediaSession
import ai.aneesiqbal.rozan.airplay.media.MediaSessionFactory
import ai.aneesiqbal.rozan.airplay.media.MediaSessionParams
import ai.aneesiqbal.rozan.airplay.media.RecordingAudioSink
import ai.aneesiqbal.rozan.airplay.media.RecordingVideoSink
import ai.aneesiqbal.rozan.airplay.pairing.CodeDisplay
import ai.aneesiqbal.rozan.airplay.pairing.ConnectionPairing
import ai.aneesiqbal.rozan.airplay.pairing.PairingReply
import ai.aneesiqbal.rozan.airplay.pairing.PairingService
import ai.aneesiqbal.rozan.airplay.pairing.SetupAuthorization
import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverEvents
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Records everything the receiver tells the platform. Events are kept as short strings; sinks are the
 * media tests' recording sinks.
 */
internal class RecordingPlatform : ReceiverPlatform {
    val videoSinks = CopyOnWriteArrayList<RecordingVideoSink>()
    val audioSinks = CopyOnWriteArrayList<RecordingAudioSink>()

    val published = LinkedBlockingQueue<List<ServiceRecord>>()
    val eventLog = LinkedBlockingQueue<String>()
    val nowPlaying = CopyOnWriteArrayList<NowPlaying?>()
    val clients = CopyOnWriteArrayList<ClientInfo>()

    @Volatile var unpublished = 0

    override fun publish(records: List<ServiceRecord>) {
        published += records
    }

    override fun unpublish() {
        unpublished++
    }

    override fun createVideoSink(): VideoSink = RecordingVideoSink().also { videoSinks += it }

    override fun createAudioSink(format: AudioFormat): AudioSink = RecordingAudioSink(format).also { audioSinks += it }

    override val events: ReceiverEvents =
        object : ReceiverEvents {
            override fun onConnectionStarted(client: ClientInfo) = record("connection", client)

            override fun onConnectionEnded(client: ClientInfo) = record("connectionEnded", client)

            override fun onPinRequired(
                pin: String,
                client: ClientInfo,
            ) = record("pin $pin", client)

            override fun onPinFinished(
                client: ClientInfo,
                result: PinResult,
            ) = record("pinFinished $result", client)

            override fun onMirroringStarted(client: ClientInfo) = record("mirroringStarted", client)

            override fun onMirroringStopped(client: ClientInfo) = record("mirroringStopped", client)

            override fun onAudioStarted(client: ClientInfo) = record("audioStarted", client)

            override fun onAudioStopped(client: ClientInfo) = record("audioStopped", client)

            override fun onNowPlaying(
                client: ClientInfo,
                nowPlaying: NowPlaying?,
            ) {
                this@RecordingPlatform.nowPlaying += nowPlaying
                record("nowPlaying", client)
            }
        }

    private fun record(
        event: String,
        client: ClientInfo,
    ) {
        clients += client
        eventLog += event
    }

    /** Waits for the next event and checks it is [expected]. */
    fun expectEvent(expected: String) {
        val event = eventLog.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: fail("no event, expected $expected")
        assertEquals(expected, event)
    }

    /** Waits for the next event matching [predicate] and returns it, skipping others. */
    fun awaitEvent(predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val event = eventLog.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            if (predicate(event)) return event
        }
        fail("no matching event")
    }

    /** The next published record set. */
    fun awaitPublish(): List<ServiceRecord> =
        published.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: fail("nothing published")

    companion object {
        const val WAIT_SECONDS = 5L
    }
}

/** A media layer that records calls and hands out fixed ports. */
internal class FakeMedia : MediaSessionFactory {
    val sessions = CopyOnWriteArrayList<Session>()

    @Volatile var failOpen: Exception? = null

    override fun open(
        params: MediaSessionParams,
        platform: ReceiverPlatform,
        listener: MediaListener,
    ): MediaSession {
        failOpen?.let { throw it }
        return Session(params.sessionKey.copyOf(), params, listener).also { sessions += it }
    }

    /** The only session opened so far. */
    fun single(): Session {
        assertEquals(1, sessions.size, "sessions opened")
        return sessions[0]
    }

    class Session(
        val sessionKey: ByteArray,
        val params: MediaSessionParams,
        val listener: MediaListener,
    ) : MediaSession {
        val calls = CopyOnWriteArrayList<String>()
        val volumes = CopyOnWriteArrayList<Float>()

        /** The audio IV as it was at open; the caller may zero its own array afterwards. */
        val audioIv: ByteArray = params.audioIv.copyOf()

        @Volatile var closed = false

        override val timingPort: Int = TIMING_PORT

        override fun startMirroring(streamConnectionId: Long): Int {
            calls += "startMirroring ${java.lang.Long.toUnsignedString(streamConnectionId)}"
            return MIRROR_PORT
        }

        override fun stopMirroring() {
            calls += "stopMirroring"
        }

        override fun startAudio(request: AudioStreamRequest): AudioPorts {
            calls += "startAudio ct=${request.compressionType} spf=${request.framesPerPacket} " +
                "control=${request.clientControlPort} screen=${request.usingScreen} " +
                "latency=${request.latencyMinFrames}..${request.latencyMaxFrames}"
            return AudioPorts(AUDIO_DATA_PORT, AUDIO_CONTROL_PORT)
        }

        override fun stopAudio() {
            calls += "stopAudio"
        }

        override fun flushAudio(
            nextSequence: Int?,
            nextRtpTime: Long?,
        ) {
            calls += "flush $nextSequence $nextRtpTime"
        }

        override fun setVolume(linear: Float) {
            volumes += linear
        }

        /** What [audioActivity] returns; tests advance it to stand for arriving audio packets. */
        @Volatile var activity = 0L

        override fun audioActivity(): Long = activity

        override fun close() {
            closed = true
        }
    }

    companion object {
        const val TIMING_PORT = 50_001
        const val MIRROR_PORT = 50_002
        const val AUDIO_DATA_PORT = 50_003
        const val AUDIO_CONTROL_PORT = 50_004
    }
}

/**
 * A pairing layer with scripted behaviour: Digest is a fixed header value, `pair-verify` with a body
 * starting 0x01 "verifies" with [sharedSecret], and the identity rotates on [rotate].
 */
internal class FakePairing(
    private val display: CodeDisplay,
    private val onIdentityChanged: () -> Unit,
) : PairingService {
    @Volatile var key: ByteArray = ByteArray(32) { 0x11 }
    val sessionsEnded =
        java.util.concurrent.atomic
            .AtomicInteger()
    val ticks =
        java.util.concurrent.atomic
            .AtomicInteger()

    @Volatile var lockedOut = false

    /** What [needsTicks] reports: whether housekeeping keeps running with no connection open. */
    @Volatile var wantsTicks = false

    override val identityPublicKey: ByteArray get() = key.copyOf()

    override val needsTicks: Boolean get() = wantsTicks

    override fun pinStart(
        client: InetAddress,
        clientName: String?,
    ): PairingReply {
        if (lockedOut) return PairingReply.Error(470, close = false)
        display.show(PIN, client, clientName)
        return PairingReply.Ok(ByteArray(0), null)
    }

    override fun openConnection(client: InetAddress): ConnectionPairing = Connection(client)

    override fun sessionEnded() {
        sessionsEnded.incrementAndGet()
    }

    override fun tick(sessionActive: Boolean) {
        ticks.incrementAndGet()
    }

    fun rotate() {
        key = ByteArray(32) { 0x22 }
        onIdentityChanged()
    }

    inner class Connection(
        private val client: InetAddress,
    ) : ConnectionPairing {
        private var digestOk = false
        private var verified = false

        override fun pairSetup(body: ByteArray): PairingReply =
            if (body.size == 32) {
                PairingReply.Ok(key.copyOf(), "application/octet-stream")
            } else {
                PairingReply.Error(400, false)
            }

        override fun pairSetupPin(body: ByteArray): PairingReply = PairingReply.Error(470, close = true)

        override fun pairVerify(body: ByteArray): PairingReply {
            verified = body.isNotEmpty() && body[0].toInt() == 1
            return PairingReply.Ok(ByteArray(0), null)
        }

        override fun authorizeSetup(
            method: String,
            uri: String,
            authorization: String?,
            senderDeviceId: String?,
            senderName: String?,
        ): SetupAuthorization =
            when {
                isAuthenticated -> {
                    SetupAuthorization.Authorized
                }

                lockedOut -> {
                    SetupAuthorization.Refused(401, close = false)
                }

                authorization == GOOD_AUTHORIZATION -> {
                    digestOk = true
                    display.finish(client, PinResult.ACCEPTED)
                    SetupAuthorization.Authorized
                }

                else -> {
                    display.show(PIN, client, senderName)
                    SetupAuthorization.Challenge(CHALLENGE)
                }
            }

        override val isAuthenticated: Boolean get() = digestOk || verified

        override val verifiedSharedSecret: ByteArray? get() = if (verified) SHARED_SECRET.copyOf() else null

        override fun endAuthentication() {
            digestOk = false
            verified = false
        }

        override fun close() = Unit
    }

    companion object {
        const val PIN = "0042"
        const val GOOD_AUTHORIZATION = "Digest good"
        const val CHALLENGE = "Digest realm=\"raop\", nonce=\"00112233445566778899aabbccddeeff\""
        val SHARED_SECRET = ByteArray(32) { (it + 1).toByte() }
    }
}

/** FairPlay with fixed replies and a fixed key; `decryptKey` of an ekey starting 0x00 fails. */
class FakeFairPlay : FairPlayHandler {
    override fun setup(request: ByteArray): ByteArray = ByteArray(142) { 1 }

    override fun handshake(request: ByteArray): ByteArray {
        if (request[0].toInt() == 0) throw FairPlayFailure("bad handshake")
        return ByteArray(32) { 2 }
    }

    override fun decryptKey(encryptedKey: ByteArray): ByteArray {
        if (encryptedKey[0].toInt() == 0) throw FairPlayFailure("bad ekey")
        return KEY.copyOf()
    }

    companion object {
        val KEY = ByteArray(16) { (0xA0 + it).toByte() }
    }
}
