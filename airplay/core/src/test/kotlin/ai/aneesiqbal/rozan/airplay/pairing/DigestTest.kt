package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Mode D Digest gate on SETUP (02 §6) and its parser. */
class DigestTest {
    private val fixture = PairingFixture(PinMode.DIGEST)
    private val service = fixture.service
    private val display = fixture.display
    private val sender = Addresses.sender
    private val uri = "rtsp://192.168.1.20/2934859221"
    private val deviceId = "AA:BB:CC:DD:EE:FF"

    private fun ConnectionPairing.setup(
        authorization: String? = null,
        device: String? = deviceId,
    ): SetupAuthorization = authorizeSetup("SETUP", uri, authorization, device, "Den Mac")

    private fun ConnectionPairing.challenge(authorization: String? = null): String =
        DigestClient.nonce(assertIs<SetupAuthorization.Challenge>(setup(authorization)).wwwAuthenticate)

    private fun wrong(code: String): String = ((code.toInt() + 1) % 10_000).toString().padStart(4, '0')

    @Test
    fun `the challenge has exactly realm and a fresh 32-hex nonce, and shows a code`() {
        val connection = service.openConnection(sender)
        val challenge = assertIs<SetupAuthorization.Challenge>(connection.setup()).wwwAuthenticate
        assertTrue(Regex("Digest realm=\"raop\", nonce=\"[0-9a-f]{32}\"").matches(challenge), challenge)
        val shown = assertIs<RecordingDisplay.Shown>(display.events.single())
        assertEquals("Den Mac", shown.clientName)
        assertEquals(sender, shown.client)
        assertNotEquals(DigestClient.nonce(challenge), connection.challenge(), "each challenge has a new nonce")
        assertEquals(1, display.shownCodes.size, "a repeated challenge keeps the code")
    }

    @Test
    fun `a correct response without qop authorizes and consumes the code`() {
        val connection = service.openConnection(sender)
        val nonce = connection.challenge()
        val code = display.lastCode
        assertSame(SetupAuthorization.Authorized, connection.setup(DigestClient.authorization(code, nonce, uri)))
        assertEquals(listOf(PinResult.ACCEPTED), display.results)
        assertTrue(connection.isAuthenticated)
        assertSame(SetupAuthorization.Authorized, connection.setup(), "the connection stays authorized")

        // The next connection needs a new code.
        fixture.clock.advanceSeconds(6)
        val next = service.openConnection(sender)
        val nextNonce = next.challenge()
        assertEquals(2, display.shownCodes.size)
        assertIs<SetupAuthorization.Challenge>(next.setup(DigestClient.authorization(code, nextNonce, uri)))
    }

    @Test
    fun `a correct response with qop authorizes`() {
        val connection = service.openConnection(sender)
        val nonce = connection.challenge()
        val header = DigestClient.authorization(display.lastCode, nonce, uri, qop = "auth", cnonce = "abc123")
        assertSame(SetupAuthorization.Authorized, connection.setup(header))
    }

    @Test
    fun `the response is compared without regard to hex case, the username is taken verbatim`() {
        val connection = service.openConnection(sender)
        val nonce = connection.challenge()
        val code = display.lastCode
        val response = DigestClient.response(code, nonce, uri, username = "Some Mac").uppercase()
        val header =
            "Digest username=\"Some Mac\", realm=\"raop\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
        assertSame(SetupAuthorization.Authorized, connection.setup(header))
    }

