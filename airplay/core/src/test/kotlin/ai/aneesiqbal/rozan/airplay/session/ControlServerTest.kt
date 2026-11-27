package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistString
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.rtsp.RtspProtocol
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponse
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Control-level behaviour over real sockets (01 §3-§6), with fake pairing, FairPlay and media layers. */
class ControlServerTest {
    private val harness = ReceiverHarness(mode = PinMode.DIGEST)

    @AfterTest
    fun stop() = harness.close()

    private fun header(
        response: RtspResponse,
        name: String,
    ) = response.headers[name]

    private fun assertStandardHeaders(
        response: RtspResponse,
        cSeq: String,
    ) {
        assertEquals(cSeq, header(response, "CSeq"))
        assertEquals("AirTunes/220.68", header(response, "Server"))
        assertEquals("connected; type=digital", header(response, "Audio-Jack-Status"))
        assertEquals(response.body.size.toString(), header(response, "Content-Length"))
    }

    @Test
    fun `publishes both services on the bound port at start`() {
        val records = harness.platform.awaitPublish()
        assertEquals(listOf("_airplay._tcp", "_raop._tcp"), records.map { it.serviceType })
        assertTrue(records.all { it.port == harness.port && it.port > 0 })
        assertEquals("021122334455@Living Room", records[1].instanceName)
    }

    @Test
    fun `OPTIONS answers with Public and the standard headers`() {
        harness.connect().use { client ->
            val response = client.request("OPTIONS", "*")
            assertEquals(200, response.status)
            assertStandardHeaders(response, "0")
            val public = "ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            assertEquals(public, header(response, "Public"))
            assertEquals("0", header(response, "Content-Length"))
            assertStandardHeaders(client.request("OPTIONS", "*"), "1")
        }
    }

    @Test
    fun `GET info in both forms`() {
        harness.connect().use { client ->
            val qualifier = buildPlistDict { put("qualifier", PlistArray(listOf(PlistString("txtAirPlay")))) }
            val first = client.request("GET", "/info", BinaryPlist.encode(qualifier), ContentTypes.BPLIST)
            assertEquals(200, first.status)
            assertEquals(ContentTypes.BPLIST, header(first, "Content-Type"))
            val rdata = plistBody(first).data("txtAirPlay")!!
            assertTrue(String(rdata, Charsets.UTF_8).contains("deviceid=02:11:22:33:44:55"))
            val full = plistBody(client.request("GET", "/info"))
            assertEquals("Living Room", full.string("name"))
            assertContentEquals(ByteArray(32) { 0x11 }, full.data("pk"))
            assertEquals(1384120038L, full.long("features"))
            assertEquals(0.0, full.double("initialVolume"))
            val query = plistBody(client.request("GET", "/info?txtAirPlay&txtRAOP"))
            assertEquals(setOf("txtAirPlay", "txtRAOP"), query.entries.keys)
        }
    }

    @Test
    fun `info follows identity rotation and rename, and both republish`() {
        harness.platform.awaitPublish()
        harness.pairing.rotate()
        val rotated = harness.platform.awaitPublish()
        assertTrue(rotated[0].txt.contains("pk" to "22".repeat(32)))
        harness.connect().use { client ->
            assertContentEquals(ByteArray(32) { 0x22 }, plistBody(client.request("GET", "/info")).data("pk"))
            harness.receiver.rename("Den.local")
            assertEquals("Den", harness.platform.awaitPublish()[0].instanceName)
            assertEquals("Den", plistBody(client.request("GET", "/info")).string("name"))
        }
    }

    @Test
    fun `missing or invalid CSeq gets 400 without CSeq and closes`() {
        for (cSeq in listOf(null, "-1", "abc", "99999999999")) {
            harness.connect().use { client ->
                val headers = if (cSeq == null) "" else "CSeq: $cSeq\r\n"
                client.send("OPTIONS * RTSP/1.0\r\n$headers\r\n".toByteArray())
                val response = client.response()!!
                assertEquals(400, response.status)
                assertNull(header(response, "CSeq"))
                assertTrue(client.closedByReceiver())
            }
        }
    }

    @Test
    fun `unknown methods are 501, unknown GET and POST paths 200`() {
        harness.connect().use { client ->
            assertEquals(501, client.request("SETRATEANCHORTIME", TestClient.SESSION_URI).status)
            assertEquals(501, client.request("SETPEERS", TestClient.SESSION_URI).status)
            assertEquals(200, client.request("GET", "/whatever").status)
            assertEquals(200, client.request("POST", "/whatever").status)
            assertEquals(200, client.request("POST", "/feedback").status)
            assertEquals(200, client.request("TEARDOWN", TestClient.SESSION_URI).status)
        }
    }

