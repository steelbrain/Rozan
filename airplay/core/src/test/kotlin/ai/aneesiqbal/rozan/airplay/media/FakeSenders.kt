package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.crypto.AesCbc
import ai.aneesiqbal.rozan.airplay.crypto.AesCtr
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()

/**
 * Plays the sender's side of a type-110 stream: builds 128-byte headers, encrypts type-0 payloads with
 * the stream's own continuous AES-CTR keystream (keys derived like the receiver's, 03 §2.3) and writes
 * records to the receiver's data port, optionally split into small writes.
 *
 * @param sessionKey the 16-byte `session_key`.
 * @param streamConnectionId the stream's `streamConnectionID`.
 */
internal class FakeMirroringSender(
    sessionKey: ByteArray,
    streamConnectionId: Long,
) : AutoCloseable {
    private val ctr =
        AesCtr(MediaKeys.videoKey(sessionKey, streamConnectionId), MediaKeys.videoIv(sessionKey, streamConnectionId))
    private var socket: Socket? = null
    private var output: OutputStream? = null

    /** Connects to the receiver's data port. */
    fun connect(
        port: Int,
        address: InetAddress = LOOPBACK,
    ) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(address, port), 5_000)
        socket = s
        output = s.getOutputStream()
    }

    /** A type-1 record with [avcC] and the geometry floats at 56/60. */
    fun codecPacket(
        avcC: ByteArray,
        timestamp: Long = 0,
        width: Float = 1280f,
        height: Float = 720f,
        option: Int = 0x16,
    ): ByteArray {
        val packet = record(1, avcC, timestamp, option)
        putFloatLe(packet, 16, width)
        putFloatLe(packet, 20, height)
        putFloatLe(packet, 56, width)
        putFloatLe(packet, 60, height)
        return packet
    }

    /** A type-0 record of the NAL units [nals] as an AVCC access unit, encrypted (consumes keystream). */
    fun videoPacket(
        nals: List<ByteArray>,
        timestamp: Long = 0,
    ): ByteArray = encryptedPacket(avcc(nals), timestamp)

    /** A type-0 record whose plaintext payload is [plaintext], encrypted (consumes keystream). */
    fun encryptedPacket(
        plaintext: ByteArray,
        timestamp: Long = 0,
    ): ByteArray {
        val packet = record(0, plaintext, timestamp, 0)
        ctr.process(plaintext, 0, plaintext.size, packet, MediaLimits.VIDEO_HEADER_BYTES)
        return packet
    }

    /** Writes [bytes] in writes of at most [chunk] bytes. */
    fun send(
        bytes: ByteArray,
        chunk: Int = bytes.size,
    ) {
        val out = checkNotNull(output) { "not connected" }
        var position = 0
        while (position < bytes.size) {
            val n = minOf(chunk, bytes.size - position)
            out.write(bytes, position, n)
            out.flush()
            position += n
        }
    }

    /** Closes the connection; [connect] may open a new one that continues the keystream. */
    fun disconnect() {
        socket?.close()
        socket = null
        output = null
    }

    override fun close() = disconnect()

    /** Record builders that need no keystream. */
    companion object {
        /** A clear record of [type] with [payload]. */
        fun record(
            type: Int,
            payload: ByteArray,
            timestamp: Long = 0,
            option: Int = 0,
        ): ByteArray {
            val packet = ByteArray(MediaLimits.VIDEO_HEADER_BYTES + payload.size)
            putIntLe(packet, 0, payload.size)
            packet[4] = type.toByte()
            packet[6] = option.toByte()
            packet[7] = if (type == 1) 1 else 0
            for (i in 0 until 8) packet[8 + i] = (timestamp ushr (8 * i)).toByte()
            payload.copyInto(packet, MediaLimits.VIDEO_HEADER_BYTES)
            return packet
        }

        /** A header announcing [size] payload bytes of [type], for bound tests. */
        fun header(
            type: Int,
            size: Long,
        ): ByteArray {
            val packet = ByteArray(MediaLimits.VIDEO_HEADER_BYTES)
            putIntLe(packet, 0, size.toInt())
            packet[4] = type.toByte()
            return packet
        }

        /** `[u32be length][NAL]…` for [nals]. */
        fun avcc(nals: List<ByteArray>): ByteArray {
            val out = ByteArray(nals.sumOf { 4 + it.size })
            var position = 0
            for (nal in nals) {
                out[position] = (nal.size ushr 24).toByte()
                out[position + 1] = (nal.size ushr 16).toByte()
                out[position + 2] = (nal.size ushr 8).toByte()
                out[position + 3] = nal.size.toByte()
                nal.copyInto(out, position + 4)
                position += 4 + nal.size
            }
            return out
        }

        /** `00 00 00 01 NAL…` for [nals], what the receiver should submit. */
        fun annexB(nals: List<ByteArray>): ByteArray {
            val out = avcc(nals)
            var position = 0
            for (nal in nals) {
                out[position] = 0
                out[position + 1] = 0
                out[position + 2] = 0
                out[position + 3] = 1
                position += 4 + nal.size
            }
            return out
        }

        private fun putIntLe(
            out: ByteArray,
            offset: Int,
            value: Int,
        ) {
            for (i in 0 until 4) out[offset + i] = (value ushr (8 * i)).toByte()
        }

        private fun putFloatLe(
            out: ByteArray,
            offset: Int,
            value: Float,
        ) = putIntLe(out, offset, value.toRawBits())
    }
}

