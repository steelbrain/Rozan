package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.hex
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The type-110 stream over loopback TCP, driven through [DefaultMediaSession]. */
class MirroringStreamTest {
    private val platform = FakePlatform()
    private val listener = RecordingListener()
    private val log = CopyOnWriteArrayList<String>()
    private val sessions = CopyOnWriteArrayList<MediaSession>()
    private val senders = CopyOnWriteArrayList<FakeMirroringSender>()

    private fun open(
        client: InetAddress = InetAddress.getLoopbackAddress(),
        config: MediaConfig = testConfig(log = log),
    ): MediaSession {
        val params = MediaSessionParams(client, Vectors.sessionKey.copyOf(), Vectors.audioIv.copyOf(), 0, true)
        return DefaultMediaSessionFactory(config).open(params, platform, listener).also { sessions += it }
    }

    private fun sender(id: Long = Vectors.STREAM_ID_A) =
        FakeMirroringSender(Vectors.sessionKey, id).also {
            senders +=
                it
        }

    @AfterTest
    fun closeEverything() {
        senders.forEach { it.close() }
        sessions.forEach { it.close() }
        eventually("media threads end") { mediaThreads().isEmpty() }
    }

    @Test
    fun `encrypted frames arrive in order through split writes, with reconfiguration`() {
        val session = open()
        val port = session.startMirroring(Vectors.STREAM_ID_A)
        val sender = sender()
        sender.connect(port)
        eventually("connected") { "connected" in listener.events }
        sender.send(sender.codecPacket(hex(AVCC)), chunk = 7)
        val expected = mutableListOf<ByteArray>()
        val chunks = intArrayOf(1, 13, 128, 1_000, 100_000)
        for (i in 0 until 20) {
            val nals = if (i == 0) listOf(nal(0x65, 5_000)) else listOf(nal(0x41, 100 + i * 997), nal(0x41, 5))
            sender.send(sender.videoPacket(nals, timestamp = i * 143_165_576L), chunk = chunks[i % chunks.size])
            expected += FakeMirroringSender.annexB(nals)
        }
        val sink = platform.videoSinks.single()
        eventually("20 frames") { sink.frames.size == 20 }
        assertContentEquals(hex(CSD) + expected[0], sink.frames[0].bytes)
        for (i in 1 until 20) assertContentEquals(expected[i], sink.frames[i].bytes, "frame $i")
        assertTrue(sink.frames.all { it.thread == "rozan-media-mirroring" })
        val first = sink.frames[0].presentationNanos
        assertTrue(sink.frames[19].presentationNanos - first in 633_000_000L..634_000_000L, "19/30 s later")

        sender.send(sender.codecPacket(hex(OTHER_AVCC), width = 1080f, height = 1920f))
        sender.send(sender.videoPacket(listOf(nal(0x65, 64))))
        eventually("reconfigured") { sink.configs.size == 2 && sink.frames.size == 21 }
        assertEquals(1080, sink.configs[1].width)
        assertContentEquals(hex(OTHER_CSD) + FakeMirroringSender.annexB(listOf(nal(0x65, 64))), sink.frames[20].bytes)
    }

    @Test
    fun `a reconnect continues the keystream`() {
        val session = open()
        val port = session.startMirroring(Vectors.STREAM_ID_B)
        val sender = sender(Vectors.STREAM_ID_B)
        sender.connect(port)
        sender.send(sender.codecPacket(hex(AVCC)))
        sender.send(sender.videoPacket(listOf(nal(0x65, 300))))
        val sink = platform.videoSinks.single()
        eventually("first frame") { sink.frames.size == 1 }
        sender.disconnect()
        eventually("disconnect reported") { listener.events.any { it.startsWith("disconnected: the sender closed") } }

        sender.connect(port)
        sender.send(sender.codecPacket(hex(AVCC)))
        sender.send(sender.videoPacket(listOf(nal(0x65, 400))))
        eventually("frame after reconnect") { sink.frames.size == 2 }
        assertContentEquals(hex(CSD) + FakeMirroringSender.annexB(listOf(nal(0x65, 400))), sink.frames[1].bytes)
        assertEquals(listOf("connected", "connected"), listener.events.filter { it == "connected" })
        assertEquals(false, sink.closed)
    }

