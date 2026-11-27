package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The receiver identity (02 §2.2) and Mode P rotation (02 §8.2). */
class RotationTest {
    private val sender = Addresses.sender
    private val identity = Ed25519KeyPair.generate()

    /** pin-start, the PIN flow and pair-verify from [sender]; returns the receiver key the sender saw. */
    private fun pinPairAndVerify(fixture: PairingFixture): ByteArray {
        fixture.clock.advanceSeconds(6)
        assertIs<PairingReply.Ok>(fixture.service.pinStart(sender, null))
        val connection = fixture.service.openConnection(sender)
        val key = connection.pinPair(PinPairingClient(pin = fixture.display.lastCode, identity = identity))
        connection.verify(PairVerifyClient(identity), key)
        assertTrue(connection.isAuthenticated)
        connection.close()
        return key
    }

    private fun pairSetupKey(fixture: PairingFixture): ByteArray =
        assertIs<PairingReply.Ok>(fixture.service.openConnection(sender).pairSetup(identity.publicKey)).body

    @Test
    fun `Mode D uses the seed's key everywhere and never rotates`() {
        val seed = ByteArray(32) { it.toByte() }
        val fixture = PairingFixture(PinMode.DIGEST, identitySeed = seed)
        val expected = Ed25519KeyPair.fromSeed(seed).publicKey
        assertContentEquals(expected, fixture.service.identityPublicKey)
        assertContentEquals(expected, pairSetupKey(fixture))
        assertContentEquals(expected, pinPairAndVerify(fixture))
        fixture.service.sessionEnded()
        fixture.clock.advanceSeconds(600)
        fixture.service.tick(sessionActive = false)
        assertContentEquals(expected, fixture.service.identityPublicKey)
        assertEquals(0, fixture.rotations)
    }

    @Test
    fun `Mode P makes its own identity and ignores the seed`() {
        val seed = ByteArray(32) { 1 }
        val one = PairingFixture(PinMode.PIN_PAIRING, ScriptedRandom(ByteArray(32) { 2 }), seed)
        val two = PairingFixture(PinMode.PIN_PAIRING, ScriptedRandom(ByteArray(32) { 3 }), seed)
        assertFalse(one.service.identityPublicKey.contentEquals(two.service.identityPublicKey))
        assertFalse(one.service.identityPublicKey.contentEquals(Ed25519KeyPair.fromSeed(seed).publicKey))
        assertContentEquals(Ed25519KeyPair.fromSeed(ByteArray(32) { 2 }).publicKey, one.service.identityPublicKey)
    }

    @Test
    fun `Mode P rotates when a PIN-paired session ends and forgets the sender`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val old = fixture.service.identityPublicKey
        assertContentEquals(old, pinPairAndVerify(fixture))
        assertContentEquals(old, pairSetupKey(fixture))
        assertTrue(fixture.service.isTrusted(identity.publicKey))

        fixture.service.sessionEnded()
        assertEquals(0, fixture.rotations, "not right after step 3: the sender may still be verifying")
        assertFalse(fixture.service.isTrusted(identity.publicKey), "the trust ends with the session at once")
        val early = fixture.service.openConnection(sender)
        assertPairingError(470, true, early.pairVerify(PairVerifyClient(identity).step1()))
        fixture.service.tick(sessionActive = false)
        assertEquals(0, fixture.rotations)
        fixture.clock.advanceSeconds(10)
        fixture.service.tick(sessionActive = false)
        assertEquals(1, fixture.rotations)
        val new = fixture.service.identityPublicKey
        assertFalse(old.contentEquals(new))
        assertContentEquals(new, pairSetupKey(fixture))
        assertFalse(fixture.service.isTrusted(identity.publicKey))

