package ai.aneesiqbal.rozan.airplay.android.audio

import ai.aneesiqbal.rozan.airplay.alac.AlacConfig
import ai.aneesiqbal.rozan.airplay.alac.AlacDecoder

/**
 * Decodes Apple Lossless packets to interleaved signed 16-bit PCM. A small
 * seam in front of `:airplay:alac`, so the sink can be tested with a fake.
 */
internal interface AlacPacketDecoder {
    /** Sample rate of the decoded PCM. */
    val sampleRate: Int

    /** Channels of the decoded PCM. */
    val channels: Int

    /** Smallest `output` array [decode] accepts, in samples. */
    val minOutputSize: Int

    /**
     * Decodes `packet[offset, offset + length)` into [output]; returns the
     * number of frames per channel written. Throws an `IOException` (or an
     * `IllegalArgumentException`) on malformed input.
     */
    fun decode(
        packet: ByteArray,
        offset: Int,
        length: Int,
        output: ShortArray,
    ): Int
}

/** Creates [AlacPacketDecoder]s for a stream. */
internal fun interface AlacDecoderFactory {
    /** A decoder for the stream described by [magicCookie] (ALACSpecificConfig, bare or atom-wrapped). */
    fun create(magicCookie: ByteArray): AlacPacketDecoder
}

/** The production decoder: the Kotlin port of Apple's reference decoder in `:airplay:alac`. */
internal object AlacDecoders : AlacDecoderFactory {
    override fun create(magicCookie: ByteArray): AlacPacketDecoder {
        val config = AlacConfig.fromMagicCookie(magicCookie)
        val decoder = AlacDecoder(config)
        return object : AlacPacketDecoder {
            override val sampleRate: Int = config.sampleRate
            override val channels: Int = config.numChannels
            override val minOutputSize: Int = decoder.minOutputSize

            override fun decode(
                packet: ByteArray,
                offset: Int,
                length: Int,
                output: ShortArray,
            ): Int = decoder.decode(packet, offset, length, output)
        }
    }
}
