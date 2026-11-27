package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** The data and control sockets of one type-96 stream, bound to ephemeral ports (03c §4.1). */
internal class AudioSockets private constructor(
    /** Receives RTP audio. */
    val data: DatagramSocket,
    /** Receives sync packets and retransmissions; sends resend requests. */
    val control: DatagramSocket,
) : AutoCloseable {
    override fun close() {
        data.close()
        control.close()
    }

    /** Opening. */
    companion object {
        /**
         * Binds both sockets on the wildcard address.
         *
         * @throws IOException if either cannot be opened; neither is left open.
         */
        fun open(): AudioSockets {
            val data = DatagramSocket(InetSocketAddress(0))
            try {
                data.receiveBufferSize = MediaLimits.AUDIO_SOCKET_RECEIVE_BUFFER_BYTES
                return AudioSockets(data, DatagramSocket(InetSocketAddress(0)))
            } catch (e: IOException) {
                data.close()
                throw e
            }
        }
    }
}

/**
 * One running type-96 stream: a [AudioPacketProcessor] fed by a data thread and a control thread, and a
 * playout thread that makes every [sink] call (submit, flush, volume, close). Datagrams from any address
 * other than [client] are dropped, and each socket pins the source port of the first datagram it accepts
 * (a stored RTP packet; a well-formed sync or a stored retransmission) and drops datagrams from any other
 * port after that, so a host that spoofs the sender's address also has to know its port (03c §4.1).
 * Resend requests go from the control socket to `client:clientControlPort` when that port is valid.
 *
 * Threads ("rozan-media-audio-data", "-control", "-playout") are daemons started by [start] and stopped by
 * [close]. A socket or sink failure stops the stream and is reported once with
 * [MediaListener.onAudioFailed]; [close] is not reported.
 *
 * The hot path allocates nothing of its own: one receive buffer per socket, the jitter ring and one
 * output buffer are allocated here. (The platform's `DatagramSocket.receive` may allocate the sender's
 * address object per datagram; that is outside this code.)
 */
