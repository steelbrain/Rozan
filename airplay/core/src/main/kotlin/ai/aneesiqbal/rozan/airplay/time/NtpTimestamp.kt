package ai.aneesiqbal.rozan.airplay.time

/**
 * A 64-bit NTP-format time: Q32.32 fixed point, whole seconds in the high 32 bits and the fraction (units
 * of 2^-32 s) in the low 32 bits, both unsigned.
 *
 * What the seconds count from depends on the source. In AirPlay the "client NTP domain" counts from the
 * sender's own epoch plus [UNIX_EPOCH_OFFSET_SECONDS], and video timestamps use the same clock without
 * that offset (docs/protocol/03-media-streams.md §0). [toNanos] and [fromNanos] therefore convert the
 * fixed-point value without assuming an epoch; [toUnixNanos] and [fromUnixNanos] are for true wall-clock
 * NTP times only.
 *
 * @property bits the raw 64-bit value as it appears on the wire.
 */
@JvmInline
public value class NtpTimestamp(
    public val bits: Long,
) {
    /** Whole seconds, 0 to 2^32 - 1. */
    public val seconds: Long get() = bits ushr 32

    /** Fraction of a second in units of 2^-32 s, 0 to 2^32 - 1. */
    public val fraction: Long get() = bits and LOW_32

    /**
     * This value in nanoseconds: `seconds * 1e9 + (fraction * 1e9) >>> 32`, truncating the fraction as the
     * protocol spec does. Always in 0 until 2^32 * 1e9, so it fits a signed `Long`.
     */
    public fun toNanos(): Long = seconds * NANOS_PER_SECOND + ((fraction * NANOS_PER_SECOND) ushr 32)

    /**
     * Wall-clock nanoseconds since 1970-01-01T00:00:00Z, reading the seconds as NTP era 0 (from 1900) when
     * their top bit is set and as era 1 (from 2036-02-07) when it is clear, as RFC 4330 §3 suggests. This
     * covers 1968-01-20 to 2104-02-26.
     */
    public fun toUnixNanos(): Long {
        val eraSeconds = if (seconds and ERA_HALF != 0L) 0L else ERA_SECONDS
        return toNanos() + (eraSeconds - UNIX_EPOCH_OFFSET_SECONDS) * NANOS_PER_SECOND
    }

    override fun toString(): String = "NtpTimestamp($seconds + $fraction/2^32 s)"

    /** Constants and factories. */
    public companion object {
        /** Seconds from the NTP epoch (1900-01-01) to the Unix epoch (1970-01-01): 2,208,988,800. */
        public const val UNIX_EPOCH_OFFSET_SECONDS: Long = 2_208_988_800L

        /** Nanoseconds in one second. */
        public const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** One past the largest value [fromNanos] accepts: 2^32 seconds in nanoseconds. */
        public const val NANOS_LIMIT: Long = (1L shl 32) * NANOS_PER_SECOND

        private const val LOW_32 = 0xFFFF_FFFFL
        private const val ERA_SECONDS = 1L shl 32
        private const val ERA_HALF = 1L shl 31

        // RFC 4330 window for toUnixNanos/fromUnixNanos, in Unix seconds.
        private const val UNIX_WINDOW_START = ERA_HALF - UNIX_EPOCH_OFFSET_SECONDS
        private const val UNIX_WINDOW_END = UNIX_WINDOW_START + ERA_SECONDS

        /** A timestamp from whole [seconds] and a [fraction] in units of 2^-32 s, each 0 to 2^32 - 1. */
        public fun of(
            seconds: Long,
            fraction: Long,
        ): NtpTimestamp {
            require(seconds in 0..LOW_32) { "seconds out of range" }
            require(fraction in 0..LOW_32) { "fraction out of range" }
            return NtpTimestamp((seconds shl 32) or fraction)
        }

        /**
         * The NTP-format value for [nanos], which must be in 0 until [NANOS_LIMIT]:
         * `seconds = nanos / 1e9`, `fraction = ((nanos % 1e9) << 32) / 1e9` (truncating). Converting back
         * with [toNanos] returns [nanos] or one less.
         */
        public fun fromNanos(nanos: Long): NtpTimestamp {
            require(nanos in 0 until NANOS_LIMIT) { "nanoseconds out of the NTP range" }
            val seconds = nanos / NANOS_PER_SECOND
            val fraction = ((nanos % NANOS_PER_SECOND) shl 32) / NANOS_PER_SECOND
            return NtpTimestamp((seconds shl 32) or fraction)
        }

        /**
         * The wall-clock NTP time for [unixNanos] (nanoseconds since 1970-01-01T00:00:00Z), which must fall
         * in the window [toUnixNanos] covers. Times from 2036-02-07 on wrap into era 1.
         */
        public fun fromUnixNanos(unixNanos: Long): NtpTimestamp {
            val unixSeconds = Math.floorDiv(unixNanos, NANOS_PER_SECOND)
            require(unixSeconds in UNIX_WINDOW_START until UNIX_WINDOW_END) { "time outside the NTP era window" }
            val ntpSeconds = (unixSeconds + UNIX_EPOCH_OFFSET_SECONDS) and LOW_32
            val nanosOfSecond = Math.floorMod(unixNanos, NANOS_PER_SECOND)
            return NtpTimestamp((ntpSeconds shl 32) or ((nanosOfSecond shl 32) / NANOS_PER_SECOND))
        }
    }
}