        // A sender that kept the old pairing is refused at pair-verify and must PIN-pair again.
        val stale = fixture.service.openConnection(sender)
        assertPairingError(470, true, stale.pairVerify(PairVerifyClient(identity).step1()))
        assertContentEquals(new, pinPairAndVerify(fixture))
        fixture.clock.advanceSeconds(10)
        fixture.service.sessionEnded()
        assertEquals(2, fixture.rotations)
        fixture.service.sessionEnded()
        assertEquals(2, fixture.rotations, "nothing left to forget")
    }

    @Test
    fun `Mode P waits for a PIN flow in progress before rotating`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        pinPairAndVerify(fixture)
        assertIs<PairingReply.Ok>(fixture.service.pinStart(Addresses.other, null))
        fixture.service.sessionEnded()
        fixture.service.tick(sessionActive = false)
        assertEquals(0, fixture.rotations, "a code is on screen")

        fixture.clock.advanceSeconds(120)
        fixture.service.tick(sessionActive = false)
        assertEquals(PinResult.CANCELLED, fixture.display.results.last())
        assertEquals(1, fixture.rotations, "the pending rotation runs once the code has expired")
    }

    @Test
    fun `Mode P - a pending rotation also runs when the other flow completes`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        pinPairAndVerify(fixture)
        val other = Ed25519KeyPair.generate()
        assertIs<PairingReply.Ok>(fixture.service.pinStart(Addresses.other, null))
        fixture.service.sessionEnded()
        val connection = fixture.service.openConnection(Addresses.other)
        val key = connection.pinPair(PinPairingClient(pin = fixture.display.lastCode, identity = other))
        assertContentEquals(fixture.service.identityPublicKey, key, "the identity is stable through the flow")
        connection.verify(PairVerifyClient(other), key)
        fixture.service.tick(sessionActive = true)
        assertEquals(0, fixture.rotations, "never during an active session")
        fixture.service.tick(sessionActive = false)
        assertEquals(0, fixture.rotations, "within the grace after step 3")
        fixture.clock.advanceSeconds(10)
        fixture.service.tick(sessionActive = false)
        assertEquals(1, fixture.rotations)
    }

    @Test
    fun `Mode P rotates after 60 s idle but not during a session`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        pinPairAndVerify(fixture)
        fixture.clock.advanceSeconds(59)
        fixture.service.tick(sessionActive = false)
        assertEquals(0, fixture.rotations)
        fixture.clock.advanceSeconds(120)
        fixture.service.tick(sessionActive = true)
        assertEquals(0, fixture.rotations)
        fixture.service.tick(sessionActive = false)
        assertEquals(0, fixture.rotations, "the session just ended; idle time starts now")
        fixture.clock.advanceSeconds(60)
        fixture.service.tick(sessionActive = false)
        assertEquals(1, fixture.rotations)
    }

    @Test
    fun `Mode P - requests that authenticate nothing are not activity`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        pinPairAndVerify(fixture)
        val stranger = Ed25519KeyPair.generate()
        // Another host keeps poking the pairing endpoints; none of it authenticates anything.
        repeat(2) {
            fixture.clock.advanceSeconds(30)
            val connection = fixture.service.openConnection(Addresses.other)
            assertIs<PairingReply.Ok>(connection.pairSetup(stranger.publicKey))
            assertPairingError(470, true, connection.pairVerify(PairVerifyClient(stranger).step1()))
            fixture.service.tick(sessionActive = false)
        }
        assertEquals(1, fixture.rotations, "60 s after the PIN pairing, whatever others sent")
    }

    @Test
    fun `Mode P without PIN pairing keeps its identity`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        val before = fixture.service.identityPublicKey
        val connection = fixture.service.openConnection(sender)
        val challenge = assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("SETUP", "u", null, "d", null))
        val header =
            DigestClient.authorization(
                fixture.display.lastCode,
                DigestClient.nonce(challenge.wwwAuthenticate),
                "u",
            )
        assertIs<SetupAuthorization.Authorized>(connection.authorizeSetup("SETUP", "u", header, "d", null))
        fixture.service.sessionEnded()
        fixture.clock.advanceSeconds(600)
        fixture.service.tick(sessionActive = false)
        assertEquals(0, fixture.rotations)
        assertContentEquals(before, fixture.service.identityPublicKey)
    }

    @Test
    fun `callbacks run outside the service lock`() {
        val fixture = PairingFixture(PinMode.PIN_PAIRING)
        var probes = 0
        val probe = {
            val reader = Thread { fixture.service.identityPublicKey }
            reader.isDaemon = true
            reader.start()
            reader.join(2_000)
            assertFalse(reader.isAlive, "a callback ran while the service lock was held")
            probes++
        }
        fixture.display.onEvent = { probe() }
        fixture.onRotation = { probe() }
        pinPairAndVerify(fixture)
        fixture.clock.advanceSeconds(10)
        fixture.service.sessionEnded()
        assertEquals(3, probes, "show, finish and the rotation")
    }
}
