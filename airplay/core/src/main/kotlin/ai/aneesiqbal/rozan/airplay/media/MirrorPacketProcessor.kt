package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.crypto.AesCtr
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp

/**
 * The socket-free core of one type-110 stream (03b §3.2–§3.6): header bounds, the continuous AES-CTR
 * keystream, avcC to Annex-B parameter sets, AVCC to Annex-B access units in place, presentation times,
 * and the [VideoSink] calls. A packet is handed over as [beginPacket], any number of [payload] chunks
 * and [endPacket].
 *
 * Allocates nothing per packet in steady state: the plaintext buffer is allocated once and grows by
 * doubling (up to [MediaLimits.MAX_VIDEO_PAYLOAD_BYTES] plus [MediaLimits.PARAMETER_SET_RESERVE_BYTES])
 * only when a larger packet than any before arrives; only codec configurations, which come at stream
 * start and on format changes, allocate (their parameter sets). Payload chunks must not come from the
 * plaintext buffer: decrypting into a different array is what keeps the JCA cipher from copying its input.
 *
 * Not thread-safe: one stream thread drives it.
 *
 * @param videoKey the stream's 16-byte `video_key`; the caller may zero it after construction.
 * @param videoIv the stream's 16-byte `video_iv`, the initial counter block.
 * @param sink where configurations and access units go.
 * @param clockSync the NTP offset; until it is synced, the first video packet's arrival anchors the times.
 */
