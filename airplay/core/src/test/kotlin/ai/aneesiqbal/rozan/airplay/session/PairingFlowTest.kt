package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.pairing.DefaultPairingService
import ai.aneesiqbal.rozan.airplay.pairing.DigestClient
import ai.aneesiqbal.rozan.airplay.pairing.PairVerifyClient
import ai.aneesiqbal.rozan.airplay.pairing.PinPairingClient
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.toHex
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The control layer with the real pairing service (02) over sockets: the Mode P PIN flow across two
 * connections, the Digest fallback, wrong codes and lockout. FairPlay and media are fakes.
 */
class PairingFlowTest {
    private val clock = AtomicLong(1_000_000_000L)
    private val uri = TestClient.SESSION_URI
    private val fpSetup = byteArrayOf(0x46, 0x50, 0x4C, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0)

    private fun harness(mode: PinMode) =
        ReceiverHarness(
            mode = mode,
            limits = ControlLimits(housekeepingIntervalMillis = 20),
            clock = clock::get,
            realPairing = { m, seed, display, changed, time -> DefaultPairingService(m, seed, display, changed, time) },
        )

    private val sessionSetup =
        buildPlistDict {
            put("ekey", ByteArray(72) { 0x46 })
            put("eiv", ByteArray(16) { 7 })
            put("timingPort", 60373)
            put("deviceID", "AA:BB:CC:DD:EE:FF")
            put("name", "Anna's iPhone")
        }

    private fun fairPlay(client: TestClient) {
        assertEquals(200, client.request("POST", "/fp-setup", fpSetup).status)
        assertEquals(200, client.request("POST", "/fp-setup", ByteArray(164) { 1 }).status)
    }

    /**
     * FairPlay, then three wrong Digest answers on [client] (a code takes four). Returns true when the
     * address turns out to be locked out on the way: a plain 401, no challenge and no code (02 §9.2).
     */
    private fun answerWrongThrice(client: TestClient): Boolean {
        fairPlay(client)
        var reply = client.setup(sessionSetup)
        repeat(3) {
            val header = reply.headers["WWW-Authenticate"]
            if (header == null) {
                assertEquals(401, reply.status)
                return true
            }
            val bad = DigestClient.authorization("abcd", DigestClient.nonce(header), uri)
            reply = client.setup(sessionSetup, listOf("Authorization" to bad))
            assertEquals(401, reply.status)
        }
        return false
    }

    private fun pinFrom(harness: ReceiverHarness): String =
        harness.platform.awaitEvent { it.startsWith("pin ") }.removePrefix("pin ")

    @Test
    fun `Mode P - PIN on one connection, pairing on the next, then an unchallenged session`() {
        harness(PinMode.PIN_PAIRING).use { h ->
            val sender = Ed25519KeyPair.generate()
            val first = h.platform.awaitPublish()
            val receiverKey =
                h.connect().use { a ->
                    val key = assertNotNull(plistBody(a.request("GET", "/info")).data("pk"))
                    assertTrue(first[0].txt.contains("pk" to key.toHex()))
                    assertEquals(200, a.request("POST", "/pair-pin-start").status)
                    key
                }
            val pin = pinFrom(h)
            assertTrue(Regex("[0-9]{4}").matches(pin))
            h.connect().use { b ->
                val pairing = PinPairingClient(pin, identity = sender)
                val r1 = b.request("POST", "/pair-setup-pin", pairing.step1(), ContentTypes.BPLIST)
                val r2 = b.request("POST", "/pair-setup-pin", pairing.step2(r1.body), ContentTypes.BPLIST)
                val r3 = b.request("POST", "/pair-setup-pin", pairing.step3(r2.body), ContentTypes.BPLIST)
                assertContentEquals(receiverKey, pairing.receiverKey(r3.body))
                h.platform.awaitEvent { it == "pinFinished ${PinResult.ACCEPTED}" }
                val verify = PairVerifyClient(sender)
                val v1 = b.request("POST", "/pair-verify", verify.step1(), ContentTypes.OCTET_STREAM)
                val v2 =
                    b.request(
                        "POST",
                        "/pair-verify",
                        verify.step2(v1.body, receiverKey),
                        ContentTypes.OCTET_STREAM,
                    )
                assertEquals(200, v2.status)
                fairPlay(b)
                // PIN-paired and verified: no Digest challenge (02 §8.2 rule 4).
                assertEquals(200, b.setup(sessionSetup).status)
                val expected = Crypto.sha512(FakeFairPlay.KEY, verify.sharedSecret!!).copyOf(16)
                assertContentEquals(expected, h.media.single().sessionKey)
                assertEquals(200, b.request("TEARDOWN", uri).status)
            }
            // The session ended: after the post-pairing grace the identity rotates and is republished.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(11))
            var rotated = h.platform.awaitPublish()
            while (rotated[0].txt.contains("pk" to receiverKey.toHex())) rotated = h.platform.awaitPublish()
            h.connect().use { c ->
                val key = assertNotNull(plistBody(c.request("GET", "/info")).data("pk"))
                assertTrue(!key.contentEquals(receiverKey))
                assertTrue(rotated[0].txt.contains("pk" to key.toHex()))
                assertTrue(rotated[1].txt.contains("pk" to key.toHex()))
                // The old key no longer verifies: pair-verify with the remembered pairing is refused.
                val stale = PairVerifyClient(sender)
                assertEquals(470, c.request("POST", "/pair-verify", stale.step1(), ContentTypes.OCTET_STREAM).status)
            }
        }
    }