    @Test
    fun `a wrong response re-challenges with the same code and the fourth makes a new one`() {
        val connection = service.openConnection(sender)
        var nonce = connection.challenge()
        val first = display.lastCode
        repeat(3) {
            fixture.clock.advanceSeconds(2)
            nonce = connection.challenge(DigestClient.authorization(wrong(first), nonce, uri))
            assertEquals(listOf(first), display.shownCodes, "failure ${it + 1} keeps the code")
            assertEquals(emptyList(), display.results)
        }
        nonce = connection.challenge(DigestClient.authorization(wrong(first), nonce, uri))
        assertEquals(listOf(PinResult.REJECTED), display.results)
        assertEquals(2, display.shownCodes.size)
        val second = display.lastCode
        // The used-up code no longer works (unless the new code happens to be the same digits).
        if (second != first) nonce = connection.challenge(DigestClient.authorization(first, nonce, uri))
        assertSame(SetupAuthorization.Authorized, connection.setup(DigestClient.authorization(second, nonce, uri)))
        assertEquals(listOf(PinResult.REJECTED, PinResult.ACCEPTED), display.results)
    }

    @Test
    fun `only the latest nonce is valid and stale ones are not counted`() {
        val connection = service.openConnection(sender)
        val old = connection.challenge()
        val code = display.lastCode
        val latest = connection.challenge()
        repeat(
            6,
        ) { assertIs<SetupAuthorization.Challenge>(connection.setup(DigestClient.authorization(code, old, uri))) }
        assertEquals(emptyList(), display.results, "stale nonces cost nothing and use nothing up")
        // The stale requests re-challenged, so the nonce moved on again.
        assertIs<SetupAuthorization.Challenge>(connection.setup(DigestClient.authorization(code, latest, uri)))
        val current = connection.challenge()
        assertSame(SetupAuthorization.Authorized, connection.setup(DigestClient.authorization(code, current, uri)))
    }

    @Test
    fun `the nonce belongs to its connection`() {
        val one = service.openConnection(sender)
        val nonce = one.challenge()
        val code = display.lastCode
        val two = service.openConnection(sender)
        assertIs<SetupAuthorization.Challenge>(two.setup(DigestClient.authorization(code, nonce, uri)))
        assertFalse(two.isAuthenticated)
    }

    @Test
    fun `realm must be raop`() {
        val connection = service.openConnection(sender)
        val nonce = connection.challenge()
        val header = DigestClient.authorization(display.lastCode, nonce, uri, realm = "other")
        assertIs<SetupAuthorization.Challenge>(connection.setup(header))
        assertEquals(emptyList(), display.results)
    }

    @Test
    fun `an expired code is replaced`() {
        val connection = service.openConnection(sender)
        val nonce = connection.challenge()
        val code = display.lastCode
        fixture.clock.advanceSeconds(120)
        assertIs<SetupAuthorization.Challenge>(connection.setup(DigestClient.authorization(code, nonce, uri)))
        assertEquals(listOf(PinResult.CANCELLED), display.results)
        assertEquals(2, display.shownCodes.size)
    }

    @Test
    fun `a different deviceID from the same address replaces the code`() {
        val one = service.openConnection(sender)
        one.challenge()
        fixture.clock.advanceSeconds(6)
        val two = service.openConnection(sender)
        assertIs<SetupAuthorization.Challenge>(two.setup(device = "11:22:33:44:55:66"))
        assertEquals(listOf(PinResult.CANCELLED), display.results)
        assertEquals(2, display.shownCodes.size)
    }

    @Test
    fun `the same deviceID on a new connection keeps the code and closing the owner cancels it`() {
        val one = service.openConnection(sender)
        one.challenge()
        val two = service.openConnection(sender)
        val nonce = two.challenge()
        assertEquals(1, display.shownCodes.size)
        one.close()
        assertEquals(emptyList(), display.results, "the prompt moved to the newer connection")
        two.close()
        assertEquals(listOf(PinResult.CANCELLED), display.results)
        assertIs<SetupAuthorization.Refused>(two.setup(DigestClient.authorization(display.lastCode, nonce, uri)))
    }

    @Test
    fun `closing an authorized connection cancels nothing`() {
        val connection = service.openConnection(sender)
        val nonce = connection.challenge()
        connection.setup(DigestClient.authorization(display.lastCode, nonce, uri))
        connection.close()
        assertEquals(listOf(PinResult.ACCEPTED), display.results)
    }

