package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.media.DefaultMediaSessionFactory
import ai.aneesiqbal.rozan.airplay.media.eventually
import ai.aneesiqbal.rozan.airplay.media.testConfig
import ai.aneesiqbal.rozan.airplay.pairing.DefaultPairingService
import ai.aneesiqbal.rozan.airplay.pairing.DigestClient
import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.testing.AudioSender
import ai.aneesiqbal.rozan.airplay.testing.FairPlayV2Sender
import ai.aneesiqbal.rozan.airplay.testing.SenderClock
import ai.aneesiqbal.rozan.airplay.testing.TimingResponder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.InetAddress
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Music's classic RAOP play session against the whole receiver (01 §4.3.2): FairPlay v2 through the real
 * `FairPlaySession`, the key as `fpaeskey` in the ANNOUNCE SDP, the code as the Digest password, the
 * classic SETUP, and encrypted ALAC over RTP with a lost packet that is asked for and resent. The audio is
 * encrypted with the FairPlay key as is (03 §2.1), so the sink sees the payloads only if the receiver
 * derives the key that way. Real pairing, FairPlay and media layers; real sockets on loopback.
 */
class ClassicEndToEndTest {
    private val uri = "rtsp://127.0.0.1/3878543842"
    private val musicHeaders = listOf("DACP-ID" to "F7C2CDC26FC71581", "Active-Remote" to "67622618")

    private fun harness(mode: PinMode) =
        ReceiverHarness(
            mode = mode,
            limits = ControlLimits(housekeepingIntervalMillis = 50),
            fairPlay = { ReceiverEndToEndTest.RealFairPlay() },
            realPairing = { m, seed, display, changed, time -> DefaultPairingService(m, seed, display, changed, time) },
            mediaFactory = DefaultMediaSessionFactory(testConfig()),
        )

    private fun sdp(
        keyBlob: ByteArray,
        iv: ByteArray,
    ): ByteArray =
        listOf(
            "v=0",
            "o=iTunes 3878543842 0 IN IP4 127.0.0.1",
            "s=iTunes",
            "c=IN IP4 127.0.0.1",
            "t=0 0",
            "m=audio 0 RTP/AVP 96",
            "a=rtpmap:96 AppleLossless",
            "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100",
            "a=fpaeskey:" + Base64.getEncoder().encodeToString(keyBlob),
            "a=aesiv:" + Base64.getEncoder().withoutPadding().encodeToString(iv),
        ).joinToString("") { "$it\r\n" }.toByteArray()

    /** The value of [name] in a `;`-separated `Transport` header. */
    private fun transportPort(
        header: String,
        name: String,
    ): Int =
        assertNotNull(
            header
                .split(';')
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.toIntOrNull(),
            "$name in $header",
        )

    @ParameterizedTest
    @EnumSource(PinMode::class)
    fun `Music's session plays encrypted ALAC with a resend`(mode: PinMode) {
        harness(mode).use { h ->
            h.connect().use { client ->
                val fairPlay = FairPlayV2Sender()
                val reply1 = client.request("POST", "/fp-setup", fairPlay.setupRequest(), ContentTypes.OCTET_STREAM)
                val reply2 =
                    client.request(
                        "POST",
                        "/fp-setup",
                        fairPlay.handshakeRequest(reply1.body),
                        ContentTypes.OCTET_STREAM,
                    )
                fairPlay.checkHandshakeReply(reply2.body)

                val mediaKey = Crypto.randomBytes(16)
                val iv = Crypto.randomBytes(16)
                val body = sdp(fairPlay.keyBlob(mediaKey), iv)
                val challenge = client.request("ANNOUNCE", uri, body, ContentTypes.SDP, musicHeaders)
                assertEquals(401, challenge.status)
                val code = h.platform.awaitEvent { it.startsWith("pin ") }.removePrefix("pin ")
                val nonce = DigestClient.nonce(assertNotNull(challenge.headers["WWW-Authenticate"]))
                val authorization =
                    DigestClient.authorization(code, nonce, uri, username = "iTunes", method = "ANNOUNCE")
                val announced =
                    client.request(
                        "ANNOUNCE",
                        uri,
                        body,
                        ContentTypes.SDP,
                        musicHeaders + ("Authorization" to authorization),
                    )
                assertEquals(200, announced.status)

                val clock = SenderClock()
                TimingResponder(clock).use { timing ->
                    AudioSender(mediaKey, iv, clock).use { audio ->
                        val transport =
                            "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${audio.controlPort};" +
                                "timing_port=${timing.port}"
                        val setup = client.request("SETUP", uri, headers = musicHeaders + ("Transport" to transport))
                        assertEquals(200, setup.status)
                        val reply = assertNotNull(setup.headers["Transport"])
                        audio.connect(
                            InetAddress.getLoopbackAddress(),
                            transportPort(reply, "server_port"),
                            transportPort(reply, "control_port"),
                        )
                        audio.startResendResponder()
                        h.platform.awaitEvent { it == "audioStarted" }
                        eventually("timing requests") { timing.requestsAnswered > 0 }
                        play(h, client, audio, clock)
                    }
                }
                assertEquals(200, client.request("TEARDOWN", uri, headers = musicHeaders).status)
                eventually("sink closed") { h.platform.audioSinks.all { it.closed } }
            }
        }
    }

    private fun play(
        h: ReceiverHarness,
        client: TestClient,
        audio: AudioSender,
        clock: SenderClock,
    ) {
        val first = audio.firstSequence
        val rtp0 = audio.firstRtpTime
        val rtpInfo = "seq=$first;rtptime=${rtp0.toUInt()}"
        val record =
            client.request(
                "RECORD",
                uri,
                headers =
                    musicHeaders + listOf("Range" to "npt=0-", "RTP-Info" to rtpInfo),
            )
        assertEquals(200, record.status)
        assertEquals(Profile.AUDIO_LATENCY_FRAMES.toString(), record.headers["Audio-Latency"])

        // 100 bytes: six encrypted blocks and a 4-byte clear tail; 0x20 opens an ALAC frame (03c §4.2).
        val payloads = (0 until 8).map { k -> ByteArray(100) { (k * 29 + it).toByte() }.also { it[0] = 0x20 } }
        audio.sendSync(rtpPlay = rtp0, ntpNanos = clock.ntpNanos() + 500_000_000L, rtpNow = rtp0, first = true)
        val packets = payloads.mapIndexed { k, payload -> audio.packet(first + k, rtp0 + 352 * k, payload) }
        // Packet 3 is lost; the sender still has it for the resend.
        for (k in listOf(0, 1, 2, 4, 5, 6, 7)) audio.send(packets[k])
        eventually("audio units", timeoutMillis = 8_000) {
            h.platform.audioSinks
                .singleOrNull()
                ?.units
                ?.size == payloads.size
        }
        val sink = h.platform.audioSinks.single()
        val format = assertIs<AudioFormat.Alac>(sink.format)
        assertEquals(352, format.framesPerPacket)
        for ((k, payload) in payloads.withIndex()) assertContentEquals(payload, sink.units[k].bytes, "unit $k")
        assertTrue(audio.resendRequests.isNotEmpty() && audio.retransmissions >= 1, "packet 3 was asked for and resent")
    }
}
