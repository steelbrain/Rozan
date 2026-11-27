package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.time.NtpTimestamp
import ai.aneesiqbal.rozan.airplay.time.RtpTimestamp
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The type-96 stream and the timing client over loopback UDP, driven through [DefaultMediaSession]. */
class AudioStreamTest {
    private val platform = FakePlatform()
    private val listener = RecordingListener()
    private val log = CopyOnWriteArrayList<String>()
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()

    private fun open(
        config: MediaConfig = testConfig(log = log),
        timingPort: Int = 0,
        client: InetAddress = InetAddress.getLoopbackAddress(),
    ): DefaultMediaSession {
        val params =
            MediaSessionParams(client, Vectors.sessionKey.copyOf(), Vectors.audioIv.copyOf(), timingPort, false)
        val session = DefaultMediaSessionFactory(config).open(params, platform, listener)
        closeables += session
        return session as DefaultMediaSession
    }

    private fun <T : AutoCloseable> track(closeable: T): T = closeable.also { closeables += it }

    @AfterTest
    fun closeEverything() {
        closeables.reversed().forEach { it.close() }
        eventually("media threads end") { mediaThreads().isEmpty() }
    }

    /** A synced session on a manual clock at 5000 s, the sender's clock [CLIENT_OFFSET] ahead. */
    private fun syncedSession(): Pair<DefaultMediaSession, ManualClock> {
        val clock = ManualClock(START)
        val responder = track(FakeTimingResponder(CLIENT_OFFSET, clock))
        val session = open(testConfig(clock = clock, log = log), timingPort = responder.port)
        eventually("clock synced") { session.clockSync.isSynced }
        assertTrue(responder.requests.isNotEmpty())
        assertTrue(
            kotlin.math.abs(session.clockSync.offsetNanos - CLIENT_OFFSET) <= 3,
            "θ ${session.clockSync.offsetNanos}",
        )
        return session to clock
    }

