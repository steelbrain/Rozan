package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.receiver.ClientInfo
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import ai.aneesiqbal.rozan.airplay.rtsp.RtspBodyLimit
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspProtocol
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequestReader
import ai.aneesiqbal.rozan.airplay.rtsp.RtspStatus
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One control connection (docs/protocol/01 §3, §8): reads requests strictly in order on its own thread,
 * answers each one, and on close tears down whatever session it owns (a TCP close is an implicit
 * whole-session TEARDOWN, 01 §6.13).
 *
 * Framing errors (oversized fields or bodies, bad request lines, a missing CSeq) get their status and
 * close this connection only. Other threads may end it with [terminate].
 *
 * Deadlines (01 §3.1), checked by housekeeping through [overdue] on the real monotonic clock (tests shrink
 * them through [ControlLimits]): until the connection is authenticated, each request must arrive whole
 * within [ControlLimits.requestTimeoutMillis] of its first byte, and the connection must authenticate
 * within [ControlLimits.authTimeoutMillis]. The idle timeout alone bounds one read, not a request, so a peer
 * sending a byte now and then could otherwise hold its slot for as long as it liked. An authenticated
 * connection must have a session within [ControlLimits.sessionStartTimeoutMillis] of authenticating; a
 * whole-session TEARDOWN ends its authentication, so one code never buys more than one session. The idle
 * timeout closes a quiet connection, except one between requests whose classic session's audio still
 * arrives ([MediaControl.audioKeepsAlive]).
 */
