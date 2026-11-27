package ai.aneesiqbal.rozan.airplay.rtsp

import ai.aneesiqbal.rozan.airplay.ProtocolException

/**
 * An RTSP or HTTP message on the control channel could not be parsed or exceeds a limit.
 *
 * The connection cannot be trusted to be in sync after this, so it should be closed. Before closing, the
 * session layer may answer with [status] (for example 400, 413, 414, 431, 501 or 505).
 *
 * @param message what was wrong.
 * @param status the response status code that best describes the problem.
 * @param cause the underlying failure, if any.
 */
public class RtspException(
    message: String,
    public val status: Int = RtspStatus.BAD_REQUEST,
    cause: Throwable? = null,
) : ProtocolException(message, cause)
