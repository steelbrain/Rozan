package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.media.AudioFormats
import ai.aneesiqbal.rozan.airplay.media.AudioStreamRequest
import ai.aneesiqbal.rozan.airplay.pairing.ConnectionPairing
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
import ai.aneesiqbal.rozan.airplay.rtsp.RtspStatus

/** Local UDP ports of a classic session, for the classic SETUP reply's `Transport` (01 §6.8.1). */
internal class ClassicPorts(
    val dataPort: Int,
    val controlPort: Int,
    val timingPort: Int,
)

/**
 * The classic RAOP requests of one control connection (docs/protocol/01 §4.3.2, §6.8.1, §6.17; 02 §6.4):
 * `ANNOUNCE`, which carries the Digest exchange in both PIN modes and leaves the decrypted stream
 * description on the connection, and the classic `SETUP`, which turns that description into a session
 * with one realtime ALAC stream through [MediaControl.openClassicSession].
 *
 * Used from the connection's thread only; the description never leaves it. [clear] zeroes it.
 */
internal class ClassicControl(
    private val connection: ControlConnection,
    private val pairing: ConnectionPairing,
    private val media: MediaControl,
) {
    /** What the last authenticated ANNOUNCE described, until a classic SETUP uses it. */
    private var announced: Announcement? = null

    private class Announcement(
        val fairPlayKey: ByteArray,
        val audioIv: ByteArray,
        val framesPerPacket: Int,
        val latencyMinFrames: Int?,
        val latencyMaxFrames: Int?,
    ) {
        fun destroy() {
            fairPlayKey.fill(0)
            audioIv.fill(0)
        }
    }

    /**
     * `ANNOUNCE` (01 §6.17): the SDP is checked before access control, so an unplayable stream gets no code;
     * then the Digest gate (02 §6.4), then the FairPlay unwrap of `fpaeskey`. [fairPlay] is the connection's
     * FairPlay state, if fp-setup ran.
     */
    fun announce(
        request: RtspRequest,
        fairPlay: FairPlayHandler?,
    ): Reply {
        if (ContentTypes.mediaType(request.headers["Content-Type"]) != ContentTypes.SDP) {
            throw RtspException("ANNOUNCE body is not application/sdp", RtspStatus.UNSUPPORTED_MEDIA_TYPE)
        }
        val description = SessionDescription.parse(request.body)
        try {
            val handler = fairPlay ?: throw RtspException("ANNOUNCE before fp-setup")
            // The classic flow has no deviceID; DACP-ID tells two senders on one address apart (02 §6.4).
            media.authorize(request, ClientText.clean(request.headers["DACP-ID"]), senderName = null)?.let { return it }
            val key = unwrap(handler, description.fairPlayKeyBlob)
            clear()
            announced =
                Announcement(
                    fairPlayKey = key,
                    audioIv = description.audioIv.copyOf(),
                    framesPerPacket = description.framesPerPacket,
                    latencyMinFrames = description.latencyMinFrames,
                    latencyMaxFrames = description.latencyMaxFrames,
                )
            connection.log(ReceiverLog.Level.INFO, "ANNOUNCE accepted: ALAC, ${description.framesPerPacket} frames")
            return Reply.OK
        } finally {
            description.destroy()
        }
    }

    /**
     * The classic `SETUP` (01 §6.8.1) with the request's [transport] header: 470 before authentication, 455
     * without an ANNOUNCE, 400/461 for a bad `Transport`; otherwise the session and its audio stream, and a
     * `Transport` reply with the three local ports and `Session: 1`. The ANNOUNCE description is used up.
     */
    fun setup(transport: String): Reply {
        if (!pairing.isAuthenticated) return Reply(Status.CONNECTION_AUTHORIZATION_REQUIRED)
        val description =
            announced ?: throw RtspException(
                "classic SETUP before ANNOUNCE",
                RtspStatus.METHOD_NOT_VALID_IN_THIS_STATE,
            )
        val ports = ClassicTransport.parse(transport)
        announced = null
        try {
            val audio =
                AudioStreamRequest(
                    compressionType = AudioFormats.CT_ALAC,
                    framesPerPacket = description.framesPerPacket,
                    sampleRate = SetupRequest.SAMPLE_RATE,
                    clientControlPort = ports.clientControlPort,
                    latencyMinFrames = description.latencyMinFrames,
                    latencyMaxFrames = description.latencyMaxFrames,
                    usingScreen = false,
                )
            val local =
                media.openClassicSession(description.fairPlayKey, description.audioIv, ports.clientTimingPort, audio)
            val reply = ClassicTransport.reply(local.dataPort, local.controlPort, local.timingPort)
            return Reply(RtspStatus.OK, headers = listOf("Transport" to reply, "Session" to SESSION_ID))
        } finally {
            description.destroy()
        }
    }

    /** Drops and zeroes the ANNOUNCE description: on a new ANNOUNCE, a TEARDOWN, the connection's close. */
    fun clear() {
        announced?.destroy()
        announced = null
    }

    private companion object {
        /** The classic SETUP reply's `Session` (01 §6.8.1, as the AirTunes receiver in [NTO] sends it). */
        const val SESSION_ID = "1"

        /** 01 §6.17 step 5: the FairPlay key wrapped in `fpaeskey` (04 §1.5). */
        fun unwrap(
            handler: FairPlayHandler,
            keyBlob: ByteArray,
        ): ByteArray =
            try {
                handler.decryptKey(keyBlob)
            } catch (e: FairPlayFailure) {
                throw RtspException("FairPlay rejected fpaeskey", cause = e)
            }
    }
}