internal class ControlConnection(
    val id: Int,
    private val socket: Socket,
    private val core: ReceiverCore,
    private val onClosed: (ControlConnection) -> Unit,
) {
    /** The sender's address; media sockets talk only to it. */
    val client: InetAddress = socket.inetAddress

    /** What the UI is told about the sender; names come from headers and SETUP. */
    @Volatile var clientInfo: ClientInfo = ClientInfo(client.hostAddress, null, null)

    private val pairing = core.pairing.openConnection(client)
    private val media = MediaControl(this, core, pairing)
    private val handler = RequestHandler(this, core, pairing, media)

    @Volatile private var closed = false

    /** Where the connection is in its life, for the deadlines and eviction; written on its own thread. */
    private enum class Phase { UNAUTHENTICATED, AUTHENTICATED, IN_SESSION }

    @Volatile private var phaseSince = System.nanoTime()

    @Volatile private var phase = Phase.UNAUTHENTICATED

    /** When the first byte of the request being read arrived, or [NO_REQUEST] between requests. */
    @Volatile private var requestStartedAt = NO_REQUEST

    /**
     * Whether the server may close this connection to make room for a new one: it has not authenticated
     * and does not own the streaming session. An authenticated or streaming connection is never evicted.
     */
    val evictable: Boolean get() = phase == Phase.UNAUTHENTICATED && core.owner !== this

    private val finished = CountDownLatch(1)

    /** Set once the first request raised `onConnectionStarted`; the close then raises `onConnectionEnded`. */
    @Volatile var announced = false

    /** Body caps per request (01 §3.2, 03 §8.1), decided before the body is read. */
    private val bodyLimit =
        RtspBodyLimit { method, _, headers ->
            RequestBodyLimits.forRequest(
                method = method,
                mediaType = ContentTypes.mediaType(headers["Content-Type"]),
                authenticated = pairing.isAuthenticated,
                ownSession = core.owner === this,
            )
        }

    /** Serves the connection until it closes. Runs on the connection's own thread. */
    fun run() {
        guarded(core.log, "connection #$id") {
            try {
                serve()
            } catch (ignored: SocketTimeoutException) {
                log(ReceiverLog.Level.INFO, "idle for ${core.limits.idleTimeoutMillis / MILLIS_PER_SECOND} s, closing")
            } catch (e: IOException) {
                if (!closed) log(ReceiverLog.Level.INFO, "connection ended: ${describe(e)}")
            }
        }
        guarded(core.log, "closing connection #$id") { finish() }
        finished.countDown()
    }

    /** Waits up to [millis] for [run] to return; false on timeout. */
    fun awaitFinished(millis: Long): Boolean = finished.await(millis, TimeUnit.MILLISECONDS)

    /**
     * Ends this connection from another thread (receiver stop, disconnect from the TV, a takeover, the
     * feedback timeout): tears down the session now, firing the stopped events, then closes the socket so
     * the connection's thread exits. [endsSession] is false when a new session of the same sender takes
     * over, which is not a session end for the pairing layer.
     */
    fun terminate(
        reason: String,
        endsSession: Boolean = true,
    ) {
        log(ReceiverLog.Level.INFO, "terminating: $reason")
        media.endSession(reason, notifyPairing = endsSession)
        closeSocket()
    }

    /** Whether this connection owns a session whose `/feedback` stopped (03 §6.4). */
    fun feedbackExpired(now: Long): Boolean = media.feedbackExpired(now)

    /**
     * Why this connection is past one of its deadlines at [now] (`System.nanoTime()`), or null when it is
     * not. Any thread; the caller ends the connection with [terminate].
     */
    fun overdue(now: Long): String? {
        val limits = core.limits
        val started = requestStartedAt
        val inPhase = now - phaseSince
        return when (phase) {
            Phase.UNAUTHENTICATED -> {
                when {
                    started != NO_REQUEST && now - started > millisToNanos(limits.requestTimeoutMillis) -> {
                        "request not received in time"
                    }

                    inPhase > millisToNanos(limits.authTimeoutMillis) -> {
                        "not authenticated in time"
                    }

                    else -> {
                        null
                    }
                }
            }

            Phase.AUTHENTICATED -> {
                if (inPhase > millisToNanos(limits.sessionStartTimeoutMillis)) "authenticated but no session" else null
            }

            Phase.IN_SESSION -> {
                null
            }
        }
    }

    /** Logs [message] for this connection, identified by number only. */
    fun log(
        level: ReceiverLog.Level,
        message: String,
    ) = core.log(level, "#$id $message")

    private fun serve() {
        socket.soTimeout = core.limits.idleTimeoutMillis
        socket.tcpNoDelay = true
        val output = BufferedOutputStream(socket.getOutputStream())
        val input = RequestStartInput(socket.getInputStream())
        val reader = RtspRequestReader(input, ControlLimits.RTSP, ControlLimits.READ_BUFFER_BYTES, bodyLimit)
        log(ReceiverLog.Level.INFO, "connected")
        while (!closed) {
            val request =
                try {
                    reader.read()
                } catch (e: SocketTimeoutException) {
                    // 01 §3.1: between requests, a classic session's audio keeps its quiet connection open.
                    if (requestStartedAt != NO_REQUEST || !media.audioKeepsAlive()) throw e
                    log(ReceiverLog.Level.INFO, "idle, kept open: the classic session's audio still arrives")
                    continue
                } catch (e: RtspException) {
                    log(ReceiverLog.Level.WARN, "malformed request (${e.status}): ${e.message}")
                    send(output, Reply(e.status, close = true), RtspProtocol.RTSP_1_0, null)
                    return
                } ?: return
            // Bytes of the next request that are already buffered start its deadline at the next read.
            requestStartedAt = NO_REQUEST
            if (!respond(request, output)) return
        }
    }

    /** Notes when the first byte of a request arrives, for [ControlLimits.requestTimeoutMillis]. */
    private inner class RequestStartInput(
        input: InputStream,
    ) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) requestBytesArrived() }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(buffer, offset, length).also { if (it > 0) requestBytesArrived() }
    }

    private fun requestBytesArrived() {
        if (requestStartedAt == NO_REQUEST) requestStartedAt = System.nanoTime()
    }

    /** Moves to the phase the last request left the connection in, restarting the phase's clock. */
    private fun updatePhase() {
        val next =
            when {
                !pairing.isAuthenticated -> Phase.UNAUTHENTICATED
                media.hasSession -> Phase.IN_SESSION
                else -> Phase.AUTHENTICATED
            }
        if (next == phase) return
        phaseSince = System.nanoTime()
        phase = next
    }

    /** Answers [request]; false when the connection must close afterwards. */
    private fun respond(
        request: RtspRequest,
        output: OutputStream,
    ): Boolean {
        val rtsp = request.protocol == RtspProtocol.RTSP_1_0
        val cSeq = if (rtsp) validCSeq(request.cSeq) else null
        if (rtsp && cSeq == null) {
            // 01 §3.4: no or invalid CSeq gets 400 without CSeq, then close.
            log(ReceiverLog.Level.WARN, "${request.method} without a valid CSeq")
            send(output, Reply(RtspStatus.BAD_REQUEST, close = true), request.protocol, null)
            return false
        }
        val reply = handler.handle(request)
        updatePhase()
        log(ReceiverLog.Level.DEBUG, "${request.method} ${pathForLog(request.target)} -> ${reply.status}")
        send(output, reply, request.protocol, cSeq)
        val senderCloses = request.headers["Connection"]?.equals("close", ignoreCase = true) == true
        return !reply.close && !senderCloses
    }

    private fun send(
        output: OutputStream,
        reply: Reply,
        protocol: RtspProtocol,
        cSeq: String?,
    ) {
        output.write(ReplyWriter.encode(reply, protocol, cSeq))
        output.flush()
    }

    private fun finish() {
        closed = true
        media.endSession("connection closed")
        handler.close()
        pairing.close()
        closeSocket()
        onClosed(this)
        if (announced) core.events.connectionEnded(clientInfo)
        log(ReceiverLog.Level.INFO, "closed")
    }

    private fun closeSocket() {
        closed = true
        try {
            socket.close()
        } catch (e: IOException) {
            log(ReceiverLog.Level.DEBUG, "closing the socket: ${describe(e)}")
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
        const val MAX_CSEQ_DIGITS = 10

        /** [requestStartedAt] between requests; no `System.nanoTime()` reading is this value in practice. */
        const val NO_REQUEST = Long.MIN_VALUE

        fun millisToNanos(millis: Int): Long = TimeUnit.MILLISECONDS.toNanos(millis.toLong())

        /** The CSeq as a canonical non-negative decimal (01 §3.3), or null when missing or invalid. */
        fun validCSeq(value: String?): String? {
            if (value == null || value.isEmpty() || value.length > MAX_CSEQ_DIGITS) return null
            if (!value.all { it in '0'..'9' }) return null
            val number = value.toLong()
            return if (number <= Int.MAX_VALUE) number.toString() else null
        }

        /** The request path for logs: query strings and rtsp:// hosts dropped. */
        fun pathForLog(target: String): String =
            when {
                target.startsWith("/") -> target.substringBefore('?')
                target == "*" -> target
                else -> "<uri>"
            }
    }
}
