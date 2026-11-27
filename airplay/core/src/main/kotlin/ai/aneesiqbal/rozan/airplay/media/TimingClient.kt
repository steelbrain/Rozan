package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/** The clock offset between the sender's NTP domain and the local [MediaClock] (03c §4.6). Thread-safe. */
internal interface ClockSync {
    /** Whether at least one timing reply has been accepted, so [offsetNanos] means something. */
    val isSynced: Boolean

    /** `θ_used = client_ntp_ns − local_ns`, the smoothed clock offset. */
    val offsetNanos: Long

    /** The smallest round-trip delay among the kept samples, in nanoseconds. */
    val roundTripNanos: Long

    /** Offsets. */
    companion object {
        /** Never synced, for streams without a timing client. */
        val NONE: ClockSync =
            object : ClockSync {
                override val isSynced: Boolean get() = false
                override val offsetNanos: Long get() = 0L
                override val roundTripNanos: Long get() = 0L
            }
    }
}

/**
 * The NTP clock filter of 03c §4.6, without sockets: request bookkeeping, reply validation, the
 * smallest-delay filter and smoothing. Allocates nothing after construction. One thread calls
 * [writeRequest] and [onReply]; any thread may read the [ClockSync] values.
 *
 * Local times are encoded as `localNanos − baseNanos` in NTP format. [baseNanos] is 0 when the clock at
 * construction reads between 1 s and 2^31 s (Android's `CLOCK_MONOTONIC`), else that reading minus 1 s,
 * so encoded values stay in the NTP range for decades.
 *
 * @param startNanos the local clock when the filter is created.
 */
