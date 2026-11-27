package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `/pair-setup` (02 §3) and legacy `/pair-verify` (02 §4), including the Mode P acceptance rule. */
class PairVerifyTest {
    private val sender = Addresses.sender
    private val identity = Ed25519KeyPair.generate()

    private fun pinPairedConnection(fixture: PairingFixture): Pair<ConnectionPairing, ByteArray> {
        assertIs<PairingReply.Ok>(fixture.service.pinStart(sender, null))
        val connection = fixture.service.openConnection(sender)
        val key = connection.pinPair(PinPairingClient(pin = fixture.display.lastCode, identity = identity))
        return connection to key
    }

    @Test
    fun `Mode D accepts any sender key but still needs Digest`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        val shared = connection.verify(PairVerifyClient(identity), fixture.service.identityPublicKey)
        assertContentEquals(shared, connection.verifiedSharedSecret)
        assertFalse(connection.isAuthenticated)
        assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("SETUP", "rtsp://x/1", null, "id", null))
    }

    @Test
    fun `Mode P refuses a key that did not PIN-pair`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val connection = fixture.service.openConnection(sender)
        assertPairingError(470, true, connection.pairVerify(PairVerifyClient(identity).step1()))
        assertNull(connection.verifiedSharedSecret)
    }

    @Test
    fun `Mode P refuses another key on a PIN-paired connection`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val (connection, receiverKey) = pinPairedConnection(fixture)
        assertContentEquals(fixture.service.identityPublicKey, receiverKey)
        assertPairingError(470, true, connection.pairVerify(PairVerifyClient(Ed25519KeyPair.generate()).step1()))
    }

    @Test
    fun `Mode P accepts a key from the session trust on a later connection`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val (first, receiverKey) = pinPairedConnection(fixture)
        first.close()
        val reconnect = fixture.service.openConnection(sender)
        reconnect.verify(PairVerifyClient(identity), receiverKey)
        assertTrue(reconnect.isAuthenticated)
        assertSame(SetupAuthorization.Authorized, reconnect.authorizeSetup("SETUP", "rtsp://x/1", null, "id", null))
    }

    @Test
    fun `Mode P session trust lasts 30 s after step 3 outside a session`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val (first, receiverKey) = pinPairedConnection(fixture)
        first.close()
        fixture.clock.advanceSeconds(29)
        fixture.service.openConnection(sender).verify(PairVerifyClient(identity), receiverKey)
        fixture.clock.advanceSeconds(1)
        val late = fixture.service.openConnection(sender)
        assertPairingError(470, true, late.pairVerify(PairVerifyClient(identity).step1()))
        assertFalse(fixture.service.isTrusted(identity.publicKey))
        assertEquals(0, fixture.rotations, "the key may still rotate later; the trust is gone already")
    }

    @Test
    fun `Mode P session trust lasts while a session is active and ends with it`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val (first, receiverKey) = pinPairedConnection(fixture)
        first.close()
        repeat(600) {
            fixture.clock.advanceSeconds(1)
            fixture.service.tick(sessionActive = true)
        }
        fixture.service.openConnection(sender).verify(PairVerifyClient(identity), receiverKey)
        fixture.service.sessionEnded()
        assertFalse(fixture.service.isTrusted(identity.publicKey))
    }

    @Test
    fun `Mode P - the end of a session clears the trust even while another host's code defers the rotation`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val (first, receiverKey) = pinPairedConnection(fixture)
        first.close()
        fixture.service.tick(sessionActive = true)
        assertIs<PairingReply.Ok>(fixture.service.pinStart(Addresses.other, null))
        fixture.clock.advanceSeconds(10)
        fixture.service.sessionEnded()
        assertEquals(0, fixture.rotations, "a code is on screen, so the identity stays for now")
        assertContentEquals(receiverKey, fixture.service.identityPublicKey)
        val back = fixture.service.openConnection(sender)
        assertPairingError(470, true, back.pairVerify(PairVerifyClient(identity).step1()))
    }

    @Test
    fun `pair-setup returns the identity key and binds pair-verify to the announced key`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        assertPairingError(400, false, connection.pairSetup(ByteArray(31)))
        assertPairingError(400, false, connection.pairSetup(ByteArray(33)))
        val reply = assertIs<PairingReply.Ok>(connection.pairSetup(identity.publicKey))
        assertEquals("application/octet-stream", reply.contentType)
        assertContentEquals(fixture.service.identityPublicKey, reply.body)
        assertPairingError(470, true, connection.pairVerify(PairVerifyClient(Ed25519KeyPair.generate()).step1()))

        val second = fixture.service.openConnection(sender)
        assertIs<PairingReply.Ok>(second.pairSetup(identity.publicKey))
        second.verify(PairVerifyClient(identity), reply.body)
        assertFalse(second.isAuthenticated, "pair-setup authenticates nobody")
    }

    @Test
    fun `Mode P - a pair-setup-only connection gets the Digest challenge`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val connection = fixture.service.openConnection(sender)
        assertIs<PairingReply.Ok>(connection.pairSetup(identity.publicKey))
        assertFalse(connection.isAuthenticated)
        assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("SETUP", "rtsp://x/1", null, "id", null))
    }

    @Test
    fun `the receiver signature verifies against the advertised key`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        val client = PairVerifyClient(identity)
        val response = assertIs<PairingReply.Ok>(connection.pairVerify(client.step1()))
        assertEquals(96, response.body.size)
        // PairVerifyClient.step2 throws when the signature does not verify against the given key.
        client.step2(response.body, fixture.service.identityPublicKey)
        val wrongKey = Ed25519KeyPair.generate().publicKey
        val fresh = PairVerifyClient(identity)
        val second = assertIs<PairingReply.Ok>(connection.pairVerify(fresh.step1()))
        kotlin.test.assertFailsWith<IllegalStateException> { fresh.step2(second.body, wrongKey) }
    }

    @Test
    fun `step 2 without step 1 is refused`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        assertPairingError(470, false, connection.pairVerify(ByteArray(68)))
    }

    @Test
    fun `a bad sender signature closes the connection`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        val client = PairVerifyClient(identity)
        val response = assertIs<PairingReply.Ok>(connection.pairVerify(client.step1()))
        val step2 = client.step2(response.body, fixture.service.identityPublicKey)
        step2[10] = (step2[10] + 1).toByte()
        assertPairingError(470, true, connection.pairVerify(step2))
        assertNull(connection.verifiedSharedSecret)
        // The verify state is gone: replaying step 2 is out of order.
        assertPairingError(470, false, connection.pairVerify(step2))
    }

    @Test
    fun `a signature by another key is refused`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        val announced = Ed25519KeyPair.generate()
        val impostor = PairVerifyClient(identity)
        val step1 = impostor.step1()
        announced.publicKey.copyInto(step1, 36)
        val response = assertIs<PairingReply.Ok>(connection.pairVerify(step1))
        assertPairingError(
            470,
            true,
            connection.pairVerify(impostor.step2(response.body, fixture.service.identityPublicKey)),
        )
    }

    @Test
    fun `a repeated step 1 restarts the verify`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        assertIs<PairingReply.Ok>(connection.pairVerify(PairVerifyClient(identity).step1()))
        connection.verify(PairVerifyClient(identity), fixture.service.identityPublicKey)
        assertEquals(32, connection.verifiedSharedSecret?.size)
    }

    @Test
    fun `malformed verify messages get 400`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        val good = PairVerifyClient(identity).step1()
        assertPairingError(400, false, connection.pairVerify(good.copyOf(67)))
        assertPairingError(400, false, connection.pairVerify(good + 0))
        assertPairingError(400, false, connection.pairVerify(ByteArray(0)))
        assertPairingError(400, false, connection.pairVerify(good.copyOf().also { it[0] = 2 }))
        assertPairingError(400, false, connection.pairVerify(good.copyOf().also { it[2] = 1 }))
        val random = java.util.Random(11)
        repeat(300) {
            val junk = ByteArray(if (it % 3 == 0) 68 else random.nextInt(200)).also(random::nextBytes)
            assertIs<PairingReply.Error>(connection.pairVerify(junk), "junk $it")
            assertIs<PairingReply>(connection.pairSetup(junk))
        }
    }

    @Test
    fun `a low-order ephemeral key is refused`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        val step1 = byteArrayOf(1, 0, 0, 0) + ByteArray(32) + identity.publicKey
        assertPairingError(470, true, connection.pairVerify(step1))
    }

    @Test
    fun `close wipes the shared secret`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        connection.verify(PairVerifyClient(identity), fixture.service.identityPublicKey)
        val copy = assertIs<ByteArray>(connection.verifiedSharedSecret)
        connection.close()
        assertNull(connection.verifiedSharedSecret)
        assertEquals(32, copy.size, "callers get copies")
    }

    @Test
    fun `Mode P - after endAuthentication the connection must PIN-pair again`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val (connection, receiverKey) = pinPairedConnection(fixture)
        connection.verify(PairVerifyClient(identity), receiverKey)
        assertTrue(connection.isAuthenticated)
        // What a whole-session TEARDOWN does: the session ends, then the connection forgets.
        fixture.service.sessionEnded()
        connection.endAuthentication()
        assertFalse(connection.isAuthenticated)
        assertNull(connection.verifiedSharedSecret)
        assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("SETUP", "rtsp://x/1", null, "id", null))
        assertPairingError(470, true, connection.pairVerify(PairVerifyClient(identity).step1()))
    }

    @Test
    fun `Mode D - after endAuthentication Digest challenges again`() {
        val fixture = PairingFixture(PinMode.DIGEST)
        val connection = fixture.service.openConnection(sender)
        val uri = "rtsp://x/1"
        val challenge = assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("SETUP", uri, null, "i", null))
        val nonce = DigestClient.nonce(challenge.wwwAuthenticate)
        val answer = DigestClient.authorization(fixture.display.lastCode, nonce, uri)
        assertSame(SetupAuthorization.Authorized, connection.authorizeSetup("SETUP", uri, answer, "i", null))
        connection.endAuthentication()
        assertFalse(connection.isAuthenticated)
        assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("SETUP", uri, answer, "i", null))
        assertEquals(2, fixture.display.shownCodes.size, "a new code")
    }
}
