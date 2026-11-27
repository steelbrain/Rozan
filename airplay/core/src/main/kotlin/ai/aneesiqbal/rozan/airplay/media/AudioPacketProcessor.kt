package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.crypto.AesCbc
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import ai.aneesiqbal.rozan.airplay.time.RtpTimestamp
import kotlin.math.abs

/** Counters of one audio stream, for diagnostics and tests. Written under the stream's lock. */
internal class AudioCounters {
    /** Data packets (including retransmissions) stored in the jitter buffer. */
    var accepted: Long = 0

    /** Datagrams dropped by the length, version or payload-type checks. */
    var malformed: Long = 0

    /** Packets already in the jitter buffer (redundant copies, late retransmissions). */
    var duplicates: Long = 0

    /** Packets behind the playout position. */
    var late: Long = 0

    /** Packets with no payload or the `00 68 34 00` no-data marker. */
    var noData: Long = 0

    /** Payloads whose decrypted first byte did not fit the codec (03c §4.2). */
    var implausible: Long = 0

    /** Retransmitted packets received on the control port. */
    var retransmits: Long = 0

    /** Sync packets received. */
    var syncs: Long = 0

    /** Packets given up as lost at playout. */
    var lost: Long = 0

    /** Packets dropped at (re)start because they were already due. */
    var startDropped: Long = 0

    /** Access units handed to the sink. */
    var submitted: Long = 0

    /** Restarts after a timeline discontinuity in the sync packets. */
    var restarts: Long = 0

    /** Jitter buffer resets after a sequence jump. */
    var resets: Long = 0

    /** FLUSH requests applied. */
    var flushes: Long = 0
}

/**
 * The socket-free core of one type-96 stream (03c §4): RTP checks, the jitter buffer, per-packet
 * AES-CBC with the clear tail (only the first copy of a packet is decrypted), sync packets, resend
 * scheduling, FLUSH and the playout decisions with presentation times. Allocates nothing after
 * construction.
 *
 * Two playout modes:
 * - **Timestamped** (ALAC, 03c §4.7): waits up to [MediaConfig.syncWaitNanos] for the first sync packet
 *   and clock sync, then plays `client_play_ns(R) − θ + extra`; without them, plays on arrival plus
 *   [MediaConfig.fallbackDelayNanos]. At (re)start it drops packets due within 20 ms; a missing packet is
 *   given up 10 ms before it is due.
 * - **Live** (AAC-ELD mirroring, 03c §5.2): plays in sequence order as packets arrive, with a two-packet
 *   reorder window, on a timeline anchored at the first packet's arrival plus `extra`.
 *
 * Not thread-safe; the audio stream calls it under its lock.
 *
 * @param sessionKey the 16-byte AES key (`session_key`).
 * @param audioIv the 16-byte `eiv`, the CBC IV of every packet.
 * @param compressionType `ct`, for the plausibility check of decrypted payloads.
 * @param framesPerPacket `spf`, to place missing packets on the timeline.
 * @param sampleRate the RTP clock rate.
 * @param timestamped timestamped mode rather than live mode.
 * @param resendEnabled whether to schedule resend requests.
 * @param extraNanos receiver latency added to presentation times.
 * @param clockSync the NTP offset.
 * @param config playout tunables.
 */