    @Test
    fun `PIN-paired and verified connections skip Digest in both modes`() {
        for (mode in PinMode.entries) {
            val fixture = PairingFixture(mode)
            val identity = Ed25519KeyPair.generate()
            fixture.service.pinStart(sender, null)
            val connection = fixture.service.openConnection(sender)
            val key = connection.pinPair(PinPairingClient(pin = fixture.display.lastCode, identity = identity))
            connection.verify(PairVerifyClient(identity), key)
            assertSame(SetupAuthorization.Authorized, connection.setup(), "$mode")
            assertEquals(1, fixture.display.shownCodes.size)
        }
    }

    @Test
    fun `Mode P falls back to Digest for senders that skip PIN pairing`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val connection = fixture.service.openConnection(sender)
        val nonce = DigestClient.nonce(assertIs<SetupAuthorization.Challenge>(connection.setup()).wwwAuthenticate)
        assertSame(
            SetupAuthorization.Authorized,
            connection.setup(DigestClient.authorization(fixture.display.lastCode, nonce, uri)),
        )
    }

    @Test
    fun `a missing deviceID still works, keyed by address alone`() {
        val connection = service.openConnection(sender)
        val nonce =
            DigestClient.nonce(
                assertIs<SetupAuthorization.Challenge>(connection.setup(device = null)).wwwAuthenticate,
            )
        val header = DigestClient.authorization(display.lastCode, nonce, uri)
        assertSame(SetupAuthorization.Authorized, connection.setup(header, device = null))
    }

    @Test
    fun `parse accepts quoted and unquoted values in any order and case`() {
        val parsed =
            Digest.parse("  digest  Response=\"AB\",uri=\"/a,b\" , qop=auth,NC=00000001 ,cnonce=\"x\\\"y\",username=u")
        assertEquals(
            mapOf(
                "response" to "AB",
                "uri" to "/a,b",
                "qop" to "auth",
                "nc" to "00000001",
                "cnonce" to "x\"y",
                "username" to "u",
            ),
            parsed,
        )
        assertEquals(emptyMap(), Digest.parse("Digest"))
    }

    @Test
    fun `parse rejects other schemes and malformed or oversized headers`() {
        assertNull(Digest.parse("Basic dXNlcjpwYXNz"))
        assertNull(Digest.parse("Digestusername=\"u\""))
        assertNull(Digest.parse("Digest username"))
        assertNull(Digest.parse("Digest username=\"u"))
        assertNull(Digest.parse("Digest username=\"u\", username=\"v\""))
        assertNull(Digest.parse("Digest =x"))
        assertNull(Digest.parse("Digest username=\"" + "a".repeat(2100) + "\""))
        assertNull(Digest.parse("Digest " + (1..17).joinToString(",") { "p$it=1" }))
        assertEquals(16, Digest.parse("Digest " + (1..16).joinToString(",") { "p$it=1" })?.size)
    }

    @Test
    fun `garbage Authorization headers only re-challenge`() {
        val connection = service.openConnection(sender)
        connection.challenge()
        val random = java.util.Random(3)
        repeat(300) {
            val junk = "Digest " + String(CharArray(random.nextInt(200)) { (random.nextInt(95) + 32).toChar() })
            assertIs<SetupAuthorization.Challenge>(connection.setup(junk))
        }
        assertEquals(emptyList(), display.results)
    }

    @Test
    fun `expectedResponse needs every parameter`() {
        val full = mapOf("username" to "u", "realm" to "raop", "nonce" to "n", "uri" to "/")
        val expected = Digest.md5Hex(Digest.md5Hex("u:raop:1234") + ":n:" + Digest.md5Hex("SETUP:/"))
        assertEquals(expected, Digest.expectedResponse(full, "1234", "SETUP"))
        for (key in full.keys) assertNull(Digest.expectedResponse(full - key, "1234", "SETUP"), key)
        assertNull(Digest.expectedResponse(full + ("qop" to "auth"), "1234", "SETUP"), "qop without nc and cnonce")
        assertFalse(Digest.matches(full, "1234", "SETUP"), "no response")
    }
}
