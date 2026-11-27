package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.receiver.ReceiverPlatform
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspStatus
import java.io.IOException

/**
 * The production [MediaSessionFactory]: a [TimingClient], at most one [MirroringStream] and at most one
 * [AudioStream] per session.
 *
 * @param config tunables and the local clock.
 */
internal class DefaultMediaSessionFactory(
    private val config: MediaConfig = MediaConfig(),
) : MediaSessionFactory {
    /**
     * Opens the session's timing socket and starts its timing client.
     *
     * @throws RtspException 400 when a key or IV is not 16 bytes, 500 when the socket cannot be opened.
     */
    override fun open(
        params: MediaSessionParams,
        platform: ReceiverPlatform,
        listener: MediaListener,
    ): MediaSession {
        if (params.sessionKey.size != MediaKeys.KEY_BYTES || params.audioIv.size != MediaKeys.KEY_BYTES) {
            throw RtspException("session key and IV must be ${MediaKeys.KEY_BYTES} bytes", RtspStatus.BAD_REQUEST)
        }
        val timing =
            try {
                TimingClient(params.client, params.clientTimingPort, config)
            } catch (e: IOException) {
                throw RtspException("cannot open the timing socket", RtspStatus.INTERNAL_SERVER_ERROR, e)
            }
        timing.start()
        return DefaultMediaSession(params, timing, platform, listener, config)
    }
}

/**
 * One control connection's media (03 §2.4). Every method is serialised on the session; [close] may come
 * from any thread. Keys are copied at construction and zeroed on [close].
 */
internal class DefaultMediaSession(
    params: MediaSessionParams,
    private val timing: TimingClient,
    private val platform: ReceiverPlatform,
    private val listener: MediaListener,
    private val config: MediaConfig,
) : MediaSession {
    private val client = params.client
    private val sessionKey = params.sessionKey.copyOf()
    private val audioIv = params.audioIv.copyOf()
    private var mirroring: MirroringStream? = null
    private var audio: AudioStream? = null
    private var volume = 1f
    private var closed = false

    override val timingPort: Int = timing.localPort

    /** The clock offset the streams use; exposed for diagnostics and tests. */
    val clockSync: ClockSync get() = timing.filter

    /** Counters of the running audio stream, or null; for diagnostics and tests (read without a lock). */
    val audioCounters: AudioCounters? get() = audio?.processor?.counters

    /**
     * @throws RtspException 500 when the session is closed, the listener cannot be opened or the platform
     *   fails to create the video sink.
     */
    @Synchronized
    override fun startMirroring(streamConnectionId: Long): Int {
        checkOpen()
        stopMirroringLocked()
        val sink = createSink("video") { platform.createVideoSink() }
        val key = MediaKeys.videoKey(sessionKey, streamConnectionId)
        val iv = MediaKeys.videoIv(sessionKey, streamConnectionId)
        val stream =
            try {
                MirroringStream(client, key, iv, sink, timing.filter, listener, config)
            } catch (e: IOException) {
                closeSink(sink)
                throw RtspException("cannot open the mirroring listener", RtspStatus.INTERNAL_SERVER_ERROR, e)
            } finally {
                key.fill(0)
                iv.fill(0)
            }
        mirroring = stream
        stream.start()
        return stream.localPort
    }

    @Synchronized
    override fun stopMirroring() {
        stopMirroringLocked()
    }

    /**
     * @throws RtspException 400 for an unsupported codec or impossible parameters; 500 when the session is
     *   closed, the sockets cannot be opened or the platform fails to create the audio sink.
     */
    @Synchronized
    override fun startAudio(request: AudioStreamRequest): AudioPorts {
        checkOpen()
        val sane = request.framesPerPacket in 1..MAX_FRAMES_PER_PACKET && request.sampleRate in 1..MAX_SAMPLE_RATE
        val format = if (sane) AudioFormats.formatFor(request) else null
        if (format == null) {
            throw RtspException("unsupported audio stream (ct=${request.compressionType})", RtspStatus.BAD_REQUEST)
        }
        stopAudioLocked()
        val sockets =
            try {
                AudioSockets.open()
            } catch (e: IOException) {
                throw RtspException("cannot open the audio sockets", RtspStatus.INTERNAL_SERVER_ERROR, e)
            }
        val sink = createSink("audio", onFailure = sockets::close) { platform.createAudioSink(format) }
        val alac = request.compressionType == AudioFormats.CT_ALAC
        val processor =
            AudioPacketProcessor(
                sessionKey = sessionKey,
                audioIv = audioIv,
                compressionType = request.compressionType,
                framesPerPacket = request.framesPerPacket,
                sampleRate = request.sampleRate,
                timestamped = alac,
                // Resends matter for ALAC's 2 s latency; mirroring audio gains little (03c §4.5).
                resendEnabled = alac && request.clientControlPort in 1..MAX_PORT,
                extraNanos = if (alac) config.audioOnlyExtraNanos else config.mirroringAudioExtraNanos,
                clockSync = timing.filter,
                config = config,
            )
        val stream = AudioStream(client, sockets, request.clientControlPort, processor, sink, listener, config)
        stream.setVolume(volume)
        audio = stream
        stream.start()
        return stream.ports
    }

    @Synchronized
    override fun stopAudio() {
        stopAudioLocked()
    }

    @Synchronized
    override fun flushAudio(
        nextSequence: Int?,
        nextRtpTime: Long?,
    ) {
        audio?.flush(nextSequence, nextRtpTime)
    }

    @Synchronized
    override fun setVolume(linear: Float) {
        if (linear.isNaN()) return
        volume = linear.coerceIn(0f, 1f)
        audio?.setVolume(volume)
    }

    @Synchronized
    override fun audioActivity(): Long = audio?.activity() ?: 0L

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stopMirroringLocked()
        stopAudioLocked()
        timing.close()
        sessionKey.fill(0)
        audioIv.fill(0)
    }

    private fun stopMirroringLocked() {
        mirroring?.close()
        mirroring = null
    }

    private fun stopAudioLocked() {
        audio?.close()
        audio = null
    }

    private fun checkOpen() {
        if (closed) throw RtspException("the media session is closed", RtspStatus.INTERNAL_SERVER_ERROR)
    }

    // The platform's sink factory is outside this layer; any failure becomes a 500 for this request.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> createSink(
        what: String,
        onFailure: () -> Unit = {},
        create: () -> T,
    ): T =
        try {
            create()
        } catch (e: Exception) {
            onFailure()
            throw RtspException("cannot create the $what sink", RtspStatus.INTERNAL_SERVER_ERROR, e)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun closeSink(sink: VideoSink) {
        try {
            sink.close()
        } catch (e: Exception) {
            config.log.log("video sink close failed: $e")
        }
    }

    private companion object {
        const val MAX_PORT = 65_535
        const val MAX_FRAMES_PER_PACKET = 4096
        const val MAX_SAMPLE_RATE = 192_000
    }
}
