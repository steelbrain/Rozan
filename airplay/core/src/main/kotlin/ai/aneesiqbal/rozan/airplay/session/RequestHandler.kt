package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.pairing.ConnectionPairing
import ai.aneesiqbal.rozan.airplay.pairing.PairingReply
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspProtocol
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
import ai.aneesiqbal.rozan.airplay.rtsp.RtspStatus

/**
 * Routes the requests of one control connection (docs/protocol/01 §5, §6): discovery and pairing
 * requests here, session and stream requests in [MediaControl], the classic RAOP `ANNOUNCE` and `SETUP`
 * in [ClassicControl]. Enforces the single-client rule (01 §3.1) and the pre-authentication gate (01 §5).
 */
internal class RequestHandler(
    private val connection: ControlConnection,
    private val core: ReceiverCore,
    private val pairing: ConnectionPairing,
    private val media: MediaControl,
) {
    private var fairPlay: FairPlayHandler? = null
    private val classic = ClassicControl(connection, pairing, media)

    /** The reply to [request]. Exceptions that describe a bad request become their status. */
    fun handle(request: RtspRequest): Reply {
        // 01 §3.1: while a session streams, every other address gets 409; never its owner.
        if (core.conflicts(connection.client)) return Reply(Status.CONFLICT, close = true)
        if (!connection.announced) {
            connection.announced = true
            val name = ClientText.clean(request.headers["X-Apple-Client-Name"])
            if (name != null) connection.clientInfo = connection.clientInfo.copy(name = name)
            core.events.connectionStarted(connection.clientInfo)
        }
        return try {
            route(request)
        } catch (e: RtspException) {
            connection.log(ReceiverLog.Level.WARN, "${request.method} refused (${e.status}): ${e.message}")
            Reply(e.status)
        }
    }

    private fun route(request: RtspRequest): Reply {
        if (request.protocol == RtspProtocol.HTTP_1_1) return http(request)
        return when (request.method) {
            "GET" -> if (path(request) == "/info") info(request) else Reply.OK
            "OPTIONS" -> Reply(RtspStatus.OK, headers = listOf("Public" to Profile.PUBLIC_METHODS))
            "POST" -> post(request)
            "ANNOUNCE" -> classic.announce(request, fairPlay)
            "SETUP" -> setup(request)
            "RECORD" -> gated { media.record() }
            "GET_PARAMETER" -> gated { media.getParameter(request) }
            "SET_PARAMETER" -> gated { media.setParameter(request) }
            "FLUSH" -> gated { media.flush(request) }
            "TEARDOWN" -> teardown(request)
            else -> Reply(RtspStatus.NOT_IMPLEMENTED)
        }
    }

    /** The connection closed: zeroes the classic ANNOUNCE description, if any. */
    fun close() = classic.clear()

    /** 01 §6.8: a body-less SETUP with a `Transport` header is the classic one (§6.8.1). */
    private fun setup(request: RtspRequest): Reply {
        val transport = request.headers["Transport"]
        return if (transport != null &&
            request.body.isEmpty()
        ) {
            classic.setup(transport)
        } else {
            media.setup(request, fairPlay)
        }
    }

    /** Any TEARDOWN also drops a classic description that no SETUP used (03 §2.4). */
    private fun teardown(request: RtspRequest): Reply {
        classic.clear()
        return media.teardown(request)
    }

    private fun post(request: RtspRequest): Reply =
        when (path(request)) {
            "/pair-pin-start" -> {
                val name = ClientText.clean(request.headers["X-Apple-Client-Name"]) ?: connection.clientInfo.name
                pairingReply(core.pairing.pinStart(connection.client, name))
            }

            "/pair-setup-pin" -> {
                pairingReply(pairing.pairSetupPin(request.body))
            }

            "/pair-setup" -> {
                pairingReply(pairing.pairSetup(request.body))
            }

            "/pair-verify" -> {
                pairingReply(pairing.pairVerify(request.body))
            }

            "/fp-setup" -> {
                fpSetup(request.body)
            }

            "/feedback" -> {
                media.feedback()
                Reply.OK
            }

            "/audioMode", "/command" -> {
                gated { Reply.OK }
            }

            // 01 §6.17: unknown POST paths get 200.
            else -> {
                Reply.OK
            }
        }

    /** 01 §6.20: this profile serves no HTTP/1.1 resources. */
    private fun http(request: RtspRequest): Reply {
        val path = path(request)
        val legacyMirroring =
            (request.method == "GET" && path == "/stream.xml") || (request.method == "POST" && path == "/stream")
        return if (legacyMirroring) Reply(RtspStatus.NOT_FOUND) else Reply(RtspStatus.NOT_IMPLEMENTED)
    }

    private fun info(request: RtspRequest): Reply {
        if (!core.infoAllowed(connection.client)) return Reply(RtspStatus.SERVICE_UNAVAILABLE)
        val qualifiers = InfoResponse.qualifiers(request)
        val advertisement = core.advertisement()
        val dict =
            if (qualifiers == null) {
                InfoResponse.full(advertisement, core.config.display, core.displayUuid, media.volumeDb.toDouble())
            } else {
                InfoResponse.qualified(advertisement, qualifiers)
            }
        return Reply(RtspStatus.OK, BinaryPlist.encode(dict), ContentTypes.BPLIST)
    }

    /** `POST /fp-setup` (01 §6.7): lengths and version checked here, the rest by [FairPlayHandler]. */
    private fun fpSetup(body: ByteArray): Reply {
        val step =
            when {
                body.size == FP_HANDSHAKE_BYTES -> 2
                body.size != FP_SETUP_BYTES || !body.startsWith(FPLY) -> return Reply(RtspStatus.BAD_REQUEST)
                body[FP_VERSION_OFFSET].toInt() !in FP_VERSIONS -> return Reply(RtspStatus.NOT_IMPLEMENTED)
                else -> 1
            }
        val handler = fairPlay ?: core.fairPlay.create().also { fairPlay = it }
        val reply =
            try {
                if (step == 1) handler.setup(body) else handler.handshake(body)
            } catch (e: FairPlayFailure) {
                // The FairPlay state is reset; the sender has to start over on a new connection.
                connection.log(ReceiverLog.Level.WARN, "fp-setup step $step failed: ${e.message}")
                return Reply(RtspStatus.INTERNAL_SERVER_ERROR, close = true)
            }
        return Reply(RtspStatus.OK, reply, ContentTypes.OCTET_STREAM)
    }

    private fun pairingReply(reply: PairingReply): Reply =
        when (reply) {
            is PairingReply.Ok -> Reply(RtspStatus.OK, reply.body, reply.contentType)
            is PairingReply.Error -> Reply(reply.status, close = reply.close)
        }

    /** 01 §5: requests that need an authenticated connection get 470 and are otherwise ignored. */
    private inline fun gated(block: () -> Reply): Reply =
        if (pairing.isAuthenticated) block() else Reply(Status.CONNECTION_AUTHORIZATION_REQUIRED)

    private fun path(request: RtspRequest): String = request.target.substringBefore('?')

    private companion object {
        const val FP_SETUP_BYTES = 16
        const val FP_HANDSHAKE_BYTES = 164
        const val FP_VERSION_OFFSET = 4
        val FP_VERSIONS = 2..3
        val FPLY = byteArrayOf(0x46, 0x50, 0x4C, 0x59)
    }
}