    @Test
    fun `Mode P - a sender that skips PIN pairing gets the Digest code`() {
        harness(PinMode.PIN_PAIRING).use { h -> digestFlow(h) }
    }

    @Test
    fun `Mode D - Digest code per connection`() {
        harness(PinMode.DIGEST).use { h ->
            digestFlow(h)
            // The next connection gets a new challenge and a new code: nothing is remembered. (Codes for
            // one address are at least 5 s apart, 02 §9.1.)
            clock.addAndGet(TimeUnit.SECONDS.toNanos(6))
            h.connect().use { again ->
                fairPlay(again)
                val challenge = again.setup(sessionSetup)
                assertEquals(401, challenge.status)
                assertNotNull(challenge.headers["WWW-Authenticate"])
            }
        }
    }

    private fun digestFlow(h: ReceiverHarness) {
        h.connect().use { client ->
            fairPlay(client)
            // Media before authentication is refused.
            val mirror =
                buildPlistDict {
                    put("type", 110)
                    put("streamConnectionID", 1)
                }
            val streams = buildPlistDict { put("streams", PlistArray(listOf(mirror))) }
            assertEquals(470, client.setup(streams).status)
            val challenge = client.setup(sessionSetup)
            assertEquals(401, challenge.status)
            val nonce = DigestClient.nonce(challenge.headers["WWW-Authenticate"]!!)
            val code = pinFrom(h)
            val wrong = DigestClient.authorization(if (code == "0000") "1111" else "0000", nonce, uri)
            assertEquals(401, client.setup(sessionSetup, listOf("Authorization" to wrong)).status)
            val retry = client.setup(sessionSetup)
            val fresh = DigestClient.nonce(retry.headers["WWW-Authenticate"]!!)
            val right = DigestClient.authorization(code, fresh, uri)
            assertEquals(200, client.setup(sessionSetup, listOf("Authorization" to right)).status)
            h.platform.awaitEvent { it == "pinFinished ${PinResult.ACCEPTED}" }
            assertContentEquals(FakeFairPlay.KEY, h.media.single().sessionKey)
            assertEquals(200, client.request("RECORD", uri).status)
        }
    }

    @Test
    fun `while one sender's code is on screen another address gets 409 and no code`() {
        val v6 = InetAddress.getByName("::1")
        harness(PinMode.PIN_PAIRING).use { h ->
            assumeTrue(runCatching { h.connect(v6).close() }.isSuccess, "IPv6 loopback is not available")
            h.connect().use { a -> assertEquals(200, a.request("POST", "/pair-pin-start").status) }
            pinFrom(h)
            h.connect(v6).use { other ->
                assertEquals(409, other.request("POST", "/pair-pin-start").status)
                assertTrue(other.closedByReceiver())
            }
            h.connect(v6).use { other ->
                fairPlay(other)
                assertEquals(409, other.setup(sessionSetup).status, "no Digest prompt either")
                assertTrue(other.closedByReceiver())
            }
            h.receiver.stop()
            assertTrue(h.platform.eventLog.none { it.startsWith("pin ") }, "no code after the first")
        }
    }

    @Test
    fun `wrong PIN proofs are refused and close the connection`() {
        harness(PinMode.PIN_PAIRING).use { h ->
            h.connect().use { a -> assertEquals(200, a.request("POST", "/pair-pin-start").status) }
            val pin = pinFrom(h)
            val wrong = if (pin == "0000") "1111" else "0000"
            h.connect().use { b ->
                val pairing = PinPairingClient(wrong)
                val r1 = b.request("POST", "/pair-setup-pin", pairing.step1(), ContentTypes.BPLIST)
                assertEquals(200, r1.status)
                val r2 = b.request("POST", "/pair-setup-pin", pairing.step2(r1.body), ContentTypes.BPLIST)
                assertEquals(470, r2.status)
                assertTrue(b.closedByReceiver())
            }
            assertTrue(h.media.sessions.isEmpty())
        }
    }

    @Test
    fun `repeated failures lock the address out`() {
        harness(PinMode.DIGEST).use { h ->
            var lockedOut = false
            // Three wrong answers per connection (a Digest code takes four), so that the lockout comes
            // before the address's budget of three unpaired codes runs out (02 §9.1).
            for (attempt in 1..3) {
                lockedOut = h.connect().use { client -> answerWrongThrice(client) }
                if (lockedOut) break
                // Stay clear of the per-address code throttle (02 §9.1).
                clock.addAndGet(TimeUnit.SECONDS.toNanos(6))
            }
            assertTrue(lockedOut, "no lockout after repeated failures")
            h.connect().use { client -> assertEquals(470, client.request("POST", "/pair-pin-start").status) }
            assertTrue(h.media.sessions.isEmpty())
        }
    }
}
