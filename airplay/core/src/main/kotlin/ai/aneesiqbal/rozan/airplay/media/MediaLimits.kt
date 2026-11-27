package ai.aneesiqbal.rozan.airplay.media

/**
 * Every bound the media layer enforces and every buffer it allocates, in one place so a memory budget can be
 * computed (docs/protocol/03-media-streams.md §8). Buffers are allocated once per stream (the video buffers
 * grow on demand up to their cap) and reused for every packet.
 */
internal object MediaLimits {
    /** Mirroring header size (03b §3.2). */
    const val VIDEO_HEADER_BYTES: Int = 128

    /** Largest type-0 (encrypted video) payload accepted; larger closes the stream (03 §8.2). */
    const val MAX_VIDEO_PAYLOAD_BYTES: Int = 4 * 1024 * 1024

    /** Largest type-1 (codec configuration) payload accepted. */
    const val MAX_CODEC_CONFIG_BYTES: Int = 4096

    /** Largest type-5 (streaming report) payload accepted; it is read and discarded. */
    const val MAX_REPORT_PAYLOAD_BYTES: Int = 256 * 1024

    /** Largest payload of an unknown packet type; it is read and discarded. */
    const val MAX_UNKNOWN_PAYLOAD_BYTES: Int = 1024 * 1024

    /** Initial size of each video payload buffer; they double up to [MAX_VIDEO_PAYLOAD_BYTES] as needed. */
    const val INITIAL_VIDEO_BUFFER_BYTES: Int = 256 * 1024

    /** Room kept in front of each video access unit for in-band parameter sets. */
    const val PARAMETER_SET_RESERVE_BYTES: Int = 4096

    /** Most bytes of SPS and PPS together in one codec configuration (03 §8.2). */
    const val MAX_PARAMETER_SET_BYTES: Int = 2048

    /** Longest single SPS or PPS. */
    const val MAX_PARAMETER_SET_NAL_BYTES: Int = 512

    /** Most SPS entries in an avcC record. */
    const val MAX_SPS_COUNT: Int = 31

    /** Most PPS entries in an avcC record. */
    const val MAX_PPS_COUNT: Int = 255

    /** Consecutive undecodable video packets after which the stream is closed (the key is probably wrong). */
    const val MAX_INVALID_VIDEO_PACKETS: Int = 30

    /**
     * Socket read chunk of a mirroring stream. Payloads are read here and decrypted (or copied) into the
     * payload buffer; report and unknown payloads are read here and discarded.
     */
    const val VIDEO_READ_BUFFER_BYTES: Int = 64 * 1024

    /** Kernel receive buffer requested for mirroring data connections, so IDR bursts do not stall the sender. */
    const val MIRROR_SOCKET_RECEIVE_BUFFER_BYTES: Int = 1024 * 1024

    /** Largest header geometry value taken as a frame size (03 §8.2). */
    const val MAX_GEOMETRY: Float = 8192f

    /**
     * Upper bound of the buffers one mirroring stream allocates: the payload buffer at its cap with the
     * parameter-set reserve, the codec configuration buffer, the read chunk and the header. Ciphertext is
     * decrypted from the read chunk into the payload buffer rather than in place, because an in-place JCA
     * update copies its whole input on every call.
     */
    const val VIDEO_STREAM_MAX_BYTES: Int =
        PARAMETER_SET_RESERVE_BYTES + MAX_VIDEO_PAYLOAD_BYTES + MAX_CODEC_CONFIG_BYTES + VIDEO_READ_BUFFER_BYTES +
            VIDEO_HEADER_BYTES

    /** RTP header size. */
    const val RTP_HEADER_BYTES: Int = 12

    /** Largest audio payload (03 §8.3); also the size of one jitter buffer slot. */
    const val MAX_AUDIO_PAYLOAD_BYTES: Int = 2048

    /** Largest RTP data datagram accepted. */
    const val MAX_RTP_DATAGRAM_BYTES: Int = RTP_HEADER_BYTES + MAX_AUDIO_PAYLOAD_BYTES

    /** Jitter buffer depth in packets: 4.1 s of ALAC, 5.6 s of AAC-ELD (03c §4.3). Must be a power of two. */
    const val JITTER_SLOTS: Int = 512

    /** UDP receive buffer per socket; larger than any accepted datagram so oversize ones are detected. */
    const val UDP_RECEIVE_BUFFER_BYTES: Int = 64 * 1024

    /** Kernel receive buffer requested for the audio data socket (03c §4.1). */
    const val AUDIO_SOCKET_RECEIVE_BUFFER_BYTES: Int = 256 * 1024

    /** Most resend requests per missing sequence number (03 §8.3). */
    const val MAX_RESENDS_PER_PACKET: Int = 2

    /** Most packets asked for in resend requests per second (03 §8.3). */
    const val MAX_RESEND_PACKETS_PER_SECOND: Int = 50

    /** Gaps (runs of missing audio packets) tracked for resend requests at once; more are not requested. */
    const val MAX_RESEND_GAPS: Int = 32

    /** Most resend requests sent in one batch (one scan of the jitter buffer). */
    const val MAX_RESEND_REQUESTS_PER_BATCH: Int = 16

    /**
     * Upper bound of the buffers one audio stream allocates: the jitter ring (payload slots plus at most
     * 16 bytes of per-entry fields), the output buffer, the data and control receive buffers, the resend
     * batch and the 4 KiB scratch block of the AES-CBC helper. About 1.2 MiB.
     */
    const val AUDIO_STREAM_MAX_BYTES: Int =
        JITTER_SLOTS * (MAX_AUDIO_PAYLOAD_BYTES + 16) + MAX_AUDIO_PAYLOAD_BYTES + 2 * UDP_RECEIVE_BUFFER_BYTES +
            MAX_RESEND_REQUESTS_PER_BATCH * 8 + 4096

    /** Timing requests remembered for matching replies (03c §4.6). */
    const val TIMING_OUTSTANDING: Int = 8

    /** Timing samples kept for the clock filter (03c §4.6). */
    const val TIMING_SAMPLES: Int = 8

    /** Duplicate-T3 history kept by the timing client. */
    const val TIMING_SEEN_T3: Int = 8

    /** Timing datagram size. */
    const val TIMING_PACKET_BYTES: Int = 32
}