    @Test
    fun `HTTP requests are 501 or 404 without RTSP headers`() {
        harness.connect().use { client ->
            val reverse = client.request("POST", "/reverse", protocol = RtspProtocol.HTTP_1_1)
            assertEquals(501, reverse.status)
            assertEquals(RtspProtocol.HTTP_1_1, reverse.protocol)
            assertNull(header(reverse, "CSeq"))
            assertNull(header(reverse, "Audio-Jack-Status"))
            assertEquals("0", header(reverse, "Content-Length"))
            assertEquals(404, client.request("GET", "/stream.xml", protocol = RtspProtocol.HTTP_1_1).status)
            assertEquals(404, client.request("POST", "/stream", protocol = RtspProtocol.HTTP_1_1).status)
            assertEquals(501, client.request("GET", "/server-info", protocol = RtspProtocol.HTTP_1_1).status)
            assertEquals(501, client.request("PUT", "/photo", protocol = RtspProtocol.HTTP_1_1).status)
        }
    }

    @Test
    fun `media requests before authentication get 470`() {
        harness.connect().use { client ->
            val streams =
                buildPlistDict {
                    put(
                        "streams",
                        PlistArray(
                            listOf(
                                buildPlistDict {
                                    put("type", 110)
                                    put("streamConnectionID", 1)
                                },
                            ),
                        ),
                    )
                }
            assertEquals(470, client.setup(streams).status)
            assertEquals(470, client.request("RECORD", TestClient.SESSION_URI).status)
            val volume = "volume\r\n".toByteArray()
            assertEquals(470, client.request("GET_PARAMETER", TestClient.SESSION_URI, volume, "text/parameters").status)
            val setVolume = "volume: -3\r\n".toByteArray()
            val set = client.request("SET_PARAMETER", TestClient.SESSION_URI, setVolume, "text/parameters")
            assertEquals(470, set.status)
            assertEquals(470, client.request("FLUSH", TestClient.SESSION_URI).status)
            assertEquals(470, client.request("POST", "/audioMode").status)
            assertEquals(470, client.request("POST", "/command").status)
            // Still allowed: discovery, pairing, FairPlay, feedback, TEARDOWN.
            assertEquals(200, client.request("POST", "/feedback").status)
            assertEquals(200, client.request("TEARDOWN", TestClient.SESSION_URI).status)
            assertEquals(200, client.request("GET", "/info").status)
            assertTrue(harness.media.sessions.isEmpty())
        }
    }

    @Test
    fun `fp-setup checks lengths, magic and version`() {
        harness.connect().use { client ->
            val setup = byteArrayOf(0x46, 0x50, 0x4C, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0)
            val step1 = client.request("POST", "/fp-setup", setup, ContentTypes.OCTET_STREAM)
            assertEquals(200, step1.status)
            assertEquals(142, step1.body.size)
            assertEquals(ContentTypes.OCTET_STREAM, header(step1, "Content-Type"))
            val handshake = client.request("POST", "/fp-setup", ByteArray(164) { 1 }, ContentTypes.OCTET_STREAM)
            assertEquals(32, handshake.body.size)
            assertEquals(501, client.request("POST", "/fp-setup", setup.copyOf().also { it[4] = 4 }).status)
            assertEquals(400, client.request("POST", "/fp-setup", setup.copyOf().also { it[0] = 0 }).status)
            assertEquals(400, client.request("POST", "/fp-setup", ByteArray(15)).status)
            assertEquals(400, client.request("POST", "/fp-setup", ByteArray(0)).status)
            // A FairPlay failure is a 500 and closes the connection.
            assertEquals(500, client.request("POST", "/fp-setup", ByteArray(164)).status)
            assertTrue(client.closedByReceiver())
        }
    }

    @Test
    fun `oversized and malformed requests close only their own connection`() {
        harness.connect().use { healthy ->
            assertEquals(200, healthy.request("OPTIONS", "*").status)
            val cases =
                listOf(
                    "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n" + (1..33).joinToString("") { "X-$it: v\r\n" } + "\r\n",
                    "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n${"N".repeat(65)}: v\r\n\r\n",
                    "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nX: ${"v".repeat(1025)}\r\n\r\n",
                    "OPTIONS /${"a".repeat(2100)} RTSP/1.0\r\nCSeq: 1\r\n\r\n",
                    "POST /pair-setup RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 70000\r\n\r\n",
                    "OPTIONS * HTTP/1.0\r\nCSeq: 1\r\n\r\n",
                    "\u0001\u0002garbage\r\n\r\n",
                    "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: -5\r\n\r\n",
                )
            val expected = listOf(431, 431, 431, 414, 413, 505, 400, 400)
            for ((index, raw) in cases.withIndex()) {
                harness.connect().use { client ->
                    client.send(raw.toByteArray(Charsets.ISO_8859_1))
                    assertEquals(expected[index], client.response()?.status, raw.take(40))
                    assertTrue(client.closedByReceiver())
                }
            }
            assertEquals(200, healthy.request("OPTIONS", "*").status)
        }
    }

    @Test
    fun `Connection close from the sender closes after the reply`() {
        harness.connect().use { client ->
            assertEquals(200, client.request("GET", "/info", headers = listOf("Connection" to "close")).status)
            assertTrue(client.closedByReceiver())
        }
    }

