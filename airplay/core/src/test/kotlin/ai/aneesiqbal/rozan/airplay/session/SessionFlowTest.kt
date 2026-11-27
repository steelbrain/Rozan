package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistData
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistInteger
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.BindException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session SETUP, streams, parameters and teardown over real sockets with fake layers (01 §6.8-§6.14, 03). */
class SessionFlowTest {
    private val clock = AtomicLong(1_000_000_000L)
    private val harness =
        ReceiverHarness(
            mode = PinMode.DIGEST,
            limits = ControlLimits(housekeepingIntervalMillis = 20),
            clock = clock::get,
        )
    private val uri = TestClient.SESSION_URI

    @AfterTest
    fun stop() = harness.close()

    private val fpSetup = byteArrayOf(0x46, 0x50, 0x4C, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0)

    private fun sessionSetup(ekeyFirstByte: Byte = 0x46) =
        buildPlistDict {
            put("ekey", ByteArray(72) { ekeyFirstByte })
            put("eiv", ByteArray(16) { 7 })
            put("timingProtocol", "NTP")
            put("timingPort", 60373)
            put("deviceID", "AA:BB:CC:DD:EE:FF")
            put("name", "Anna's iPhone")
            put("model", "iPhone16,2")
            put("isScreenMirroringSession", true)
        }

    private fun mirroringStream(id: PlistInteger = PlistInteger.ofUnsigned(0xC0FFEE1234567890UL)) =
        buildPlistDict {
            put(
                "streams",
                PlistArray(
                    listOf(
                        buildPlistDict {
                            put("type", 110)
                            put("streamConnectionID", id)
                        },
                    ),
                ),
            )
        }

    private fun audioStream(
        ct: Int,
        usingScreen: Boolean,
    ) = buildPlistDict {
        put(
            "streams",
            PlistArray(
                listOf(
                    buildPlistDict {
                        put("type", 96)
                        put("ct", ct)
                        put("spf", if (ct == 8) 480 else 352)
                        put("controlPort", 63658)
                        put("usingScreen", usingScreen)
                    },
                ),
            ),
        )
    }

