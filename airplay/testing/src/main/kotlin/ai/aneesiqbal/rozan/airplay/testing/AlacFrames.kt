package ai.aneesiqbal.rozan.airplay.testing

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Apple Lossless frames as an AirPlay sender sends them (docs/protocol/03c-audio-rtp-and-timing.md
 * §4.8.1): one channel-pair element and the end tag. No compressing encoder is available on the JVM, so
 * every frame is an escape frame: the 16-bit samples stored verbatim, which ALAC allows and every decoder
 * handles. The first byte is 0x20 like any AirPlay ALAC frame.
 */
public object AlacFrames {
    private const val ELEMENT_CPE = 1
    private const val ELEMENT_END = 7
    private const val ESCAPE_FLAG = 1
    private const val PARTIAL_FLAG = 8
    private const val BITS_PER_SAMPLE = 16

    /** Element tag 3, instance tag 4, reserved 12, flags 4. */
    private const val HEADER_BITS = 23
    private const val COUNT_BITS = 32
    private const val END_BITS = 3

    /** The size in bytes of an escape frame of [frames] stereo frames, with the count when [partial]. */
    public fun size(
        frames: Int,
        partial: Boolean = false,
    ): Int = (HEADER_BITS + (if (partial) COUNT_BITS else 0) + frames * 2 * BITS_PER_SAMPLE + END_BITS + 7) / 8

    /**
     * An escape frame of [frames] interleaved stereo samples from [pcm] (L, R, L, R, …). With fewer frames
     * than [framesPerPacket] it carries the partial-frame count.
     */
    public fun encode(
        pcm: ShortArray,
        frames: Int,
        framesPerPacket: Int,
    ): ByteArray {
        require(frames in 1..framesPerPacket && pcm.size >= frames * 2) { "bad frame count" }
        val partial = frames != framesPerPacket
        val bits = BitWriter(size(frames, partial))
        bits.write(ELEMENT_CPE, 3)
        bits.write(0, 4) // element instance tag
        bits.write(0, 12) // reserved
        bits.write(ESCAPE_FLAG or (if (partial) PARTIAL_FLAG else 0), 4) // no bytes shifted off
        if (partial) bits.write(frames, COUNT_BITS)
        for (i in 0 until frames * 2) bits.write(pcm[i].toInt() and 0xFFFF, BITS_PER_SAMPLE)
        bits.write(ELEMENT_END, END_BITS)
        return bits.bytes
    }

    /**
     * A sine tone of [frequency] Hz at [amplitude], as [frames] interleaved stereo samples starting at
     * sample [firstFrame] of the tone, at [sampleRate].
     */
    public fun tone(
        firstFrame: Long,
        frames: Int,
        frequency: Double = 440.0,
        amplitude: Int = 6000,
        sampleRate: Int = 44_100,
    ): ShortArray {
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val value = (amplitude * sin(2 * PI * frequency * (firstFrame + i) / sampleRate)).roundToInt().toShort()
            pcm[2 * i] = value
            pcm[2 * i + 1] = value
        }
        return pcm
    }

    private class BitWriter(
        capacity: Int,
    ) {
        private val out = ByteArray(capacity)
        private var position = 0

        val bytes: ByteArray get() = out.copyOf((position + 7) / 8)

        fun write(
            value: Int,
            count: Int,
        ) {
            for (i in count - 1 downTo 0) {
                if ((value ushr i) and 1 == 1) {
                    val index = position ushr 3
                    out[index] = (out[index].toInt() or (0x80 ushr (position and 7))).toByte()
                }
                position++
            }
        }
    }
}