internal class TimingFilter(
    startNanos: Long,
) : ClockSync {
    /** Subtracted from local times before encoding them. */
    val baseNanos: Long = if (startNanos in NANOS until BASELESS_LIMIT) 0L else startNanos - NANOS

    private val outstandingBits = LongArray(MediaLimits.TIMING_OUTSTANDING)
    private val outstandingNanos = LongArray(MediaLimits.TIMING_OUTSTANDING)
    private val outstandingLive = BooleanArray(MediaLimits.TIMING_OUTSTANDING)
    private var nextOutstanding = 0

    private val seenT3 = LongArray(MediaLimits.TIMING_SEEN_T3)
    private var seenCount = 0
    private var nextSeen = 0

    private val sampleOffset = LongArray(MediaLimits.TIMING_SAMPLES)
    private val sampleDelay = LongArray(MediaLimits.TIMING_SAMPLES)
    private var sampleCount = 0
    private var nextSample = 0

    @Volatile private var used = 0L

    @Volatile private var bestDelay = 0L

    @Volatile private var synced = false

    override val isSynced: Boolean get() = synced
    override val offsetNanos: Long get() = used
    override val roundTripNanos: Long get() = bestDelay

    /** Replies accepted so far. */
    var acceptedReplies: Int = 0
        private set

    /**
     * Writes the 32-byte request `80 D2 00 07 …` with transmit time T1 = [nowNanos] into [out] at [offset]
     * and remembers T1 for matching the reply, forgetting the oldest of [MediaLimits.TIMING_OUTSTANDING].
     *
     * @return false, writing nothing, when [nowNanos] cannot be encoded (the clock went backwards past the
     *   base).
     */
    fun writeRequest(
        out: ByteArray,
        offset: Int,
        nowNanos: Long,
    ): Boolean {
        val encoded = nowNanos - baseNanos
        if (encoded !in 0 until NtpTimestamp.NANOS_LIMIT) return false
        val bits = NtpTimestamp.fromNanos(encoded).bits
        out.fill(0, offset, offset + MediaLimits.TIMING_PACKET_BYTES)
        out[offset] = RTP_V2.toByte()
        out[offset + 1] = TIMING_REQUEST.toByte()
        out[offset + 3] = TIMING_FLAGS.toByte()
        out.putI64Be(offset + T1_OFFSET, bits)
        outstandingBits[nextOutstanding] = bits
        outstandingNanos[nextOutstanding] = nowNanos
        outstandingLive[nextOutstanding] = true
        nextOutstanding = (nextOutstanding + 1) % MediaLimits.TIMING_OUTSTANDING
        return true
    }

    /**
     * Handles one reply datagram `buffer[offset, offset + length)` received at local time [receivedNanos]
     * (T4). Applies every check of 03c §4.6 and updates the estimate.
     *
     * @return whether the reply was accepted.
     */
    fun onReply(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        receivedNanos: Long,
    ): Boolean {
        if (length < MediaLimits.TIMING_PACKET_BYTES || buffer.u8(offset + 1) != TIMING_REPLY) return false
        val request = takeOutstanding(buffer.i64Be(offset + ORIGIN_OFFSET))
        val t3Bits = buffer.i64Be(offset + T3_OFFSET)
        // A reply that repeats an earlier T3 is a duplicate (03c §4.6 step 1).
        if (request < 0 || !rememberT3(t3Bits)) return false
        // Local times in the encoded domain (0 until 2^32 s) so that no sum below can overflow.
        val t1 = outstandingNanos[request] - baseNanos
        val t4 = receivedNanos - baseNanos
        val t2 = NtpTimestamp(buffer.i64Be(offset + T2_OFFSET)).toNanos()
        val t3 = NtpTimestamp(t3Bits).toNanos()
        val delay = (t4 - t1) - (t3 - t2)
        if (delay <= 0 || delay > NANOS) return false
        addSample(((t2 - t1) + (t3 - t4)) / 2 - baseNanos, delay)
        acceptedReplies++
        return true
    }

    /**
     * Writes the reply to a timing request the sender sent us (03c §4.6, optional): `80 D3 00 07`, origin =
     * the request's transmit time, T2 = [receivedNanos], T3 = [nowNanos].
     *
     * @return false when the request is malformed or the times cannot be encoded.
     */
    fun writeAnswer(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        receivedNanos: Long,
        nowNanos: Long,
        out: ByteArray,
    ): Boolean {
        val received = receivedNanos - baseNanos
        val now = nowNanos - baseNanos
        val valid =
            requestLength >= MediaLimits.TIMING_PACKET_BYTES &&
                received in 0 until NtpTimestamp.NANOS_LIMIT &&
                now in 0 until NtpTimestamp.NANOS_LIMIT
        if (!valid) return false
        out.fill(0, 0, MediaLimits.TIMING_PACKET_BYTES)
        out[0] = RTP_V2.toByte()
        out[1] = TIMING_REPLY.toByte()
        out[3] = TIMING_FLAGS.toByte()
        out.putI64Be(ORIGIN_OFFSET, request.i64Be(requestOffset + T1_OFFSET))
        out.putI64Be(T2_OFFSET, NtpTimestamp.fromNanos(received).bits)
        out.putI64Be(T3_OFFSET, NtpTimestamp.fromNanos(now).bits)
        return true
    }

    private fun takeOutstanding(origin: Long): Int {
        for (i in 0 until MediaLimits.TIMING_OUTSTANDING) {
            if (outstandingLive[i] && outstandingBits[i] == origin) {
                outstandingLive[i] = false
                return i
            }
        }
        return -1
    }

    private fun rememberT3(bits: Long): Boolean {
        for (i in 0 until seenCount) {
            if (seenT3[i] == bits) return false
        }
        seenT3[nextSeen] = bits
        nextSeen = (nextSeen + 1) % MediaLimits.TIMING_SEEN_T3
        if (seenCount < MediaLimits.TIMING_SEEN_T3) seenCount++
        return true
    }

    private fun addSample(
        offset: Long,
        delay: Long,
    ) {
        sampleOffset[nextSample] = offset
        sampleDelay[nextSample] = delay
        nextSample = (nextSample + 1) % MediaLimits.TIMING_SAMPLES
        if (sampleCount < MediaLimits.TIMING_SAMPLES) sampleCount++
        var best = 0
        for (i in 1 until sampleCount) {
            if (sampleDelay[i] < sampleDelay[best]) best = i
        }
        val bestOffset = sampleOffset[best]
        // θ_used += 0.05 × (θ_best − θ_used), in integer arithmetic: a double cannot hold ~2.2e18 exactly.
        used = if (synced) used + (bestOffset - used) / SMOOTHING_DIVISOR else bestOffset
        bestDelay = sampleDelay[best]
        synced = true
    }

    private companion object {
        const val NANOS = NtpTimestamp.NANOS_PER_SECOND
        const val BASELESS_LIMIT = (1L shl 31) * NANOS
        const val SMOOTHING_DIVISOR = 20L
        const val RTP_V2 = 0x80
        const val TIMING_REQUEST = 0xD2
        const val TIMING_REPLY = 0xD3
        const val TIMING_FLAGS = 0x07
        const val ORIGIN_OFFSET = 8
        const val T2_OFFSET = 16
        const val T3_OFFSET = 24
        const val T1_OFFSET = 24
    }
}

