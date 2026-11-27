package ai.aneesiqbal.rozan.airplay.time

/**
 * A 32-bit RTP timestamp: a sample (frame) counter that wraps at 2^32.
 *
 * Arithmetic is modulo 2^32. Comparing two timestamps only makes sense when they are less than 2^31 apart,
 * which [minus] assumes (serial-number arithmetic, `sdiff32` in docs/protocol/03c-audio-rtp-and-timing.md).
 *
 * @property bits the 32 bits as a signed `Int`, as read from the wire with a plain 32-bit read.
 */
@JvmInline
public value class RtpTimestamp(
    public val bits: Int,
) {
    /** The unsigned value, 0 to 2^32 - 1. */
    public val value: Long get() = bits.toLong() and 0xFFFF_FFFFL

    /** The timestamp [frames] later, wrapping at 2^32. */
    public operator fun plus(frames: Int): RtpTimestamp = RtpTimestamp(bits + frames)

    /** The timestamp [frames] earlier, wrapping at 2^32. */
    public operator fun minus(frames: Int): RtpTimestamp = RtpTimestamp(bits - frames)

    /**
     * Signed distance in frames from [other] to this timestamp (`sdiff32(this, other)`): positive when this
     * one is later. Correct while the two are less than 2^31 frames apart.
     */
    public operator fun minus(other: RtpTimestamp): Int = bits - other.bits

    /** Whether this timestamp is later than [other] in serial-number order. */
    public fun isAfter(other: RtpTimestamp): Boolean = this - other > 0

    override fun toString(): String = "RtpTimestamp($value)"

    /** Factories and conversions. */
    public companion object {
        /** The timestamp with unsigned [value], 0 to 2^32 - 1. */
        public fun of(value: Long): RtpTimestamp {
            require(value in 0..0xFFFF_FFFFL) { "RTP timestamp out of range" }
            return RtpTimestamp(value.toInt())
        }

        /**
         * Duration of [frames] at [clockRate] frames per second, in nanoseconds, truncated toward zero
         * (`frames * 1e9 / clockRate`, computed without overflow).
         */
        public fun framesToNanos(
            frames: Long,
            clockRate: Int,
        ): Long {
            require(clockRate > 0) { "clock rate must be positive" }
            val whole = frames / clockRate
            val rest = frames % clockRate
            return whole * NtpTimestamp.NANOS_PER_SECOND + rest * NtpTimestamp.NANOS_PER_SECOND / clockRate
        }
    }
}

/**
 * A 16-bit RTP sequence number, wrapping at 65 536.
 *
 * @property value the number, 0 to 65 535.
 */
@JvmInline
public value class RtpSequenceNumber(
    public val value: Int,
) {
    init {
        require(value in 0..MAX) { "RTP sequence number out of range" }
    }

    /** The number [count] packets later, wrapping at 65 536. */
    public operator fun plus(count: Int): RtpSequenceNumber = RtpSequenceNumber((value + count) and MAX)

    /** The number [count] packets earlier, wrapping at 65 536. */
    public operator fun minus(count: Int): RtpSequenceNumber = RtpSequenceNumber((value - count) and MAX)

    /**
     * Signed distance in packets from [other] to this number (`sdiff16(this, other)`), in -32 768..32 767:
     * positive when this one is later.
     */
    public operator fun minus(other: RtpSequenceNumber): Int = ((value - other.value) and MAX).toShort().toInt()

    /** Whether this number is later than [other] in serial-number order. */
    public fun isAfter(other: RtpSequenceNumber): Boolean = this - other > 0

    override fun toString(): String = "RtpSequenceNumber($value)"

    private companion object {
        const val MAX = 0xFFFF
    }
}

/**
 * Extends 32-bit RTP timestamps to a 64-bit frame count that keeps growing across wraps, for presentation
 * times. The first timestamp seen maps to its own unsigned value; each later one moves the count by its
 * serial distance from the previous one, so reordered packets map correctly as long as neighbours are less
 * than 2^31 frames apart.
 *
 * Not thread-safe: use one per stream, from one thread.
 */
public class RtpTimestampExtender {
    private var last = RtpTimestamp(0)
    private var extended = 0L
    private var started = false

    /** Returns the 64-bit position of [timestamp]. */
    public fun extend(timestamp: RtpTimestamp): Long {
        extended = if (started) extended + (timestamp - last) else timestamp.value
        started = true
        last = timestamp
        return extended
    }

    /** Forgets the history; the next timestamp starts a new timeline (after a flush, for example). */
    public fun reset() {
        started = false
    }
}
