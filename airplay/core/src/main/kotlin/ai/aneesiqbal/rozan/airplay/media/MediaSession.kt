package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import java.net.InetAddress

/*
 * Contract between the control layer (session package) and the media layer (this package).
 *
 * The control layer parses and validates RTSP/plist requests (docs/protocol/03-media-streams.md §1), runs
 * access control and FairPlay, derives `session_key` (§2.1), and fires ReceiverEvents. The media layer owns
 * every media socket and thread: the NTP timing client (03c §4.6), the type-110 mirroring TCP stream (03b)
 * and the type-96 RTP audio stream (03c), including decryption, buffering, playout timing and the sinks.
 */

/**
 * Inputs from a successful session SETUP (03 §1.2). The arrays are copied by the media layer and the
 * copies zeroed when the session closes.
 *
 * @property client the sender's address; media sockets talk only to it.
 * @property sessionKey the 16-byte `session_key` (03 §2.1), already hashed with the pair-verify secret
 *   when there was one.
 * @property audioIv the 16-byte `eiv` (03 §2.2).
 * @property clientTimingPort the sender's NTP port (`timingPort`), or 0 to run without timing.
 * @property screenMirroring `isScreenMirroringSession`; for policy and logging only.
 */
internal class MediaSessionParams(
    val client: InetAddress,
    val sessionKey: ByteArray,
    val audioIv: ByteArray,
    val clientTimingPort: Int,
    val screenMirroring: Boolean,
)

/**
 * A validated type-96 stream request (03 §1.3.2). The control layer has already checked `ct`, `spf` and
 * `sr` against the spec's table.
 *
 * @property compressionType `ct`: 2 (ALAC) or 8 (AAC-ELD).
 * @property framesPerPacket `spf`: 352 for ALAC (1..4096 accepted), 480 for AAC-ELD.
 * @property sampleRate `sr`, 44 100.
 * @property clientControlPort the sender's UDP port for resend requests, or 0 to never send them.
 * @property latencyMinFrames `latencyMin`, or null when absent.
 * @property latencyMaxFrames `latencyMax`, or null when absent.
 * @property usingScreen `usingScreen`: the audio belongs to a mirroring session.
 */
internal class AudioStreamRequest(
    val compressionType: Int,
    val framesPerPacket: Int,
    val sampleRate: Int,
    val clientControlPort: Int,
    val latencyMinFrames: Int?,
    val latencyMaxFrames: Int?,
    val usingScreen: Boolean,
)

/** Local UDP ports of a type-96 stream, for the SETUP response. */
internal class AudioPorts(
    val dataPort: Int,
    val controlPort: Int,
)

/** What the media layer reports back to the control layer. Called on media threads; must not block. */
internal interface MediaListener {
    /** The sender connected to the mirroring data port. */
    fun onMirroringConnected()

    /** The mirroring data connection ended (closed by the sender, idle, or a fatal stream error). */
    fun onMirroringDisconnected(reason: String)

    /** The audio stream stopped on its own because of a fatal error (socket failure, sink failure). */
    fun onAudioFailed(reason: String)
}

/**
 * The media state of one control connection between session SETUP and full TEARDOWN (03 §2.4).
 * Methods are called from the control connection's thread, one at a time; [close] may also come from
 * another thread (receiver stop, disconnect from the TV) and must be idempotent.
 */
internal interface MediaSession : AutoCloseable {
    /** Local UDP port of the timing socket, for the session SETUP response (`timingPort`). */
    val timingPort: Int

    /**
     * Sets up the type-110 stream (03 §1.3.1), first stopping any running one, and returns the local TCP
     * data port. [streamConnectionId] is the raw 64 bits, read as unsigned.
     */
    fun startMirroring(streamConnectionId: Long): Int

    /** Stops the type-110 stream and releases its sink. No-op when none is running. */
    fun stopMirroring()

    /** Sets up the type-96 stream, first stopping any running one, and returns its local ports. */
    fun startAudio(request: AudioStreamRequest): AudioPorts

    /** Stops the type-96 stream and releases its sink. No-op when none is running. */
    fun stopAudio()

    /**
     * FLUSH (03 §6.2): drop queued audio. [nextSequence] and [nextRtpTime] come from the `RTP-Info`
     * header (`seq=`, `rtptime=`), or are null when absent.
     */
    fun flushAudio(
        nextSequence: Int?,
        nextRtpTime: Long?,
    )

    /** Linear gain 0.0..1.0 for the audio sink, kept and applied to later audio streams too. */
    fun setVolume(linear: Float)

    /**
     * A count that grows while the running type-96 stream accepts packets (stored data packets and sync
     * packets), or 0 without one; only its changes mean anything. For the classic liveness rule (01 §3.1).
     */
    fun audioActivity(): Long

    /** Stops everything, closes every socket and thread, zeroes keys. Idempotent. */
    override fun close()
}

/** Opens media sessions. Thread-safe. */
internal fun interface MediaSessionFactory {
    /**
     * Opens the timing socket, starts the timing client toward [MediaSessionParams.clientTimingPort],
     * and returns the session. Sinks come from [platform].
     */
    fun open(
        params: MediaSessionParams,
        platform: ReceiverPlatform,
        listener: MediaListener,
    ): MediaSession
}