internal class AudioStream(
    private val client: InetAddress,
    private val sockets: AudioSockets,
    clientControlPort: Int,
    /** The packet core; exposed for diagnostics and tests. */
    val processor: AudioPacketProcessor,
    private val sink: AudioSink,
    private val listener: MediaListener,
    private val config: MediaConfig,
) : AutoCloseable {
    /** Local ports for the SETUP response. */
    val ports: AudioPorts = AudioPorts(sockets.data.localPort, sockets.control.localPort)

    private val lock = Any()
    private val dataBuffer = ByteArray(MediaLimits.UDP_RECEIVE_BUFFER_BYTES)
    private val controlBuffer = ByteArray(MediaLimits.UDP_RECEIVE_BUFFER_BYTES)
    private val out = ByteArray(MediaLimits.MAX_AUDIO_PAYLOAD_BYTES)
    private val resendPacket =
        if (clientControlPort in 1..MAX_PORT) {
            DatagramPacket(processor.resends.requests, 0, ResendScheduler.REQUEST_BYTES, client, clientControlPort)
        } else {
            null
        }

    private val dataThread = Thread({ receive(sockets.data, dataBuffer, control = false) }, "rozan-media-audio-data")
    private val controlThread =
        Thread({ receive(sockets.control, controlBuffer, control = true) }, "rozan-media-audio-control")
    private val playoutThread = Thread(::playout, "rozan-media-audio-playout")
    private val stopping = AtomicBoolean(false)

    @Volatile private var volume = 1f

    @Volatile private var volumeDirty = true

    /** Starts the three threads. */
    fun start() {
        for (thread in arrayOf(dataThread, controlThread, playoutThread)) {
            thread.isDaemon = true
            thread.start()
        }
    }

    /** FLUSH (03 §6.2); see [AudioPacketProcessor.flush]. */
    fun flush(
        nextSequence: Int?,
        nextRtpTime: Long?,
    ) {
        synchronized(lock) { processor.flush(nextSequence, nextRtpTime) }
        LockSupport.unpark(playoutThread)
    }

    /** Data packets stored plus sync packets received so far; see [MediaSession.audioActivity]. */
    fun activity(): Long = synchronized(lock) { processor.counters.accepted + processor.counters.syncs }

    /** Sets the sink's linear gain from the playout thread. */
    fun setVolume(linear: Float) {
        volume = linear
        volumeDirty = true
        LockSupport.unpark(playoutThread)
    }

    /** Stops the stream: closes both sockets, waits for the threads and zeroes the IV copy. Idempotent. */
    override fun close() {
        stopping.set(true)
        sockets.close()
        LockSupport.unpark(playoutThread)
        for (thread in arrayOf(dataThread, controlThread, playoutThread)) {
            if (thread.isAlive && thread !== Thread.currentThread()) thread.join(config.joinTimeoutMillis)
        }
        synchronized(lock) { processor.destroy() }
    }

    private fun receive(
        socket: DatagramSocket,
        buffer: ByteArray,
        control: Boolean,
    ) {
        val packet = DatagramPacket(buffer, buffer.size)
        // The sender's source port: unknown until the first datagram this socket accepts, then the only one
        // listened to (03c §4.1). It need not be the port the sender advertised.
        var senderPort = NO_PORT
        try {
            while (!stopping.get()) {
                packet.length = buffer.size
                socket.receive(packet)
                val port = packet.port
                if (packet.address != client || (senderPort != NO_PORT && port != senderPort)) continue
                val now = config.clock.nanoTime()
                val accepted =
                    synchronized(lock) {
                        if (control) {
                            processor.onControlPacket(buffer, 0, packet.length, now)
                        } else {
                            processor.onDataPacket(buffer, 0, packet.length, now)
                        }
                    }
                if (accepted && senderPort == NO_PORT) senderPort = port
                LockSupport.unpark(playoutThread)
            }
        } catch (e: IOException) {
            fail("audio socket failed: $e")
        }
    }

    // The sink belongs to another layer: whatever it throws stops this stream, never the process.
    @Suppress("TooGenericExceptionCaught")
    private fun playout() {
        try {
            playoutLoop()
        } catch (e: Exception) {
            fail("audio playout failed: $e")
        } finally {
            try {
                sink.close()
            } catch (e: Exception) {
                config.log.log("audio sink close failed: $e")
            }
        }
    }

    private fun playoutLoop() {
        while (!stopping.get()) {
            val now = config.clock.nanoTime()
            var flush: Boolean
            var length = AudioPacketProcessor.NOTHING
            var presentation = 0L
            var wake: Long
            var resends: Int
            var waitsForPacket: Boolean
            synchronized(lock) {
                flush = processor.takeSinkFlush()
                if (!flush) {
                    length = processor.nextOutput(now, out)
                    presentation = processor.outputPresentationNanos
                }
                wake = processor.wakeAtNanos
                resends = processor.collectResends(now)
                waitsForPacket = !flush && processor.waitsForPacket
            }
            sendResends(resends)
            if (volumeDirty) {
                volumeDirty = false
                sink.setVolume(volume)
            }
            when {
                flush -> {
                    sink.flush()
                }

                length >= 0 -> {
                    sink.submit(out, 0, length, presentation)
                }

                // Paused or between packets: sleep until a packet, flush, volume change or stop unparks
                // this thread, instead of checking the empty buffer every few milliseconds.
                waitsForPacket -> {
                    LockSupport.park(this)
                }

                else -> {
                    val wait = wake - config.clock.nanoTime()
                    LockSupport.parkNanos(wait.coerceIn(MIN_PARK_NANOS, config.playoutPollNanos))
                }
            }
        }
    }

    private fun sendResends(count: Int) {
        val packet = resendPacket ?: return
        for (i in 0 until count) {
            packet.setData(processor.resends.requests, i * ResendScheduler.REQUEST_BYTES, ResendScheduler.REQUEST_BYTES)
            try {
                sockets.control.send(packet)
            } catch (e: IOException) {
                // A lost resend request is only a lost chance to recover a packet.
                if (!stopping.get()) config.log.log("resend request failed: $e")
            }
        }
    }

    // The listener belongs to another layer; its failure must not escape a media thread.
    @Suppress("TooGenericExceptionCaught")
    private fun fail(reason: String) {
        if (!stopping.compareAndSet(false, true)) return
        sockets.close()
        LockSupport.unpark(playoutThread)
        try {
            listener.onAudioFailed(reason)
        } catch (e: Exception) {
            config.log.log("audio listener failed: $e")
        }
    }

    private companion object {
        const val MAX_PORT = 65_535
        const val MIN_PARK_NANOS = 100_000L
        const val NO_PORT = -1
    }
}
