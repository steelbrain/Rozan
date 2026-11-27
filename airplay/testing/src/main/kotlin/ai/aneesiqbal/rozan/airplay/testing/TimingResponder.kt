package ai.aneesiqbal.rozan.airplay.testing

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The sender's NTP responder (docs/protocol/03c-audio-rtp-and-timing.md §4.6): the receiver asks, the
 * sender answers each `0xD2` request with `0xD3`, origin = the request's T1, T2 = when the request
 * arrived and T3 = when the reply leaves, both in the sender's NTP domain. The socket is bound to every
 * local address, so a receiver on the same host reaches it on 127.0.0.1.
 *
 * @param clock the sender's clock.
 */
public class TimingResponder(
    private val clock: SenderClock,
) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(0))
    private val answered = AtomicInteger()
    private val thread = Thread(::run, "test-sender-timing").apply { isDaemon = true }

    /** The port to send as the session SETUP's `timingPort`. */
    public val port: Int = socket.localPort

    /** Requests answered so far. */
    public val requestsAnswered: Int get() = answered.get()

    init {
        thread.start()
    }

    private fun run() {
        val buffer = ByteArray(RECEIVE_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        val reply = ByteArray(PACKET_BYTES)
        try {
            while (true) {
                packet.length = buffer.size
                socket.receive(packet)
                val received = clock.ntpNanos()
                if (packet.length < PACKET_BYTES || buffer[1] != REQUEST) continue
                reply.fill(0)
                reply[0] = 0x80.toByte()
                reply[1] = REPLY
                reply[3] = 0x07
                buffer.copyInto(reply, 8, 24, 32)
                reply.putI64Be(16, SenderClock.q32(received))
                reply.putI64Be(24, SenderClock.q32(clock.ntpNanos()))
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                answered.incrementAndGet()
            }
        } catch (ignored: IOException) {
            // Closed.
        }
    }

    override fun close() {
        socket.close()
        thread.join(JOIN_MILLIS)
    }

    private companion object {
        const val PACKET_BYTES = 32
        const val RECEIVE_BYTES = 2048
        const val REQUEST = 0xD2.toByte()
        const val REPLY = 0xD3.toByte()
        const val JOIN_MILLIS = 2_000L
    }
}