    @Test
    fun `an address at its cap replaces its oldest unauthenticated connection`() {
        val clients = (1..4).map { harness.connect() }
        try {
            for (client in clients) assertEquals(200, client.request("OPTIONS", "*").status)
            harness.connect().use { extra -> assertEquals(200, extra.request("OPTIONS", "*").status) }
            assertTrue(clients[0].closedByReceiver(), "the oldest made room")
            for (client in clients.drop(1)) assertEquals(200, client.request("OPTIONS", "*").status)
        } finally {
            clients.forEach { it.close() }
        }
    }

    @Test
    fun `GET info is rate limited per address`() {
        harness.connect().use { client ->
            val statuses = (1..15).map { client.request("GET", "/info?txtAirPlay").status }
            assertEquals(List(10) { 200 }, statuses.take(10))
            assertTrue(503 in statuses.drop(10))
        }
    }

    @Test
    fun `first request fires connection started with the client name`() {
        harness.connect().use { client ->
            val longName = "Anna's iPad " + "x".repeat(100)
            client.request("GET", "/info?txtAirPlay", headers = listOf("X-Apple-Client-Name" to longName))
            harness.platform.expectEvent("connection")
            assertEquals(
                ("Anna's iPad " + "x".repeat(100)).take(64),
                harness.platform.clients
                    .last()
                    .name,
            )
            client.request("OPTIONS", "*")
        }
        harness.platform.expectEvent("connectionEnded")
        harness.receiver.disconnectAll()
        assertNull(harness.platform.eventLog.poll())
    }

    @Test
    fun `connection ended follows the close of every announced connection`() {
        harness.connect().use { client -> assertEquals(200, client.request("GET", "/info?txtAirPlay").status) }
        harness.platform.expectEvent("connection")
        harness.platform.expectEvent("connectionEnded")
        // A connection that never sent a request was never announced.
        harness.connect().close()
        harness.connect().use { client -> client.request("OPTIONS", "*") }
        harness.platform.expectEvent("connection")
        harness.platform.expectEvent("connectionEnded")
        harness.receiver.disconnectAll()
        assertNull(harness.platform.eventLog.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `without a FairPlay handler fp-setup fails and closes`() {
        harness.close()
        val bare =
            ai.aneesiqbal.rozan.airplay.receiver.AirPlayReceiver(
                harness.config,
                RecordingPlatform(),
                null,
                ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog.NONE,
                ReceiverParts(FakeMedia(), { _, _, display, changed -> FakePairing(display, changed) }),
            )
        bare.start()
        try {
            TestClient(InetAddress.getLoopbackAddress(), bare.controlPort).use { client ->
                val setup = byteArrayOf(0x46, 0x50, 0x4C, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0)
                assertEquals(500, client.request("POST", "/fp-setup", setup).status)
                assertTrue(client.closedByReceiver())
            }
        } finally {
            bare.stop()
        }
    }

    @Test
    fun `pair-pin-start shows the code with the header name`() {
        harness.connect().use { client ->
            val start = client.request("POST", "/pair-pin-start", headers = listOf("X-Apple-Client-Name" to "Mac"))
            assertEquals(200, start.status)
            harness.platform.expectEvent("connection")
            harness.platform.expectEvent("pin ${FakePairing.PIN}")
            assertEquals(
                "Mac",
                harness.platform.clients
                    .last()
                    .name,
            )
            harness.pairing.lockedOut = true
            assertEquals(470, client.request("POST", "/pair-pin-start").status)
        }
    }

    @Test
    fun `stop closes connections, unpublishes and frees the port`() {
        val client = harness.connect()
        assertEquals(200, client.request("OPTIONS", "*").status)
        val port = harness.port
        harness.receiver.stop()
        assertTrue(client.closedByReceiver())
        assertEquals(0, harness.receiver.controlPort)
        assertTrue(harness.platform.unpublished >= 1)
        ServerSocket(port).close()
        harness.receiver.stop()
        client.close()
    }

    @Test
    fun `a port in use fails start`() {
        ServerSocket(0).use { taken ->
            val config =
                ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig(
                    name = "X",
                    deviceId = ByteArray(6),
                    identitySeed = ByteArray(32),
                    display =
                        ai.aneesiqbal.rozan.airplay.receiver
                            .DisplayInfo(1920, 1080, 60),
                    controlPort = taken.localPort,
                )
            val receiver =
                ai.aneesiqbal.rozan.airplay.receiver.AirPlayReceiver(
                    config,
                    RecordingPlatform(),
                    { FakeFairPlay() },
                    ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog.NONE,
                    ReceiverParts(FakeMedia(), { _, _, display, changed -> FakePairing(display, changed) }),
                )
            assertFailsWith<java.io.IOException> { receiver.start() }
            assertEquals(0, receiver.controlPort)
        }
    }

    @Test
    fun `logs never contain the client address`() {
        harness.connect().use { client -> client.request("GET", "/info") }
        harness.receiver.disconnectAll()
        val address = InetAddress.getLoopbackAddress().hostAddress
        assertTrue(harness.logs.isNotEmpty())
        assertTrue(harness.logs.none { it.contains(address) }, harness.logs.toString())
    }
}
