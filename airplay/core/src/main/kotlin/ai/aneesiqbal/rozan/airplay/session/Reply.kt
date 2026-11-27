package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.rtsp.RtspProtocol
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponse
import ai.aneesiqbal.rozan.airplay.rtsp.RtspStatus

/**
 * What a handler answers; [ReplyWriter] adds the headers every response needs (01 §3.4).
 *
 * @property headers extra header fields, after the standard ones.
 * @property close close the connection after sending.
 * @property analogJack send `Audio-Jack-Status: connected; type=analog` (RECORD) instead of digital.
 */
internal class Reply(
    val status: Int,
    val body: ByteArray = EMPTY,
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val close: Boolean = false,
    val analogJack: Boolean = false,
) {
    companion object {
        private val EMPTY = ByteArray(0)

        /** 200 with no body. */
        val OK: Reply = Reply(RtspStatus.OK)
    }
}

/** Status codes the RTSP package has no constant for (01 §3.4). */
internal object Status {
    /** Another client owns the receiver. */
    const val CONFLICT = 409

    /** GET/SET_PARAMETER without Content-Type. */
    const val PARAMETER_NOT_UNDERSTOOD = 451

    /** Pairing failure, or a request that needs authentication first. */
    const val CONNECTION_AUTHORIZATION_REQUIRED = 470

    /** The reason phrase for [status], using the Apple TV phrases where the RTSP tables have none. */
    fun reason(
        status: Int,
        protocol: RtspProtocol,
    ): String =
        when (status) {
            CONFLICT -> "Conflict"
            CONNECTION_AUTHORIZATION_REQUIRED -> "Connection Authorization Required"
            else -> RtspStatus.reasonPhrase(status, protocol).ifEmpty { "Error" }
        }
}

/** Turns a [Reply] into wire bytes with the headers of 01 §3.4. */
internal object ReplyWriter {
    private const val DIGITAL_JACK = "connected; type=digital"
    private const val ANALOG_JACK = "connected; type=analog"

    /**
     * The response to a request of [protocol] whose validated CSeq is [cSeq] (null to leave it out, as
     * for HTTP/1.1 or a request whose CSeq was missing).
     */
    fun encode(
        reply: Reply,
        protocol: RtspProtocol,
        cSeq: String?,
    ): ByteArray {
        val builder = RtspResponse.Builder(protocol, reply.status).reason(Status.reason(reply.status, protocol))
        if (protocol == RtspProtocol.RTSP_1_0 && cSeq != null) builder.header("CSeq", cSeq)
        builder.header("Server", Profile.SERVER)
        if (protocol == RtspProtocol.RTSP_1_0) {
            builder.header("Audio-Jack-Status", if (reply.analogJack) ANALOG_JACK else DIGITAL_JACK)
        }
        if (reply.body.isNotEmpty() || reply.contentType != null) builder.body(reply.body, reply.contentType)
        for ((name, value) in reply.headers) builder.header(name, value)
        return builder.build().toByteArray()
    }
}
