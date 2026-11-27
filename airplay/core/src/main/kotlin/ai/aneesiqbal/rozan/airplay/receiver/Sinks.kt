package ai.aneesiqbal.rozan.airplay.receiver

/** Video codecs a mirroring stream can carry. */
public enum class VideoCodec {
    H264,
    HEVC,
}

/**
 * Receives decrypted video for one mirroring session. Methods are called from
 * the mirroring thread, in order. [submit] may block briefly when the decoder
 * is full; that back-pressures the sender over TCP.
 */
public interface VideoSink {
    /**
     * (Re)configures the decoder. [parameterSets] holds the codec configuration
     * in Annex-B form (start code + SPS + start code + PPS, plus VPS for HEVC).
     * Called before the first frame and again whenever the resolution or codec
     * configuration changes. [width]/[height] are the sender's frame size in
     * pixels when known, else 0.
     */
    public fun configure(
        codec: VideoCodec,
        parameterSets: ByteArray,
        width: Int,
        height: Int,
    )

    /**
     * One access unit in Annex-B form, valid only during the call (the buffer
     * is reused). [presentationNanos] is on the local `System.nanoTime()` clock.
     */
    public fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    )

    /** Releases the decoder. No calls follow. */
    public fun close()
}

/** Audio formats a stream can carry. Decoding happens in the sink. */
public sealed interface AudioFormat {
    /** Sample rate in Hz. */
    public val sampleRate: Int

    /** Channel count. */
    public val channels: Int

    /** PCM frames per access unit. */
    public val framesPerPacket: Int

    /** Apple Lossless. [magicCookie] is the 24-byte ALACSpecificConfig. */
    public class Alac(
        override val sampleRate: Int,
        override val channels: Int,
        override val framesPerPacket: Int,
        public val magicCookie: ByteArray,
    ) : AudioFormat

    /** AAC (LC or ELD). [audioSpecificConfig] is the MPEG-4 AudioSpecificConfig for the decoder. */
    public class Aac(
        override val sampleRate: Int,
        override val channels: Int,
        override val framesPerPacket: Int,
        public val eld: Boolean,
        public val audioSpecificConfig: ByteArray,
    ) : AudioFormat

    /** Signed 16-bit big-endian interleaved PCM. */
    public class Pcm(
        override val sampleRate: Int,
        override val channels: Int,
        override val framesPerPacket: Int,
    ) : AudioFormat
}

/**
 * Receives decrypted audio access units for one stream, in presentation order
 * (gaps already concealed or skipped by the receiver). Called from one thread.
 */
public interface AudioSink {
    /**
     * One encoded access unit, valid only during the call (the buffer is
     * reused). [presentationNanos] is when its first sample should be heard, on
     * the local `System.nanoTime()` clock.
     */
    public fun submit(
        accessUnit: ByteArray,
        offset: Int,
        length: Int,
        presentationNanos: Long,
    )

    /** Linear gain in 0.0..1.0 (0 = muted). */
    public fun setVolume(linear: Float)

    /** Drops everything queued (the sender seeked or paused). */
    public fun flush()

    /** Releases the output. No calls follow. */
    public fun close()
}
