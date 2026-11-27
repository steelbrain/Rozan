package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.media.AudioStreamRequest
import ai.aneesiqbal.rozan.airplay.media.MediaListener
import ai.aneesiqbal.rozan.airplay.media.MediaSession
import ai.aneesiqbal.rozan.airplay.media.MediaSessionParams
import ai.aneesiqbal.rozan.airplay.media.Volume
import ai.aneesiqbal.rozan.airplay.pairing.ConnectionPairing
import ai.aneesiqbal.rozan.airplay.pairing.SetupAuthorization
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistException
import ai.aneesiqbal.rozan.airplay.plist.PlistLimits
import ai.aneesiqbal.rozan.airplay.plist.PlistValue
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.NowPlaying
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
import ai.aneesiqbal.rozan.airplay.rtsp.RtspStatus
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The session side of one control connection (docs/protocol/01 §6.8-§6.14, 03 §1, §2.1, §6): the
 * session and stream SETUPs, the session of a classic SETUP ([openClassicSession], 03 §1.6), RECORD, the
 * parameters, FLUSH and TEARDOWN, and the matching [ai.aneesiqbal.rozan.airplay.receiver.ReceiverEvents].
 *
 * Every media session transition runs under [ReceiverCore.sessionLock]. Started/stopped events are
 * paired with atomic flags, so each start gets exactly one stop whichever thread ends the stream.
 */