    /** fp-setup, the 401 challenge, then the authorized session SETUP. */
    private fun openSession(client: TestClient): PlistDict {
        assertEquals(200, client.request("POST", "/fp-setup", fpSetup).status)
        assertEquals(200, client.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
        val challenge = client.setup(sessionSetup())
        assertEquals(401, challenge.status)
        assertEquals(FakePairing.CHALLENGE, challenge.headers["WWW-Authenticate"])
        assertEquals("0", challenge.headers["Content-Length"])
        val ok = client.setup(sessionSetup(), listOf("Authorization" to FakePairing.GOOD_AUTHORIZATION))
        assertEquals(200, ok.status)
        assertEquals(ContentTypes.BPLIST, ok.headers["Content-Type"])
        return plistBody(ok)
    }

    @Test
    fun `Digest session then mirroring and audio streams`() {
        harness.connect().use { client ->
            val session = openSession(client)
            assertEquals(0L, session.long("eventPort"))
            assertEquals(FakeMedia.TIMING_PORT.toLong(), session.long("timingPort"))
            val media = harness.media.single()
            // Mode D without pair-verify: the session key is the raw FairPlay key (03 §2.1).
            assertContentEquals(FakeFairPlay.KEY, media.sessionKey)
            assertContentEquals(ByteArray(16) { 7 }, media.params.audioIv)
            assertEquals(60373, media.params.clientTimingPort)
            assertEquals(InetAddress.getLoopbackAddress(), media.params.client)

            val record = client.request("RECORD", uri, headers = listOf("RTP-Info" to "seq=1;rtptime=2"))
            assertEquals(200, record.status)
            assertEquals("11025", record.headers["Audio-Latency"])
            assertEquals("connected; type=analog", record.headers["Audio-Jack-Status"])

            val mirroring = plistBody(client.setup(mirroringStream())).array("streams")!!.items.single() as PlistDict
            assertEquals(110L, mirroring.long("type"))
            assertEquals(FakeMedia.MIRROR_PORT.toLong(), mirroring.long("dataPort"))
            assertEquals("startMirroring 13907095936298285200", media.calls.last())

            val audioReply = plistBody(client.setup(audioStream(8, usingScreen = true)))
            val audio = audioReply.array("streams")!!.items.single() as PlistDict
            assertEquals(listOf("type", "dataPort", "controlPort"), audio.entries.keys.toList())
            assertEquals(FakeMedia.AUDIO_CONTROL_PORT.toLong(), audio.long("controlPort"))
            assertEquals("startAudio ct=8 spf=480 control=63658 screen=true latency=null..null", media.calls.last())

            val p = harness.platform
            p.expectEvent("connection")
            p.expectEvent("pin ${FakePairing.PIN}")
            p.expectEvent("pinFinished ${PinResult.ACCEPTED}")
            p.expectEvent("mirroringStarted")
            assertEquals("Anna's iPhone", p.clients.last().name)
            assertEquals("iPhone16,2", p.clients.last().model)

            // Partial teardowns: audio, then mirroring; the session stays.
            assertEquals(200, client.postPlist("/feedback", PlistDict(emptyMap())).status)
            val audioOnly = buildPlistDict { put("streams", PlistArray(listOf(buildPlistDict { put("type", 96) }))) }
            val partial = client.request("TEARDOWN", uri, BinaryPlist.encode(audioOnly), ContentTypes.BPLIST)
            assertEquals(200, partial.status)
            assertNull(partial.headers["Connection"])
            assertEquals("stopAudio", media.calls.last())
            val video = buildPlistDict { put("streams", PlistArray(listOf(buildPlistDict { put("type", 110) }))) }
            client.request("TEARDOWN", uri, BinaryPlist.encode(video), ContentTypes.BPLIST)
            assertEquals("stopMirroring", media.calls.last())
            p.expectEvent("mirroringStopped")
            assertTrue(!media.closed)

            val full = client.request("TEARDOWN", uri)
            assertEquals("close", full.headers["Connection"])
            assertTrue(media.closed)
            assertEquals(1, harness.pairing.sessionsEnded.get())
            // The sender may keep the connection, but it is no longer authenticated (01 §6.13).
            assertEquals(470, client.setup(mirroringStream()).status)
        }
    }

    @Test
    fun `session SETUP before fp-setup or with a bad ekey is 400 and opens nothing`() {
        harness.connect().use { client ->
            val authorized = listOf("Authorization" to FakePairing.GOOD_AUTHORIZATION)
            assertEquals(400, client.setup(sessionSetup(), authorized).status)
            assertEquals(200, client.request("POST", "/fp-setup", fpSetup).status)
            assertEquals(200, client.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
            assertEquals(400, client.setup(sessionSetup(ekeyFirstByte = 0)).status)
            val shortEkey = PlistDict(sessionSetup().entries + ("ekey" to PlistData(ByteArray(71))))
            assertEquals(400, client.setup(shortEkey).status)
            assertTrue(harness.media.sessions.isEmpty())
            assertEquals(200, client.setup(sessionSetup()).status)
        }
    }

    @Test
    fun `pair-verify secret hashes the session key`() {
        harness.connect().use { client ->
            assertEquals(200, client.request("POST", "/pair-verify", byteArrayOf(1) + ByteArray(67)).status)
            assertEquals(200, client.request("POST", "/fp-setup", fpSetup).status)
            assertEquals(200, client.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
            // Verified, so no challenge.
            assertEquals(200, client.setup(sessionSetup()).status)
            val expected = Crypto.sha512(FakeFairPlay.KEY, FakePairing.SHARED_SECRET).copyOf(16)
            assertContentEquals(expected, harness.media.single().sessionKey)
        }
    }

    @Test
    fun `audio-only stream fires audio events and now playing`() {
        harness.connect().use { client ->
            openSession(client)
            val media = harness.media.single()
            val p = harness.platform
            client.setup(audioStream(2, usingScreen = false))
            p.awaitEvent { it == "audioStarted" }

            assertEquals(451, client.request("SET_PARAMETER", uri, "volume: -3\r\n".toByteArray()).status)
            val quieter = "volume: -20.000000\r\n".toByteArray()
            assertEquals(200, client.request("SET_PARAMETER", uri, quieter, "text/parameters").status)
            assertEquals(0.1f, media.volumes.last(), 1e-6f)
            client.request("SET_PARAMETER", uri, "volume: -144\r\n".toByteArray(), "text/parameters")
            client.request("SET_PARAMETER", uri, "volume: NaN\r\n".toByteArray(), "text/parameters")
            client.request("SET_PARAMETER", uri, "volume: 6\r\n".toByteArray(), "text/parameters")
            // The session starts at full volume; NaN is ignored and +6 dB clamps to 0 dB.
            assertEquals(4, media.volumes.size)
            assertEquals(listOf(1f, 0f, 1f), listOf(media.volumes[0], media.volumes[2], media.volumes[3]))
            val get = client.request("GET_PARAMETER", uri, "volume\r\n".toByteArray(), "text/parameters")
            assertEquals("volume: 0.000000\r\n", String(get.body))
            assertEquals("text/parameters", get.headers["Content-Type"])
            assertEquals(451, client.request("GET_PARAMETER", uri, "volume\r\n".toByteArray()).status)
            val other = client.request("GET_PARAMETER", uri, "other\r\n".toByteArray(), "text/parameters")
            assertEquals(0, other.body.size)

            client.request("SET_PARAMETER", uri, "progress: 1/2/3\r\n".toByteArray(), "text/parameters")
            val dmap = dmapItem("mlit", dmapItem("minm", "Song".toByteArray()) + dmapItem("asar", "Band".toByteArray()))
            assertEquals(200, client.request("SET_PARAMETER", uri, dmap, ContentTypes.DMAP).status)
            p.awaitEvent { it == "nowPlaying" }
            assertEquals("Song", p.nowPlaying.last()?.title)
            assertEquals("Band", p.nowPlaying.last()?.artist)
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
            client.request("SET_PARAMETER", uri, jpeg, "image/png")
            p.awaitEvent { it == "nowPlaying" }
            assertContentEquals(jpeg, p.nowPlaying.last()?.artwork)
            assertEquals("Song", p.nowPlaying.last()?.title)
            client.request("SET_PARAMETER", uri, byteArrayOf(1, 2, 3), "image/jpeg")
            client.request("SET_PARAMETER", uri, ByteArray(0), "image/none")
            p.awaitEvent { it == "nowPlaying" }
            assertNull(p.nowPlaying.last()?.artwork)
            assertEquals(3, p.nowPlaying.size)

            client.request("FLUSH", uri, headers = listOf("RTP-Info" to "seq=24561;rtptime=1844137298"))
            assertEquals("flush 24561 1844137298", media.calls.last())
            client.request("FLUSH", uri)
            assertEquals("flush null null", media.calls.last())
            assertEquals(200, client.postPlist("/audioMode", buildPlistDict { put("audioMode", "default") }).status)

            client.request("TEARDOWN", uri)
            p.awaitEvent { it == "audioStopped" }
            p.awaitEvent { it == "nowPlaying" }
            assertNull(p.nowPlaying.last())
        }
    }

    @Test
    fun `malformed TEARDOWN bodies end the whole session`() {
        harness.connect().use { client ->
            openSession(client)
            val response = client.request("TEARDOWN", uri, "bplist00garbage".toByteArray(), ContentTypes.BPLIST)
            assertEquals(200, response.status)
            assertEquals("close", response.headers["Connection"])
            assertTrue(harness.media.single().closed)
        }
    }

    @Test
    fun `a whole-session TEARDOWN ends the connection's authentication`() {
        harness.connect().use { client ->
            openSession(client)
            // A stream TEARDOWN keeps the session and the authentication.
            val audio = buildPlistDict { put("streams", PlistArray(listOf(buildPlistDict { put("type", 96) }))) }
            assertEquals(200, client.request("TEARDOWN", uri, BinaryPlist.encode(audio), ContentTypes.BPLIST).status)
            assertEquals(200, client.request("RECORD", uri).status)

            assertEquals(200, client.request("TEARDOWN", uri).status)
            assertEquals(200, client.request("OPTIONS", "*").status, "the connection stays open")
            assertEquals(470, client.request("RECORD", uri).status)
            assertEquals(470, client.setup(mirroringStream()).status)
            val again = client.setup(sessionSetup())
            assertEquals(401, again.status, "a new session needs a new code")
            assertEquals(FakePairing.CHALLENGE, again.headers["WWW-Authenticate"])
            assertEquals(1, harness.media.sessions.size)
        }
    }

    @Test
    fun `unsupported stream types are left out, alone they are 400`() {
        harness.connect().use { client ->
            openSession(client)
            val only103 = buildPlistDict { put("streams", PlistArray(listOf(buildPlistDict { put("type", 103) }))) }
            assertEquals(400, client.setup(only103).status)
            val mixed =
                buildPlistDict {
                    val mirror = mirroringStream().array("streams")!!.items[0]
                    put("streams", PlistArray(listOf(buildPlistDict { put("type", 130) }, mirror)))
                }
            val streams = plistBody(client.setup(mixed)).array("streams")!!
            assertEquals(listOf(110L), streams.items.map { (it as PlistDict).long("type") })
        }
    }

    @Test
    fun `media layer errors become their status`() {
        harness.media.failOpen = RtspException("bad timing port", 400)
        harness.connect().use { client ->
            assertEquals(200, client.request("POST", "/fp-setup", fpSetup).status)
            assertEquals(200, client.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
            val response = client.setup(sessionSetup(), listOf("Authorization" to FakePairing.GOOD_AUTHORIZATION))
            assertEquals(400, response.status)
            harness.media.failOpen = BindException("no ports")
            assertEquals(500, client.setup(sessionSetup()).status)
            harness.media.failOpen = null
            assertEquals(200, client.setup(sessionSetup()).status)
        }
    }

    @Test
    fun `an unexpected media failure closes the connection and frees the receiver`() {
        val v6 = InetAddress.getByName("::1")
        assumeTrue(runCatching { harness.connect(v6).close() }.isSuccess, "IPv6 loopback is not available")
        harness.media.failOpen = IllegalStateException("bug in the media layer")
        harness.connect().use { client ->
            assertEquals(200, client.request("POST", "/fp-setup", fpSetup).status)
            assertEquals(200, client.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
            client.send(
                ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
                    .Builder("SETUP", uri)
                    .header("CSeq", "9")
                    .header("Authorization", FakePairing.GOOD_AUTHORIZATION)
                    .body(BinaryPlist.encode(sessionSetup()), ContentTypes.BPLIST)
                    .build()
                    .toByteArray(),
            )
            assertTrue(client.closedByReceiver())
        }
        // No session owner is left behind, so another address is not refused with 409.
        harness.connect(v6).use { assertEquals(200, it.request("GET", "/info").status) }
    }

    @Test
    fun `closing the control connection tears the session down`() {
        val client = harness.connect()
        openSession(client)
        client.setup(mirroringStream())
        harness.platform.awaitEvent { it == "mirroringStarted" }
        client.close()
        harness.platform.awaitEvent { it == "mirroringStopped" }
        assertTrue(harness.media.single().closed)
    }

    @Test
    fun `disconnectAll ends sessions and fires the stopped events before returning`() {
        harness.connect().use { client ->
            openSession(client)
            client.setup(mirroringStream())
            harness.platform.awaitEvent { it == "mirroringStarted" }
            harness.receiver.disconnectAll()
            assertEquals("mirroringStopped", harness.platform.eventLog.poll())
            assertTrue(harness.media.single().closed)
            assertTrue(client.closedByReceiver())
        }
        // The receiver keeps listening.
        harness.connect().use { assertEquals(200, it.request("OPTIONS", "*").status) }
    }

    @Test
    fun `missing feedback tears the session down after the timeout`() {
        harness.connect().use { client ->
            openSession(client)
            client.setup(mirroringStream())
            harness.platform.awaitEvent { it == "mirroringStarted" }
            clock.addAndGet(TimeUnit.SECONDS.toNanos(10))
            assertEquals(200, client.request("POST", "/feedback").status)
            clock.addAndGet(TimeUnit.SECONDS.toNanos(10))
            Thread.sleep(200)
            assertTrue(!harness.media.single().closed)
            clock.addAndGet(TimeUnit.SECONDS.toNanos(6))
            harness.platform.awaitEvent { it == "mirroringStopped" }
            assertTrue(harness.media.single().closed)
            assertTrue(client.closedByReceiver())
        }
    }

    @Test
    fun `a second session from the same sender takes over the first`() {
        val first = harness.connect()
        openSession(first)
        first.setup(mirroringStream())
        harness.platform.awaitEvent { it == "mirroringStarted" }
        harness.connect().use { second ->
            openSession(second)
            harness.platform.awaitEvent { it == "mirroringStopped" }
            assertTrue(harness.media.sessions[0].closed)
            assertTrue(!harness.media.sessions[1].closed)
            assertTrue(first.closedByReceiver())
            // Taking over is not a session end for Mode P rotation.
            assertEquals(0, harness.pairing.sessionsEnded.get())
        }
        first.close()
    }

    @Test
    fun `other addresses get 409 while a session streams`() {
        val v6 = InetAddress.getByName("::1")
        val v6Reachable = runCatching { harness.connect(v6).close() }.isSuccess
        assumeTrue(v6Reachable, "IPv6 loopback is not available")
        harness.connect(v6).use { idle -> assertEquals(200, idle.request("OPTIONS", "*").status) }
        harness.connect().use { owner ->
            openSession(owner)
            harness.connect(v6).use { other ->
                val conflict = other.request("GET", "/info")
                assertEquals(409, conflict.status)
                assertEquals("Conflict", conflict.reason)
                assertTrue(other.closedByReceiver())
            }
            // Never to the owner's address.
            harness.connect().use { assertEquals(200, it.request("GET", "/info").status) }
            owner.request("TEARDOWN", uri)
            harness.connect(v6).use { assertEquals(200, it.request("GET", "/info").status) }
        }
    }

    private fun dmapItem(
        tag: String,
        value: ByteArray,
    ): ByteArray = tag.toByteArray() + ByteBuffer.allocate(4).putInt(value.size).array() + value
}
