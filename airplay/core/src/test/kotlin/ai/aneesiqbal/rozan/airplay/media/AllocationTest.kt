package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.crypto.AesCtr
import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.receiver.AudioSink
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.receiver.VideoSink
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory-efficiency regression tests (03 §8.4): once warmed up, the per-packet paths allocate nothing.
 * Each test runs rounds of [PACKETS] fresh packets and requires [QUIET_ROUNDS] consecutive rounds that
 * allocate 0 bytes on this thread within [MAX_ROUNDS]. The first rounds may include class loading and a
 * few stray bytes of JIT bookkeeping; a per-packet allocation would cost at least 16 bytes × [PACKETS]
 * in every round. Sockets are not involved: the socket loops only copy datagrams and chunks into
 * buffers allocated once per stream, and the platform's own per-datagram allocation is outside this
 * code.
 */
class AllocationTest {
    private val threads = ManagementFactory.getThreadMXBean()

    private fun allocatedBytes(): Long = (threads as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes

    private fun assumeCounter() {
        assumeTrue(threads is com.sun.management.ThreadMXBean, "needs HotSpot's per-thread allocation counter")
    }

    /**
     * Runs [round] with increasing round indexes until [QUIET_ROUNDS] in a row allocate nothing, at most
     * [MAX_ROUNDS] times, and returns the bytes each round allocated.
     */
    private inline fun roundsUntilSteady(round: (Int) -> Unit): List<Long> {
        val allocations = LongArray(MAX_ROUNDS)
        var quiet = 0
        var r = 0
        while (r < MAX_ROUNDS && quiet < QUIET_ROUNDS) {
            val before = allocatedBytes()
            round(r)
            allocations[r] = allocatedBytes() - before
            quiet = if (allocations[r] == 0L) quiet + 1 else 0
            r++
        }
        return allocations.copyOf(r).toList()
    }

    private fun assertSteadyZero(
        what: String,
        allocations: List<Long>,
    ) {
        val steady = allocations.size >= QUIET_ROUNDS && allocations.takeLast(QUIET_ROUNDS).all { it == 0L }
        assertTrue(steady, "$what kept allocating; bytes per round of $PACKETS packets: $allocations")
    }

    @Test
    fun `mirroring header, AES-CTR, AVCC to Annex-B and submit allocate nothing`() {
        assumeCounter()
        val sink = CountingVideoSink()
        val clockSync = FakeClockSync(isSynced = false)
        val processor =
            MirrorPacketProcessor(
                MediaKeys.videoKey(Vectors.sessionKey, Vectors.STREAM_ID_A),
                MediaKeys.videoIv(Vectors.sessionKey, Vectors.STREAM_ID_A),
                sink,
                clockSync,
            )
        val sender = FakeMirroringSender(Vectors.sessionKey, Vectors.STREAM_ID_A)
        val config = sender.codecPacket(hex(AVCC))
        processor.processPacket(config, 0, config.size, 0)
        // The sender's side encrypts on the fly from plaintext templates into one reused packet buffer
        // (separate arrays, so its cipher does not allocate either).
        val senderCtr =
            AesCtr(
                MediaKeys.videoKey(Vectors.sessionKey, Vectors.STREAM_ID_A),
                MediaKeys.videoIv(Vectors.sessionKey, Vectors.STREAM_ID_A),
            )
        val templates = Array(VARIANTS) { mirrorPayload(it) }
        val packet = ByteArray(MediaLimits.VIDEO_HEADER_BYTES + templates.maxOf { it.size })
        val chunk = ByteArray(MediaLimits.VIDEO_READ_BUFFER_BYTES)

        val allocations =
            roundsUntilSteady { r ->
                for (k in 0 until PACKETS) {
                    val i = r * PACKETS + k
                    val variant = i % VARIANTS
                    val payload = templates[variant]
                    val type = mirrorType(variant)
                    writeHeader(packet, type, payload.size, 0x0000deef_00000000L + i * 143_165_576L)
                    if (type == 0) {
                        senderCtr.process(payload, 0, payload.size, packet, MediaLimits.VIDEO_HEADER_BYTES)
                    } else {
                        payload.copyInto(packet, MediaLimits.VIDEO_HEADER_BYTES)
                    }
                    // Both presentation-time paths: clock offset and first-arrival anchor.
                    clockSync.isSynced = k % 2 == 0
                    // As the socket loop does: header, then the payload copied through the read chunk.
                    var remaining = processor.beginPacket(packet, 0)
                    var position = MediaLimits.VIDEO_HEADER_BYTES
                    while (remaining > 0) {
                        val n = minOf(remaining, chunk.size)
                        packet.copyInto(chunk, 0, position, position + n)
                        processor.payload(chunk, 0, n)
                        position += n
                        remaining -= n
                    }
                    processor.endPacket(i * 33_000_000L)
                }
            }
        assertSteadyZero("mirroring", allocations)
        assertEquals(0L, processor.invalidFrames)
        assertTrue(sink.frames > allocations.size * PACKETS * 9 / 10, "frames ${sink.frames}")
    }

    @Test
    fun `ALAC RTP, AES-CBC, jitter buffer, resends and submit allocate nothing`() {
        assumeCounter()
        val sink = CountingAudioSink()
        val processor =
            AudioPacketProcessor(
                Vectors.sessionKey,
                Vectors.audioIv,
                compressionType = 2,
                framesPerPacket = 352,
                sampleRate = 44_100,
                timestamped = true,
                resendEnabled = true,
                extraNanos = 250_000_000L,
                clockSync = FakeClockSync(offsetNanos = CLIENT - LOCAL, roundTripNanos = 3_000_000),
                config = testConfig(),
            )
        val builder = AudioPacketBuilder(Vectors.sessionKey, Vectors.audioIv)
        val sync = FakeAudioSender.syncPacket(RTP0, NtpTimestamp.fromNanos(CLIENT).bits, RTP0 + 77_175, first = true)
        processor.onControlPacket(sync, 0, sync.size, LOCAL)
        // Sequence numbers wrap during the run; every 97th packet is lost and comes back as a retransmission.
        val total = MAX_ROUNDS * PACKETS
        val data = Array(total) { i -> builder.rtp((SEQ0 + i) and 0xFFFF, RTP0 + i * 352, alacFrame(i)) }
        val retransmits = Array(total) { i -> if (i % 97 == 5) builder.retransmit(data[i]) else null }
        val out = ByteArray(MediaLimits.MAX_AUDIO_PAYLOAD_BYTES)
        var now = LOCAL - 2_000_000_000L

        val allocated =
            roundsUntilSteady { r ->
                for (k in 0 until PACKETS) {
                    val i = r * PACKETS + k
                    now += 7_981_859
                    if (i % 97 != 5) processor.onDataPacket(data[i], 0, data[i].size, now)
                    val retransmit = if (i % 97 == 7) retransmits[i - 2] else null
                    if (retransmit != null) processor.onControlPacket(retransmit, 0, retransmit.size, now)
                    if (k % 125 == 0) processor.onControlPacket(sync, 0, sync.size, now)
                    processor.collectResends(now)
                    while (true) {
                        val length = processor.nextOutput(now, out)
                        if (length < 0) break
                        sink.submit(out, 0, length, processor.outputPresentationNanos)
                    }
                }
            }
        assertSteadyZero("ALAC", allocated)
        assertEquals(0L, processor.counters.lost)
        assertTrue(processor.resends.requestsWritten > 0, "resends were exercised")
        assertEquals(allocated.size.toLong() * PACKETS, sink.units)
    }

    @Test
    fun `AAC-ELD with redundant copies in live mode allocates nothing`() {
        assumeCounter()
        val sink = CountingAudioSink()
        val processor =
            AudioPacketProcessor(
                Vectors.sessionKey,
                Vectors.audioIv,
                compressionType = 8,
                framesPerPacket = 480,
                sampleRate = 44_100,
                timestamped = false,
                resendEnabled = false,
                extraNanos = 0,
                clockSync = FakeClockSync(),
                config = testConfig(),
            )
        val builder = AudioPacketBuilder(Vectors.sessionKey, Vectors.audioIv)
        val total = MAX_ROUNDS * PACKETS
        val data = Array(total) { i -> builder.rtp(i and 0xFFFF, i * 480, eldFrame(i)) }
        val out = ByteArray(MediaLimits.MAX_AUDIO_PAYLOAD_BYTES)
        var now = LOCAL

        val allocated =
            roundsUntilSteady { r ->
                for (k in 0 until PACKETS) {
                    val i = r * PACKETS + k
                    now += 10_884_353
                    // Each packet three times, staggered (03c §4.2): a duplicate and a late copy.
                    processor.onDataPacket(data[i], 0, data[i].size, now)
                    processor.onDataPacket(data[i], 0, data[i].size, now)
                    val previous = maxOf(0, i - 1)
                    processor.onDataPacket(data[previous], 0, data[previous].size, now)
                    while (true) {
                        val length = processor.nextOutput(now, out)
                        if (length < 0) break
                        sink.submit(out, 0, length, processor.outputPresentationNanos)
                    }
                }
            }
        assertSteadyZero("AAC-ELD", allocated)
        val played = allocated.size.toLong() * PACKETS
        assertTrue(processor.counters.duplicates >= played)
        assertTrue(processor.counters.late > 0)
        assertEquals(played, sink.units)
    }

    @Test
    fun `timing requests and replies allocate nothing`() {
        assumeCounter()
        val filter = TimingFilter(LOCAL)
        val request = ByteArray(32)
        val reply = ByteArray(32)
        reply[0] = 0x80.toByte()
        reply[1] = 0xD3.toByte()
        reply[3] = 0x07
        var now = LOCAL

        val allocated =
            roundsUntilSteady {
                for (k in 0 until PACKETS) {
                    now += 3_000_000_000L
                    filter.writeRequest(request, 0, now)
                    val t2 = now + (CLIENT - LOCAL) + 1_000_000 + (k and 7) * 1_000
                    reply.putI64Be(8, request.i64Be(24))
                    reply.putI64Be(16, NtpTimestamp.fromNanos(t2).bits)
                    reply.putI64Be(24, NtpTimestamp.fromNanos(t2 + 50_000).bits)
                    filter.onReply(reply, 0, 32, now + 2_050_000)
                }
            }
        assertSteadyZero("timing", allocated)
        assertEquals(allocated.size * PACKETS, filter.acceptedReplies)
        assertTrue(filter.isSynced)
    }

    /** Sums what it gets without keeping it. */
    private class CountingVideoSink : VideoSink {
        var frames = 0L
        var checksum = 0L

        override fun configure(
            codec: VideoCodec,
            parameterSets: ByteArray,
            width: Int,
            height: Int,
        ) = Unit

        override fun submit(
            accessUnit: ByteArray,
            offset: Int,
            length: Int,
            presentationNanos: Long,
        ) {
            frames++
            checksum += accessUnit[offset + 4] + length + presentationNanos
        }

        override fun close() = Unit
    }

    /** Sums what it gets without keeping it. */
    private class CountingAudioSink : AudioSink {
        var units = 0L
        var checksum = 0L

        override fun submit(
            accessUnit: ByteArray,
            offset: Int,
            length: Int,
            presentationNanos: Long,
        ) {
            units++
            checksum += accessUnit[offset] + length + presentationNanos
        }

        override fun setVolume(linear: Float) = Unit

        override fun flush() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val MAX_ROUNDS = 12
        const val QUIET_ROUNDS = 3
        const val PACKETS = 1_000
        const val VARIANTS = 64
        const val LOCAL = 5_000_000_000_000L
        const val CLIENT = 2_209_045_872_000_000_000L
        const val SEQ0 = 64_000
        const val RTP0 = -10_000_000
        const val AVCC = "0164c028ffe100106764c028ac56200d814fe59b8101010101000428ee3cb0"

        /** Packet type of template [variant]: video mostly, a report and a heartbeat now and then. */
        fun mirrorType(variant: Int): Int =
            when (variant) {
                10 -> 5
                20 -> 2
                else -> 0
            }

        /**
         * Plaintext payload of template [variant]: frame sizes from 200 bytes to about 29 KB, several NALs
         * per frame and an SEI plus IDR now and then.
         */
        fun mirrorPayload(variant: Int): ByteArray =
            when (mirrorType(variant)) {
                5 -> {
                    ByteArray(1_500 + variant)
                }

                2 -> {
                    ByteArray(0)
                }

                else -> {
                    val size = 200 + variant * 461
                    val nals =
                        if (variant % 16 == 0) {
                            listOf(nal(0x06, 20), nal(0x65, size))
                        } else {
                            listOf(nal(0x41, size), nal(0x41, 1 + variant * 3))
                        }
                    FakeMirroringSender.avcc(nals)
                }
            }

        /** Writes a 128-byte header (zero beyond the fields set here) for [type] and [size]. */
        fun writeHeader(
            packet: ByteArray,
            type: Int,
            size: Int,
            timestamp: Long,
        ) {
            packet.fill(0, 0, MediaLimits.VIDEO_HEADER_BYTES)
            for (b in 0 until 4) packet[b] = (size ushr (8 * b)).toByte()
            packet[4] = type.toByte()
            for (b in 0 until 8) packet[8 + b] = (timestamp ushr (8 * b)).toByte()
        }

        fun nal(
            header: Int,
            size: Int,
        ): ByteArray = ByteArray(size) { if (it == 0) header.toByte() else it.toByte() }

        fun alacFrame(i: Int): ByteArray = ByteArray(300 + i % 1_100) { if (it == 0) 0x20 else (it + i).toByte() }

        fun eldFrame(i: Int): ByteArray = ByteArray(100 + i % 300) { if (it == 0) 0x8D.toByte() else (it * i).toByte() }
    }
}
