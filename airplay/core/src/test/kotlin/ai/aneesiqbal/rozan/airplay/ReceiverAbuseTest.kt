package ai.aneesiqbal.rozan.airplay

import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.pairing.Addresses
import ai.aneesiqbal.rozan.airplay.pairing.DefaultPairingService
import ai.aneesiqbal.rozan.airplay.pairing.DigestClient
import ai.aneesiqbal.rozan.airplay.pairing.PairVerifyClient
import ai.aneesiqbal.rozan.airplay.pairing.PairingFixture
import ai.aneesiqbal.rozan.airplay.pairing.PairingReply
import ai.aneesiqbal.rozan.airplay.pairing.PinPairingClient
import ai.aneesiqbal.rozan.airplay.pairing.RecordingDisplay
import ai.aneesiqbal.rozan.airplay.pairing.SetupAuthorization
import ai.aneesiqbal.rozan.airplay.pairing.assertPairingError
import ai.aneesiqbal.rozan.airplay.pairing.pinPair
import ai.aneesiqbal.rozan.airplay.pairing.verify
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.session.ContentTypes
import ai.aneesiqbal.rozan.airplay.session.ControlLimits
import ai.aneesiqbal.rozan.airplay.session.ReceiverHarness
import ai.aneesiqbal.rozan.airplay.session.TestClient
import ai.aneesiqbal.rozan.airplay.session.plistBody
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Abuse of the network-facing receiver (`:airplay:core`) by hosts on the LAN, each test an attack from a
 * security review and the property that defeats it. The threat model is SECURITY.md: anyone on the LAN can
 * send bytes; showing content must need the on-screen code, and the receiver must stay usable. Each test
 * starts with a baseline that shows the attack would be noticed.
 *
 * Only loopback sockets are opened. Clocks are fakes wherever the pairing layer allows it.
 */
class ReceiverAbuseTest {
    // ---- Mode P (the production default) forgets a sender however busy others keep it ------------------

    /**
     * 02 §8.2 rule 5 rotates the identity (and clears the session trust) after 60 s without activity.
     * Requests that authenticate nothing are not activity, so a second host that POSTs 32 bytes to
     * `/pair-setup` every 50 s cannot keep a sender that PIN-paired once trusted: after the hour that
     * sender's pair-verify is refused and casting needs a new code.
     */
    @Test
    fun `Mode P - pairing attempts from another host do not keep an old pairing valid`() {
        // Baseline: with no other traffic the identity rotates after 60 s idle and the old pairing is refused.
        val quiet = AtomicLong(1_000_000_000L)
        realPairingHarness(PinMode.PIN_PAIRING, quiet).use { h ->
            val guest = Ed25519KeyPair.generate()
            val receiverKey = pinPairOverSockets(h, guest)
            quiet.addAndGet(TimeUnit.SECONDS.toNanos(61))
            while (h.platform
                    .awaitPublish()[0]
                    .txt
                    .contains("pk" to receiverKey.toHex())
            ) {
                continue
            }
            h.connect().use { c ->
                val v1 = c.request("POST", "/pair-verify", PairVerifyClient(guest).step1(), ContentTypes.OCTET_STREAM)
                assertEquals(470, v1.status, "baseline: after an idle rotation the old pairing is refused")
            }
        }

        val clock = AtomicLong(1_000_000_000L)
        realPairingHarness(PinMode.PIN_PAIRING, clock).use { h ->
            val guest = Ed25519KeyPair.generate()
            // The guest is in the room once: reads the code off the TV, PIN-pairs, then leaves without casting.
            val receiverKey = pinPairOverSockets(h, guest)
            // For an hour another LAN host (no pairing, no code) sends one unauthenticated request every 50 s.
            h.connect().use { other ->
                repeat(HOUR_IN_50_S_STEPS) {
                    clock.addAndGet(TimeUnit.SECONDS.toNanos(50))
                    val reply = other.request("POST", "/pair-setup", ByteArray(32) { 9 }, ContentTypes.OCTET_STREAM)
                    assertEquals(200, reply.status)
                    // Let housekeeping (every 20 ms) run its rotation check at this clock value.
                    Thread.sleep(HOUSEKEEPING_WAIT_MILLIS)
                }
            }
            // The guest comes back, out of sight of the TV, and casts with the remembered pairing.
            val codesBefore = h.platform.eventLog.count { it.startsWith("pin ") }
            val setupStatus =
                h.connect().use { c ->
                    val verify = PairVerifyClient(guest)
                    val v1 = c.request("POST", "/pair-verify", verify.step1(), ContentTypes.OCTET_STREAM)
                    if (v1.status != 200) return@use v1.status
                    val v2 =
                        c.request(
                            "POST",
                            "/pair-verify",
                            verify.step2(v1.body, receiverKey),
                            ContentTypes.OCTET_STREAM,
                        )
                    if (v2.status != 200) return@use v2.status
                    fairPlay(c)
                    c.setup(SESSION_SETUP).status
                }
            val codesShown = h.platform.eventLog.count { it.startsWith("pin ") } - codesBefore
            assertNotEquals(
                200,
                setupStatus,
                "Mode P: a sender that PIN-paired an hour ago opened a session without a code ($codesShown codes " +
                    "shown, ${h.media.sessions.size} media sessions): another host's unauthenticated " +
                    "POST /pair-setup every 50 s kept the identity from rotating",
            )
        }
    }