    @Test
    fun `ALAC with gaps, reordering, duplicates and resends plays in order at the sync times`() {
        val (session, clock) = syncedSession()
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        val ports = session.startAudio(audioRequest(ct = 2, controlPort = sender.controlPort))
        sender.connect(ports)
        sender.startResendResponder()
        val sink = platform.audioSinks.single()
        assertIs<AudioFormat.Alac>(sink.format)

        // RTP 0 is heard 2 s from now on the sender's clock.
        val anchorBits = NtpTimestamp.fromNanos(clock.nanoTime() + CLIENT_OFFSET + 2_000_000_000L).bits
        sender.sendSync(0, anchorBits, 77_175, first = true)
        // 5 and 6 are lost on the way and only come back as retransmissions; 3 arrives twice, 9 after 10.
        sender.rtpPacket(5, 5 * 352, alac(5))
        sender.rtpPacket(6, 6 * 352, alac(6))
        val order = listOf(0, 1, 2, 3, 3, 4, 7, 8, 10, 9) + (11..19)
        for (s in order) sender.sendData(s, s * 352, alac(s))

        eventually("20 units") { sink.units.size == 20 }
        assertEquals((0..19).toList(), sink.units.map { it.bytes[1].toInt() })
        val theta = session.clockSync.offsetNanos
        val anchor = NtpTimestamp(anchorBits).toNanos()
        for ((i, unit) in sink.units.withIndex()) {
            val expected = anchor + RtpTimestamp.framesToNanos(i * 352L, 44_100) - theta + 250_000_000L
            assertEquals(expected, unit.presentationNanos, "unit $i")
        }
        assertTrue(sink.units.all { it.thread == "rozan-media-audio-playout" })
        assertTrue(
            sender.resendRequests.any {
                it.copyOfRange(4, 8).contentEquals(byteArrayOf(0, 5, 0, 2))
            },
            "asked for 5 and 6",
        )
        val counters = checkNotNull(session.audioCounters)
        assertTrue(counters.retransmits >= 2, "retransmissions used")
        assertEquals(0L, counters.lost)
        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `with nothing to play the playout thread sleeps until a packet arrives`() {
        val clock = ManualClock(START)
        val playoutReads = AtomicLong()
        // Every pass of the playout loop reads the clock: counting its reads counts its wake-ups.
        val counting =
            MediaClock {
                if (Thread.currentThread().name == PLAYOUT_THREAD) playoutReads.incrementAndGet()
                clock.nanoTime()
            }
        val responder = track(FakeTimingResponder(CLIENT_OFFSET, clock))
        val session = open(testConfig(clock = counting, log = log), timingPort = responder.port)
        eventually("clock synced") { session.clockSync.isSynced }
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        sender.connect(session.startAudio(audioRequest(ct = 2)))
        val sink = platform.audioSinks.single()
        val anchorBits = NtpTimestamp.fromNanos(clock.nanoTime() + CLIENT_OFFSET + 2_000_000_000L).bits
        sender.sendSync(0, anchorBits, 0, first = true)
        for (s in 0..4) sender.sendData(s, s * 352, alac(s))
        eventually("5 units") { sink.units.size == 5 }

        // Paused: the buffer is empty and nothing is missing. (The test config polls every 1 ms.)
        Thread.sleep(SETTLE_MILLIS)
        val before = playoutReads.get()
        Thread.sleep(IDLE_MILLIS)
        val passes = playoutReads.get() - before
        println("playout clock reads in $IDLE_MILLIS ms with nothing to play: $passes")
        assertTrue(passes <= 2, "the playout thread woke up $passes times with nothing to play")

        sender.sendData(5, 5 * 352, alac(5))
        eventually("a packet wakes it") { sink.units.size == 6 }
    }

    @Test
    fun `flush drops queued audio and later packets before the flush point`() {
        val (session, clock) = syncedSession()
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        sender.connect(session.startAudio(audioRequest(ct = 2)))
        val sink = platform.audioSinks.single()
        val anchorBits = NtpTimestamp.fromNanos(clock.nanoTime() + CLIENT_OFFSET + 2_000_000_000L).bits
        sender.sendSync(0, anchorBits, 0, first = true)
        for (s in 0..9) sender.sendData(s, s * 352, alac(s))
        eventually("10 units") { sink.units.size == 10 }

        session.flushAudio(20, 20L * 352)
        eventually("sink flushed") { sink.flushes == 1 }
        for (s in 10..15) sender.sendData(s, s * 352, alac(s))
        sender.sendSync(20 * 352, anchorBits, 0, first = true)
        for (s in 20..24) sender.sendData(s, s * 352, alac(s))
        eventually("15 units") { sink.units.size == 15 }
        assertEquals((20..24).toList(), sink.units.drop(10).map { it.bytes[1].toInt() })
    }

    @Test
    fun `mirroring audio plays redundant copies once, in order, without resends`() {
        val session = open()
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        sender.connect(
            session.startAudio(audioRequest(ct = 8, spf = 480, controlPort = sender.controlPort, usingScreen = true)),
        )
        sender.startResendResponder()
        val sink = platform.audioSinks.single()
        assertIs<AudioFormat.Aac>(sink.format)
        for (n in 0 until 30) {
            for (copy in listOf(n, n - 1, n - 2)) if (copy >= 0) sender.sendData(copy, copy * 480, eld(copy))
        }
        eventually("30 units") { sink.units.size == 30 }
        assertEquals((0 until 30).toList(), sink.units.map { it.bytes[1].toInt() })
        val first = sink.units[0].presentationNanos
        assertEquals(RtpTimestamp.framesToNanos(29 * 480L, 44_100), sink.units[29].presentationNanos - first)
        Thread.sleep(50)
        assertTrue(sender.resendRequests.isEmpty())
    }

    @Test
    fun `ALAC without timing plays on arrival after the sync wait`() {
        val session = open(testConfig(syncWaitNanos = 100_000_000L, log = log))
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        sender.connect(session.startAudio(audioRequest(ct = 2)))
        val sink = platform.audioSinks.single()
        val before = System.nanoTime()
        for (s in 0..4) sender.sendData(s, 1_000 + s * 352, alac(s))
        eventually("5 units") { sink.units.size == 5 }
        val first = sink.units[0].presentationNanos
        assertTrue(first - before >= 350_000_000L, "hold plus the fallback delay")
        assertEquals(RtpTimestamp.framesToNanos(4 * 352L, 44_100), sink.units[4].presentationNanos - first)
        assertTrue(log.any { "on arrival" in it })
    }

    @Test
    fun `garbage and foreign datagrams are dropped and the stream keeps working`() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures += e }
        try {
            val session = open()
            val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
            sender.connect(session.startAudio(audioRequest(ct = 8, spf = 480)))
            val random = Random(7)
            repeat(300) {
                val size = if (it % 50 == 0) 60_000 else random.nextInt(0, 3_000)
                val bytes = random.nextBytes(size)
                if (it % 3 == 0 && size > 2) bytes[1] = 0x60
                if (it % 4 == 0 && size > 2) bytes[0] = 0x80.toByte()
                // Sequence numbers below 1024 and no retransmission wrappers, so that the valid packets
                // below are far outside the window the garbage may have opened.
                if (size > 3) bytes[2] = (bytes[2].toInt() and 0x03).toByte()
                if (size > 2 && (bytes[1].toInt() and 0x7F) == 0x56) bytes[1] = 0x57
                if (it % 2 == 0) sender.sendRaw(bytes) else sender.sendControl(bytes)
            }
            Thread.sleep(100)
            // Packets after the garbage play, though random packets may have moved the sequence.
            for (s in 40_000 until 40_010) sender.sendData(s, s * 480, eld(s))
            val sink = platform.audioSinks.single()
            eventually("valid audio plays") { sink.units.any { it.bytes.contentEquals(eld(40_009)) } }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(emptyList(), failures)
        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `datagrams from other addresses are dropped`() {
        val session = open(client = InetAddress.getByName("192.0.2.1"))
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        sender.connect(session.startAudio(audioRequest(ct = 8, spf = 480)))
        for (s in 0..9) sender.sendData(s, s * 480, eld(s))
        Thread.sleep(200)
        assertTrue(
            platform.audioSinks
                .single()
                .units
                .isEmpty(),
        )
    }

    @Test
    fun `once a socket has the sender's port, datagrams from its address but another port are dropped`() {
        val session = open()
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        // Another socket on the sender's address, as a host spoofing that address would send from.
        val spoofer = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        val ports = session.startAudio(audioRequest(ct = 8, spf = 480))
        sender.connect(ports)
        spoofer.connect(ports)
        val sink = platform.audioSinks.single()
        for (s in 0 until 5) sender.sendData(s, s * 480, eld(s))
        sender.sendSync(0, 0, 0, first = true)
        eventually("the sender's packets are in") {
            session.audioCounters.let { it != null && it.accepted == 5L && it.syncs == 1L }
        }

        for (s in 5 until 10) spoofer.sendData(s, s * 480, eld(100 + s))
        spoofer.rtpPacket(4, 4 * 480, eld(104))
        spoofer.sendRetransmit(4)
        spoofer.sendSync(0, 0, 0, first = true)
        Thread.sleep(200)
        val counters = assertNotNull(session.audioCounters)
        assertEquals(5L, counters.accepted, "nothing from the other port was looked at")
        assertEquals(0L, counters.retransmits)
        assertEquals(0L, counters.duplicates)
        assertEquals(1L, counters.syncs)

        // The sender's own packets 5-9 still play, with its payloads.
        for (s in 5 until 10) sender.sendData(s, s * 480, eld(s))
        eventually("10 units") { sink.units.size == 10 }
        assertEquals((0 until 10).toList(), sink.units.map { it.bytes[1].toInt() })
    }

    @Test
    fun `once the timing client has the sender's port, replies from another port are dropped`() {
        val responder = track(DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)))
        val spoofer = track(DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)))
        responder.soTimeout = 5_000
        val client = track(TimingClient(InetAddress.getLoopbackAddress(), responder.localPort, testConfig(log = log)))
        client.start()
        val target = InetSocketAddress(InetAddress.getLoopbackAddress(), client.localPort)

        fun reply(request: ByteArray): DatagramPacket {
            val bytes = ByteArray(32)
            bytes[0] = 0x80.toByte()
            bytes[1] = 0xD3.toByte()
            bytes[3] = 0x07
            val t1 = request.i64Be(24)
            bytes.putI64Be(8, t1)
            bytes.putI64Be(16, t1)
            bytes.putI64Be(24, t1)
            return DatagramPacket(bytes, bytes.size, target)
        }

        fun nextRequest(): ByteArray {
            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)
            responder.receive(packet)
            return buffer.copyOf(packet.length)
        }
        responder.send(reply(nextRequest()))
        eventually("first reply accepted") { client.filter.acceptedReplies == 1 }

        // A reply that matches an outstanding request, but from another port: dropped.
        val second = nextRequest()
        spoofer.send(reply(second))
        Thread.sleep(200)
        assertEquals(1, client.filter.acceptedReplies)
        // The same reply from the sender's port is accepted.
        responder.send(reply(second))
        eventually("second reply accepted") { client.filter.acceptedReplies == 2 }
    }

    @Test
    fun `close from another thread while playing`() {
        val session = open()
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        sender.connect(session.startAudio(audioRequest(ct = 8, spf = 480)))
        val sink = platform.audioSinks.single()
        val writer =
            Thread {
                try {
                    var s = 0
                    while (!Thread.currentThread().isInterrupted) {
                        sender.sendData(s and 0xFFFF, s * 480, eld(s))
                        s++
                        Thread.sleep(1)
                    }
                } catch (_: Exception) {
                    // stopped
                }
            }
        writer.start()
        eventually("playing") { sink.units.size > 10 }
        val closer = Thread { session.close() }
        closer.start()
        closer.join(5_000)
        writer.interrupt()
        writer.join(5_000)
        assertTrue(sink.closed)
        eventually("threads gone") { mediaThreads().isEmpty() }
        assertTrue(listener.events.isEmpty(), "a requested stop is not reported")
    }

    @Test
    fun `volume applies to the current and later sinks`() {
        val session = open()
        session.setVolume(0.5f)
        session.startAudio(audioRequest(ct = 8, spf = 480))
        val first = platform.audioSinks[0]
        eventually("initial volume") { first.volumes.lastOrNull() == 0.5f }
        session.setVolume(0.25f)
        eventually("volume change") { first.volumes.lastOrNull() == 0.25f }
        session.setVolume(Float.NaN)
        session.stopAudio()
        assertTrue(first.closed)
        session.startAudio(audioRequest(ct = 2))
        val second = platform.audioSinks[1]
        eventually("kept for the next stream") { second.volumes.lastOrNull() == 0.25f }
        session.setVolume(3f)
        eventually("clamped") { second.volumes.lastOrNull() == 1f }
    }

    @Test
    fun `a failing sink stops the stream and is reported once`() {
        val session = open()
        val sender = track(FakeAudioSender(Vectors.sessionKey, Vectors.audioIv))
        sender.connect(session.startAudio(audioRequest(ct = 8, spf = 480)))
        val sink = platform.audioSinks.single()
        sink.failOnSubmit = IllegalStateException("audio device gone")
        for (s in 0..9) sender.sendData(s, s * 480, eld(s))
        eventually("failure reported") { listener.events.any { "audio device gone" in it } }
        eventually("sink closed") { sink.closed }
        Thread.sleep(50)
        assertEquals(1, listener.events.size)
        session.stopAudio()
    }

    private fun alac(tag: Int): ByteArray =
        ByteArray(40).also {
            it[0] = 0x20
            it[1] = tag.toByte()
        }

    private fun eld(tag: Int): ByteArray =
        ByteArray(20).also {
            it[0] = 0x8C.toByte()
            it[1] = tag.toByte()
        }

    private companion object {
        const val START = 5_000_000_000_000L
        const val CLIENT_OFFSET = 2_209_040_871_397_999_999L
        const val PLAYOUT_THREAD = "rozan-media-audio-playout"
        const val SETTLE_MILLIS = 50L
        const val IDLE_MILLIS = 500L
    }
}
