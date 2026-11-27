package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspProtocol
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponse
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponseReader
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One RTSP control connection to the receiver, as an Apple sender opens it: CSeq counts from 0 and every
 * request carries the sender's `User-Agent`, `DACP-ID`, `Active-Remote` and `X-Apple-Client-Name`.
 *
 * Requests are serialised, so a heartbeat thread may share the connection with the test thread.
 *
 * @param host the receiver's address.
 * @param port the receiver's control port.
 * @param identity the sender, for the identifying headers.
 * @param timeoutMillis the bound on connecting and on waiting for each response.
 */
public class SenderConnection(
    host: InetAddress,
    port: Int,
    private val identity: SenderIdentity,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val socket =
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMillis)
            soTimeout = timeoutMillis
        }
    private val reader = RtspResponseReader(socket.getInputStream())
    private val lock = Any()
    private var cSeq = 0

    @Volatile private var closedByPeer = false

    /** This end's address, the one the receiver sees as the client address. */
    public val localAddress: InetAddress = socket.localAddress

    /** The receiver's address. */
    public val remoteAddress: InetAddress = socket.inetAddress

    /** Whether the receiver has closed the connection, as far as this side has noticed. */
    public val isClosedByReceiver: Boolean get() = closedByPeer

    /**
     * Sends a request and waits for its response.
     *
     * @throws SenderException if the receiver closes the connection instead of answering, or the
     *   response does not arrive within the timeout.
     */
    public fun request(
        method: String,
        target: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): RtspResponse =
        synchronized(lock) {
            val builder = RtspRequest.Builder(method, target, RtspProtocol.RTSP_1_0)
            builder.header("CSeq", (cSeq++).toString())
            builder.header("User-Agent", USER_AGENT)
            builder.header("X-Apple-ProtocolVersion", "1")
            builder.header("DACP-ID", identity.dacpId)
            builder.header("Active-Remote", identity.activeRemote)
            builder.header("X-Apple-Client-Name", identity.name)
            for ((name, value) in headers) builder.header(name, value)
            if (body.isNotEmpty() || contentType != null) builder.body(body, contentType)
            try {
                val output = socket.getOutputStream()
                output.write(builder.build().toByteArray())
                output.flush()
                reader.read() ?: throw closed("$method $target", null)
            } catch (e: SenderException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw SenderException("no response to $method $target within $timeoutMillis ms", cause = e)
            } catch (e: RtspException) {
                throw SenderException("malformed response to $method $target", cause = e)
            } catch (e: IOException) {
                throw closed("$method $target", e)
            }
        }

    /**
     * Waits up to [timeoutMillis] for the receiver to close the connection, which it does when it ends
     * the session on its own (for example when the viewer presses Back). Returns whether it did. Bytes
     * the receiver sends unasked are an error; it never sends any.
     */
    public fun awaitClosed(timeoutMillis: Long): Boolean =
        synchronized(lock) {
            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
            try {
                while (!closedByPeer) {
                    val left = (deadline - System.nanoTime()) / NANOS_PER_MILLI
                    if (left <= 0) break
                    socket.soTimeout = left.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    closedByPeer = readsEndOfStream()
                }
            } catch (ignored: IOException) {
                // A reset is a close too.
                closedByPeer = true
            } finally {
                if (!socket.isClosed) socket.soTimeout = this.timeoutMillis
            }
            closedByPeer
        }

    /** Reads until the timeout: true at the end of the stream, false when nothing arrived in time. */
    private fun readsEndOfStream(): Boolean {
        val unexpected =
            try {
                reader.read() ?: return true
            } catch (ignored: SocketTimeoutException) {
                return false
            } catch (e: RtspException) {
                throw IllegalStateException("the receiver sent malformed bytes without a request", e)
            }
        throw IllegalStateException("the receiver sent ${unexpected.status} without a request")
    }

    /** Closes the connection from this side. */
    override fun close() {
        socket.close()
    }

    private fun closed(
        what: String,
        cause: Throwable?,
    ): SenderException {
        closedByPeer = true
        return SenderException("the receiver closed the connection instead of answering $what", 0, true, cause)
    }

    /** Defaults. */
    public companion object {
        /** Connect and response timeout. */
        public const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000

        /** The `User-Agent` an iOS 27 sender sends (01 §3.3). */
        public const val USER_AGENT: String = "AirPlay/980.67.2"

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