internal class MirrorPacketProcessor(
    videoKey: ByteArray,
    videoIv: ByteArray,
    private val sink: VideoSink,
    private val clockSync: ClockSync,
) {
    private val ctr = AesCtr(videoKey, videoIv)
    private var plain = ByteArray(RESERVE + MediaLimits.INITIAL_VIDEO_BUFFER_BYTES)
    private val codecConfig = ByteArray(MediaLimits.MAX_CODEC_CONFIG_BYTES)

    private var type = NO_PACKET
    private var size = 0
    private var received = 0
    private var timestamp = 0L
    private var width = 0
    private var height = 0
    private var option = 0

    private var parameterSets: ByteArray? = null
    private var awaitingConfig = true
    private var inBandPending = false
    private var invalidRun = 0
    private var fallbackValid = false
    private var fallbackOffset = 0L

    /** Access units handed to the sink. */
    var framesSubmitted: Long = 0
        private set

    /** Type-0 packets dropped because no codec configuration had been applied yet. */
    var framesBeforeConfig: Long = 0
        private set

    /** Type-0 packets whose NAL walk failed after decryption. */
    var invalidFrames: Long = 0
        private set

    /** Codec configurations rejected by the avcC checks. */
    var invalidConfigs: Long = 0
        private set

    /** Calls to [VideoSink.configure]. */
    var configurations: Int = 0
        private set

    /** Whether the last codec configuration carried the "stream stopping" option (03b §3.8). */
    var suspended: Boolean = false
        private set

    /**
     * Starts a packet from its 128-byte header at `header[offset ..]` and returns the payload size the
     * caller must hand over next.
     *
     * @throws MediaStreamException when the size breaks the bound for its type (03 §8.2) or the sender
     *   asks for an unsupported codec.
     */
    fun beginPacket(
        header: ByteArray,
        offset: Int,
    ): Int {
        check(type == NO_PACKET) { "packet already started" }
        val payloadSize = header.i32Le(offset).toLong() and U32_MASK
        val packetType = header.u8(offset + TYPE_OFFSET)
        val limit =
            when (packetType) {
                TYPE_VIDEO -> MediaLimits.MAX_VIDEO_PAYLOAD_BYTES
                TYPE_CODEC -> MediaLimits.MAX_CODEC_CONFIG_BYTES
                TYPE_HEARTBEAT -> 0
                TYPE_REPORT -> MediaLimits.MAX_REPORT_PAYLOAD_BYTES
                else -> MediaLimits.MAX_UNKNOWN_PAYLOAD_BYTES
            }
        if (packetType == TYPE_CODEC && payloadSize == 0L) {
            throw MediaStreamException("the sender wants a video codec this receiver does not support")
        }
        if (payloadSize > limit) {
            throw MediaStreamException("type $packetType payload of $payloadSize bytes exceeds $limit")
        }
        type = packetType
        size = payloadSize.toInt()
        received = 0
        timestamp = header.i64Le(offset + TIMESTAMP_OFFSET)
        option = header.u8(offset + OPTION_OFFSET)
        if (packetType == TYPE_CODEC) {
            width = geometry(header, offset + WIDTH_OFFSET)
            height = geometry(header, offset + HEIGHT_OFFSET)
        } else if (packetType == TYPE_VIDEO && RESERVE + size > plain.size) {
            // Grow once to the largest packet so far (doubling), never beyond the bound.
            val grown = maxOf(plain.size * 2, RESERVE + size)
            plain = ByteArray(minOf(grown, RESERVE + MediaLimits.MAX_VIDEO_PAYLOAD_BYTES))
        }
        return size
    }

    /** The next [length] payload bytes of the current packet, from `source[offset ..]`. */
    fun payload(
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(type != NO_PACKET && length <= size - received) { "payload beyond the packet" }
        when (type) {
            TYPE_VIDEO -> ctr.process(source, offset, length, plain, RESERVE + received)
            TYPE_CODEC -> source.copyInto(codecConfig, received, offset, offset + length)
        }
        received += length
    }

    /**
     * Ends the current packet; [arrivalNanos] is when its header arrived, on the local clock.
     *
     * @throws MediaStreamException after [MediaLimits.MAX_INVALID_VIDEO_PACKETS] undecodable packets in a
     *   row, or for an HEVC configuration.
     */
    fun endPacket(arrivalNanos: Long) {
        check(type != NO_PACKET && received == size) { "packet incomplete" }
        val packetType = type
        type = NO_PACKET
        when (packetType) {
            TYPE_VIDEO -> onVideo(arrivalNanos)
            TYPE_CODEC -> onCodecConfig()
        }
    }

    /**
     * Handles a packet that is contiguous in `packet[offset, offset + length)`: header and whole payload.
     * For tests and benchmarks; the socket loop streams chunks instead.
     */
    fun processPacket(
        packet: ByteArray,
        offset: Int,
        length: Int,
        arrivalNanos: Long,
    ) {
        val payloadSize = beginPacket(packet, offset)
        require(length == MediaLimits.VIDEO_HEADER_BYTES + payloadSize) { "length does not match the header" }
        payload(packet, offset + MediaLimits.VIDEO_HEADER_BYTES, payloadSize)
        endPacket(arrivalNanos)
    }

    /**
     * The data connection was lost. A new connection continues the keystream (03 §6.5) but must send a
     * codec configuration before its frames are used.
     */
    fun onDisconnect() {
        type = NO_PACKET
        awaitingConfig = true
        invalidRun = 0
    }

    private fun onVideo(arrivalNanos: Long) {
        if (awaitingConfig) {
            // Decrypted already, so the keystream stays aligned (03b §3.4 step 7).
            framesBeforeConfig++
            return
        }
        val nalTypes = H264.avccToAnnexB(plain, RESERVE, size)
        if (nalTypes == 0) {
            invalidFrames++
            invalidRun++
            if (invalidRun >= MediaLimits.MAX_INVALID_VIDEO_PACKETS) {
                throw MediaStreamException("$invalidRun undecodable video packets in a row; the key is probably wrong")
            }
            return
        }
        invalidRun = 0
        var start = RESERVE
        val sets = parameterSets
        if (inBandPending && sets != null && (nalTypes and (1 shl H264.NAL_IDR)) != 0) {
            // In-band SPS/PPS in front of the first IDR after a (re)configure (03b §3.3 step 4).
            start -= sets.size
            sets.copyInto(plain, start)
            inBandPending = false
        }
        sink.submit(plain, start, RESERVE + size - start, presentationNanos(arrivalNanos))
        framesSubmitted++
    }

    private fun onCodecConfig() {
        if (H264.isHevcConfig(codecConfig, 0, size)) {
            throw MediaStreamException("HEVC mirroring is not supported")
        }
        val sets = H264.parseAvcC(codecConfig, 0, size)
        if (sets == null) {
            // Keep the previous configuration, or keep waiting for a valid one (03 §8.2).
            invalidConfigs++
            return
        }
        suspended = option == OPTION_SUSPEND_H264 || option == OPTION_SUSPEND_HEVC
        val current = parameterSets
        val changed = current == null || !current.contentEquals(sets)
        if (changed) {
            // An identical configuration (common at resume) leaves the decoder alone (03b §3.3).
            sink.configure(VideoCodec.H264, sets, width, height)
            parameterSets = sets
            configurations++
        }
        if (changed || awaitingConfig) inBandPending = true
        awaitingConfig = false
    }

    private fun presentationNanos(arrivalNanos: Long): Long {
        val clientNanos = NtpTimestamp(timestamp).toNanos() + CLIENT_EPOCH_NANOS
        if (clockSync.isSynced) return clientNanos - clockSync.offsetNanos
        if (!fallbackValid) {
            // θ0, the arrival offset of the first video packet (03c §5.3).
            fallbackOffset = clientNanos - arrivalNanos
            fallbackValid = true
        }
        return clientNanos - fallbackOffset
    }

    private fun geometry(
        header: ByteArray,
        offset: Int,
    ): Int {
        val value = Float.fromBits(header.i32Le(offset))
        return if (value.isFinite() && value > 0f && value <= MediaLimits.MAX_GEOMETRY) Math.round(value) else 0
    }

    private companion object {
        const val RESERVE = MediaLimits.PARAMETER_SET_RESERVE_BYTES
        const val NO_PACKET = -1
        const val TYPE_VIDEO = 0
        const val TYPE_CODEC = 1
        const val TYPE_HEARTBEAT = 2
        const val TYPE_REPORT = 5
        const val TYPE_OFFSET = 4
        const val OPTION_OFFSET = 6
        const val TIMESTAMP_OFFSET = 8
        const val WIDTH_OFFSET = 56
        const val HEIGHT_OFFSET = 60
        const val OPTION_SUSPEND_H264 = 0x56
        const val OPTION_SUSPEND_HEVC = 0x5E
        const val U32_MASK = 0xFFFF_FFFFL
        const val CLIENT_EPOCH_NANOS = NtpTimestamp.UNIX_EPOCH_OFFSET_SECONDS * NtpTimestamp.NANOS_PER_SECOND
    }
}