    /**
     * When a PIN-paired session ends, Mode P rotates the identity, but not while a PIN code is live, and
     * another host can keep one live by calling `pair-pin-start` every 100 s (inside every throttle, never
     * a wrong guess). The rotation may wait; the session's sender is forgotten at the end of the session
     * all the same, so its pair-verify is refused.
     */
    @Test
    fun `Mode P - another host's live code does not keep a finished session's sender trusted`() {
        // Baseline: without another host's code, the end of the session rotates the identity at once.
        PairingFixture(PinMode.PIN_PAIRING).let { quiet ->
            val guest = Ed25519KeyPair.generate()
            val receiverKey = pinPairOnce(quiet, guest)
            quiet.clock.advanceSeconds(61)
            quiet.service.sessionEnded()
            assertEquals(1, quiet.rotations, "baseline: the session end rotates")
            val back = quiet.service.openConnection(Addresses.ipv4(77))
            assertTrue(runCatching { back.verify(PairVerifyClient(guest), receiverKey) }.isFailure, "baseline")
        }

        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val service = fixture.service
        val guest = Ed25519KeyPair.generate()
        val receiverKey = pinPairOnce(fixture, guest)
        repeat(60) {
            fixture.clock.advanceSeconds(1)
            service.tick(sessionActive = true)
        }
        // While the guest's session runs, another host asks for a code; it keeps asking every 100 s.
        assertIs<PairingReply.Ok>(service.pinStart(Addresses.other, "Living Room"))
        fixture.clock.advanceSeconds(1)
        service.sessionEnded()
        for (second in 1..SECONDS_PER_HOUR) {
            fixture.clock.advanceSeconds(1)
            if (second % 100 == 0) service.pinStart(Addresses.other, "Living Room")
            service.tick(sessionActive = false)
        }
        val back = service.openConnection(Addresses.ipv4(77))
        val verified = runCatching { back.verify(PairVerifyClient(guest), receiverKey) }.isSuccess
        val setup = back.authorizeSetup("SETUP", TestClient.SESSION_URI, null, "AA:BB:CC:DD:EE:FF", "Guest")
        assertTrue(
            !verified && setup !== SetupAuthorization.Authorized,
            "Mode P: an hour after its session ended the guest's key still pair-verifies (verified=$verified, " +
                "SETUP authorized without a code=${setup === SetupAuthorization.Authorized}); rotations=" +
                "${fixture.rotations}, codes shown to the other host=" +
                fixture.display.events.count { it is RecordingDisplay.Shown && it.client == Addresses.other },
        )
    }

    // ---- connection slots go back from peers that never finish a request ------------------------------

