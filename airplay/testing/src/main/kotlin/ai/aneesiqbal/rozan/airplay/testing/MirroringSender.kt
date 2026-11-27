package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.AesCtr
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.locks.LockSupport

/**
 * The sender's side of one type-110 mirroring stream (docs/protocol/03b-mirroring-video.md): records of a
 * 128-byte little-endian header and a payload over TCP to the receiver's `dataPort`. Codec records carry
 * the avcC in clear; video records carry AVCC access units encrypted with the stream's continuous
 * AES-CTR keystream (03 §2.3). Timestamps are the sender's uptime in Q32.32 (03b §3.5).
 *
 * Not thread-safe; one streaming thread owns it.
 *
 * @param sessionKey the session's 16-byte `session_key`.
 * @param streamConnectionId the `streamConnectionID` sent in the stream SETUP.
 * @param clock the sender's clock, for timestamps.
 */
public class MirroringSender(
    sessionKey: ByteArray,
    public val streamConnectionId: Long,
    private val clock: SenderClock,
) : AutoCloseable {
    private val ctr =
        AesCtr(MediaKeys.videoKey(sessionKey, streamConnectionId), MediaKeys.videoIv(sessionKey, streamConnectionId))
    private var socket: Socket? = null
    private var output: OutputStream? = null

    /** Video records written. */
    @Volatile
    public var framesSent: Int = 0
        private set

    /**
     * The most bytes one socket write carries; records are split across writes above it, so a test can
     * make the receiver reassemble headers and payloads from small reads.
     */
    public var maxWriteBytes: Int = Int.MAX_VALUE

    /** Connects to the receiver's data port. */
    public fun connect(
        address: InetAddress,
        port: Int,
    ) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
        socket = s
        output = s.getOutputStream()
    }

    /** Writes a type-1 codec record for [sps] and [pps] at [width]×[height], stamped [uptimeNanos]. */
    public fun sendCodecConfig(
        sps: ByteArray,
        pps: ByteArray,
        width: Int,
        height: Int,
        uptimeNanos: Long = clock.uptimeNanos(),
    ) {
        val payload = avcC(sps, pps)
        val record = header(TYPE_CODEC, payload.size, uptimeNanos) + payload
        record[6] = OPTION_RUNNING
        record[7] = 1
        for (offset in intArrayOf(16, 40, 56)) {
            record.putI32Le(offset, width.toFloat().toRawBits())
            record.putI32Le(offset + 4, height.toFloat().toRawBits())
        }
        write(record)
    }

    /** Writes [frame] as an encrypted type-0 record stamped [uptimeNanos]; consumes keystream. */
    public fun sendFrame(
        frame: H264Clip.Frame,
        uptimeNanos: Long = clock.uptimeNanos(),
    ) {
        val payload = avcc(frame.nalUnits)
        val record = header(TYPE_VIDEO, payload.size, uptimeNanos)
        record[5] = if (frame.isIdr) IDR_FLAG else 0
        ctr.process(payload)
        write(record + payload)
        framesSent++
    }

    /**
     * Streams [clip] in real time at [frameRate] for [durationNanos], looping it from the start, until
     * [stop] returns true or the receiver closes the connection. The codec record goes out first, with the
     * first frame's timestamp.
     *
     * @return whether the stream ran to the end (false: stopped, or closed by the receiver).
     */
    public fun stream(
        clip: H264Clip,
        durationNanos: Long,
        frameRate: Int = DEFAULT_FRAME_RATE,
        stop: () -> Boolean = { false },
    ): Boolean {
        val frameNanos = NANOS_PER_SECOND / frameRate
        val frames = (durationNanos / frameNanos).toInt()
        val start = System.nanoTime()
        return try {
            sendCodecConfig(clip.sps, clip.pps, clip.width, clip.height, clock.uptimeNanosAt(start))
            for (i in 0 until frames) {
                val due = start + i * frameNanos
                sleepUntil(due)
                if (stop()) return false
                sendFrame(clip.frames[i % clip.frames.size], clock.uptimeNanosAt(due))
            }
            true
        } catch (ignored: IOException) {
            false
        }
    }

    /** Writes raw bytes to the data connection, in writes of at most [maxWriteBytes]. */
    public fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("not connected")
        var position = 0
        while (position < bytes.size) {
            val n = minOf(maxWriteBytes, bytes.size - position)
            out.write(bytes, position, n)
            out.flush()
            position += n
        }
    }

    /** Closes the data connection. */
    override fun close() {
        socket?.close()
        socket = null
        output = null
    }

    /** Record layout. */
    public companion object {
        /** Header size. */
        public const val HEADER_BYTES: Int = 128

        /** Frames per second when [stream] is not told otherwise. */
        public const val DEFAULT_FRAME_RATE: Int = 30

        private const val TYPE_VIDEO = 0
        private const val TYPE_CODEC = 1
        private const val IDR_FLAG: Byte = 0x10
        private const val OPTION_RUNNING: Byte = 0x16
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val NANOS_PER_SECOND = 1_000_000_000L

        private fun header(
            type: Int,
            payloadSize: Int,
            uptimeNanos: Long,
        ): ByteArray {
            val header = ByteArray(HEADER_BYTES)
            header.putI32Le(0, payloadSize)
            header[4] = type.toByte()
            header.putI64Le(8, SenderClock.q32(uptimeNanos))
            return header
        }

        /** The AVCDecoderConfigurationRecord for one SPS and one PPS, 4-byte NAL lengths (03b §3.3). */
        public fun avcC(
            sps: ByteArray,
            pps: ByteArray,
        ): ByteArray {
            val out = ByteArray(11 + sps.size + pps.size)
            out[0] = 1
            out[1] = sps[1]
            out[2] = sps[2]
            out[3] = sps[3]
            out[4] = 0xFF.toByte()
            out[5] = 0xE1.toByte()
            out.putU16Be(6, sps.size)
            sps.copyInto(out, 8)
            out[8 + sps.size] = 1
            out.putU16Be(9 + sps.size, pps.size)
            pps.copyInto(out, 11 + sps.size)
            return out
        }

        /** `[u32be length][NAL]…`, the plaintext of a video record. */
        public fun avcc(nalUnits: List<ByteArray>): ByteArray {
            val out = ByteArray(nalUnits.sumOf { 4 + it.size })
            var position = 0
            for (nal in nalUnits) {
                out.putI32Be(position, nal.size)
                nal.copyInto(out, position + 4)
                position += 4 + nal.size
            }
            return out
        }
    }
}

/** Parks until `System.nanoTime()` reaches [nanoTime]. */
internal fun sleepUntil(nanoTime: Long) {
    var left = nanoTime - System.nanoTime()
    while (left > 0) {
        LockSupport.parkNanos(left)
        left = nanoTime - System.nanoTime()
    }
}
