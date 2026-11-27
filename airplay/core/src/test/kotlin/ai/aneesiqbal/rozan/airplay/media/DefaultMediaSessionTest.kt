package ai.aneesiqbal.rozan.airplay.media

import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultMediaSessionTest {
    private val platform = FakePlatform()
    private val listener = RecordingListener()
    private val sessions = CopyOnWriteArrayList<MediaSession>()

    private fun open(
        key: ByteArray = Vectors.sessionKey.copyOf(),
        iv: ByteArray = Vectors.audioIv.copyOf(),
        timingPort: Int = 0,
    ): MediaSession {
        val params = MediaSessionParams(InetAddress.getLoopbackAddress(), key, iv, timingPort, false)
        return DefaultMediaSessionFactory(testConfig()).open(params, platform, listener).also { sessions += it }
    }

    @AfterTest
    fun closeEverything() {
        sessions.forEach { it.close() }
        eventually("media threads end") { mediaThreads().isEmpty() }
    }

    @Test
    fun `keys of the wrong size are a bad request`() {
        assertEquals(400, assertFailsWith<RtspException> { open(key = ByteArray(15)) }.status)
        assertEquals(400, assertFailsWith<RtspException> { open(iv = ByteArray(32)) }.status)
    }

    @Test
    fun `the session copies its keys`() {
        val key = Vectors.sessionKey.copyOf()
        val session = open(key = key)
        key.fill(0)
        val sender = FakeMirroringSender(Vectors.sessionKey, 1L)
        sender.connect(session.startMirroring(1L))
        sender.send(
            sender.codecPacket(
                ai.aneesiqbal.rozan.airplay
                    .hex(AVCC),
            ),
        )
        sender.send(sender.videoPacket(listOf(byteArrayOf(0x65, 1, 2))))
        eventually("frame decrypted with the original key") {
            platform.videoSinks
                .single()
                .frames.size == 1
        }
        sender.close()
    }

    @Test
    fun `unsupported audio is a bad request`() {
        val session = open()
        for (request in listOf(audioRequest(ct = 1), audioRequest(ct = 4, spf = 1024), audioRequest(ct = 2, spf = 0))) {
            assertEquals(400, assertFailsWith<RtspException> { session.startAudio(request) }.status)
        }
        assertTrue(platform.audioSinks.isEmpty())
    }

    @Test
    fun `sink failures are internal errors`() {
        val session = open()
        platform.failSinks = true
        assertEquals(500, assertFailsWith<RtspException> { session.startMirroring(1L) }.status)
        assertEquals(500, assertFailsWith<RtspException> { session.startAudio(audioRequest()) }.status)
        platform.failSinks = false
        assertTrue(session.startMirroring(1L) > 0, "the session still works")
        val ports = session.startAudio(audioRequest())
        assertTrue(ports.dataPort > 0 && ports.controlPort > 0 && ports.dataPort != ports.controlPort)
    }

    @Test
    fun `a closed session refuses new streams and close is idempotent from any thread`() {
        val session = open()
        session.startMirroring(1L)
        session.startAudio(audioRequest())
        val start = CountDownLatch(1)
        val closers =
            List(4) {
                Thread {
                    start.await()
                    session.close()
                }
            }
        closers.forEach { it.start() }
        start.countDown()
        closers.forEach { it.join(5_000) }
        session.close()
        assertTrue(platform.videoSinks.single().closed)
        assertTrue(platform.audioSinks.single().closed)
        assertEquals(500, assertFailsWith<RtspException> { session.startMirroring(2L) }.status)
        assertEquals(500, assertFailsWith<RtspException> { session.startAudio(audioRequest()) }.status)
        session.stopAudio()
        session.stopMirroring()
        session.flushAudio(null, null)
        session.setVolume(0.5f)
        eventually("threads gone") { mediaThreads().isEmpty() }
    }

    @Test
    fun `stopping streams releases their sinks and leaves the session usable`() {
        val session = open()
        session.startAudio(audioRequest(ct = 8, spf = 480))
        session.startAudio(audioRequest(ct = 2))
        assertTrue(platform.audioSinks[0].closed, "replaced")
        session.stopAudio()
        assertTrue(platform.audioSinks[1].closed)
        session.stopAudio()
        session.flushAudio(1, 2)
        session.startMirroring(5L)
        session.stopMirroring()
        assertTrue(platform.videoSinks.single().closed)
        assertTrue(session.timingPort > 0)
    }

    @Test
    fun `the timing client asks the sender's timing port`() {
        FakeTimingResponder(clientOffsetNanos = 1_000_000_000_000L).use { responder ->
            val session = open(timingPort = responder.port) as DefaultMediaSession
            eventually("synced") { session.clockSync.isSynced }
            val request = responder.requests.first()
            assertContentEquals(byteArrayOf(0x80.toByte(), 0xD2.toByte(), 0, 7), request.copyOf(4))
        }
    }

    private companion object {
        const val AVCC = "0164c028ffe100106764c028ac56200d814fe59b8101010101000428ee3cb0"
    }
}