/**
 * The receiver's NTP client (03c §4.6): one UDP socket, a request burst then periodic requests to
 * `client:clientPort`, replies fed to a [TimingFilter]. Datagrams from any other address are dropped, and
 * once a reply matches one of our requests, so are datagrams from any other port than the one it came
 * from (03c §4.1). Requests are only sent when [clientPort] is a valid port; the socket still answers the
 * sender's own timing requests from that port (or the pinned one).
 *
 * The socket is bound in the constructor; [start] starts the "rozan-media-timing" daemon thread and
 * [close] stops it.
 *
 * @throws SocketException if the socket cannot be opened.
 */
internal class TimingClient(
    private val client: InetAddress,
    private val clientPort: Int,
    private val config: MediaConfig,
) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(0))

    /** The filter the replies feed; the [ClockSync] streams use. */
    val filter: TimingFilter = TimingFilter(config.clock.nanoTime())

    /** Local port of the timing socket. */
    val localPort: Int = socket.localPort

    private val receiveBuffer = ByteArray(MediaLimits.UDP_RECEIVE_BUFFER_BYTES)
    private val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
    private val sendBuffer = ByteArray(MediaLimits.TIMING_PACKET_BYTES)
    private val requestPacket =
        if (clientPort in 1..MAX_PORT) DatagramPacket(sendBuffer, sendBuffer.size, client, clientPort) else null
    private val thread = Thread(::run, "rozan-media-timing").apply { isDaemon = true }

    /** The sender's source port, once a reply matched one of our requests; timing thread only. */
    private var senderPort = NO_PORT

    @Volatile private var closed = false

    /** Starts the timing thread. */
    fun start() {
        thread.start()
    }

    /** Closes the socket and waits for the thread to finish. Idempotent. */
    override fun close() {
        closed = true
        socket.close()
        if (thread.isAlive && thread !== Thread.currentThread()) thread.join(config.joinTimeoutMillis)
    }

    // A failure here must not crash the process; timing is then simply unavailable.
    @Suppress("TooGenericExceptionCaught")
    private fun run() {
        try {
            loop()
        } catch (e: Exception) {
            if (!closed) config.log.log("timing client stopped: $e")
        }
    }

    private fun loop() {
        var sent = 0
        var nextSend = config.clock.nanoTime()
        while (!closed) {
            val now = config.clock.nanoTime()
            if (requestPacket != null && now - nextSend >= 0) {
                if (filter.writeRequest(sendBuffer, 0, now)) socket.send(requestPacket)
                sent++
                val burst = sent < config.timingBurstCount
                val interval = if (burst) config.timingBurstIntervalMillis else config.timingIntervalMillis
                nextSend = now + interval * NANOS_PER_MILLI
            }
            val waitMillis = if (requestPacket == null) IDLE_WAIT_MILLIS else (nextSend - now) / NANOS_PER_MILLI
            socket.soTimeout = waitMillis.coerceIn(1L, IDLE_WAIT_MILLIS).toInt()
            try {
                receivePacket.setData(receiveBuffer, 0, receiveBuffer.size)
                socket.receive(receivePacket)
            } catch (_: SocketTimeoutException) {
                continue
            }
            handle(config.clock.nanoTime())
        }
    }

    private fun handle(receivedNanos: Long) {
        if (receivePacket.address != client) return
        val port = receivePacket.port
        if (senderPort != NO_PORT && port != senderPort) return
        val length = receivePacket.length
        if (length < 2) return
        when (receiveBuffer.u8(1)) {
            TIMING_REPLY -> {
                // A reply that matches one of our requests pins the sender's port (03c §4.1).
                if (filter.onReply(receiveBuffer, 0, length, receivedNanos) && senderPort == NO_PORT) senderPort = port
            }

            TIMING_REQUEST -> {
                // Answered only from the pinned port or, before that, the advertised one.
                val fromSender = if (senderPort == NO_PORT) port == clientPort else true
                val now = config.clock.nanoTime()
                if (fromSender && filter.writeAnswer(receiveBuffer, 0, length, receivedNanos, now, sendBuffer)) {
                    socket.send(DatagramPacket(sendBuffer, sendBuffer.size, client, port))
                }
            }
        }
    }

    private companion object {
        const val MAX_PORT = 65_535
        const val NANOS_PER_MILLI = 1_000_000L
        const val IDLE_WAIT_MILLIS = 1_000L
        const val TIMING_REQUEST = 0xD2
        const val TIMING_REPLY = 0xD3
        const val NO_PORT = -1
    }
}
