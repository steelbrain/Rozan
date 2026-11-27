package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.pairing.DefaultPairingService
import ai.aneesiqbal.rozan.airplay.pairing.DigestClient
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The classic RAOP flow of Music on a Mac (01 §4.3.2, §6.8.1, §6.17; 02 §6.4) over sockets, with the real
 * pairing service in both PIN modes and fake FairPlay and media layers.
 */
class ClassicFlowTest {
    private val clock = AtomicLong(1_000_000_000L)
    private val uri = "rtsp://127.0.0.1/3878543842"
    private val fpSetupV2 = byteArrayOf(0x46, 0x50, 0x4C, 0x59, 2, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0)
    private val iv = ByteArray(16) { (0x30 + it).toByte() }
    private val musicTransport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002"
    private val musicHeaders =
        listOf(
            "User-Agent" to "Music/1.7 (Macintosh; OS X 27.0.1) AppleWebKit/625.1.29.11.28",
            "Client-Instance" to "F7C2CDC26FC71581",
            "DACP-ID" to "F7C2CDC26FC71581",
            "Active-Remote" to "67622618",
        )

    private fun harness(
        mode: PinMode,
        limits: ControlLimits = ControlLimits(housekeepingIntervalMillis = 20),
    ) = ReceiverHarness(
        mode = mode,
        limits = limits,
        clock = clock::get,
        realPairing = { m, seed, display, changed, time -> DefaultPairingService(m, seed, display, changed, time) },
    )

    /** The SDP of 01 §6.17 with a test key blob (FakeFairPlay refuses one starting 0x00) and IV. */
    private fun sdp(
        keyFirstByte: Byte = 0x46,
        rtpmap: String = "AppleLossless",
    ): ByteArray {
        val key = Base64.getEncoder().encodeToString(ByteArray(72) { if (it == 0) keyFirstByte else 0x46 })
        val aesIv = Base64.getEncoder().withoutPadding().encodeToString(iv)
        return listOf(
            "v=0",
            "o=iTunes 3878543842 0 IN IP4 127.0.0.1",
            "s=iTunes",
            "c=IN IP4 127.0.0.1",
            "t=0 0",
            "m=audio 0 RTP/AVP 96",
            "a=rtpmap:96 $rtpmap",
            "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100",
            "a=fpaeskey:$key",
            "a=aesiv:$aesIv",
        ).joinToString("") { "$it\r\n" }.toByteArray()
    }

    private fun TestClient.fairPlay() {
        assertEquals(200, request("POST", "/fp-setup", fpSetupV2, ContentTypes.OCTET_STREAM).status)
        assertEquals(200, request("POST", "/fp-setup", ByteArray(164) { 1 }, ContentTypes.OCTET_STREAM).status)
    }

    private fun TestClient.announce(
        authorization: String? = null,
        body: ByteArray = sdp(),
        contentType: String = ContentTypes.SDP,
    ): RtspResponse {
        val auth = listOfNotNull(authorization?.let { "Authorization" to it })
        return request("ANNOUNCE", uri, body, contentType, musicHeaders + auth)
    }

    private fun TestClient.classicSetup(transport: String = musicTransport): RtspResponse =
        request("SETUP", uri, headers = musicHeaders + ("Transport" to transport))

    private fun TestClient.setVolume(): RtspResponse =
        request("SET_PARAMETER", uri, "volume: -15.000000\r\n".toByteArray(), ContentTypes.TEXT_PARAMETERS)

    private fun ReceiverHarness.pin(): String = platform.awaitEvent { it.startsWith("pin ") }.removePrefix("pin ")

    private fun answer(
        challenge: RtspResponse,
        code: String,
    ): String {
        assertEquals(401, challenge.status)
        val header = assertNotNull(challenge.headers["WWW-Authenticate"], "a Digest challenge")
        assertTrue(header.startsWith("Digest realm=\"raop\", nonce=\""), header)
        val nonce = DigestClient.nonce(header)
        return DigestClient.authorization(code, nonce, uri, username = "iTunes", method = "ANNOUNCE")
    }

    /** fp-setup and an ANNOUNCE answered with the code on screen; returns the `Authorization` that worked. */
    private fun authenticate(
        h: ReceiverHarness,
        client: TestClient,
    ): String {
        client.fairPlay()
        val challenge = client.announce()
        val authorization = answer(challenge, h.pin())
        assertEquals(200, client.announce(authorization).status)
        h.platform.awaitEvent { it == "pinFinished ${PinResult.ACCEPTED}" }
        return authorization
    }

