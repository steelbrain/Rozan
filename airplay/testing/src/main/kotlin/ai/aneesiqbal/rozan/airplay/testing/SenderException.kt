package ai.aneesiqbal.rozan.airplay.testing

import java.io.IOException

/**
 * The receiver refused a step, answered something a real sender would reject, or closed the connection.
 *
 * @property status the RTSP status of the refusing response, or 0 when there was no response (a closed
 *   connection, a bad body).
 * @property closedByReceiver whether the receiver closed the control connection.
 */
public class SenderException(
    message: String,
    public val status: Int = 0,
    public val closedByReceiver: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)
