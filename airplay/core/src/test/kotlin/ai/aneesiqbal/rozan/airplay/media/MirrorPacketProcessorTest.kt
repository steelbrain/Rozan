package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MirrorPacketProcessorTest {
    private val sink = RecordingVideoSink()
    private val clockSync = FakeClockSync(isSynced = false)
    private val sender = FakeMirroringSender(Vectors.sessionKey, Vectors.STREAM_ID_A)
    private val processor =
        MirrorPacketProcessor(
            MediaKeys.videoKey(Vectors.sessionKey, Vectors.STREAM_ID_A),
            MediaKeys.videoIv(Vectors.sessionKey, Vectors.STREAM_ID_A),
            sink,
            clockSync,
        )

    private fun feed(
        packet: ByteArray,
        arrival: Long = ARRIVAL,
    ) = processor.processPacket(packet, 0, packet.size, arrival)

    private fun configure(avcC: ByteArray = hex(D_AVCC)) = feed(sender.codecPacket(avcC))

    @Test
    fun `03d D header and avcC configure the sink`() {
        val packet = hex(D_HEADER + "00".repeat(64) + D_AVCC)
        assertEquals(128 + 31, packet.size)
        feed(packet)
        val config = sink.configs.single()
        assertEquals(VideoCodec.H264, config.codec)
        assertEquals(D_CSD, config.parameterSets.toHex())
        assertEquals(863, config.width)
        assertEquals(647, config.height)
    }

    @Test
    fun `03d B ciphertexts decrypt with one continuous keystream`() {
        configure()
        feed(FakeMirroringSender.record(0, hex(B_CIPHER_1)))
        feed(FakeMirroringSender.record(0, hex(B_CIPHER_2)))
        assertEquals(2, sink.frames.size)
        // The IDR gets the parameter sets in front (03b §3.3 step 4).
        assertEquals(D_CSD + "00000001" + B_PLAIN_1.substring(8), sink.frames[0].bytes.toHex())
        assertEquals("00000001" + B_PLAIN_2.substring(8), sink.frames[1].bytes.toHex())
        assertEquals(2L, processor.framesSubmitted)
    }

    @Test
    fun `video before the first configuration is dropped but keeps the keystream aligned`() {
        feed(FakeMirroringSender.record(0, hex(B_CIPHER_1)))
        assertEquals(1L, processor.framesBeforeConfig)
        assertTrue(sink.frames.isEmpty())
        configure()
        feed(FakeMirroringSender.record(0, hex(B_CIPHER_2)))
        assertEquals(
            "00000001" + B_PLAIN_2.substring(8),
            sink.frames
                .single()
                .bytes
                .toHex(),
        )
    }

    @Test
    fun `payloads may arrive in chunks of any size`() {
        configure()
        val packet = sender.videoPacket(listOf(idr(300), nonIdr(77)))
        val size = processor.beginPacket(packet, 0)
        var position = 128
        var chunk = 1
        while (position < packet.size) {
            val n = minOf(chunk, packet.size - position)
            processor.payload(packet, position, n)
            position += n
            chunk = chunk * 2 + 1
        }
        processor.endPacket(ARRIVAL)
        assertEquals(packet.size - 128, size)
        assertContentEquals(
            hex(D_CSD) + FakeMirroringSender.annexB(listOf(idr(300), nonIdr(77))),
            sink.frames.single().bytes,
        )
    }

    @Test
    fun `an identical configuration does not reconfigure, a new one does`() {
        configure()
        feed(sender.videoPacket(listOf(idr(10))))
        configure()
        assertEquals(1, sink.configs.size)
        feed(sender.videoPacket(listOf(nonIdr(10))))
        assertContentEquals(FakeMirroringSender.annexB(listOf(nonIdr(10))), sink.frames.last().bytes)

        val other = hex("0164c01fffe100046764c01f01000428ee3cb1")
        feed(sender.codecPacket(other, width = 720f, height = 1280f))
        assertEquals(2, sink.configs.size)
        assertEquals(720, sink.configs.last().width)
        assertEquals(
            "000000016764c01f0000000128ee3cb1",
            sink.configs
                .last()
                .parameterSets
                .toHex(),
        )
        // In-band sets wait for the IDR, not the next frame.
        feed(sender.videoPacket(listOf(nonIdr(5))))
        assertContentEquals(FakeMirroringSender.annexB(listOf(nonIdr(5))), sink.frames.last().bytes)
        feed(sender.videoPacket(listOf(sei(3), idr(8))))
        val expected = hex("000000016764c01f0000000128ee3cb1") + FakeMirroringSender.annexB(listOf(sei(3), idr(8)))
        assertContentEquals(expected, sink.frames.last().bytes)
        assertEquals(2, processor.configurations)
    }

    @Test
    fun `a reconnect needs a configuration again and keeps the keystream`() {
        configure()
        feed(sender.videoPacket(listOf(idr(10))))
        processor.onDisconnect()
        feed(sender.videoPacket(listOf(nonIdr(10))))
        assertEquals(1, sink.frames.size, "dropped until a configuration comes")
        configure()
        assertEquals(1, sink.configs.size, "same configuration: the decoder is kept")
        feed(sender.videoPacket(listOf(idr(4))))
        assertContentEquals(hex(D_CSD) + FakeMirroringSender.annexB(listOf(idr(4))), sink.frames.last().bytes)
    }

    @Test
    fun `presentation times follow the clock offset`() {
        configure()
        clockSync.isSynced = true
        clockSync.offsetNanos = 2_209_000_000_000_000_000L
        feed(sender.videoPacket(listOf(idr(4)), timestamp = D_TIMESTAMP))
        assertEquals(D_CLIENT_NANOS - clockSync.offsetNanos, sink.frames.last().presentationNanos)
    }

    @Test
    fun `without clock sync the first frame's arrival anchors the times`() {
        configure()
        feed(sender.videoPacket(listOf(idr(4)), timestamp = D_TIMESTAMP), arrival = 123_000_000_000L)
        // 1/30 s later in Q32.32 units, arriving late: the time follows the sender's clock.
        val later = D_TIMESTAMP + (1L shl 32) / 30
        feed(sender.videoPacket(listOf(nonIdr(4)), timestamp = later), arrival = 999_000_000_000L)
        assertEquals(123_000_000_000L, sink.frames[0].presentationNanos)
        val step = NtpTimestamp(later).toNanos() - NtpTimestamp(D_TIMESTAMP).toNanos()
        assertTrue(step in 33_333_332L..33_333_334L)
        assertEquals(123_000_000_000L + step, sink.frames[1].presentationNanos)
    }

    @Test
    fun `undecodable frames are dropped and thirty in a row close the stream`() {
        configure()
        repeat(29) { feed(sender.encryptedPacket(hex("000000ff65"))) }
        assertEquals(29L, processor.invalidFrames)
        feed(sender.videoPacket(listOf(idr(4))))
        assertEquals(1, sink.frames.size, "a good frame resets the run")
        repeat(29) { feed(sender.encryptedPacket(hex("00000002e5aa"))) }
        val error = assertFailsWith<MediaStreamException> { feed(sender.encryptedPacket(hex("0000"))) }
        assertTrue("30 undecodable" in error.message.orEmpty())
    }

    @Test
    fun `bad and unsupported configurations`() {
        feed(sender.codecPacket(hex("0264c028ffe1")))
        assertEquals(1L, processor.invalidConfigs)
        assertTrue(sink.configs.isEmpty())
        assertFailsWith<MediaStreamException> {
            feed(sender.codecPacket(hex("01016000" + "68766331" + "00".repeat(40))))
        }
    }

    @Test
    fun `the suspend option is noted`() {
        feed(sender.codecPacket(hex(D_AVCC), option = 0x56))
        assertTrue(processor.suspended)
        feed(sender.codecPacket(hex(D_AVCC), option = 0x16))
        assertEquals(false, processor.suspended)
    }

    @Test
    fun `payload sizes are bounded per type`() {
        val bad =
            listOf(
                0 to MediaLimits.MAX_VIDEO_PAYLOAD_BYTES + 1L,
                0 to 0xFFFF_FFFFL,
                1 to 0L,
                1 to 4_097L,
                2 to 1L,
                5 to 256 * 1024 + 1L,
                9 to 1024 * 1024 + 1L,
            )
        for ((type, size) in bad) {
            val fresh = MirrorPacketProcessor(ByteArray(16), ByteArray(16), sink, clockSync)
            assertFailsWith<MediaStreamException>("type $type size $size") {
                fresh.beginPacket(FakeMirroringSender.header(type, size), 0)
            }
        }
        val fresh = MirrorPacketProcessor(ByteArray(16), ByteArray(16), sink, clockSync)
        for ((type, size) in listOf(0 to 4L * 1024 * 1024, 1 to 4_096L, 2 to 0L, 5 to 256 * 1024L, 9 to 1024 * 1024L)) {
            assertEquals(size.toInt(), fresh.beginPacket(FakeMirroringSender.header(type, size), 0))
            val zeros = ByteArray(64 * 1024)
            var left = size.toInt()
            while (left > 0) {
                val n = minOf(left, zeros.size)
                fresh.payload(zeros, 0, n)
                left -= n
            }
            fresh.endPacket(ARRIVAL)
        }
    }

    @Test
    fun `heartbeats, reports and unknown types are skipped without touching the keystream`() {
        configure()
        feed(FakeMirroringSender.record(2, ByteArray(0)))
        feed(FakeMirroringSender.record(5, ByteArray(25_000) { 7 }))
        feed(FakeMirroringSender.record(77, ByteArray(10)))
        feed(FakeMirroringSender.record(0, hex(B_CIPHER_1)))
        assertEquals(
            D_CSD + "00000001" + B_PLAIN_1.substring(8),
            sink.frames
                .single()
                .bytes
                .toHex(),
        )
    }

    private fun idr(size: Int) = nal(0x65, size)

    private fun nonIdr(size: Int) = nal(0x41, size)

    private fun sei(size: Int) = nal(0x06, size)

    private fun nal(
        header: Int,
        size: Int,
    ): ByteArray = ByteArray(size) { if (it == 0) header.toByte() else (it * 7).toByte() }

    private companion object {
        const val ARRIVAL = 50_000_000_000L
        const val D_HEADER =
            "1f000000010006001d9a9f59efde0000" + "00005844000022440000000000000000" +
                "00000000000000000000584400002244" + "000050430000104200c0574400c02144"
        const val D_AVCC = "0164c028ffe100106764c028ac56200d814fe59b8101010101000428ee3cb0"
        const val D_CSD = "000000016764c028ac56200d814fe59b81010101" + "0000000128ee3cb0"
        const val D_TIMESTAMP = 0x0000deef599f9a1dL
        const val D_CLIENT_NANOS = 2_209_045_871_350_091_583L
        const val B_CIPHER_1 = "36f4bf1560a665797ef80b7825e37ffaf7fa84cc7e"
        const val B_PLAIN_1 = "0000001165888400210102030405060708090a0b0c"
        const val B_CIPHER_2 =
            "8a246a95918b6e0a7f7469a715690405bc80f817801a9998896589ca48597cdf552eaf039278e7c0"
        const val B_PLAIN_2 = "00000024419a2244808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
    }
}