    private fun awaitLog(
        h: ReceiverHarness,
        fragment: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RecordingPlatform.WAIT_SECONDS)
        while (h.logs.none { it.contains(fragment) }) {
            if (System.nanoTime() > deadline) fail("no log containing \"$fragment\": ${h.logs}")
            Thread.sleep(10)
        }
    }

    @ParameterizedTest
    @EnumSource(PinMode::class)
    fun `the selection probe needs no code and lists ANNOUNCE`(mode: PinMode) {
        harness(mode).use { h ->
            h.connect().use { probe ->
                probe.fairPlay()
                val options = probe.request("OPTIONS", "*", headers = musicHeaders)
                assertEquals(200, options.status)
                val methods = assertNotNull(options.headers["Public"]).split(',').map { it.trim() }
                assertTrue("ANNOUNCE" in methods && "SETUP" in methods && "RECORD" in methods, methods.toString())
            }
            assertTrue(h.platform.eventLog.none { it.startsWith("pin ") }, "the probe shows no code")
        }
    }

    @ParameterizedTest
    @EnumSource(PinMode::class)
    fun `the play session asks for the code at ANNOUNCE, then SETUP, RECORD and TEARDOWN`(mode: PinMode) {
        harness(mode).use { h ->
            h.connect().use { client ->
                client.fairPlay()
                // Nothing past ANNOUNCE runs before the code (02 §6.4).
                assertEquals(470, client.classicSetup().status)
                assertEquals(470, client.request("RECORD", uri).status)
                assertEquals(470, client.setVolume().status)
                val challenge = client.announce()
                val code = h.pin()
                assertTrue(Regex("[0-9]{4}").matches(code))
                val wrong = if (code == "0000") "1111" else "0000"
                val again = client.announce(answer(challenge, wrong))
                assertEquals(200, client.announce(answer(again, code)).status)
                h.platform.awaitEvent { it == "pinFinished ${PinResult.ACCEPTED}" }

                val setup = client.classicSetup()
                assertEquals(200, setup.status)
                assertEquals(
                    "RTP/AVP/UDP;unicast;mode=record;server_port=${FakeMedia.AUDIO_DATA_PORT};" +
                        "control_port=${FakeMedia.AUDIO_CONTROL_PORT};timing_port=${FakeMedia.TIMING_PORT}",
                    setup.headers["Transport"],
                )
                assertEquals("1", setup.headers["Session"])
                val session = h.media.single()
                // 03 §2.1: the FairPlay key as is, never hashed.
                assertContentEquals(FakeFairPlay.KEY, session.sessionKey)
                assertContentEquals(iv, session.audioIv)
                assertEquals(6002, session.params.clientTimingPort)
                assertFalse(session.params.screenMirroring)
                assertEquals(
                    listOf("startAudio ct=2 spf=352 control=6001 screen=false latency=null..null"),
                    session.calls,
                )
                h.platform.awaitEvent { it == "audioStarted" }

                val record = client.request("RECORD", uri, headers = listOf("RTP-Info" to "seq=100;rtptime=200"))
                assertEquals(200, record.status)
                assertEquals(Profile.AUDIO_LATENCY_FRAMES.toString(), record.headers["Audio-Latency"])
                assertEquals(200, client.setVolume().status)
                assertTrue(session.volumes.isNotEmpty())
                val flush = client.request("FLUSH", uri, headers = listOf("RTP-Info" to "seq=300;rtptime=400"))
                assertEquals(200, flush.status)
                assertTrue("flush 300 400" in session.calls, session.calls.toString())
                // The description is used up: a second classic SETUP needs a new ANNOUNCE.
                assertEquals(455, client.classicSetup().status)

                val teardown = client.request("TEARDOWN", uri, headers = musicHeaders)
                assertEquals(200, teardown.status)
                assertEquals("close", teardown.headers["Connection"])
                h.platform.awaitEvent { it == "audioStopped" }
                assertTrue(session.closed)
                // The authentication ended with the session (01 §6.13): another one needs a new code.
                assertEquals(470, client.classicSetup().status)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(PinMode::class)
    fun `an answer from one connection does not open another`(mode: PinMode) {
        harness(mode).use { h ->
            val used =
                h.connect().use { first ->
                    val authorization = authenticate(h, first)
                    assertEquals(200, first.classicSetup().status)
                    assertEquals(200, first.request("TEARDOWN", uri).status)
                    authorization
                }
            // Codes for one address are at least 5 s apart (02 §9.1).
            clock.addAndGet(TimeUnit.SECONDS.toNanos(6))
            h.connect().use { second ->
                second.fairPlay()
                val replay = second.announce(used)
                assertEquals(401, replay.status, "the old nonce and code are gone")
                assertNotNull(replay.headers["WWW-Authenticate"])
                h.pin()
                assertEquals(470, second.classicSetup().status)
            }
            assertEquals(1, h.media.sessions.size)
        }
    }

    @Test
    fun `ANNOUNCE refusals show no code`() {
        harness(PinMode.DIGEST).use { h ->
            h.connect().use { early ->
                assertEquals(400, early.announce().status, "before fp-setup")
            }
            h.connect().use { client ->
                client.fairPlay()
                assertEquals(415, client.announce(contentType = "text/plain").status)
                assertEquals(415, client.announce(body = sdp(rtpmap = "mpeg4-generic/44100/2")).status)
                assertEquals(400, client.announce(body = "v=0\r\n".toByteArray()).status)
                assertEquals(415, client.request("ANNOUNCE", uri, headers = musicHeaders).status, "no body")
                assertEquals(470, client.classicSetup().status)
            }
            h.connect().use { big ->
                val body = ByteArray(SessionDescription.MAX_BYTES + 1) { 'a'.code.toByte() }
                assertEquals(413, big.announce(body = body).status)
                assertTrue(big.closedByReceiver())
            }
            assertTrue(h.platform.eventLog.none { it.startsWith("pin ") })
            assertTrue(h.media.sessions.isEmpty())
        }
    }

    @Test
    fun `a key FairPlay cannot unwrap is 400 after the code`() {
        harness(PinMode.DIGEST).use { h ->
            h.connect().use { client ->
                client.fairPlay()
                val challenge = client.announce(body = sdp(keyFirstByte = 0))
                assertEquals(400, client.announce(answer(challenge, h.pin()), sdp(keyFirstByte = 0)).status)
                assertEquals(455, client.classicSetup().status, "authenticated, but nothing was announced")
                assertTrue(h.media.sessions.isEmpty())
            }
        }
    }

    @Test
    fun `other addresses get 409 while a classic session streams`() {
        val v6 = InetAddress.getByName("::1")
        harness(PinMode.DIGEST).use { h ->
            assumeTrue(runCatching { h.connect(v6).close() }.isSuccess, "IPv6 loopback is not available")
            h.connect().use { owner ->
                authenticate(h, owner)
                assertEquals(200, owner.classicSetup().status)
                h.connect(v6).use { other ->
                    assertEquals(409, other.request("OPTIONS", "*").status)
                    assertTrue(other.closedByReceiver())
                }
                assertEquals(200, owner.request("TEARDOWN", uri).status)
            }
        }
    }

    @Test
    fun `a classic session is held to feedback only from its first`() {
        harness(PinMode.DIGEST).use { h ->
            h.connect().use { client ->
                authenticate(h, client)
                assertEquals(200, client.classicSetup().status)
                val session = h.media.single()
                clock.addAndGet(TimeUnit.SECONDS.toNanos(60))
                Thread.sleep(200)
                assertFalse(session.closed, "no /feedback yet: not timed out")
                assertEquals(200, client.request("POST", "/feedback").status)
                awaitLog(h, "first /feedback of a classic session")
                clock.addAndGet(TimeUnit.SECONDS.toNanos(16))
                h.platform.awaitEvent { it == "audioStopped" }
                assertTrue(session.closed)
                assertTrue(client.closedByReceiver())
            }
        }
    }

    @Test
    fun `a quiet classic connection stays open while its audio arrives`() {
        val limits = ControlLimits(housekeepingIntervalMillis = 20, idleTimeoutMillis = 500)
        harness(PinMode.DIGEST, limits).use { h ->
            h.connect().use { client ->
                authenticate(h, client)
                assertEquals(200, client.classicSetup().status)
                // One packet before the first idle timeout, none after it.
                h.media.single().activity = 1
                awaitLog(h, "idle, kept open")
                assertTrue(client.closedByReceiver())
                awaitLog(h, "s, closing")
            }
        }
    }
}
