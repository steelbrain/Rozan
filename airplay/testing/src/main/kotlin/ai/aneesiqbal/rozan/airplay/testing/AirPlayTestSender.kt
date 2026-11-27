package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistInteger
import ai.aneesiqbal.rozan.airplay.plist.PlistString
import ai.aneesiqbal.rozan.airplay.plist.PlistValue
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponse
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Locale
import java.util.UUID

/**
 * A scripted AirPlay sender for end-to-end tests, playing a Mac or iPhone against a real receiver at a
 * known address (no discovery). It follows the session of docs/protocol/01-discovery-and-session.md
 * §4.2 step by step, so a test can stop anywhere, inject a mistake, or check the receiver's reaction:
 *
 *     AirPlayTestSender(InetAddress.getLoopbackAddress(), 7000).use { sender ->
 *         sender.pairWithPin { readPinOffTheScreen() }   // PIN pairing and pair-verify
 *         sender.fairPlaySetup()                        // FairPlay v2, from the sender side
 *         sender.setupSession()                         // keys, timing
 *         val video = sender.setupMirroring()           // type 110
 *         val audio = sender.setupAudio()               // type 96, ALAC
 *         sender.record()
 *         sender.setVolume(-15.0)
 *         sender.stream(H264Clip.parse(bytes), 3_000_000_000L)
 *         sender.teardown()
 *     }
 *
 * Every step checks the receiver's answer like a sender would and throws [SenderException] otherwise.
 * Control requests are serialised, so the heartbeat ([startHeartbeat]) can run next to the test.
 *
 * @param host the receiver's address.
 * @param port the receiver's control port.
 * @param identity who the sender is; keep it to connect again as the same sender.
 * @param clock the sender's clock.
 */
