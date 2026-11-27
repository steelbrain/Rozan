package ai.aneesiqbal.rozan.airplay.media

/**
 * Resend requests for missing audio packets (03c §4.5, 03 §8.3): a request when a gap opens, one retry
 * after `RTT + 20 ms` if it is still open, never more than [MediaLimits.MAX_RESENDS_PER_PACKET] per
 * packet and never more than [MediaLimits.MAX_RESEND_PACKETS_PER_SECOND] packets asked for per second.
 * Requests are written as `80 D5 <counter> <first> <count>` (03d §G) into [requests]. Allocates nothing.
 *
 * Not thread-safe; the audio stream guards it with its lock.
 */
internal class ResendScheduler {
    private val gapFirst = IntArray(MediaLimits.MAX_RESEND_GAPS)
    private val gapCount = IntArray(MediaLimits.MAX_RESEND_GAPS)
    private val gapSends = IntArray(MediaLimits.MAX_RESEND_GAPS)
    private val gapNextNanos = LongArray(MediaLimits.MAX_RESEND_GAPS)
    private var gaps = 0
    private var counter = 0
    private var windowStartNanos = 0L
    private var windowPackets = 0
    private var windowValid = false

    /** Requests written by the last [collect]: entry `i` is `requests[8i, 8i + 8)`. */
    val requests: ByteArray = ByteArray(MediaLimits.MAX_RESEND_REQUESTS_PER_BATCH * REQUEST_BYTES)

    /** Gaps not tracked because [MediaLimits.MAX_RESEND_GAPS] were already open. */
    var gapsDropped: Long = 0
        private set

    /** Requests written so far. */
    var requestsWritten: Long = 0
        private set

    /** Whether no gap is tracked, so [collect] has nothing to do until [onGap]. */
    val isIdle: Boolean get() = gaps == 0

    /** Starts tracking [count] missing packets from [first]; the first request is due at [nowNanos]. */
    fun onGap(
        first: Int,
        count: Int,
        nowNanos: Long,
    ) {
        if (gaps == MediaLimits.MAX_RESEND_GAPS) {
            gapsDropped++
            return
        }
        gapFirst[gaps] = first
        gapCount[gaps] = count
        gapSends[gaps] = 0
        gapNextNanos[gaps] = nowNanos
        gaps++
    }

    /** Forgets every gap (flush, reset, stop). */
    fun clear() {
        gaps = 0
    }

    /**
     * Writes the requests due at [nowNanos] into [requests] and returns how many. [jitter] tells which
     * packets are still missing. [deadline] gives the local play time of a sequence number, or
     * [Long.MIN_VALUE] when unknown; a gap whose first packet plays within `2 × RTT + 20 ms` is not
     * requested (03c §4.5). [roundTripNanos] is the timing client's RTT, 0 when unknown.
     */
    fun collect(
        nowNanos: Long,
        jitter: JitterBuffer,
        roundTripNanos: Long,
        deadline: PlayDeadline,
    ): Int {
        if (!windowValid || nowNanos - windowStartNanos >= NANOS_PER_SECOND) {
            windowStartNanos = nowNanos
            windowPackets = 0
            windowValid = true
        }
        var written = 0
        var i = 0
        while (i < gaps && written < MediaLimits.MAX_RESEND_REQUESTS_PER_BATCH && windowPackets < PER_SECOND) {
            when (assess(i, nowNanos, jitter, roundTripNanos, deadline)) {
                REMOVE -> {
                    remove(i)
                }

                WAIT -> {
                    i++
                }

                else -> {
                    val count = minOf(gapCount[i], PER_SECOND - windowPackets)
                    writeRequest(written++, gapFirst[i], count)
                    windowPackets += count
                    gapSends[i]++
                    gapNextNanos[i] = nowNanos + roundTripNanos + MARGIN_NANOS
                    i++
                }
            }
        }
        return written
    }

    /** Trims gap [i] and decides whether to [REMOVE] it, [WAIT] or [SEND] a request now. */
    private fun assess(
        i: Int,
        nowNanos: Long,
        jitter: JitterBuffer,
        roundTripNanos: Long,
        deadline: PlayDeadline,
    ): Int {
        trim(i, jitter)
        if (gapCount[i] == 0 || gapSends[i] >= MediaLimits.MAX_RESENDS_PER_PACKET) return REMOVE
        if (nowNanos - gapNextNanos[i] < 0) return WAIT
        val due = deadline.playNanos(gapFirst[i])
        // Too late for a resend to arrive in time (03c §4.5).
        if (due != Long.MIN_VALUE && due - nowNanos <= 2 * roundTripNanos + MARGIN_NANOS) return REMOVE
        return SEND
    }

    /** Narrows gap [i] to its still-missing packets, from the first missing one to the last. */
    private fun trim(
        i: Int,
        jitter: JitterBuffer,
    ) {
        var first = gapFirst[i]
        var count = gapCount[i]
        while (count > 0 && isResolved(first, jitter)) {
            first = (first + 1) and SEQUENCE_MASK
            count--
        }
        while (count > 0 && jitter.isPresent((first + count - 1) and SEQUENCE_MASK)) count--
        gapFirst[i] = first
        gapCount[i] = count
    }

    /** Whether [sequence] no longer needs a resend: it arrived or playout has passed it. */
    private fun isResolved(
        sequence: Int,
        jitter: JitterBuffer,
    ): Boolean = jitter.isEmpty || jitter.isPresent(sequence) || JitterBuffer.serial16(sequence, jitter.head) < 0

    private fun remove(i: Int) {
        gaps--
        gapFirst[i] = gapFirst[gaps]
        gapCount[i] = gapCount[gaps]
        gapSends[i] = gapSends[gaps]
        gapNextNanos[i] = gapNextNanos[gaps]
    }

    private fun writeRequest(
        index: Int,
        first: Int,
        count: Int,
    ) {
        counter = (counter + 1) and SEQUENCE_MASK
        val offset = index * REQUEST_BYTES
        requests[offset] = RTP_V2.toByte()
        requests[offset + 1] = RESEND_REQUEST.toByte()
        requests.putU16Be(offset + 2, counter)
        requests.putU16Be(offset + 4, first)
        requests.putU16Be(offset + 6, count)
        requestsWritten++
    }

    /** Request layout. */
    companion object {
        /** Bytes per resend request. */
        const val REQUEST_BYTES: Int = 8

        private const val PER_SECOND = MediaLimits.MAX_RESEND_PACKETS_PER_SECOND
        private const val REMOVE = 0
        private const val WAIT = 1
        private const val SEND = 2
        private const val RTP_V2 = 0x80
        private const val RESEND_REQUEST = 0xD5
        private const val SEQUENCE_MASK = 0xFFFF
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MARGIN_NANOS = 20_000_000L
    }
}

/** The local play time of an audio sequence number, for resend decisions. */
internal fun interface PlayDeadline {
    /** Local play time of [sequence] in nanoseconds, or [Long.MIN_VALUE] when unknown. */
    fun playNanos(sequence: Int): Long
}