    /**
     * The idle timeout is `Socket.soTimeout`, which bounds one `read()`, not a request, so a peer that
     * sends one header byte per (timeout - ε) could hold its slot for weeks (a 2 KiB request line plus
     * 35 KiB of headers at one byte per 59 s). With the default caps (12 total, 4 per address) three
     * addresses, or one IPv6 host with many, would lock every sender out. The request deadline, the
     * authentication deadline and replacement of unauthenticated connections when the table is full give
     * the slots back. Shrunk caps here; the mechanism is the same.
     */
    @Test
    fun `peers that never finish a request lose their connection slots`() {
        val idleMillis = 400
        val limits = ControlLimits(maxConnections = 2, maxConnectionsPerAddress = 2, idleTimeoutMillis = idleMillis)
        // Baseline: peers that go silent in the middle of a request are closed after the idle timeout.
        ReceiverHarness(limits = limits).use { h ->
            val silent = List(2) { partialRequest(h.port) }
            Thread.sleep(idleMillis * 3L)
            assertEquals(0, silent.count(::isOpen), "baseline: silent peers are closed")
            assertEquals(200, h.connect().use { it.request("OPTIONS", "*").status }, "baseline: slots are free")
            silent.forEach(Socket::close)
        }

        ReceiverHarness(limits = limits).use { h ->
            val holders = List(2) { partialRequest(h.port) }
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleMillis * 5L)
            while (System.nanoTime() < end) {
                for (holder in holders) runCatching { holder.getOutputStream().write('a'.code) }
                Thread.sleep(DRIBBLE_INTERVAL_MILLIS)
            }
            val stillOpen = holders.count(::isOpen)
            val served = runCatching { h.connect().use { it.request("OPTIONS", "*").status } }.getOrNull()
            holders.forEach(Socket::close)
            assertEquals(
                200,
                served,
                "after ${idleMillis * 5} ms (5x the idle timeout) $stillOpen peers that never completed a " +
                    "request still hold every slot, and a new sender was refused",
            )
        }
    }

    // ---- one code buys one session, however long the connection is kept open --------------------------

    /**
     * Whoever typed one code could keep its TCP connection open (from a phone in a pocket, a neighbour's
     * flat on the same Wi-Fi) and, if the authentication outlived the session, start new sessions on it
     * later without the screen showing anything. A whole-session TEARDOWN ends the connection's
     * authentication, so the next session SETUP is challenged for a new code.
     */
    @Test
    fun `a whole-session TEARDOWN ends the connection's authentication`() {
        val clock = AtomicLong(1_000_000_000L)
        realPairingHarness(PinMode.DIGEST, clock, idleTimeoutMillis = 300).use { h ->
            h.connect().use { client ->
                fairPlay(client)
                val challenge = client.setup(SESSION_SETUP)
                assertEquals(401, challenge.status)
                val nonce = DigestClient.nonce(assertNotNull(challenge.headers["WWW-Authenticate"]))
                val answer = DigestClient.authorization(pinFrom(h), nonce, TestClient.SESSION_URI)
                assertEquals(200, client.setup(SESSION_SETUP, listOf("Authorization" to answer)).status)
                assertEquals(200, client.request("TEARDOWN", TestClient.SESSION_URI).status)
                // Kept open with a request every 200 ms for 5x the idle timeout; an hour passes on the clocks.
                repeat(8) {
                    Thread.sleep(200)
                    assertEquals(200, client.request("OPTIONS", "*").status)
                }
                clock.addAndGet(TimeUnit.HOURS.toNanos(1))
                val again = client.setup(SESSION_SETUP)
                assertNotEquals(
                    200,
                    again.status,
                    "a new session opened an hour after a whole-session TEARDOWN with no new code " +
                        "(media sessions opened: ${h.media.sessions.size}, codes shown after the first: " +
                        "${h.platform.eventLog.count { it.startsWith("pin ") }})",
                )
            }
        }
    }

    // ---- no one address owns the global code budget -----------------------------------------------------

    /**
     * Codes are limited to 6 per minute globally (02 §9.1), and only wrong guesses count towards a
     * lockout, so one address that asked for a code the moment the 60 s window freed a slot would take
     * the whole budget without being penalised: every other sender would get 470 from `pair-pin-start`
     * (or a code-less 401 for Digest), with the attacker's prompt on screen. The per-address budget of
     * unpaired codes (3 in 10 minutes) stops it, and the owner gets a code.
     */
    @Test
    fun `one unpaired address cannot take the code budget from other senders`() {
        // Baseline: without the attacker the owner gets a code at once.
        PairingFixture(PinMode.PIN_PAIRING).let { quiet ->
            assertIs<PairingReply.Ok>(quiet.service.pinStart(Addresses.sender, "Owner's Mac"))
            assertEquals(1, quiet.display.shownCodes.size, "baseline")
        }

        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val service = fixture.service
        val attacker = Addresses.other
        val owner = Addresses.sender
        var ownerAttempts = 0
        var ownerRefused = 0
        // 30 minutes in 100 ms steps: the attacker sends pair-pin-start every 100 ms (cheap, unauthenticated).
        for (step in 1..30 * 60 * 10) {
            fixture.clock.advanceMillis(100)
            service.pinStart(attacker, "Living Room")
            // From minute 2 on, the owner tries every 7 s.
            if (step > 1200 && step % 70 == 35) {
                ownerAttempts++
                if (service.pinStart(owner, "Owner's Mac") is PairingReply.Error) ownerRefused++
            }
        }
        val shown = fixture.display.events.filterIsInstance<RecordingDisplay.Shown>()
        val attackerCodes = shown.count { it.client == attacker }
        val ownerCodes = shown.count { it.client == owner }
        assertTrue(
            ownerCodes > 0,
            "the owner was refused $ownerRefused of $ownerAttempts pair-pin-start attempts over 28 minutes and " +
                "never got a code; the attacker was shown $attackerCodes codes and never locked out",
        )
    }

    // ---- an IPv6 lockout covers the host that earned it, not its prefix ---------------------------------

    /**
     * Every host on a link shares fe80::/64, and every host on a home LAN shares its SLAAC /64. Were
     * failures counted per /64, one host's six wrong guesses would lock all IPv6 senders on the network
     * out of pairing for 30 s, doubling to 10 min with each further failure. They are counted per address
     * (02 §9.2), with the global failure counter against hosts that rotate addresses.
     */
    @Test
    fun `six wrong guesses from one link-local host lock out only that host`() {
        // Baseline: on IPv4 the same failures lock out only the address that made them.
        val v4 = ownerReplyAfterSixFailures(Addresses.ipv4(1), Addresses.ipv4(2))
        assertIs<PairingReply.Ok>(v4.first, "baseline: another IPv4 address still gets a code")

        val attacker = InetAddress.getByName("fe80::bad:1")
        val owner = InetAddress.getByName("fe80::1c2:3ff:fe04:506")
        val (reply, results) = ownerReplyAfterSixFailures(attacker, owner)
        assertIs<PairingReply.Ok>(
            reply,
            "a different link-local host (${owner.hostAddress}) is refused a code because ${attacker.hostAddress} " +
                "failed 6 times: results on screen $results",
        )
    }

    /** Six wrong PIN proofs from [attacker] (two codes), then `pair-pin-start` from [owner]: its reply. */
    private fun ownerReplyAfterSixFailures(
        attacker: InetAddress,
        owner: InetAddress,
    ): Pair<PairingReply, List<PinResult>> {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val service = fixture.service
        repeat(2) {
            assertIs<PairingReply.Ok>(service.pinStart(attacker, null))
            val code = fixture.display.lastCode
            repeat(3) {
                val connection = service.openConnection(attacker)
                val guess = PinPairingClient(pin = wrongPin(code))
                val step1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(guess.step1()))
                assertPairingError(470, true, connection.pairSetupPin(guess.step2(step1.body)))
            }
            fixture.clock.advanceSeconds(6)
        }
        return service.pinStart(owner, "Owner's Mac") to fixture.display.results
    }

    // ---- parallel step-2 proofs share one code's three checks ---------------------------------------------

    /**
     * The SRP check runs outside the pairing lock. Were a failure counted only after it, proofs sent on
     * parallel connections would all pass the liveness check first, and a code that allows 3 guesses
     * would get one per concurrent connection (4 per address with the default caps): 3 wrong guesses and
     * the right one would leave the TV showing REJECTED while the fourth completes PIN pairing. Each
     * proof check is reserved against the code before it runs, and step 3 checks the code again.
     * (Timing-based; retried so a lucky scheduling cannot hide a race.)
     */
    @Test
    fun `parallel step-2 proofs share the code's three checks`() {
        repeat(RACE_TRIALS) {
            val fixture = PairingFixture(PinMode.PIN_PAIRING)
            val service = fixture.service
            assertIs<PairingReply.Ok>(service.pinStart(Addresses.sender, null))
            val code = fixture.display.lastCode
            val identity = Ed25519KeyPair.generate()
            val clients =
                List(3) { PinPairingClient(pin = wrongPin(code)) } + PinPairingClient(code, identity = identity)
            val connections = List(4) { service.openConnection(Addresses.sender) }
            val bodies =
                clients.indices.map { i ->
                    clients[i].step2(assertIs<PairingReply.Ok>(connections[i].pairSetupPin(clients[i].step1())).body)
                }
            val replies = arrayOfNulls<PairingReply>(4)
            val gate = CyclicBarrier(4)
            val threads =
                List(4) { i ->
                    Thread {
                        gate.await()
                        replies[i] = connections[i].pairSetupPin(bodies[i])
                    }
                }
            threads.forEach(Thread::start)
            threads.forEach { thread -> thread.join(RACE_JOIN_MILLIS) }
            val right = replies[3]
            if (PinResult.REJECTED in fixture.display.results && right is PairingReply.Ok) {
                val step3 = assertIs<PairingReply.Ok>(connections[3].pairSetupPin(clients[3].step3(right.body)))
                connections[3].verify(PairVerifyClient(identity), clients[3].receiverKey(step3.body))
                fail(
                    "4 SRP proofs were checked against one code (limit 3): the screen shows " +
                        "${fixture.display.results}, yet the 4th parallel proof was accepted and the connection " +
                        "is authenticated=${connections[3].isAuthenticated}",
                )
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private fun realPairingHarness(
        mode: PinMode,
        clock: AtomicLong,
        idleTimeoutMillis: Int = 60_000,
    ) = ReceiverHarness(
        mode = mode,
        limits = ControlLimits(idleTimeoutMillis = idleTimeoutMillis, housekeepingIntervalMillis = 20),
        clock = clock::get,
        realPairing = { m, seed, display, changed, time -> DefaultPairingService(m, seed, display, changed, time) },
    )

    private fun fairPlay(client: TestClient) {
        assertEquals(200, client.request("POST", "/fp-setup", FP_SETUP).status)
        assertEquals(200, client.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
    }

    private fun pinFrom(h: ReceiverHarness): String =
        h.platform.awaitEvent { it.startsWith("pin ") }.removePrefix("pin ")

    /** `GET /info`, `pair-pin-start` and the three PIN steps over sockets for [guest]; returns the receiver key. */
    private fun pinPairOverSockets(
        h: ReceiverHarness,
        guest: Ed25519KeyPair,
    ): ByteArray {
        val receiverKey =
            h.connect().use { a ->
                val key = assertNotNull(plistBody(a.request("GET", "/info")).data("pk"))
                assertEquals(200, a.request("POST", "/pair-pin-start").status)
                key
            }
        val pin = pinFrom(h)
        h.connect().use { b ->
            val pairing = PinPairingClient(pin, identity = guest)
            val r1 = b.request("POST", "/pair-setup-pin", pairing.step1(), ContentTypes.BPLIST)
            val r2 = b.request("POST", "/pair-setup-pin", pairing.step2(r1.body), ContentTypes.BPLIST)
            val r3 = b.request("POST", "/pair-setup-pin", pairing.step3(r2.body), ContentTypes.BPLIST)
            assertEquals(receiverKey.toHex(), pairing.receiverKey(r3.body).toHex())
            h.platform.awaitEvent { it == "pinFinished ${PinResult.ACCEPTED}" }
        }
        return receiverKey
    }

    /** A loopback connection to [port] that has sent the start of a request and nothing more. */
    private fun partialRequest(port: Int): Socket =
        Socket().apply {
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
            soTimeout = PROBE_TIMEOUT_MILLIS
            getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 0\r\nX-Slow: ".toByteArray(Charsets.US_ASCII))
        }

    /** `pair-pin-start`, the three PIN steps and pair-verify for [guest]; returns the receiver key. */
    private fun pinPairOnce(
        fixture: PairingFixture,
        guest: Ed25519KeyPair,
    ): ByteArray {
        assertIs<PairingReply.Ok>(fixture.service.pinStart(Addresses.sender, "Guest phone"))
        val connection = fixture.service.openConnection(Addresses.sender)
        val receiverKey = connection.pinPair(PinPairingClient(fixture.display.lastCode, identity = guest))
        connection.verify(PairVerifyClient(guest), receiverKey)
        assertTrue(connection.isAuthenticated)
        connection.close()
        return receiverKey
    }

    private fun wrongPin(code: String): String = ((code.toInt() + 1) % 10_000).toString().padStart(4, '0')

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

    private companion object {
        const val SECONDS_PER_HOUR = 3_600
        const val HOUR_IN_50_S_STEPS = 72
        const val HOUSEKEEPING_WAIT_MILLIS = 50L
        const val CONNECT_TIMEOUT_MILLIS = 2_000
        const val PROBE_TIMEOUT_MILLIS = 50
        const val DRIBBLE_INTERVAL_MILLIS = 100L
        const val RACE_TRIALS = 5
        const val RACE_JOIN_MILLIS = 10_000L

        val FP_SETUP = byteArrayOf(0x46, 0x50, 0x4C, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0)

        val SESSION_SETUP: PlistDict =
            buildPlistDict {
                put("ekey", ByteArray(72) { 0x46 })
                put("eiv", ByteArray(16) { 7 })
                put("timingPort", 60373)
                put("deviceID", "AA:BB:CC:DD:EE:FF")
                put("name", "Guest phone")
            }
    }
}
