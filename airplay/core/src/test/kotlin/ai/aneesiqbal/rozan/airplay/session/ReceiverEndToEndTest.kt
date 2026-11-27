package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.fairplay.FairPlayException
import ai.aneesiqbal.rozan.airplay.fairplay.FairPlaySession
import ai.aneesiqbal.rozan.airplay.media.DefaultMediaSessionFactory
import ai.aneesiqbal.rozan.airplay.media.FakeMirroringSender
import ai.aneesiqbal.rozan.airplay.media.eventually
import ai.aneesiqbal.rozan.airplay.media.testConfig
import ai.aneesiqbal.rozan.airplay.pairing.DefaultPairingService
import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import ai.aneesiqbal.rozan.airplay.testing.AirPlayTestSender
import ai.aneesiqbal.rozan.airplay.testing.H264Clip
import ai.aneesiqbal.rozan.airplay.testing.SenderIdentity
import java.net.InetAddress
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole receiver in process against the test sender of `:airplay:testing`: PIN pairing (Mode P) and
 * pair-verify, FairPlay v2 through the real `FairPlaySession`, a session, encrypted H.264 over the
 * mirroring data port in small writes, and encrypted ALAC over RTP with a gap, reordering and a resend.
 * The platform's sinks receive exactly the decrypted data. Real pairing, FairPlay and media layers; real
 * sockets on loopback.
 */
class ReceiverEndToEndTest {
    private val sps =
        byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte(), 0xB4.toByte(), 0x03, 0xC0.toByte(), 0x11, 0x3F, 0x2C)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())

    private fun harness() =
        ReceiverHarness(
            mode = PinMode.PIN_PAIRING,
            limits = ControlLimits(housekeepingIntervalMillis = 50),
            fairPlay = { RealFairPlay() },
            realPairing = { m, seed, display, changed, time -> DefaultPairingService(m, seed, display, changed, time) },
            mediaFactory = DefaultMediaSessionFactory(testConfig()),
        )

    @Test
    fun `paired sender mirrors H264 and plays ALAC with gaps, reordering and a resend`() {
        harness().use { h ->
            val identity = SenderIdentity(name = "Test Mac")
            AirPlayTestSender(InetAddress.getLoopbackAddress(), h.port, identity).use { sender ->
                sender.pairWithPin { h.platform.awaitEvent { it.startsWith("pin ") }.removePrefix("pin ") }
                sender.fairPlaySetup()
                val session = sender.setupSession()
                assertTrue((session.long("timingPort") ?: 0) > 0)
                eventually("timing requests") { sender.timingResponder.requestsAnswered > 0 }
                assertTrue(h.platform.clients.any { it.name == "Test Mac" }, "the sender's name reaches the events")

                mirror(h, sender)
                audio(h, sender)

                sender.teardown()
                eventually("sinks closed") {
                    h.platform.videoSinks.all { it.closed } && h.platform.audioSinks.all { it.closed }
                }
            }
        }
    }

    private fun mirror(
        h: ReceiverHarness,
        sender: AirPlayTestSender,
    ) {
        val video = sender.setupMirroring()
        h.platform.awaitEvent { it == "mirroringStarted" }
        video.maxWriteBytes = 17
        video.sendCodecConfig(sps, pps, 1280, 720)
        val frames =
            listOf(
                listOf(byteArrayOf(0x65, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)),
                listOf(byteArrayOf(0x41, 9, 8, 7), byteArrayOf(0x41, 6, 5)),
                listOf(ByteArray(3000) { (it * 7).toByte() }.also { it[0] = 0x41 }),
            )
        for ((index, nals) in frames.withIndex()) video.sendFrame(H264Clip.Frame(nals, isIdr = index == 0))
        eventually("video frames") {
            h.platform.videoSinks
                .singleOrNull()
                ?.frames
                ?.size == frames.size
        }
        val sink = h.platform.videoSinks.single()
        val config = sink.configs.single()
        assertEquals(VideoCodec.H264, config.codec)
        assertContentEquals(FakeMirroringSender.annexB(listOf(sps, pps)), config.parameterSets)
        for ((index, nals) in frames.withIndex()) {
            val submitted = sink.frames[index].bytes
            // The first access unit after a configuration may carry the parameter sets in front (03b).
            val expected = FakeMirroringSender.annexB(nals)
            assertContentEquals(expected, submitted.copyOfRange(submitted.size - expected.size, submitted.size))
        }
    }

    private fun audio(
        h: ReceiverHarness,
        sender: AirPlayTestSender,
    ) {
        val audio = sender.setupAudio(usingScreen = false)
        h.platform.awaitEvent { it == "audioStarted" }
        sender.setVolume(-20.0)
        sender.record()

        val first = audio.firstSequence
        val rtp0 = audio.firstRtpTime
        // 100 bytes: six encrypted blocks and a 4-byte clear tail; 0x20 opens an ALAC frame (03c §4.2).
        val payloads = (0 until 8).map { k -> ByteArray(100) { (k * 31 + it).toByte() }.also { it[0] = 0x20 } }
        // rtp0 is heard half a second from now, on the sender's clock.
        audio.sendSync(rtpPlay = rtp0, ntpNanos = sender.clock.ntpNanos() + 500_000_000L, rtpNow = rtp0, first = true)
        val packets = payloads.mapIndexed { k, payload -> audio.packet(first + k, rtp0 + 352 * k, payload) }
        // Packet 2 is lost (the responder still has it for the resend); 4 and 5 arrive out of order.
        for (k in listOf(0, 1, 3, 5, 4, 6, 7)) audio.send(packets[k])
        eventually("audio units", timeoutMillis = 8_000) {
            h.platform.audioSinks
                .singleOrNull()
                ?.units
                ?.size ==
                payloads.size
        }
        val sink = h.platform.audioSinks.single()
        val format = assertIs<AudioFormat.Alac>(sink.format)
        assertEquals(24, format.magicCookie.size)
        assertEquals(352, format.framesPerPacket)
        for ((k, payload) in payloads.withIndex()) assertContentEquals(payload, sink.units[k].bytes, "unit $k")
        val times = sink.units.map { it.presentationNanos }
        assertTrue(times.zipWithNext().all { (a, b) -> b > a }, "presentation times increase: $times")
        assertTrue(audio.resendRequests.isNotEmpty() && audio.retransmissions >= 1, "packet 2 was asked for and resent")
        assertTrue(sink.volumes.any { abs(it - 0.1f) < 1e-4f }, "volume -20 dB applied: ${sink.volumes}")
    }

    /** `:airplay:fairplay` behind the receiver's FairPlay contract, as the apps wire it. */
    internal class RealFairPlay : FairPlayHandler {
        private val session = FairPlaySession()

        override fun setup(request: ByteArray): ByteArray = adapt { session.setup(request) }

        override fun handshake(request: ByteArray): ByteArray = adapt { session.handshake(request) }

        override fun decryptKey(encryptedKey: ByteArray): ByteArray = adapt { session.decryptKey(encryptedKey) }

        private inline fun adapt(step: () -> ByteArray): ByteArray =
            try {
                step()
            } catch (e: FairPlayException) {
                throw FairPlayFailure(e.message ?: "FairPlay failed", e)
            }
    }
}
