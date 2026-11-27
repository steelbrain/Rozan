package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.AesCbc
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The sender's side of one type-96 realtime audio stream (docs/protocol/03c-audio-rtp-and-timing.md §4):
 * RTP packets of ALAC escape frames, each payload AES-CBC encrypted from `eiv` with the tail in clear (03
 * §2.2), to the receiver's data port; sync packets and retransmissions from its own control socket, which
 * also answers the receiver's resend requests.
 *
 * Both sockets are bound to every local address, so a receiver on the same host reaches them on
 * 127.0.0.1. Building and sending packets happens on one thread; the resend responder runs on its own.
 *
 * @param sessionKey the session's 16-byte `session_key`.
 * @param audioIv the session's 16-byte `eiv`.
 * @param clock the sender's clock, for sync packets.
 * @param framesPerPacket `spf`, 352 for AirPlay ALAC.
 * @param sampleRate the RTP clock rate.
 * @param latencyFrames how far ahead of hearing the sender sends: sync packets announce
 *   `rtp_play = rtp_now − latencyFrames` at the current time.
 */
public class AudioSender(
    sessionKey: ByteArray,
    audioIv: ByteArray,
    private val clock: SenderClock,
    public val framesPerPacket: Int = DEFAULT_FRAMES_PER_PACKET,
    public val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    public val latencyFrames: Int = DEFAULT_LATENCY_FRAMES,
) : AutoCloseable {
    private val cbc = AesCbc(sessionKey)
    private val iv = audioIv.copyOf()
    private val data = DatagramSocket(InetSocketAddress(0))
    private val control = DatagramSocket(InetSocketAddress(0))
    private val history = ConcurrentHashMap<Int, ByteArray>()
    private val retransmitted = AtomicInteger()
    private var receiver: InetAddress? = null
    private var dataPort = 0
    private var receiverControlPort = 0
    private var responder: Thread? = null

    /** The sender's control port, sent as the stream SETUP's `controlPort` so resend requests come here. */
    public val controlPort: Int = control.localPort

    /** The first sequence number [stream] sends. */
    public val firstSequence: Int

    /** The RTP timestamp of the first packet [stream] sends. */
    public val firstRtpTime: Int

    init {
        val random = SecureRandom()
        firstSequence = random.nextInt(SEQUENCE_SPACE)
        firstRtpTime = random.nextInt()
    }

    /** Resend requests received (8 bytes each). */
    public val resendRequests: MutableList<ByteArray> = CopyOnWriteArrayList()

    /** Packets sent again because the receiver asked. */
    public val retransmissions: Int get() = retransmitted.get()

    /** Data packets sent. */
    @Volatile
    public var packetsSent: Int = 0
        private set

    /** Sets where packets go: the receiver's data and control ports from the stream SETUP response. */
    public fun connect(
        address: InetAddress,
        dataPort: Int,
        controlPort: Int,
    ) {
        receiver = address
        this.dataPort = dataPort
        receiverControlPort = controlPort
    }

    /**
     * An RTP data packet (PT 0x60) for [sequence] at [rtpTime] with [payload] encrypted per 03 §2.2. It is
     * remembered, so a resend request for it can be answered.
     */
    public fun packet(
        sequence: Int,
        rtpTime: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(RTP_HEADER_BYTES + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = PAYLOAD_TYPE
        packet.putU16Be(2, sequence)
        packet.putI32Be(4, rtpTime)
        val whole = payload.size - payload.size % BLOCK
        if (whole > 0) cbc.encrypt(iv, payload, 0, whole).copyInto(packet, RTP_HEADER_BYTES)
        payload.copyInto(packet, RTP_HEADER_BYTES + whole, whole, payload.size)
        history[sequence and 0xFFFF] = packet
        history.remove((sequence - HISTORY) and 0xFFFF)
        return packet
    }

    /** Sends [packet] to the data port. */
    public fun send(packet: ByteArray) {
        data.send(DatagramPacket(packet, packet.size, checkNotNull(receiver), dataPort))
        packetsSent++
    }

    /** Sends a sync packet (03c §4.4): [rtpPlay] is heard at [ntpNanos]; [rtpNow] is the next packet. */
    public fun sendSync(
        rtpPlay: Int,
        ntpNanos: Long,
        rtpNow: Int,
        first: Boolean,
    ) {
        val packet = ByteArray(SYNC_BYTES)
        packet[0] = if (first) 0x90.toByte() else 0x80.toByte()
        packet[1] = 0xD4.toByte()
        packet[3] = 0x04
        packet.putI32Be(4, rtpPlay)
        packet.putI64Be(8, SenderClock.q32(ntpNanos))
        packet.putI32Be(16, rtpNow)
        sendControl(packet)
    }

    /** Sends the remembered packet [sequence] again as a 0xD6 retransmission; false if it is not known. */
    public fun retransmit(sequence: Int): Boolean {
        val inner = history[sequence and 0xFFFF] ?: return false
        val packet = ByteArray(RETRANSMIT_HEADER_BYTES + inner.size)
        packet[0] = 0x80.toByte()
        packet[1] = 0xD6.toByte()
        packet.putU16Be(2, retransmitted.incrementAndGet())
        inner.copyInto(packet, RETRANSMIT_HEADER_BYTES)
        sendControl(packet)
        return true
    }

    /**
     * Starts answering resend requests on the control socket: each `0xD5` request is recorded and the
     * packets it names are retransmitted when [answer] agrees and they are still remembered.
     */
    public fun startResendResponder(answer: (Int) -> Boolean = { true }) {
        check(responder == null) { "already answering" }
        val thread = Thread({ answerResends(answer) }, "test-sender-resends")
        thread.isDaemon = true
        thread.start()
        responder = thread
    }

    /**
     * Streams a 440 Hz tone in real time for [durationNanos], as consecutive packets from [firstSequence]
     * and [firstRtpTime], with a sync packet first (marked as the first) and then about once a second,
     * until [stop] returns true or sending fails.
     *
     * @return whether it ran to the end.
     */
    public fun stream(
        durationNanos: Long,
        stop: () -> Boolean = { false },
    ): Boolean {
        val packetNanos = framesPerPacket * NANOS_PER_SECOND / sampleRate
        val packets = (durationNanos / packetNanos).toInt()
        val packetsPerSync = (sampleRate / framesPerPacket).coerceAtLeast(1)
        val start = System.nanoTime()
        return try {
            for (k in 0 until packets) {
                val due = start + k.toLong() * framesPerPacket * NANOS_PER_SECOND / sampleRate
                sleepUntil(due)
                if (stop()) return false
                val rtp = firstRtpTime + k * framesPerPacket
                if (k % packetsPerSync == 0) sendSync(rtp - latencyFrames, clock.ntpNanosAt(due), rtp, first = k == 0)
                val pcm = AlacFrames.tone(k.toLong() * framesPerPacket, framesPerPacket, sampleRate = sampleRate)
                send(packet(firstSequence + k, rtp, AlacFrames.encode(pcm, framesPerPacket, framesPerPacket)))
            }
            true
        } catch (ignored: IOException) {
            false
        }
    }

    private fun sendControl(packet: ByteArray) {
        control.send(DatagramPacket(packet, packet.size, checkNotNull(receiver), receiverControlPort))
    }

    private fun answerResends(answer: (Int) -> Boolean) {
        val buffer = ByteArray(RECEIVE_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (true) {
                packet.length = buffer.size
                control.receive(packet)
                if (packet.length != RESEND_REQUEST_BYTES || buffer[1] != 0xD5.toByte()) continue
                resendRequests += buffer.copyOf(RESEND_REQUEST_BYTES)
                val first = buffer.u16Be(4)
                for (k in 0 until buffer.u16Be(6)) {
                    val sequence = (first + k) and 0xFFFF
                    if (answer(sequence)) retransmit(sequence)
                }
            }
        } catch (ignored: IOException) {
            // Closed.
        }
    }

    override fun close() {
        data.close()
        control.close()
        responder?.join(JOIN_MILLIS)
    }

    /** Defaults and sizes. */
    public companion object {
        /** AirPlay ALAC packets carry 352 frames. */
        public const val DEFAULT_FRAMES_PER_PACKET: Int = 352

        /** 44.1 kHz. */
        public const val DEFAULT_SAMPLE_RATE: Int = 44_100

        /** Half a second ahead of hearing. Apple's audio-only senders use about 2 s; tests want less. */
        public const val DEFAULT_LATENCY_FRAMES: Int = 22_050

        private const val PAYLOAD_TYPE: Byte = 0x60
        private const val RTP_HEADER_BYTES = 12
        private const val RETRANSMIT_HEADER_BYTES = 4
        private const val SYNC_BYTES = 20
        private const val RESEND_REQUEST_BYTES = 8
        private const val RECEIVE_BYTES = 2048
        private const val BLOCK = 16
        private const val SEQUENCE_SPACE = 65_536
        private const val HISTORY = 1024
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val JOIN_MILLIS = 2_000L
    }
}