/**
 * Plays the sender's side of a type-96 stream: RTP packets with per-packet AES-CBC (whole blocks with
 * `eiv`, the tail in clear, 03 §2.2) to the receiver's data port, sync packets and retransmissions from its
 * own control socket, and an optional resend responder on that socket.
 *
 * @param sessionKey the 16-byte AES key.
 * @param audioIv the 16-byte `eiv`.
 * @param address the local address to bind (loopback by default).
 */
internal class FakeAudioSender(
    sessionKey: ByteArray,
    audioIv: ByteArray,
    address: InetAddress = LOOPBACK,
) : AutoCloseable {
    private val builder = AudioPacketBuilder(sessionKey, audioIv)
    private val data = DatagramSocket(InetSocketAddress(address, 0))
    private val control = DatagramSocket(InetSocketAddress(address, 0))
    private var target: AudioPorts? = null
    private var receiverAddress: InetAddress = LOOPBACK
    private var responder: Thread? = null

    /** The sender's control port: pass it as `clientControlPort` so resend requests come here. */
    val controlPort: Int = control.localPort

    /** Every RTP packet built, by sequence number, for retransmissions. */
    val packets = ConcurrentHashMap<Int, ByteArray>()

    /** Resend requests received (8 bytes each). */
    val resendRequests = CopyOnWriteArrayList<ByteArray>()

    /** Sets the receiver's ports. */
    fun connect(
        ports: AudioPorts,
        address: InetAddress = LOOPBACK,
    ) {
        target = ports
        receiverAddress = address
    }

    /** An RTP data packet: header plus [payload] encrypted per 03 §2.2. Remembered for retransmission. */
    fun rtpPacket(
        sequence: Int,
        rtpTime: Int,
        payload: ByteArray,
    ): ByteArray = builder.rtp(sequence, rtpTime, payload).also { packets[sequence] = it }

    /** Sends a data packet. */
    fun sendData(
        sequence: Int,
        rtpTime: Int,
        payload: ByteArray,
    ) = sendRaw(rtpPacket(sequence, rtpTime, payload))

    /** Sends [bytes] as is to the data port. */
    fun sendRaw(bytes: ByteArray) {
        data.send(DatagramPacket(bytes, bytes.size, receiverAddress, checkNotNull(target).dataPort))
    }

    /** Sends [bytes] as is to the control port. */
    fun sendControl(bytes: ByteArray) {
        control.send(DatagramPacket(bytes, bytes.size, receiverAddress, checkNotNull(target).controlPort))
    }

    /** Sends a sync packet (03c §4.4). */
    fun sendSync(
        rtpPlay: Int,
        ntpBits: Long,
        rtpNow: Int,
        first: Boolean,
    ) = sendControl(syncPacket(rtpPlay, ntpBits, rtpNow, first))

    /** Sends the remembered packet [sequence] as a 0xD6 retransmission. */
    fun sendRetransmit(sequence: Int) {
        val inner = checkNotNull(packets[sequence]) { "packet $sequence was never built" }
        sendControl(builder.retransmit(inner))
    }

    /**
     * Starts a thread that records every resend request on the control socket and, when [answer] says
     * so for a sequence number, retransmits that packet.
     */
    fun startResendResponder(answer: (Int) -> Boolean = { true }) {
        val thread =
            Thread({
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    while (true) {
                        packet.length = buffer.size
                        control.receive(packet)
                        if (packet.length != 8 || buffer[1] != 0xD5.toByte()) continue
                        resendRequests += buffer.copyOf(8)
                        val first = buffer.u16Be(4)
                        val count = buffer.u16Be(6)
                        for (k in 0 until count) {
                            val sequence = (first + k) and 0xFFFF
                            if (answer(sequence) && packets.containsKey(sequence)) sendRetransmit(sequence)
                        }
                    }
                } catch (_: SocketException) {
                    // closed
                }
            }, "fake-audio-resend-responder")
        thread.isDaemon = true
        thread.start()
        responder = thread
    }

    override fun close() {
        data.close()
        control.close()
        responder?.join(2_000)
    }

    /** Packet builders. */
    companion object {
        /** A 20-byte sync packet. */
        fun syncPacket(
            rtpPlay: Int,
            ntpBits: Long,
            rtpNow: Int,
            first: Boolean,
        ): ByteArray {
            val packet = ByteArray(20)
            packet[0] = if (first) 0x90.toByte() else 0x80.toByte()
            packet[1] = 0xD4.toByte()
            packet[3] = 0x04
            for (i in 0 until 4) packet[4 + i] = (rtpPlay ushr (24 - 8 * i)).toByte()
            packet.putI64Be(8, ntpBits)
            for (i in 0 until 4) packet[16 + i] = (rtpNow ushr (24 - 8 * i)).toByte()
            return packet
        }
    }
}

