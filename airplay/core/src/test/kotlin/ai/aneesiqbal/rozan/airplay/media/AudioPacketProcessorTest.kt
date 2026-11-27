package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import ai.aneesiqbal.rozan.airplay.toHex
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioPacketProcessorTest {
    private val builder = AudioPacketBuilder(Vectors.sessionKey, Vectors.audioIv)
    private val log = CopyOnWriteArrayList<String>()
    private val out = ByteArray(MediaLimits.MAX_AUDIO_PAYLOAD_BYTES)

    private fun processor(
        ct: Int = 2,
        timestamped: Boolean = ct == 2,
        resend: Boolean = false,
        extra: Long = 0,
        clockSync: ClockSync = FakeClockSync(offsetNanos = THETA),
    ): AudioPacketProcessor =
        AudioPacketProcessor(
            sessionKey = Vectors.sessionKey,
            audioIv = Vectors.audioIv,
            compressionType = ct,
            framesPerPacket = if (ct == 2) 352 else 480,
            sampleRate = 44_100,
            timestamped = timestamped,
            resendEnabled = resend,
            extraNanos = extra,
            clockSync = clockSync,
            config = testConfig(log = log),
        )

    private fun AudioPacketProcessor.data(
        sequence: Int,
        rtp: Int,
        payload: ByteArray = alac(sequence),
        now: Long = T,
    ): Boolean {
        val packet = builder.rtp(sequence, rtp, payload)
        return onDataPacket(packet, 0, packet.size, now)
    }

    private fun AudioPacketProcessor.control(
        bytes: ByteArray,
        now: Long = T,
    ) = onControlPacket(bytes, 0, bytes.size, now)

    /** Anchors RTP 0 at client time [CLIENT], which is local [T] with [THETA]. */
    private fun AudioPacketProcessor.anchor(first: Boolean = true) =
        control(FakeAudioSender.syncPacket(0, NtpTimestamp.fromNanos(CLIENT).bits, 77_175, first))

    /** Runs playout at [now] until it stops; returns (first payload byte, presentation) pairs. */
    private fun AudioPacketProcessor.play(now: Long): List<Pair<Int, Long>> {
        val played = mutableListOf<Pair<Int, Long>>()
        while (true) {
            val length = nextOutput(now, out)
            if (length < 0) return played
            played += (out[1].toInt() and 0xFF) to outputPresentationNanos
        }
    }

    @Test
    fun `03d C payloads decrypt in place with the tail in clear`() {
        val wireA = "54d8c4b58c364a8b8f68350b946b66163c7b7cc5c4af093bcae3a7a6254996032021222324"
        val plainA = hex("20" + (1..0x24).joinToString("") { "%02x".format(it) })
        val packetA = builder.rtp(1, 352, plainA)
        assertEquals(wireA, packetA.copyOfRange(12, packetA.size).toHex(), "the builder matches the vector")
        val alac = processor()
        assertTrue(alac.onDataPacket(packetA, 0, packetA.size, T))
        val slot = alac.jitter.slotOffset(alac.jitter.headIndex())
        assertContentEquals(plainA, alac.jitter.data.copyOfRange(slot, slot + plainA.size))

        val plainB = hex("8d101112131415161718191a1b1c1d1e")
        val packetB = hex("80600002000003c000000000" + "af954095a83a9cd126f251daf312db18")
        val eld = processor(ct = 8)
        assertTrue(eld.onDataPacket(packetB, 0, packetB.size, T))
        val slotB = eld.jitter.slotOffset(eld.jitter.headIndex())
        assertContentEquals(plainB, eld.jitter.data.copyOfRange(slotB, slotB + 16))
        assertEquals(0L, eld.counters.implausible)
    }

    @Test
    fun `sync and timing give the 03d F play time`() {
        val filter = TimingFilter(5_000_000_000_000L)
        filter.writeRequest(ByteArray(32), 0, 5_000_000_000_000L)
        val reply = hex("80d3000700000000" + "0000138800000000" + "83ab5d6f66666666" + "83ab5d6f66703afb")
        assertTrue(filter.onReply(reply, 0, 32, 5_000_004_149_999L))
        val sync = hex("90d400041234000083ab5d706666666612352d77")
        val r = 305_463_910

        for ((extra, expected) in listOf(0L to 5_002_502_000_000L, 250_000_000L to 5_002_752_000_000L)) {
            val p = processor(clockSync = filter, extra = extra)
            p.control(sync)
            assertEquals(77_175, p.senderLatencyFrames)
            assertEquals(expected, p.localPlayNanos(r))
            assertTrue(p.data(7, r, now = 5_000_005_000_000L))
            assertEquals(listOf(7 to expected), p.play(5_000_010_000_000L))
        }
    }

    @Test
    fun `timestamped playout follows the sync mapping`() {
        val p = processor()
        p.anchor()
        for (s in 0..2) p.data(s, s * 352)
        val played = p.play(T - SECOND)
        assertEquals(listOf(T, T + 7_981_859, T + 15_963_718), played.map { it.second })
    }

    @Test
    fun `ALAC waits for sync, then plays on arrival without it`() {
        val p = processor(clockSync = FakeClockSync(isSynced = false))
        for (s in 0..2) p.data(s, 1_000 + s * 352)
        assertEquals(emptyList(), p.play(T))
        assertEquals(T + 1_000_000, p.wakeAtNanos, "polls while waiting")
        assertEquals(emptyList(), p.play(T + SECOND - 1))
        val played = p.play(T + SECOND)
        val start = T + SECOND + 250_000_000
        assertEquals(listOf(start, start + 7_981_859, start + 15_963_718), played.map { it.second })
        assertTrue(log.any { "on arrival" in it })
    }

    @Test
    fun `a sync arriving after the fallback start restarts playout on it`() {
        val clockSync = FakeClockSync(offsetNanos = THETA, isSynced = false)
        val p = processor(clockSync = clockSync)
        for (s in 0..9) p.data(s, s * 352)
        assertTrue(p.nextOutput(T + SECOND, out) > 0)
        assertEquals(T + SECOND + 250_000_000, p.outputPresentationNanos, "playing on arrival")
        clockSync.isSynced = true
        p.control(FakeAudioSender.syncPacket(0, NtpTimestamp.fromNanos(CLIENT + 5 * SECOND).bits, 0, first = true))
        assertTrue(p.nextOutput(T + SECOND, out) > 0)
        assertEquals(T + 5 * SECOND + 7_981_859, p.outputPresentationNanos, "packet 1 on the sync mapping")
        assertTrue(p.takeSinkFlush(), "the sink drops what was queued on the old timeline")
        assertEquals(1L, p.counters.restarts)
    }

    @Test
    fun `start drops packets that are already due`() {
        val p = processor()
        p.anchor()
        for (s in 0..9) p.data(s, s * 352)
        val played = p.play(T + 30_000_000)
        // Packet 6 plays at T + 47.9 ms, before now + 20 ms; packet 7 at T + 55.9 ms.
        assertEquals(7L, p.counters.startDropped)
        assertEquals(7, played.first().first)
        assertEquals(3, played.size)
    }

    @Test
    fun `a missing packet is given up 10 ms before it is due`() {
        val p = processor()
        p.anchor()
        p.data(0, 0)
        p.data(2, 704)
        assertEquals(listOf(0 to T), p.play(T - SECOND))
        val giveUp = T + 7_981_859 - 10_000_000
        assertEquals(T - SECOND + 1_000_000, p.wakeAtNanos)
        assertEquals(emptyList(), p.play(giveUp - 1))
        assertEquals(giveUp, p.wakeAtNanos, "wakes exactly when the packet is given up")
        assertEquals(listOf(2 to T + 15_963_718), p.play(giveUp))
        assertEquals(1L, p.counters.lost)
    }

    @Test
    fun `live mode plays in order with a two-packet reorder window`() {
        val p = processor(ct = 8)
        p.data(10, 4_800, eld(10))
        assertEquals(listOf(10 to T), p.play(T))
        p.data(12, 5_760, eld(12))
        assertEquals(emptyList(), p.play(T + 5_000_000), "one later packet is not enough")
        p.data(13, 6_240, eld(13))
        assertEquals(listOf(12 to T + 21_768_707, 13 to T + 32_653_061), p.play(T + 6_000_000))
        assertEquals(1L, p.counters.lost)
    }

    @Test
    fun `live mode adds the configured latency`() {
        val p = processor(ct = 8, extra = 40_000_000)
        p.data(1, 0, eld(1))
        assertEquals(listOf(1 to T + 40_000_000), p.play(T))
    }

    @Test
    fun `redundant copies are stored and decrypted once`() {
        val p = processor(ct = 8)
        val packet = builder.rtp(5, 0, eld(5))
        repeat(3) { p.onDataPacket(packet.copyOf(), 0, packet.size, T) }
        assertEquals(1L, p.counters.accepted)
        assertEquals(2L, p.counters.duplicates)
        assertEquals(listOf(5 to T), p.play(T))
        p.onDataPacket(packet.copyOf(), 0, packet.size, T)
        assertEquals(1L, p.counters.late, "a copy after playout is late")
    }

    @Test
    fun `no-data packets keep their place and are never played`() {
        val p = processor(ct = 8)
        p.data(1, 0, hex("00683400"))
        p.data(2, 480, ByteArray(0))
        p.data(3, 960, eld(3))
        assertEquals(listOf(3), p.play(T).map { it.first })
        assertEquals(2L, p.counters.noData)
        assertEquals(0L, p.counters.lost)
    }

    @Test
    fun `implausible payloads are skipped and a long run is logged`() {
        val p = processor()
        p.anchor()
        for (s in 0 until 50) p.data(s, s * 352, byteArrayOf(0x21) + ByteArray(31))
        p.data(50, 50 * 352)
        assertEquals(50L, p.counters.implausible)
        assertTrue(log.any { "audio key wrong" in it })
        assertEquals(listOf(50), p.play(T - SECOND).map { it.first })
    }

    @Test
    fun `malformed datagrams are dropped`() {
        val p = processor()
        val good = builder.rtp(1, 0, alac(1))
        assertFalse(p.onDataPacket(good, 0, 11, T), "short")
        assertFalse(p.onDataPacket(ByteArray(2_061).also { good.copyInto(it) }, 0, 2_061, T), "long")
        assertFalse(p.onDataPacket(good.copyOf().also { it[0] = 0x40 }, 0, good.size, T), "version 1")
        assertFalse(p.onDataPacket(good.copyOf().also { it[1] = 0x61 }, 0, good.size, T), "payload type 97")
        p.control(hex("80d40004"))
        p.control(hex("80d6"))
        p.control(hex("80d60001aabbccdd"))
        p.control(hex("40d4" + "00".repeat(18)))
        p.control(hex("80d9" + "00".repeat(18)))
        p.control(ByteArray(1))
        assertEquals(0L, p.counters.accepted)
        assertEquals(0L, p.counters.syncs)
        assertTrue(p.counters.malformed >= 8)
        assertTrue(p.onDataPacket(good, 0, good.size, T), "the processor still works")
        assertTrue(p.onDataPacket(builder.rtp(2, 352, alac(2)).also { it[1] = 0xE0.toByte() }, 0, 44, T), "marker bit")
    }

    @Test
    fun `retransmissions on the control port fill gaps`() {
        val p = processor()
        p.anchor()
        p.data(0, 0)
        p.data(2, 704)
        p.control(builder.retransmit(builder.rtp(1, 352, alac(1))))
        assertEquals(1L, p.counters.retransmits)
        assertEquals(listOf(0, 1, 2), p.play(T - SECOND).map { it.first })
    }

    @Test
    fun `a gap is requested as in 03d G`() {
        val p = processor(resend = true)
        p.data(257, 0)
        p.data(261, 4 * 352)
        assertEquals(1, p.collectResends(T))
        assertEquals(
            "80d5000101020003",
            p.resends.requests
                .copyOf(8)
                .toHex(),
        )
        assertEquals(0, processor(resend = false).also { it.data(1, 0) }.collectResends(T))
    }

    @Test
    fun `flush drops queued audio and waits for the next sync`() {
        val p = processor()
        p.anchor()
        for (s in 0..4) p.data(s, s * 352)
        p.flush(3, 3L * 352)
        assertTrue(p.takeSinkFlush())
        assertFalse(p.takeSinkFlush())
        assertEquals(emptyList(), p.play(T - SECOND), "the anchor is stale")
        p.anchor()
        assertEquals(listOf(3, 4), p.play(T - SECOND).map { it.first })
        assertEquals(1L, p.counters.flushes)
    }

    @Test
    fun `flush without RTP-Info drops everything`() {
        val p = processor(ct = 8)
        for (s in 0..4) p.data(s, s * 480, eld(s))
        p.flush(null, null)
        assertTrue(p.jitter.isEmpty)
        assertTrue(p.takeSinkFlush())
    }

    @Test
    fun `a first sync that moves the mapping restarts playout`() {
        val p = processor()
        p.anchor()
        for (s in 0..3) p.data(s, s * 352)
        p.nextOutput(T - SECOND, out)
        // 40 ms off from a regular sync: re-anchor only.
        p.control(FakeAudioSender.syncPacket(0, NtpTimestamp.fromNanos(CLIENT + 40_000_000).bits, 0, first = false))
        assertFalse(p.takeSinkFlush())
        // 60 ms off from a first sync: restart.
        p.control(FakeAudioSender.syncPacket(0, NtpTimestamp.fromNanos(CLIENT + 100_000_000).bits, 0, first = true))
        assertTrue(p.takeSinkFlush())
        assertEquals(1L, p.counters.restarts)
        p.nextOutput(T - SECOND, out)
        // More than 5 s off from any sync: a new stream.
        p.control(FakeAudioSender.syncPacket(0, NtpTimestamp.fromNanos(CLIENT + 6 * SECOND).bits, 0, first = false))
        assertEquals(2L, p.counters.restarts)
    }

    @Test
    fun `a sequence jump resets the stream`() {
        val p = processor(ct = 8)
        p.data(0, 0, eld(0))
        p.play(T)
        p.data(1, 480, eld(1))
        p.data(5_000, 99_999, eld(2))
        assertEquals(1L, p.counters.resets)
        assertEquals(listOf(2 to T + 1_000), p.play(T + 1_000), "re-anchored at the new packet")
    }

    private fun alac(tag: Int): ByteArray =
        ByteArray(32).also {
            it[0] = 0x20
            it[1] = tag.toByte()
        }

    private fun eld(tag: Int): ByteArray =
        ByteArray(24).also {
            it[0] = 0x8C.toByte()
            it[1] = tag.toByte()
        }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val T = 10_000 * SECOND
        const val CLIENT = 2_209_045_872_000_000_000L
        const val THETA = CLIENT - T
    }
}