internal class AudioPacketProcessor(
    sessionKey: ByteArray,
    audioIv: ByteArray,
    private val compressionType: Int,
    private val framesPerPacket: Int,
    private val sampleRate: Int,
    private val timestamped: Boolean,
    private val resendEnabled: Boolean,
    private val extraNanos: Long,
    private val clockSync: ClockSync,
    private val config: MediaConfig,
) : PlayDeadline {
    private val cbc = AesCbc(sessionKey)
    private val iv = audioIv.copyOf()

    /** The jitter buffer; exposed for tests. */
    val jitter: JitterBuffer = JitterBuffer()

    /** The resend scheduler; its [ResendScheduler.requests] hold what [collectResends] wrote. */
    val resends: ResendScheduler = ResendScheduler()

    /** What happened so far. */
    val counters: AudioCounters = AudioCounters()

    private var anchorValid = false
    private var anchorRtp = 0
    private var anchorClientNanos = 0L
    private var fallbackValid = false
    private var fallbackRtp = 0
    private var fallbackLocalNanos = 0L
    private var started = false
    private var startedWithSync = false
    private var holdValid = false
    private var holdStartNanos = 0L
    private var sinkFlushPending = false
    private var implausibleRun = 0

    /** `rtp_now − rtp_play` of the last sync packet: the sender's own playout delay in frames. */
    var senderLatencyFrames: Int = 0
        private set

    /** Presentation time of the access unit the last [nextOutput] returned. */
    var outputPresentationNanos: Long = 0
        private set

    /** When [nextOutput] returned [NOTHING]: the local time worth checking again. */
    var wakeAtNanos: Long = 0
        private set

    private var empty = false

    /**
     * Whether the last [nextOutput] found the buffer empty and no resend request is pending: nothing is
     * due before the next packet, flush or stop (each of which wakes the playout thread), so
     * [wakeAtNanos] does not apply.
     */
    val waitsForPacket: Boolean get() = empty && (!resendEnabled || resends.isIdle)

    /**
     * Handles one RTP data packet `buffer[offset, offset + length)` received at [nowNanos].
     *
     * @return whether it was stored.
     */
    fun onDataPacket(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        nowNanos: Long,
    ): Boolean {
        val valid =
            length in MediaLimits.RTP_HEADER_BYTES..MediaLimits.MAX_RTP_DATAGRAM_BYTES &&
                (buffer.u8(offset) and VERSION_MASK) == RTP_V2 &&
                (buffer.u8(offset + 1) and PAYLOAD_TYPE_MASK) == PAYLOAD_TYPE_AUDIO
        if (!valid) {
            counters.malformed++
            return false
        }
        val payloadLength = length - MediaLimits.RTP_HEADER_BYTES
        val index =
            jitter.insert(
                buffer.u16Be(offset + 2),
                buffer.i32Be(offset + 4),
                buffer,
                offset + MediaLimits.RTP_HEADER_BYTES,
                payloadLength,
            )
        when (index) {
            JitterBuffer.LATE -> counters.late++
            JitterBuffer.DUPLICATE -> counters.duplicates++
        }
        if (index < 0) return false
        if (jitter.wasReset) onReset()
        if (!holdValid) {
            holdValid = true
            holdStartNanos = nowNanos
        }
        decryptEntry(index, payloadLength)
        if (resendEnabled && jitter.gapCount > 0) resends.onGap(jitter.gapFirst, jitter.gapCount, nowNanos)
        counters.accepted++
        return true
    }

    /**
     * Handles one control-port datagram (sync or retransmission, 03c §4.4) received at [nowNanos].
     *
     * @return whether it was a well-formed sync, or a retransmission whose packet was stored.
     */
    fun onControlPacket(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        nowNanos: Long,
    ): Boolean {
        if (length < 2 || (buffer.u8(offset) and VERSION_MASK) != RTP_V2) {
            counters.malformed++
            return false
        }
        return when (buffer.u8(offset + 1) and PAYLOAD_TYPE_MASK) {
            CONTROL_SYNC -> {
                val wellFormed = length == SYNC_BYTES
                if (wellFormed) onSync(buffer, offset, nowNanos) else counters.malformed++
                wellFormed
            }

            CONTROL_RETRANSMIT -> {
                if (length in RETRANSMIT_MIN_BYTES..RETRANSMIT_MAX_BYTES) {
                    counters.retransmits++
                    onDataPacket(buffer, offset + RETRANSMIT_PREFIX_BYTES, length - RETRANSMIT_PREFIX_BYTES, nowNanos)
                } else {
                    false
                }
            }

            else -> {
                counters.malformed++
                false
            }
        }
    }

    /**
     * FLUSH (03 §6.2): drops queued packets before [nextSequence]/[nextRtpTime] (all of them when both are
     * null), marks the anchor stale and asks for a sink flush. The NTP offset is kept.
     */
    fun flush(
        nextSequence: Int?,
        nextRtpTime: Long?,
    ) {
        jitter.flush(nextSequence?.and(SEQUENCE_MASK), nextRtpTime?.toInt())
        resends.clear()
        anchorValid = false
        fallbackValid = false
        started = false
        holdValid = false
        sinkFlushPending = true
        counters.flushes++
    }

    /** Whether the sink must be flushed now; clears the request. */
    fun takeSinkFlush(): Boolean {
        val pending = sinkFlushPending
        sinkFlushPending = false
        return pending
    }

    /**
     * Decides what to play at [nowNanos]. Copies the next access unit into [out] (at least
     * [MediaLimits.MAX_AUDIO_PAYLOAD_BYTES] long) and returns its length, with its time in
     * [outputPresentationNanos]; or returns [NOTHING] with the time to check again in [wakeAtNanos].
     * Skips no-data packets and gives up missing ones as the mode says.
     */
    fun nextOutput(
        nowNanos: Long,
        out: ByteArray,
    ): Int {
        empty = false
        while (true) {
            if (jitter.isEmpty) return idle(nowNanos)
            // Started on arrival times and the sync mapping is here now: start over on it.
            if (started && !startedWithSync && hasSyncMapping()) restart()
            if (!started && !tryStart(nowNanos)) return NOTHING
            if (jitter.isHeadPresent()) {
                val index = jitter.headIndex()
                if (jitter.isPlayable(index)) {
                    val length = jitter.length(index)
                    val start = jitter.slotOffset(index)
                    jitter.data.copyInto(out, 0, start, start + length)
                    val due = localPlayNanos(jitter.timestamp(index))
                    outputPresentationNanos = if (due == NO_TIME) nowNanos else due
                    jitter.advance()
                    counters.submitted++
                    return length
                }
                jitter.advance()
                continue
            }
            if (!headGivenUp(nowNanos)) return NOTHING
            counters.lost++
            jitter.advance()
        }
    }

    /**
     * Zeroes the IV copy and the decrypted audio, and empties the buffer. The processor must not be used
     * afterwards. (The AES key schedule lives inside the JCA provider, which offers no way to wipe it.)
     */
    fun destroy() {
        iv.fill(0)
        jitter.clear()
        jitter.data.fill(0)
    }

    /** Writes the resend requests due at [nowNanos] into [ResendScheduler.requests]; returns how many. */
    fun collectResends(nowNanos: Long): Int {
        if (!resendEnabled) return 0
        val roundTrip = if (clockSync.isSynced) clockSync.roundTripNanos else 0L
        return resends.collect(nowNanos, jitter, roundTrip, this)
    }

    override fun playNanos(sequence: Int): Long =
        if (jitter.isEmpty) NO_TIME else localPlayNanos(jitter.estimateTimestamp(sequence, framesPerPacket))

    /**
     * Local play time of the frame with RTP time [rtp], or [NO_TIME] while there is no mapping
     * (03c §4.7: `client_play_ns(R) − θ_used + extra_ns`).
     */
    fun localPlayNanos(rtp: Int): Long {
        if (hasSyncMapping()) {
            return anchorClientNanos + framesToNanos(rtp - anchorRtp) - clockSync.offsetNanos + extraNanos
        }
        if (fallbackValid) return fallbackLocalNanos + framesToNanos(rtp - fallbackRtp)
        return NO_TIME
    }

    private fun decryptEntry(
        index: Int,
        length: Int,
    ) {
        val data = jitter.data
        val start = jitter.slotOffset(index)
        val noData =
            length == 0 ||
                (length == NO_DATA_MARKER_BYTES && data.i32Be(start) == NO_DATA_MARKER)
        if (noData) {
            // Keeps its place in the sequence and plays as silence (03c §4.2).
            jitter.markUnplayable(index)
            counters.noData++
            return
        }
        // Whole blocks are encrypted with eiv; a tail shorter than 16 bytes is in clear (03 §2.2).
        cbc.decryptWholeBlocks(iv, data, start, length)
        if (AudioFormats.isPlausibleFirstByte(compressionType, data.u8(start))) {
            implausibleRun = 0
            return
        }
        jitter.markUnplayable(index)
        counters.implausible++
        implausibleRun++
        if (implausibleRun == WRONG_KEY_RUN) {
            config.log.log("audio key wrong: $WRONG_KEY_RUN implausible frames in a row")
        }
    }

    private fun onSync(
        buffer: ByteArray,
        offset: Int,
        nowNanos: Long,
    ) {
        counters.syncs++
        val first = buffer.u8(offset) == SYNC_FIRST
        val rtpPlay = buffer.i32Be(offset + SYNC_RTP_PLAY_OFFSET)
        val clientNanos = NtpTimestamp(buffer.i64Be(offset + SYNC_NTP_OFFSET)).toNanos()
        senderLatencyFrames = buffer.i32Be(offset + SYNC_RTP_NOW_OFFSET) - rtpPlay
        if (!anchorValid) logFirstSync(first, nowNanos)
        if (timestamped && anchorValid) {
            val jump = abs(clientNanos - (anchorClientNanos + framesToNanos(rtpPlay - anchorRtp)))
            if (jump > DISCONTINUITY_NANOS || (first && jump > REANCHOR_NANOS)) restart()
        }
        anchorRtp = rtpPlay
        anchorClientNanos = clientNanos
        anchorValid = true
    }

    /**
     * Once per start or FLUSH: how long after the first packet the first sync came, and the sender's latency
     * it carries. The 1 s sync wait (03c §4.7 step 2) is checked against these numbers; a sync is about one
     * per second, so this is not a hot path.
     */
    private fun logFirstSync(
        first: Boolean,
        nowNanos: Long,
    ) {
        val sinceFirstPacketMillis = (nowNanos - holdStartNanos) / NANOS_PER_MILLI
        val after = if (holdValid) "$sinceFirstPacketMillis ms after the first packet" else "before any packet"
        val fallback = if (fallbackValid) ", after the fallback started" else ""
        config.log.log("audio sync (first=$first) $after$fallback; sender latency $senderLatencyFrames frames")
    }

    /** Starts playout over with the current mapping (03c §4.7 step 6). */
    private fun restart() {
        if (started) sinkFlushPending = true
        started = false
        counters.restarts++
    }

    private fun onReset() {
        counters.resets++
        resends.clear()
        fallbackValid = false
        if (started) sinkFlushPending = true
        started = false
        holdValid = false
    }

    private fun tryStart(nowNanos: Long): Boolean {
        if (!timestamped) {
            if (!fallbackValid) setFallback(nowNanos + extraNanos)
            started = true
            return true
        }
        if (!holdValid) {
            holdValid = true
            holdStartNanos = nowNanos
        }
        if (!hasSyncMapping() && !fallbackValid) {
            val holdEnd = holdStartNanos + config.syncWaitNanos
            if (nowNanos - holdEnd < 0) {
                wakeAtNanos = earlier(holdEnd, nowNanos + config.playoutPollNanos)
                return false
            }
            config.log.log("no audio sync or clock sync; playing audio on arrival")
            setFallback(nowNanos + config.fallbackDelayNanos)
        }
        // Drop what is already due (03c §4.7 step 3).
        while (!jitter.isEmpty && localPlayNanos(headTimestamp()) - (nowNanos + START_MARGIN_NANOS) < 0) {
            jitter.advance()
            counters.startDropped++
        }
        if (jitter.isEmpty) {
            idle(nowNanos)
            return false
        }
        startedWithSync = hasSyncMapping()
        started = true
        return true
    }

    private fun hasSyncMapping(): Boolean = timestamped && anchorValid && clockSync.isSynced

    private fun headGivenUp(nowNanos: Long): Boolean {
        if (timestamped) return missingHeadDue(nowNanos)
        // Live mode: the head is lost once two later packets are here (03c §5.2).
        val lost = jitter.presentAfterHead(LIVE_REORDER_PACKETS) >= LIVE_REORDER_PACKETS
        if (!lost) wakeAtNanos = nowNanos + config.playoutPollNanos
        return lost
    }

    /** Timestamped mode: a missing head is given up 10 ms before it is due (03c §4.7 step 4). */
    private fun missingHeadDue(nowNanos: Long): Boolean {
        val due = localPlayNanos(headTimestamp())
        if (due == NO_TIME) return true
        val giveUpAt = due - MISSING_MARGIN_NANOS
        if (nowNanos - giveUpAt >= 0) return true
        wakeAtNanos = earlier(giveUpAt, nowNanos + config.playoutPollNanos)
        return false
    }

    private fun setFallback(localNanos: Long) {
        fallbackRtp = headTimestamp()
        fallbackLocalNanos = localNanos
        fallbackValid = true
    }

    private fun headTimestamp(): Int =
        if (jitter.isHeadPresent()) {
            jitter.timestamp(jitter.headIndex())
        } else {
            jitter.estimateTimestamp(jitter.head, framesPerPacket)
        }

    private fun idle(nowNanos: Long): Int {
        empty = true
        wakeAtNanos = nowNanos + config.playoutPollNanos
        return NOTHING
    }

    private fun framesToNanos(frames: Int): Long = RtpTimestamp.framesToNanos(frames.toLong(), sampleRate)

    private fun earlier(
        a: Long,
        b: Long,
    ): Long = if (a - b < 0) a else b

    /** Results and wire constants. */
    companion object {
        /** [nextOutput] result: nothing to submit now. */
        const val NOTHING: Int = -1

        /** [localPlayNanos] result without a mapping. */
        const val NO_TIME: Long = Long.MIN_VALUE

        private const val VERSION_MASK = 0xC0
        private const val RTP_V2 = 0x80
        private const val PAYLOAD_TYPE_MASK = 0x7F
        private const val PAYLOAD_TYPE_AUDIO = 0x60
        private const val CONTROL_SYNC = 0x54
        private const val CONTROL_RETRANSMIT = 0x56
        private const val SYNC_BYTES = 20
        private const val SYNC_FIRST = 0x90
        private const val SYNC_RTP_PLAY_OFFSET = 4
        private const val SYNC_NTP_OFFSET = 8
        private const val SYNC_RTP_NOW_OFFSET = 16
        private const val RETRANSMIT_PREFIX_BYTES = 4
        private const val RETRANSMIT_MIN_BYTES = RETRANSMIT_PREFIX_BYTES + MediaLimits.RTP_HEADER_BYTES
        private const val RETRANSMIT_MAX_BYTES = RETRANSMIT_PREFIX_BYTES + MediaLimits.MAX_RTP_DATAGRAM_BYTES
        private const val NO_DATA_MARKER_BYTES = 4
        private const val NO_DATA_MARKER = 0x00683400
        private const val SEQUENCE_MASK = 0xFFFF
        private const val WRONG_KEY_RUN = 50
        private const val LIVE_REORDER_PACKETS = 2
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val START_MARGIN_NANOS = 20 * NANOS_PER_MILLI
        private const val MISSING_MARGIN_NANOS = 10 * NANOS_PER_MILLI
        private const val REANCHOR_NANOS = 50 * NANOS_PER_MILLI
        private const val DISCONTINUITY_NANOS = 5 * NtpTimestamp.NANOS_PER_SECOND
    }
}