/**
 * Builds RTP audio packets without sockets: 12-byte header, payload encrypted per 03 §2.2 (whole blocks
 * with AES-CBC from `eiv`, the tail in clear).
 *
 * @param sessionKey the 16-byte AES key.
 * @param audioIv the 16-byte `eiv`.
 */
internal class AudioPacketBuilder(
    sessionKey: ByteArray,
    private val audioIv: ByteArray,
) {
    private val cbc = AesCbc(sessionKey)

    /** An RTP data packet (PT 0x60) for [sequence] at [rtpTime] carrying [payload]. */
    fun rtp(
        sequence: Int,
        rtpTime: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = 0x60
        packet.putU16Be(2, sequence)
        for (i in 0 until 4) packet[4 + i] = (rtpTime ushr (24 - 8 * i)).toByte()
        val whole = payload.size - payload.size % 16
        if (whole > 0) cbc.encrypt(audioIv, payload, 0, whole).copyInto(packet, 12)
        payload.copyInto(packet, 12 + whole, whole, payload.size)
        return packet
    }

    /** A 0xD6 retransmission wrapping the data packet [inner]. */
    fun retransmit(inner: ByteArray): ByteArray {
        val packet = ByteArray(4 + inner.size)
        packet[0] = 0x80.toByte()
        packet[1] = 0xD6.toByte()
        inner.copyInto(packet, 4)
        return packet
    }
}

/**
 * Plays the sender's NTP responder (03c §4.6): answers each `0xD2` request with `0xD3`, origin = the
 * request's T1, T2 = T1 + half the delay + [clientOffsetNanos], T3 = T2 + 100 µs. With a [clock], it
 * advances that clock by [delayNanos] + 100 µs before replying, so the receiver measures exactly that
 * delay and an offset within a few nanoseconds of [clientOffsetNanos] (the receiver's base must be 0).
 *
 * @param clientOffsetNanos the sender's NTP-domain time minus the receiver's local time.
 * @param clock the receiver's manual clock, or null when it runs on the system clock.
 * @param delayNanos the simulated round trip.
 * @param address the local address to bind.
 */
internal class FakeTimingResponder(
    private val clientOffsetNanos: Long,
    private val clock: ManualClock? = null,
    private val delayNanos: Long = 2_000_000L,
    address: InetAddress = LOOPBACK,
) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(address, 0))
    private val thread = Thread(::run, "fake-timing-responder").apply { isDaemon = true }

    /** The port to give the receiver as `timingPort`. */
    val port: Int = socket.localPort

    /** Every request received. */
    val requests = CopyOnWriteArrayList<ByteArray>()

    init {
        thread.start()
    }

    private fun run() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (true) {
                packet.length = buffer.size
                socket.receive(packet)
                if (packet.length != 32 || buffer[1] != 0xD2.toByte()) continue
                requests += buffer.copyOf(32)
                val t1Bits = buffer.i64Be(24)
                val t2 = NtpTimestamp(t1Bits).toNanos() + delayNanos / 2 + clientOffsetNanos
                val t3 = t2 + TURNAROUND
                val reply = ByteArray(32)
                reply[0] = 0x80.toByte()
                reply[1] = 0xD3.toByte()
                reply[3] = 0x07
                reply.putI64Be(8, t1Bits)
                reply.putI64Be(16, NtpTimestamp.fromNanos(t2).bits)
                reply.putI64Be(24, NtpTimestamp.fromNanos(t3).bits)
                clock?.advance(delayNanos + TURNAROUND)
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        } catch (_: SocketException) {
            // closed
        }
    }

    override fun close() {
        socket.close()
        thread.join(2_000)
    }

    private companion object {
        const val TURNAROUND = 100_000L
    }
}