internal class MediaControl(
    private val connection: ControlConnection,
    private val core: ReceiverCore,
    private val pairing: ConnectionPairing,
) : MediaListener {
    private var session: MediaSession? = null
    private val track = TrackState(connection, core)
    private val mirroring = AtomicBoolean()
    private val audioOnly = AtomicBoolean()

    @Volatile private var sessionOpen = false

    @Volatile private var lastFeedbackNanos = 0L

    /** Whether the open session is held to the `/feedback` timeout: always, or for a classic one from its first. */
    @Volatile private var feedbackRequired = false

    /** Whether the open session came from a classic SETUP (01 §4.3.2). */
    @Volatile private var classicSession = false

    /** The audio activity count [audioKeepsAlive] last saw; the connection's thread only. */
    private var lastAudioActivity = 0L

    /** Whether this connection has a media session open. */
    val hasSession: Boolean get() = sessionOpen

    /** The last volume the sender set, in AirPlay dB; full volume until then. */
    @Volatile var volumeDb: Float = Volume.MAX_DB
        private set

    /** The last `progress` (start, current, end RTP timestamps), kept for diagnostics. */
    @Volatile var progress: LongArray? = null
        private set

    /** SETUP, both phases (01 §6.8, 03 §1). [fairPlay] is the connection's FairPlay state, if fp-setup ran. */
    fun setup(
        request: RtspRequest,
        fairPlay: FairPlayHandler?,
    ): Reply {
        val setup = SetupRequest.parse(request.body)
        val sessionPart = setup.session
        // 01 §5: a stream SETUP needs an authenticated connection; the session SETUP carries the auth.
        if (sessionPart == null && !pairing.isAuthenticated) return Reply(Status.CONNECTION_AUTHORIZATION_REQUIRED)
        val response = PlistDict.Builder()
        if (sessionPart != null) {
            authorize(request, sessionPart.deviceId, ClientText.clean(sessionPart.name))?.let { return it }
            val timingPort = synchronized(core.sessionLock) { openSession(sessionPart, fairPlay) }
            response.put("eventPort", 0)
            response.put("timingPort", timingPort)
        }
        val streams = setup.streams
        if (streams != null) {
            val elements = synchronized(core.sessionLock) { startStreams(streams) }
            // 03 §1.3: unsupported types are left out; nothing accepted at all is a 400.
            if (elements.isEmpty() && sessionPart == null) throw RtspException("no supported stream in SETUP")
            response.put("streams", PlistArray(elements))
        }
        return Reply(RtspStatus.OK, BinaryPlist.encode(response.build()), ContentTypes.BPLIST)
    }

    /** RECORD (03 §1.5): starts nothing; reports the receiver latency. */
    fun record(): Reply =
        Reply(
            RtspStatus.OK,
            headers = listOf("Audio-Latency" to Profile.AUDIO_LATENCY_FRAMES.toString()),
            analogJack = true,
        )

    /** GET_PARAMETER (01 §6.10): only `volume` is answered. */
    fun getParameter(request: RtspRequest): Reply {
        if (request.headers["Content-Type"] == null) return Reply(Status.PARAMETER_NOT_UNDERSTOOD)
        val asked = TextParameters.lines(request.body).any { it.substringBefore(':').trim().equals("volume", true) }
        if (!asked) return Reply.OK
        val body = String.format(Locale.ROOT, "volume: %.6f\r\n", volumeDb).toByteArray(Charsets.US_ASCII)
        return Reply(RtspStatus.OK, body, ContentTypes.TEXT_PARAMETERS)
    }

    /** SET_PARAMETER (01 §6.11, 03 §6.1): volume, progress, DMAP metadata, artwork. Always 200. */
    fun setParameter(request: RtspRequest): Reply {
        val type =
            ContentTypes.mediaType(request.headers["Content-Type"]) ?: return Reply(Status.PARAMETER_NOT_UNDERSTOOD)
        when {
            type == ContentTypes.TEXT_PARAMETERS -> textParameters(request.body)
            type == ContentTypes.DMAP -> track.metadata(request.body)
            type.startsWith("image/") -> track.artwork(request.body)
            else -> connection.log(ReceiverLog.Level.DEBUG, "SET_PARAMETER of an unknown type ignored")
        }
        return Reply.OK
    }

    /** FLUSH (03 §6.2). */
    fun flush(request: RtspRequest): Reply {
        val info = TextParameters.rtpInfo(request.headers["RTP-Info"])
        synchronized(core.sessionLock) { session?.flushAudio(info.seq, info.rtpTime) }
        return Reply.OK
    }

    /**
     * TEARDOWN (01 §6.13, 03 §6.3): the listed streams, or the whole session. A whole-session TEARDOWN also
     * ends the connection's authentication: another session on it needs a new code.
     */
    fun teardown(request: RtspRequest): Reply {
        val types = teardownTypes(request.body)
        if (types == null) {
            endSession("TEARDOWN")
            pairing.endAuthentication()
            return Reply(RtspStatus.OK, headers = listOf("Connection" to "close"))
        }
        synchronized(core.sessionLock) {
            if (SetupRequest.TYPE_MIRRORING in types) stopMirroring()
            if (SetupRequest.TYPE_AUDIO in types) stopAudio()
        }
        return Reply.OK
    }

    /** `POST /feedback` (03 §6.4): the liveness signal. A classic session is held to it from the first one. */
    fun feedback() {
        lastFeedbackNanos = core.clock()
        if (sessionOpen && !feedbackRequired) {
            feedbackRequired = true
            connection.log(ReceiverLog.Level.INFO, "first /feedback of a classic session")
        }
    }

    /** Whether a session is open, held to `/feedback`, and `/feedback` has been silent past the limit. */
    fun feedbackExpired(now: Long): Boolean =
        sessionOpen && feedbackRequired && now - lastFeedbackNanos > core.limits.feedbackTimeoutNanos

    /**
     * Whether a read timeout should leave this connection open (01 §3.1): its session came from a classic
     * SETUP and its audio sockets accepted a packet since the last call. The connection's thread only.
     */
    fun audioKeepsAlive(): Boolean {
        if (!classicSession) return false
        val activity = synchronized(core.sessionLock) { session?.audioActivity() } ?: return false
        val alive = activity != lastAudioActivity
        lastAudioActivity = activity
        return alive
    }

    /**
     * The session of a classic SETUP (01 §6.8.1, 03 §1.6): a session keyed with [fairPlayKey] as is (03 §2.1,
     * never hashed), timing toward [clientTimingPort], and one audio-only stream for [audio]. A failed stream
     * closes the session again.
     *
     * @return the local ports for the reply's `Transport`.
     */
    fun openClassicSession(
        fairPlayKey: ByteArray,
        audioIv: ByteArray,
        clientTimingPort: Int,
        audio: AudioStreamRequest,
    ): ClassicPorts {
        val sessionKey = classicSessionKey(fairPlayKey)
        try {
            synchronized(core.sessionLock) {
                val opened =
                    startSession(
                        sessionKey = sessionKey,
                        audioIv = audioIv,
                        clientTimingPort = clientTimingPort,
                        name = null,
                        model = null,
                        screenMirroring = false,
                        classic = true,
                    )
                val ports =
                    try {
                        streamCall { opened.startAudio(audio) }
                    } catch (e: RtspException) {
                        endSession("classic SETUP failed: ${e.message}")
                        throw e
                    }
                audioStarted(audioOnlyStream = true)
                connection.log(
                    ReceiverLog.Level.INFO,
                    "classic audio: data ${ports.dataPort}, control ${ports.controlPort}, timing " +
                        "${opened.timingPort}; sender control ${audio.clientControlPort}, timing $clientTimingPort",
                )
                return ClassicPorts(ports.dataPort, ports.controlPort, opened.timingPort)
            }
        } finally {
            sessionKey.fill(0)
        }
    }

    /**
     * Ends the session, if any: stops every stream, closes the media session, fires the stopped events,
     * and tells the pairing layer (Mode P rotates the identity). Idempotent; any thread.
     */
    fun endSession(
        reason: String,
        notifyPairing: Boolean = true,
    ) {
        synchronized(core.sessionLock) {
            val current = session
            if (current == null) {
                core.release(connection)
                return
            }
            session = null
            sessionOpen = false
            classicSession = false
            try {
                current.close()
            } finally {
                mirroringStopped()
                audioOnlyStopped()
                track.clear()
                core.release(connection)
                connection.log(ReceiverLog.Level.INFO, "session ended: $reason")
                // A session replaced by the same sender is not a session end for Mode P rotation (02 §8.2).
                if (notifyPairing) core.pairing.sessionEnded()
            }
        }
    }

    override fun onMirroringConnected() = connection.log(ReceiverLog.Level.INFO, "mirroring data connected")

    override fun onMirroringDisconnected(reason: String) =
        connection.log(ReceiverLog.Level.INFO, "mirroring data disconnected: $reason")

    override fun onAudioFailed(reason: String) {
        connection.log(ReceiverLog.Level.WARN, "audio failed: $reason")
        audioOnlyStopped()
    }

    /**
     * Access control for the request that carries the code: the session SETUP (02 §6.1) or the classic
     * ANNOUNCE (02 §6.4). Null when it may run, else the reply (a Digest challenge, or a refusal).
     * [senderDeviceId] and [senderName] are untrusted and only key and label the code.
     */
    fun authorize(
        request: RtspRequest,
        senderDeviceId: String?,
        senderName: String?,
    ): Reply? =
        when (
            val auth =
                pairing.authorizeSetup(
                    request.method,
                    request.target,
                    request.headers["Authorization"],
                    senderDeviceId,
                    senderName,
                )
        ) {
            SetupAuthorization.Authorized -> {
                null
            }

            is SetupAuthorization.Challenge -> {
                Reply(RtspStatus.UNAUTHORIZED, headers = listOf("WWW-Authenticate" to auth.wwwAuthenticate))
            }

            is SetupAuthorization.Refused -> {
                Reply(auth.status, close = auth.close)
            }
        }

    /** Session SETUP steps 2-4 (01 §6.8, 03 §1.2). Under the session lock; returns the timing port. */
    private fun openSession(
        setup: SessionSetup,
        fairPlay: FairPlayHandler?,
    ): Int {
        val fairPlayKey = fairPlayKey(setup, fairPlay)
        val sessionKey =
            try {
                sessionKey(fairPlayKey, pairing.verifiedSharedSecret)
            } finally {
                fairPlayKey.fill(0)
            }
        try {
            val name = ClientText.clean(setup.name)
            val model = ClientText.clean(setup.model)
            return startSession(sessionKey, setup.eiv, setup.timingPort, name, model, setup.screenMirroring)
                .timingPort
        } finally {
            sessionKey.fill(0)
        }
    }

    /**
     * Opens the media session with [sessionKey] (the caller zeroes it) and makes this connection its owner;
     * shared by the session SETUP and the classic SETUP. Under the session lock.
     */
    private fun startSession(
        sessionKey: ByteArray,
        audioIv: ByteArray,
        clientTimingPort: Int,
        name: String?,
        model: String?,
        screenMirroring: Boolean,
        classic: Boolean = false,
    ): MediaSession {
        // A second session SETUP replaces this connection's session; a session on another connection
        // of the same sender is taken over (it can only be the same address, 01 §3.1).
        endSession("replaced by a new session SETUP", notifyPairing = false)
        core.claim(connection)?.terminate("superseded by a new session from the same sender", endsSession = false)
        connection.clientInfo =
            connection.clientInfo.copy(
                name = name ?: connection.clientInfo.name,
                model = model ?: connection.clientInfo.model,
            )
        val params = MediaSessionParams(connection.client, sessionKey, audioIv, clientTimingPort, screenMirroring)
        val opened = openMedia(params)
        session = opened
        sessionOpen = true
        classicSession = classic
        // The classic flow is not known to send /feedback (01 §3.1): it is held to it once it does.
        feedbackRequired = !classic
        lastFeedbackNanos = core.clock()
        lastAudioActivity = 0L
        Volume.linearFromDb(volumeDb)?.let(opened::setVolume)
        val kind = if (classic) "classic session" else "session"
        connection.log(ReceiverLog.Level.INFO, "$kind opened, paired=${pairing.verifiedSharedSecret != null}")
        return opened
    }

    /** Opens the media session; on any failure this connection stops being the session owner. */
    private fun openMedia(params: MediaSessionParams): MediaSession {
        var opened: MediaSession? = null
        try {
            opened = core.mediaFactory.open(params, core.platform, this)
            return opened
        } catch (e: RtspException) {
            throw e
        } catch (e: IOException) {
            throw RtspException("could not open the media session", RtspStatus.INTERNAL_SERVER_ERROR, e)
        } finally {
            if (opened == null) core.release(connection)
        }
    }

    /** Stream SETUP (03 §1.3). Under the session lock; returns the response elements in request order. */
    private fun startStreams(streams: List<StreamSetup>): List<PlistValue> {
        val current = session ?: throw RtspException("stream SETUP before the session SETUP")
        val elements = ArrayList<PlistValue>(streams.size)
        for (stream in streams) {
            when (stream) {
                is StreamSetup.Mirroring -> {
                    val dataPort = streamCall { current.startMirroring(stream.streamConnectionId) }
                    elements +=
                        buildPlistDict {
                            put("type", SetupRequest.TYPE_MIRRORING)
                            put("dataPort", dataPort)
                        }
                    if (mirroring.compareAndSet(false, true)) core.events.mirroringStarted(connection.clientInfo)
                }

                is StreamSetup.Audio -> {
                    val ports = streamCall { current.startAudio(stream.request) }
                    elements +=
                        buildPlistDict {
                            put("type", SetupRequest.TYPE_AUDIO)
                            put("dataPort", ports.dataPort)
                            put("controlPort", ports.controlPort)
                        }
                    audioStarted(audioOnlyStream = !stream.request.usingScreen)
                }

                is StreamSetup.Unsupported -> {
                    connection.log(ReceiverLog.Level.WARN, "unsupported stream type ${stream.type} left out")
                }
            }
        }
        return elements
    }

    private fun stopMirroring() {
        session?.stopMirroring()
        mirroringStopped()
    }

    private fun stopAudio() {
        session?.stopAudio()
        audioOnlyStopped()
        track.clear()
    }

    private fun mirroringStopped() {
        if (mirroring.compareAndSet(true, false)) core.events.mirroringStopped(connection.clientInfo)
    }

    /** Audio-only means a type-96 stream outside mirroring (ReceiverEvents.onAudioStarted). */
    private fun audioStarted(audioOnlyStream: Boolean) {
        if (audioOnlyStream) {
            if (audioOnly.compareAndSet(false, true)) core.events.audioStarted(connection.clientInfo)
        } else {
            audioOnlyStopped()
        }
    }

    /** Takes no lock: media threads call it through [onAudioFailed] while a teardown may wait for them. */
    private fun audioOnlyStopped() {
        if (audioOnly.compareAndSet(true, false)) core.events.audioStopped(connection.clientInfo)
    }

    private fun textParameters(body: ByteArray) {
        val lines = TextParameters.lines(body)
        TextParameters.value(lines, "volume")?.let(TextParameters::volume)?.let(Volume::normalizeDb)?.let { db ->
            volumeDb = db
            val linear = Volume.linearFromDb(db) ?: return@let
            synchronized(core.sessionLock) { session?.setVolume(linear) }
        }
        TextParameters.value(lines, "progress")?.let(TextParameters::progress)?.let { progress = it }
    }

    private companion object {
        const val EKEY_BYTES = 72
        const val EIV_BYTES = 16
        const val SESSION_KEY_BYTES = 16
        val TEARDOWN_LIMITS = PlistLimits(maxObjects = 256, maxDepth = 8)

        /** 01 §6.8 step 2: checks the `ekey`/`eiv` sizes and unwraps the key with FairPlay (04). */
        fun fairPlayKey(
            setup: SessionSetup,
            fairPlay: FairPlayHandler?,
        ): ByteArray {
            val handler =
                when {
                    setup.ekey.size != EKEY_BYTES || setup.eiv.size != EIV_BYTES -> null
                    else -> fairPlay
                } ?: throw RtspException("bad ekey or eiv size, or no fp-setup before the session SETUP")
            return try {
                handler.decryptKey(setup.ekey)
            } catch (e: FairPlayFailure) {
                throw RtspException("FairPlay rejected ekey", cause = e)
            }
        }

        inline fun <T> streamCall(block: () -> T): T =
            try {
                block()
            } catch (e: RtspException) {
                throw e
            } catch (e: IOException) {
                throw RtspException("could not open the stream", RtspStatus.INTERNAL_SERVER_ERROR, e)
            }

        /**
         * 03 §2.1, the classic RAOP branch: the FairPlay key from `fpaeskey` as is, never hashed, even after
         * a pair-verify. Kept apart from [sessionKey] so neither derivation can take the other's path. A
         * fresh array the caller zeroes.
         */
        fun classicSessionKey(fairPlayKey: ByteArray): ByteArray {
            if (fairPlayKey.size != SESSION_KEY_BYTES) {
                val size = fairPlayKey.size
                throw RtspException("FairPlay returned a $size-byte key", RtspStatus.INTERNAL_SERVER_ERROR)
            }
            return fairPlayKey.copyOf()
        }

        /**
         * 03 §2.1: `SHA-512(fairplay_key ‖ ecdh_secret)[0..16)` after pair-verify, else the FairPlay key.
         * A fresh array the caller zeroes.
         */
        fun sessionKey(
            fairPlayKey: ByteArray,
            sharedSecret: ByteArray?,
        ): ByteArray {
            if (fairPlayKey.size != SESSION_KEY_BYTES) {
                val size = fairPlayKey.size
                throw RtspException("FairPlay returned a $size-byte key", RtspStatus.INTERNAL_SERVER_ERROR)
            }
            if (sharedSecret == null) return fairPlayKey.copyOf()
            val digest = Crypto.sha512(fairPlayKey, sharedSecret)
            return digest.copyOf(SESSION_KEY_BYTES).also { digest.fill(0) }
        }

        /**
         * The stream types a TEARDOWN names, or null for a whole-session TEARDOWN: no body, no `streams`,
         * or a body that does not parse (03 §6.3, CVE-2025-60458).
         */
        fun teardownTypes(body: ByteArray): Set<Long>? {
            if (body.isEmpty()) return null
            val root =
                try {
                    BinaryPlist.decode(body, TEARDOWN_LIMITS)
                } catch (ignored: PlistException) {
                    return null
                }
            val streams = (root as? PlistDict)?.array("streams") ?: return null
            return streams.items.mapNotNull { (it as? PlistDict)?.long("type") }.toSet()
        }
    }
}