    @Test
    fun `a payload over its bound ends the stream`() {
        val session = open()
        val port = session.startMirroring(Vectors.STREAM_ID_A)
        val sender = sender()
        sender.connect(port)
        sender.send(FakeMirroringSender.header(0, MediaLimits.MAX_VIDEO_PAYLOAD_BYTES + 1L))
        eventually("stream ended") { listener.events.any { it.startsWith("disconnected: type 0 payload") } }
        val sink = platform.videoSinks.single()
        eventually("sink closed") { sink.closed }
        assertFailsWith<ConnectException> {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000) }
        }
    }

    @Test
    fun `connections from other addresses are refused`() {
        val session = open(client = InetAddress.getByName("192.0.2.1"))
        val port = session.startMirroring(Vectors.STREAM_ID_A)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
            socket.soTimeout = 2_000
            assertEquals(-1, socket.getInputStream().read(), "closed by the receiver at once")
        }
        assertTrue(listener.events.none { it == "connected" })
    }

    @Test
    fun `no connection within the timeout ends the stream`() {
        val session = open(config = testConfig(firstConnectionTimeoutMillis = 300, log = log))
        session.startMirroring(Vectors.STREAM_ID_A)
        eventually("timeout reported") { listener.events.any { "no mirroring connection" in it } }
        eventually("sink closed") { platform.videoSinks.single().closed }
    }

    @Test
    fun `idle time between packets is fine, a stall inside one is not`() {
        val session = open(config = testConfig(readTimeoutMillis = 300, log = log))
        val port = session.startMirroring(Vectors.STREAM_ID_A)
        val sender = sender()
        sender.connect(port)
        sender.send(sender.codecPacket(hex(AVCC)))
        Thread.sleep(800)
        sender.send(sender.videoPacket(listOf(nal(0x65, 50))))
        val sink = platform.videoSinks.single()
        eventually("frame after idle") { sink.frames.size == 1 }
        assertTrue(listener.events.none { it.startsWith("disconnected") })
        sender.send(sender.videoPacket(listOf(nal(0x41, 50))).copyOf(60))
        eventually("stall reported") { listener.events.any { "stalled" in it } }
    }

    @Test
    fun `garbage streams end cleanly`() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures += e }
        try {
            val random = Random(42)
            val session = open(config = testConfig(readTimeoutMillis = 300, log = log))
            for (round in 0 until 6) {
                val port = session.startMirroring(random.nextLong())
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                    val out = socket.getOutputStream()
                    try {
                        repeat(20) {
                            val header = random.nextBytes(128)
                            // Small sizes keep the stream going for a while; random types and payloads.
                            if (round % 2 == 0) header[3] = 0
                            if (round % 2 == 0) header[2] = 0
                            out.write(header)
                            out.write(random.nextBytes(random.nextInt(0, 4_000)))
                        }
                        out.flush()
                    } catch (_: java.io.IOException) {
                        // The receiver closed the stream: what garbage should get.
                    }
                }
                eventually("round $round ended") { listener.events.count { it.startsWith("disconnected") } > round }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `close from another thread while streaming`() {
        val session = open()
        val port = session.startMirroring(Vectors.STREAM_ID_A)
        val sender = sender()
        sender.connect(port)
        sender.send(sender.codecPacket(hex(AVCC)))
        val sink = platform.videoSinks.single()
        val writer =
            Thread {
                try {
                    while (true) sender.send(sender.videoPacket(listOf(nal(0x41, 20_000))))
                } catch (_: java.io.IOException) {
                    // the receiver went away
                }
            }
        writer.start()
        eventually("streaming") { sink.frames.size > 5 }
        val closer = Thread { session.close() }
        closer.start()
        closer.join(5_000)
        writer.join(5_000)
        assertTrue(sink.closed)
        assertTrue(listener.events.none { it.startsWith("disconnected") }, "a requested stop is not reported")
        eventually("threads gone") { mediaThreads().isEmpty() }
    }

    @Test
    fun `starting again replaces the stream and restarts the keystream`() {
        val session = open()
        val firstPort = session.startMirroring(Vectors.STREAM_ID_A)
        val secondPort = session.startMirroring(Vectors.STREAM_ID_B)
        assertTrue(platform.videoSinks[0].closed)
        if (firstPort != secondPort) {
            assertFailsWith<ConnectException> {
                Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), firstPort), 1_000) }
            }
        }
        val sender = sender(Vectors.STREAM_ID_B)
        sender.connect(secondPort)
        sender.send(sender.codecPacket(hex(AVCC)))
        sender.send(sender.videoPacket(listOf(nal(0x65, 10))))
        eventually("frame") { platform.videoSinks[1].frames.size == 1 }
        session.stopMirroring()
        assertTrue(platform.videoSinks[1].closed)
        session.stopMirroring()
    }

    @Test
    fun `a failing sink ends the stream`() {
        val session = open()
        val port = session.startMirroring(Vectors.STREAM_ID_A)
        platform.videoSinks.single().failOnSubmit = IllegalStateException("decoder died")
        val sender = sender()
        sender.connect(port)
        sender.send(sender.codecPacket(hex(AVCC)))
        sender.send(sender.videoPacket(listOf(nal(0x65, 10))))
        eventually("failure reported") { listener.events.any { "decoder died" in it } }
        eventually("sink closed") { platform.videoSinks.single().closed }
    }

    @Test
    fun `a blocked read does not stop close`() {
        val session = open()
        val port = session.startMirroring(Vectors.STREAM_ID_A)
        val socket = Socket()
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
        eventually("connected") { "connected" in listener.events }
        val started = System.nanoTime()
        session.close()
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
        socket.soTimeout = 2_000
        try {
            assertEquals(-1, socket.getInputStream().read())
        } catch (_: java.net.SocketException) {
            // reset by the receiver: also closed
        } catch (_: SocketTimeoutException) {
            throw AssertionError("the receiver kept the connection open")
        } finally {
            socket.close()
        }
    }

    private fun nal(
        header: Int,
        size: Int,
    ): ByteArray = ByteArray(size) { if (it == 0) header.toByte() else (it * 31).toByte() }

    private companion object {
        const val AVCC = "0164c028ffe100106764c028ac56200d814fe59b8101010101000428ee3cb0"
        const val CSD = "000000016764c028ac56200d814fe59b81010101" + "0000000128ee3cb0"
        const val OTHER_AVCC = "0164c01fffe100046764c01f01000428ee3cb1"
        const val OTHER_CSD = "000000016764c01f0000000128ee3cb1"
    }
}

/** Live threads of the media layer. */
internal fun mediaThreads(): List<Thread> =
    Thread.getAllStackTraces().keys.filter {
        it.isAlive &&
            it.name.startsWith("rozan-media-")
    }
