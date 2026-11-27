package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The connection deadlines and the replacement of unauthenticated connections when the table is full
 * (01 §3.1, 02 §9.1), over loopback sockets with shrunk limits and the fake pairing layer.
 */
class ConnectionLimitsTest {
    private val v6: InetAddress = InetAddress.getByName("::1")

    /** "Authenticates" the connection through [FakePairing]: a pair-verify body starting 0x01. */
    private fun TestClient.authenticate() {
        assertEquals(200, request("POST", "/pair-verify", byteArrayOf(1) + ByteArray(67)).status)
    }

    /** A raw connection that has sent the start of a request and nothing more. */
    private fun partialRequest(port: Int): Socket =
        Socket().apply {
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MILLIS)
            soTimeout = PROBE_MILLIS
            getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 0\r\nX-Slow: ".toByteArray(Charsets.US_ASCII))
        }

    /** Whether the receiver still holds [socket] open: a read times out instead of ending or failing. */
    private fun isOpen(socket: Socket): Boolean =
        try {
            socket.getInputStream().read()
            false
        } catch (ignored: SocketTimeoutException) {
            true
        } catch (ignored: IOException) {
            false
        }

    /** Writes one byte every [DRIBBLE_MILLIS] on each of [sockets] for [millis]. */
    private fun dribble(
        sockets: List<Socket>,
        millis: Long,
    ) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < end) {
            for (socket in sockets) runCatching { socket.getOutputStream().write('a'.code) }
            Thread.sleep(DRIBBLE_MILLIS)
        }
    }

    @Test
    fun `an unauthenticated request must arrive whole within the request timeout`() {
        val limits = ControlLimits(requestTimeoutMillis = 300, housekeepingIntervalMillis = 20)
        ReceiverHarness(limits = limits).use { h ->
            val slow = partialRequest(h.port)
            dribble(listOf(slow), 1_000)
            assertFalse(isOpen(slow), "a request still unfinished after 1 s is closed")
            slow.close()
            // Requests that arrive whole are fine however long the gaps between them.
            h.connect().use { client ->
                repeat(3) {
                    assertEquals(200, client.request("OPTIONS", "*").status)
                    Thread.sleep(200)
                }
            }
        }
    }

    @Test
    fun `an authenticated connection has no request timeout`() {
        val limits = ControlLimits(requestTimeoutMillis = 300, housekeepingIntervalMillis = 20)
        ReceiverHarness(limits = limits).use { h ->
            h.connect().use { client ->
                client.authenticate()
                client.send("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nX-Slow: ".toByteArray(Charsets.US_ASCII))
                Thread.sleep(600)
                client.send("v\r\n\r\n".toByteArray(Charsets.US_ASCII))
                assertEquals(200, client.response()?.status)
            }
        }
    }

    @Test
    fun `a connection must authenticate within the auth timeout`() {
        val limits = ControlLimits(authTimeoutMillis = 500, housekeepingIntervalMillis = 20)
        ReceiverHarness(limits = limits).use { h ->
            h.connect().use { stranger ->
                h.connect().use { sender ->
                    sender.authenticate()
                    val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000)
                    while (System.nanoTime() < end) {
                        assertEquals(200, sender.request("OPTIONS", "*").status, "authenticated: kept")
                        Thread.sleep(100)
                    }
                }
                assertTrue(stranger.closedByReceiver(), "busy but never authenticated: closed")
            }
        }
    }

    @Test
    fun `an authenticated connection must start a session within the session start timeout`() {
        val limits = ControlLimits(sessionStartTimeoutMillis = 300, housekeepingIntervalMillis = 20)
        ReceiverHarness(limits = limits).use { h ->
            h.connect().use { idle ->
                h.connect().use { streaming ->
                    idle.authenticate()
                    streaming.authenticate()
                    assertEquals(200, streaming.request("POST", "/fp-setup", FP_SETUP).status)
                    assertEquals(200, streaming.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
                    assertEquals(200, streaming.setup(SESSION_SETUP).status)
                    val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000)
                    while (System.nanoTime() < end) {
                        assertEquals(200, streaming.request("POST", "/feedback").status, "in a session: kept")
                        Thread.sleep(100)
                    }
                    assertTrue(idle.closedByReceiver(), "authenticated without a session: closed")
                    // After a whole-session TEARDOWN it is a connection that has to authenticate again.
                    assertEquals(200, streaming.request("TEARDOWN", TestClient.SESSION_URI).status)
                    Thread.sleep(600)
                    assertEquals(200, streaming.request("OPTIONS", "*").status)
                }
            }
        }
    }

    @Test
    fun `authenticated connections are never replaced`() {
        val limits = ControlLimits(maxConnections = 2, maxConnectionsPerAddress = 2)
        ReceiverHarness(limits = limits).use { h ->
            val held = List(2) { h.connect().apply { authenticate() } }
            try {
                h.connect().use { extra -> assertTrue(extra.closedByReceiver(), "nothing to replace: refused") }
                for (client in held) assertEquals(200, client.request("OPTIONS", "*").status)
            } finally {
                held.forEach(TestClient::close)
            }
        }
    }

    @Test
    fun `a full table replaces an unauthenticated connection of the address with the most`() {
        val limits = ControlLimits(maxConnections = 3, maxConnectionsPerAddress = 3)
        ReceiverHarness(limits = limits).use { h ->
            assumeTrue(runCatching { ServerSocket(0, 1, v6).close() }.isSuccess, "IPv6 loopback is not available")
            val v4 = List(2) { h.connect() }
            val other = h.connect(v6)
            try {
                for (client in v4 + other) assertEquals(200, client.request("OPTIONS", "*").status)
                h.connect(v6).use { newcomer -> assertEquals(200, newcomer.request("OPTIONS", "*").status) }
                assertTrue(v4[0].closedByReceiver(), "the oldest of the address holding the most slots")
                assertEquals(200, v4[1].request("OPTIONS", "*").status)
                assertEquals(200, other.request("OPTIONS", "*").status, "the other address keeps its slot")
            } finally {
                (v4 + other).forEach(TestClient::close)
            }
        }
    }

    @Test
    fun `peers that dribble a request cannot keep a sender out`() {
        val limits = ControlLimits(maxConnections = 2, maxConnectionsPerAddress = 2, housekeepingIntervalMillis = 20)
        ReceiverHarness(limits = limits).use { h ->
            val holders = List(2) { partialRequest(h.port) }
            try {
                dribble(holders, 200)
                h.connect().use { sender -> assertEquals(200, sender.request("OPTIONS", "*").status) }
            } finally {
                holders.forEach(Socket::close)
            }
        }
    }

    @Test
    fun `housekeeping runs only while a connection is open or pairing state expires`() {
        ReceiverHarness(limits = ControlLimits(housekeepingIntervalMillis = 20)).use { h ->
            Thread.sleep(IDLE_WAIT_MILLIS)
            assertEquals(0, h.pairing.ticks.get(), "an idle receiver never wakes for housekeeping")

            h.connect().use { client ->
                assertEquals(200, client.request("OPTIONS", "*").status)
                Thread.sleep(IDLE_WAIT_MILLIS)
                assertTrue(h.pairing.ticks.get() >= 5, "ticks while a connection is open: ${h.pairing.ticks.get()}")
            }
            awaitTicksStop(h)

            // Pairing state that expires keeps housekeeping going with no connection open, until it is gone.
            h.pairing.wantsTicks = true
            h.connect().use { assertEquals(200, it.request("OPTIONS", "*").status) }
            Thread.sleep(IDLE_WAIT_MILLIS)
            val running = h.pairing.ticks.get()
            Thread.sleep(IDLE_WAIT_MILLIS)
            assertTrue(h.pairing.ticks.get() >= running + 5, "ticks while pairing needs them")
            h.pairing.wantsTicks = false
            awaitTicksStop(h)
        }
    }

    /** Waits until housekeeping has stopped: no tick for a while after the connections closed. */
    private fun awaitTicksStop(h: ReceiverHarness) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var last = h.pairing.ticks.get()
        while (true) {
            Thread.sleep(IDLE_WAIT_MILLIS)
            val now = h.pairing.ticks.get()
            if (now == last) return
            assertTrue(System.nanoTime() < deadline, "housekeeping kept running with nothing to check")
            last = now
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 2_000
        const val PROBE_MILLIS = 50
        const val DRIBBLE_MILLIS = 100L

        /** Ten housekeeping intervals of the shrunk limits. */
        const val IDLE_WAIT_MILLIS = 200L

        val FP_SETUP = byteArrayOf(0x46, 0x50, 0x4C, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0)

        val SESSION_SETUP =
            buildPlistDict {
                put("ekey", ByteArray(72) { 0x46 })
                put("eiv", ByteArray(16) { 7 })
                put("timingPort", 60373)
            }
    }
}