/**
 * Now-playing state of one connection (01 §6.11): DMAP metadata and artwork merged into one
 * [NowPlaying], raised as an event whenever it changes. Guarded by [ReceiverCore.sessionLock].
 */
internal class TrackState(
    private val connection: ControlConnection,
    private val core: ReceiverCore,
) {
    private var current: NowPlaying? = null

    /** A DMAP body; malformed ones are ignored (03 §8.1). */
    fun metadata(body: ByteArray) {
        val track = Dmap.parse(body)
        if (track == null) {
            connection.log(ReceiverLog.Level.DEBUG, "malformed DMAP ignored")
            return
        }
        update { NowPlaying(track.title, track.artist, track.album, it?.artwork) }
    }

    /** An artwork body: JPEG or PNG by magic bytes (01 §3.5), empty to clear; anything else is ignored. */
    fun artwork(body: ByteArray) {
        if (body.isNotEmpty() && !body.startsWith(JPEG_MAGIC) && !body.startsWith(PNG_MAGIC)) {
            connection.log(ReceiverLog.Level.DEBUG, "artwork of an unknown format ignored")
            return
        }
        val image = body.takeIf { it.isNotEmpty() }
        update { (it ?: EMPTY).copy(artwork = image) }
    }

    /** Forgets the track, raising a null now-playing event when there was one. */
    fun clear() = update { EMPTY }

    private fun update(change: (NowPlaying?) -> NowPlaying) {
        synchronized(core.sessionLock) {
            val next = change(current).takeUnless { it == EMPTY }
            if (next == current) return
            current = next
            core.events.nowPlaying(connection.clientInfo, next)
        }
    }

    private companion object {
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val EMPTY = NowPlaying(null, null, null, null)
    }
}
