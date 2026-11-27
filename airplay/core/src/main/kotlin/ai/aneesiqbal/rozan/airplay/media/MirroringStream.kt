package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One type-110 stream (03b §3.1): a TCP listener on an ephemeral port (backlog 1) that accepts one data
 * connection at a time from [client] only, reads `[128-byte header][payload]` records and feeds them to a
 * [MirrorPacketProcessor]. The receiver never writes on the connection.
 *
 * A connection the sender closes is reported with [MediaListener.onMirroringDisconnected] and the listener
 * stays open for a reconnect, which continues the keystream (03 §6.5). A bound violation, a stall inside a
 * packet, a sink failure or no connection within [MediaConfig.firstConnectionTimeoutMillis] ends the
 * stream, also reported there. Stopping it with [close] is not reported.
 *
 * The listener is bound in the constructor; [start] starts the "rozan-media-mirroring" daemon thread,
 * which makes every [sink] call and closes [sink] when it ends.
 *
 * @throws IOException if the listener cannot be opened.
 */
internal class MirroringStream(
    private val client: InetAddress,
    videoKey: ByteArray,
    videoIv: ByteArray,
    private val sink: VideoSink,
    clockSync: ClockSync,
    private val listener: MediaListener,
    private val config: MediaConfig,
) : AutoCloseable {
    private val server =
        ServerSocket().apply {
            // Set before bind so that accepted sockets inherit it (03b §3.1: at least 1 MiB).
            receiveBufferSize = MediaLimits.MIRROR_SOCKET_RECEIVE_BUFFER_BYTES
            bind(InetSocketAddress(0), 1)
        }

    /** Local TCP port of the listener, for the SETUP response (`dataPort`). */
    val localPort: Int = server.localPort

    /** The packet core; exposed for diagnostics and tests. */
    val processor: MirrorPacketProcessor = MirrorPacketProcessor(videoKey, videoIv, sink, clockSync)

    private val header = ByteArray(MediaLimits.VIDEO_HEADER_BYTES)
    private val chunk = ByteArray(MediaLimits.VIDEO_READ_BUFFER_BYTES)
    private val thread = Thread(::run, "rozan-media-mirroring").apply { isDaemon = true }

    @Volatile private var connection: Socket? = null

    @Volatile private var closed = false

    /** Starts the stream thread. */
    fun start() {
        thread.start()
    }

    /** Stops the stream: closes the listener and connection and waits for the thread. Idempotent. */
    override fun close() {
        closed = true
        closeQuietly(server)
        connection?.let(::closeQuietly)
        if (thread.isAlive && thread !== Thread.currentThread()) thread.join(config.joinTimeoutMillis)
    }

    // Nothing may escape this thread: a failing sink or a hostile stream ends the stream, not the process.
    @Suppress("TooGenericExceptionCaught")
    private fun run() {
        var reason: String? = null
        try {
            serve()
        } catch (e: MediaStreamException) {
            reason = e.message
        } catch (e: IOException) {
            reason = "mirroring socket failed: $e"
        } catch (e: Exception) {
            reason = "mirroring failed: $e"
        } finally {
            closeQuietly(server)
            connection?.let(::closeQuietly)
            closeSink()
        }
        if (!closed) notifyDisconnected(reason ?: "mirroring stopped")
    }

    private fun serve() {
        val deadline = config.clock.nanoTime() + config.firstConnectionTimeoutMillis * NANOS_PER_MILLI
        var connected = false
        while (!closed) {
            if (!connected) {
                val remaining = (deadline - config.clock.nanoTime()) / NANOS_PER_MILLI
                if (remaining <= 0) throw MediaStreamException("no mirroring connection within the timeout")
                server.soTimeout = remaining.toInt()
            }
            val socket = acceptOrNull() ?: continue
            if (socket.inetAddress == client) {
                if (!connected) {
                    connected = true
                    server.soTimeout = 0
                }
                serveConnection(socket)
            } else {
                // Only the RTSP client may connect (03 §8.2).
                closeQuietly(socket)
            }
        }
    }

    private fun acceptOrNull(): Socket? =
        try {
            server.accept()
        } catch (_: SocketTimeoutException) {
            null
        }

    private fun serveConnection(socket: Socket) {
        connection = socket
        try {
            if (closed) return
            socket.soTimeout = config.readTimeoutMillis
            // Liveness comes from /feedback; keepalive only reaps connections of vanished senders (03b §3.1).
            socket.keepAlive = true
            notifyConnected()
            val reason =
                try {
                    readPackets(socket.getInputStream())
                } catch (e: IOException) {
                    if (closed) return
                    "mirroring connection failed: $e"
                }
            processor.onDisconnect()
            if (!closed) notifyDisconnected(reason)
        } finally {
            connection = null
            closeQuietly(socket)
        }
    }

    /** Reads records until the sender closes between two of them; returns the reason the connection ended. */
    private fun readPackets(input: InputStream): String {
        while (!closed) {
            if (!readHeader(input)) return "the sender closed the mirroring connection"
            val arrival = config.clock.nanoTime()
            var remaining = processor.beginPacket(header, 0)
            while (remaining > 0) {
                val n = minOf(remaining, chunk.size)
                if (!readExactly(input, chunk, 0, n)) return "the mirroring connection closed inside a packet"
                processor.payload(chunk, 0, n)
                remaining -= n
            }
            processor.endPacket(arrival)
        }
        return "mirroring stopped"
    }

    /** Reads one header; false on a clean end of stream before its first byte. Idle time before it is fine. */
    private fun readHeader(input: InputStream): Boolean {
        while (true) {
            val n =
                try {
                    input.read(header, 0, header.size)
                } catch (_: SocketTimeoutException) {
                    // Idle between packets is allowed (a sleeping client sends nothing, 03 §8.2).
                    if (closed) return false
                    continue
                }
            if (n < 0) return false
            if (!readExactly(input, header, n, header.size - n)) {
                throw MediaStreamException("the mirroring connection closed inside a header")
            }
            return true
        }
    }

    /** Fills `buffer[offset, offset + length)`; false on end of stream. A stall of the read timeout throws. */
    private fun readExactly(
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        var done = 0
        while (done < length) {
            val n =
                try {
                    input.read(buffer, offset + done, length - done)
                } catch (_: SocketTimeoutException) {
                    throw MediaStreamException("the mirroring sender stalled inside a packet")
                }
            if (n < 0) return false
            done += n
        }
        return true
    }

    // The listener and sink belong to other layers; their failures must not stop the stream or its cleanup.
    @Suppress("TooGenericExceptionCaught")
    private fun notifyConnected() {
        try {
            listener.onMirroringConnected()
        } catch (e: Exception) {
            config.log.log("mirroring listener failed: $e")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun notifyDisconnected(reason: String) {
        try {
            listener.onMirroringDisconnected(reason)
        } catch (e: Exception) {
            config.log.log("mirroring listener failed: $e")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeSink() {
        try {
            sink.close()
        } catch (e: Exception) {
            config.log.log("video sink close failed: $e")
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Closes [closeable], ignoring failures (it is being discarded). */
internal fun closeQuietly(closeable: AutoCloseable) {
    try {
        closeable.close()
    } catch (_: IOException) {
        // Nothing to do: the resource is gone either way.
    }
}