public class AirPlayTestSender(
    public val host: InetAddress,
    public val port: Int,
    public val identity: SenderIdentity = SenderIdentity(),
    public val clock: SenderClock = SenderClock(),
) : AutoCloseable {
    private var connection: SenderConnection? = null
    private var sharedSecret: ByteArray? = null
    private var fairPlay: FairPlayV2Sender? = null
    private var sessionKey: ByteArray? = null
    private var audioIv: ByteArray? = null
    private var timing: TimingResponder? = null
    private val streams = ArrayList<AutoCloseable>()
    private var heartbeat: Thread? = null

    /**
     * The receiver key this sender trusts after a PIN pairing, what an Apple sender stores (02 §5.6).
     * Kept across [connect]; a later [pairVerify] uses it.
     */
    public var pairedReceiverKey: ByteArray? = null
        private set

    /** The current control connection. */
    public val control: SenderConnection get() = checkNotNull(connection) { "not connected" }

    /** The media keys in use after [setupSession]: `session_key` and `eiv`. */
    public val mediaKeys: Pair<ByteArray, ByteArray>
        get() = checkNotNull(sessionKey) { "no session" }.copyOf() to checkNotNull(audioIv).copyOf()

    /** The timing responder's port, after [setupSession]. */
    public val timingResponder: TimingResponder get() = checkNotNull(timing) { "no session" }

    /** Opens a new control connection, closing the current one and dropping its pairing and session. */
    public fun connect(): SenderConnection {
        closeConnection()
        return SenderConnection(host, port, identity).also { connection = it }
    }

    /**
     * `GET /info` with the `txtAirPlay` qualifier, then the full `GET /info`, as a sender opens a
     * connection (01 §6.1). Returns the full dictionary; its `pk` is the receiver's current identity key.
     */
    public fun info(): PlistDict {
        val qualifier = buildPlistDict { put("qualifier", PlistArray(listOf(PlistString("txtAirPlay")))) }
        val first = ok(request("GET", "/info", BinaryPlist.encode(qualifier), BPLIST), "GET /info (qualifier)")
        dict(first.body, "GET /info (qualifier)").data("txtAirPlay")
            ?: throw SenderException("GET /info with the txtAirPlay qualifier has no txtAirPlay")
        return dict(ok(request("GET", "/info"), "GET /info").body, "GET /info")
    }

    /**
     * Legacy PIN pairing then pair-verify (01 §4.2, 02 §4-§5): on one connection `GET /info` and
     * `POST /pair-pin-start`, then the PIN from [pins] and, on a new connection, the three
     * `pair-setup-pin` steps and pair-verify. Leaves that connection open and verified.
     *
     * @throws SenderException if the receiver refuses a step (a wrong PIN gets 470 and a closed
     *   connection), or returns a key other than the one `/info` advertises.
     */
    public fun pairWithPin(pins: PinSource) {
        connect()
        val advertised = info().data("pk") ?: throw SenderException("GET /info has no pk")
        ok(request("POST", "/pair-pin-start"), "POST /pair-pin-start")
        closeConnection()
        val pin = pins.pin()
        connect()
        val pairing = PinPairingClient(pin, identity.deviceId, identity.longTermKey)
        val r1 = ok(request("POST", "/pair-setup-pin", pairing.step1(), BPLIST), "pair-setup-pin step 1")
        val r2 = ok(request("POST", "/pair-setup-pin", pairing.step2(r1.body), BPLIST), "pair-setup-pin step 2")
        val r3 = ok(request("POST", "/pair-setup-pin", pairing.step3(r2.body), BPLIST), "pair-setup-pin step 3")
        val receiverKey = pairing.receiverKey(r3.body)
        if (!receiverKey.contentEquals(advertised)) {
            throw SenderException("PIN pairing returned a key other than the advertised pk")
        }
        pairedReceiverKey = receiverKey
        pairVerify()
    }

    /**
     * Pair-verify on the current connection against [receiverKey], the stored pairing by default (02 §4).
     * A sender that trusts the receiver's `pk` starts with this instead of a PIN.
     *
     * @throws SenderException if the receiver refuses (Mode P: 470 and a closed connection for a key
     *   it does not trust) or signs with another key.
     */
    public fun pairVerify(receiverKey: ByteArray = checkNotNull(pairedReceiverKey) { "never paired" }) {
        val verify = PairVerifyClient(identity.longTermKey)
        val v1 = ok(request("POST", "/pair-verify", verify.step1(), OCTET_STREAM), "pair-verify step 1")
        val v2 =
            ok(request("POST", "/pair-verify", verify.step2(v1.body, receiverKey), OCTET_STREAM), "pair-verify step 2")
        if (v2.body.isNotEmpty()) throw SenderException("pair-verify step 2 answered ${v2.body.size} bytes")
        sharedSecret = verify.sharedSecret
    }

    /** The two `/fp-setup` exchanges of FairPlay v2 (04 §1), checking the receiver's confirmation. */
    public fun fairPlaySetup(mode: Int = 0) {
        val sender = FairPlayV2Sender(mode)
        val reply1 = ok(request("POST", "/fp-setup", sender.setupRequest(), OCTET_STREAM), "fp-setup 1")
        val reply2 = ok(request("POST", "/fp-setup", sender.handshakeRequest(reply1.body), OCTET_STREAM), "fp-setup 2")
        sender.checkHandshakeReply(reply2.body)
        fairPlay = sender
    }

    /**
     * The session SETUP (03 §1.2): a fresh media key wrapped as `ekey`, a fresh `eiv`, and the timing
     * port of a new [TimingResponder]. Derives `session_key` like the receiver (03 §2.1).
     *
     * @param mirroring `isScreenMirroringSession`.
     * @param headers extra request headers, for example a Digest `Authorization`.
     * @return the response dictionary (`timingPort`, `eventPort`).
     */
    public fun setupSession(
        mirroring: Boolean = true,
        headers: List<Pair<String, String>> = emptyList(),
    ): PlistDict {
        val sender = checkNotNull(fairPlay) { "fairPlaySetup first" }
        val mediaKey = Crypto.randomBytes(16)
        val eiv = Crypto.randomBytes(16)
        val responder = timing ?: TimingResponder(clock).also { timing = it }
        val body =
            buildPlistDict {
                put("ekey", sender.keyBlob(mediaKey))
                put("eiv", eiv)
                put("et", 32)
                put("timingProtocol", "NTP")
                put("timingPort", responder.port)
                put("isScreenMirroringSession", mirroring)
                put("deviceID", identity.deviceId)
                put("macAddress", identity.deviceId)
                put("name", identity.name)
                put("model", identity.model)
                put("osName", "macOS")
                put("osVersion", "26.1")
                put("sourceVersion", SenderConnection.USER_AGENT.removePrefix("AirPlay/"))
                put("sessionUUID", UUID.randomUUID().toString().uppercase(Locale.ROOT))
            }
        val reply = dict(ok(setup(body, headers), "session SETUP").body, "session SETUP")
        sessionKey = MediaKeys.sessionKey(mediaKey, sharedSecret)
        audioIv = eiv
        return reply
    }

    /**
     * A type-110 stream SETUP (03 §1.3.1) with a random 64-bit `streamConnectionID`, and the TCP
     * connection to the `dataPort` it returns. The returned sender is closed with this one.
     */
    public fun setupMirroring(): MirroringSender {
        val id = Crypto.randomBytes(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val stream =
            buildPlistDict {
                put("type", TYPE_MIRRORING)
                put("streamConnectionID", PlistInteger.ofUnsigned(id.toULong()))
                put("latencyMs", 90)
            }
        val reply = streamReply(stream, "mirroring SETUP")
        val dataPort = reply.long("dataPort")?.toInt() ?: throw SenderException("mirroring SETUP has no dataPort")
        val sender = MirroringSender(checkNotNull(sessionKey) { "no session" }, id, clock)
        streams += sender
        sender.connect(control.remoteAddress, dataPort)
        return sender
    }

    /**
     * A type-96 ALAC stream SETUP (03 §1.3.2: `ct` 2, `spf` 352, 44.1 kHz stereo), with this side's
     * control port for resend requests, and a resend responder running. The returned sender is closed
     * with this one.
     *
     * @param usingScreen whether the audio belongs to the mirroring session.
     */
    public fun setupAudio(
        usingScreen: Boolean = true,
        latencyFrames: Int = AudioSender.DEFAULT_LATENCY_FRAMES,
    ): AudioSender {
        val (key, eiv) = mediaKeys
        val sender = AudioSender(key, eiv, clock, latencyFrames = latencyFrames)
        streams += sender
        val stream =
            buildPlistDict {
                put("type", TYPE_AUDIO)
                put("ct", 2)
                put("spf", sender.framesPerPacket)
                put("sr", sender.sampleRate)
                put("audioFormat", ALAC_44100_16_2)
                put("controlPort", sender.controlPort)
                put("latencyMin", 11_025)
                put("latencyMax", 88_200)
                put("usingScreen", usingScreen)
                put("isMedia", true)
                put("audioMode", "default")
            }
        val reply = streamReply(stream, "audio SETUP")
        val dataPort = reply.long("dataPort")?.toInt() ?: throw SenderException("audio SETUP has no dataPort")
        val controlPort = reply.long("controlPort")?.toInt() ?: throw SenderException("audio SETUP has no controlPort")
        sender.connect(control.remoteAddress, dataPort, controlPort)
        sender.startResendResponder()
        return sender
    }

    /** `RECORD` (03 §1.5); with an audio stream set up, `RTP-Info` names its first packet. */
    public fun record() {
        val audio = streams.filterIsInstance<AudioSender>().lastOrNull()
        val headers = ArrayList<Pair<String, String>>()
        headers += "Range" to "npt=0-"
        if (audio != null) {
            headers += "RTP-Info" to "seq=${audio.firstSequence};rtptime=${audio.firstRtpTime.toUInt()}"
        }
        ok(request("RECORD", SESSION_URI, headers = headers), "RECORD")
    }

    /** `SET_PARAMETER volume` in AirPlay dB, −30..0, or −144 for mute (03 §6.1). */
    public fun setVolume(db: Double) {
        val body = String.format(Locale.ROOT, "volume: %.6f\r\n", db).toByteArray(Charsets.US_ASCII)
        ok(request("SET_PARAMETER", SESSION_URI, body, TEXT_PARAMETERS), "SET_PARAMETER volume")
    }

    /** Track metadata as DMAP (`mlit` with `minm`, `asar`, `asal`), the way senders send it (01 §6.11). */
    public fun setMetadata(
        title: String,
        artist: String,
        album: String,
    ) {
        val items = dmap("minm", title) + dmap("asar", artist) + dmap("asal", album)
        ok(request("SET_PARAMETER", SESSION_URI, dmap("mlit", items), DMAP), "SET_PARAMETER metadata")
    }

    /** Artwork bytes of [contentType] (`image/jpeg`, `image/png`; empty `image/none` clears it). */
    public fun setArtwork(
        image: ByteArray,
        contentType: String,
    ) {
        ok(request("SET_PARAMETER", SESSION_URI, image, contentType), "SET_PARAMETER artwork")
    }

    /** `POST /feedback`, the sender's heartbeat (03 §6.4). */
    public fun feedback() {
        ok(request("POST", "/feedback"), "POST /feedback")
    }

    /**
     * Sends `/feedback` every [intervalMillis] on a daemon thread until the connection closes or this
     * sender is closed, as senders do during a session.
     */
    public fun startHeartbeat(intervalMillis: Long = HEARTBEAT_MILLIS) {
        check(heartbeat == null) { "heartbeat already running" }
        val connection = control
        val thread =
            Thread({
                try {
                    while (!connection.isClosedByReceiver) {
                        Thread.sleep(intervalMillis)
                        ok(connection.request("POST", "/feedback"), "POST /feedback")
                    }
                } catch (ignored: InterruptedException) {
                    // Stopped.
                } catch (ignored: SenderException) {
                    // The connection is gone; the test sees that through awaitClosedByReceiver.
                }
            }, "test-sender-heartbeat")
        thread.isDaemon = true
        thread.start()
        heartbeat = thread
    }

    /**
     * Streams [clip] over the mirroring stream and a tone over the audio stream, whichever are set up,
     * in real time for [durationNanos] on two threads, and waits for both.
     */
    public fun stream(
        clip: H264Clip?,
        durationNanos: Long,
    ): StreamResult = startStreaming(clip, durationNanos).await()

    /** Like [stream], without waiting: the returned handle waits or stops. */
    public fun startStreaming(
        clip: H264Clip?,
        durationNanos: Long,
    ): MediaStreaming {
        val video = streams.filterIsInstance<MirroringSender>().lastOrNull()
        val audio = streams.filterIsInstance<AudioSender>().lastOrNull()
        return MediaStreaming(if (clip == null) null else video, clip, audio, durationNanos)
    }

    /**
     * `TEARDOWN` of the stream types in [streamTypes] (110 mirroring, 96 audio), or of the whole session
     * when empty (01 §6.13). After a whole-session teardown the receiver answers `Connection: close` and
     * this sender closes the connection, its streams and its timing responder, as senders do.
     *
     * @return the response.
     */
    public fun teardown(vararg streamTypes: Int): RtspResponse {
        val body =
            if (streamTypes.isEmpty()) {
                ByteArray(0)
            } else {
                BinaryPlist.encode(
                    buildPlistDict {
                        put("streams", PlistArray(streamTypes.map { type -> buildPlistDict { put("type", type) } }))
                    },
                )
            }
        val response = ok(request("TEARDOWN", SESSION_URI, body, if (body.isEmpty()) null else BPLIST), "TEARDOWN")
        if (streamTypes.isEmpty() && response.headers["Connection"].equals("close", ignoreCase = true)) {
            closeConnection()
        }
        return response
    }

    /** Waits up to [timeoutMillis] for the receiver to close the control connection; whether it did. */
    public fun awaitClosedByReceiver(timeoutMillis: Long): Boolean = control.awaitClosed(timeoutMillis)

    /** Sends a request on the control connection and returns the response, whatever its status. */
    public fun request(
        method: String,
        target: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): RtspResponse = control.request(method, target, body, contentType, headers)

    /** Closes the streams, the timing responder, the heartbeat and the control connection. */
    override fun close() {
        closeConnection()
    }

    private fun closeConnection() {
        heartbeat?.interrupt()
        heartbeat = null
        for (stream in streams) stream.close()
        streams.clear()
        timing?.close()
        timing = null
        connection?.close()
        connection = null
        sharedSecret = null
        fairPlay = null
        sessionKey = null
        audioIv = null
    }

    private fun setup(
        body: PlistDict,
        headers: List<Pair<String, String>> = emptyList(),
    ): RtspResponse = request("SETUP", SESSION_URI, BinaryPlist.encode(body), BPLIST, headers)

    private fun streamReply(
        stream: PlistDict,
        what: String,
    ): PlistDict {
        val reply = dict(ok(setup(buildPlistDict { put("streams", PlistArray(listOf(stream))) }), what).body, what)
        val element = reply.array("streams")?.items?.singleOrNull() as? PlistDict
        return element ?: throw SenderException("$what answered no stream")
    }

    /**
     * The streaming threads started by [startStreaming].
     */
    public class MediaStreaming internal constructor(
        video: MirroringSender?,
        clip: H264Clip?,
        audio: AudioSender?,
        durationNanos: Long,
    ) {
        @Volatile private var stopped = false
        private var videoCompleted = true
        private var audioCompleted = true
        private val videoThread =
            video?.let { sender ->
                Thread({ videoCompleted = sender.stream(checkNotNull(clip), durationNanos) { stopped } }, "test-video")
            }
        private val audioThread =
            audio?.let { sender -> Thread({ audioCompleted = sender.stream(durationNanos) { stopped } }, "test-audio") }
        private val videoSender = video
        private val audioSender = audio

        init {
            for (thread in listOfNotNull(videoThread, audioThread)) {
                thread.isDaemon = true
                thread.start()
            }
        }

        /** Stops sending; [await] then returns soon. */
        public fun stop() {
            stopped = true
        }

        /** Waits for both threads and reports what was sent. */
        public fun await(): StreamResult {
            videoThread?.join()
            audioThread?.join()
            return StreamResult(
                videoFrames = videoSender?.framesSent ?: 0,
                audioPackets = audioSender?.packetsSent ?: 0,
                completed = !stopped && videoCompleted && audioCompleted,
            )
        }
    }

    /**
     * What [stream] sent.
     *
     * @property videoFrames video records written.
     * @property audioPackets RTP audio packets sent.
     * @property completed whether both streams ran for the whole duration (false: stopped, or the
     *   receiver closed the mirroring connection).
     */
    public data class StreamResult(
        val videoFrames: Int,
        val audioPackets: Int,
        val completed: Boolean,
    )

    /** Protocol constants. */
    public companion object {
        /** The request target senders use for session requests. */
        public const val SESSION_URI: String = "rtsp://127.0.0.1/2837493845"

        /** Stream type of screen mirroring. */
        public const val TYPE_MIRRORING: Int = 110

        /** Stream type of realtime audio. */
        public const val TYPE_AUDIO: Int = 96

        private const val ALAC_44100_16_2 = 0x40000
        private const val HEARTBEAT_MILLIS = 2_000L
        private const val BPLIST = "application/x-apple-binary-plist"
        private const val OCTET_STREAM = "application/octet-stream"
        private const val TEXT_PARAMETERS = "text/parameters"
        private const val DMAP = "application/x-dmap-tagged"

        private fun ok(
            response: RtspResponse,
            what: String,
        ): RtspResponse {
            if (response.status !=
                200
            ) {
                throw SenderException("$what: ${response.status} ${response.reason}", response.status)
            }
            return response
        }

        /** One DMAP item: tag, u32be length, value. */
        private fun dmap(
            tag: String,
            value: String,
        ): ByteArray = dmap(tag, value.toByteArray(Charsets.UTF_8))

        private fun dmap(
            tag: String,
            value: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag.toByteArray(Charsets.US_ASCII))
            out.write(ByteArray(4).also { it.putI32Be(0, value.size) })
            out.write(value)
            return out.toByteArray()
        }
    }
}
