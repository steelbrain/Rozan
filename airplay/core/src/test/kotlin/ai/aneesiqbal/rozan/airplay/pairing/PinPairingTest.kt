package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistString
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `pair-pin-start` and `pair-setup-pin` (02 §5, §9.1). */
class PinPairingTest {
    private val fixture = PairingFixture(PinMode.PIN_PAIRING)
    private val service = fixture.service
    private val display = fixture.display
    private val sender = Addresses.sender
    private val identity = Ed25519KeyPair.generate()

    private fun startPin(client: java.net.InetAddress = sender): String {
        assertIs<PairingReply.Ok>(service.pinStart(client, null))
        return display.lastCode
    }

    private fun wrongPin(code: String): String = ((code.toInt() + 1) % 10_000).toString().padStart(4, '0')

    @ParameterizedTest
    @EnumSource(PinMode::class)
    fun `full PIN flow then pair-verify authenticates the connection`(mode: PinMode) {
        val fixture = PairingFixture(mode)
        val service = fixture.service
        assertIs<PairingReply.Ok>(service.pinStart(sender, "Kitchen iPad"))
        val shown = assertIs<RecordingDisplay.Shown>(fixture.display.events.single())
        assertEquals("Kitchen iPad", shown.clientName)
        assertTrue(shown.code.matches(Regex("[0-9]{4}")))

        val connection = service.openConnection(sender)
        val receiverKey = connection.pinPair(PinPairingClient(pin = shown.code, identity = identity))
        assertContentEquals(service.identityPublicKey, receiverKey)
        assertEquals(listOf(PinResult.ACCEPTED), fixture.display.results)
        assertFalse(connection.isAuthenticated, "PIN pairing alone does not authenticate")

        val verifier = PairVerifyClient(identity)
        val shared = connection.verify(verifier, receiverKey)
        assertContentEquals(shared, connection.verifiedSharedSecret)
        assertTrue(connection.isAuthenticated)
        assertSame(SetupAuthorization.Authorized, connection.authorizeSetup("SETUP", "rtsp://x/1", null, "id", "n"))
        assertEquals(1, fixture.display.shownCodes.size, "no Digest code after PIN pairing")
    }

    @Test
    fun `the code is used up by step 3`() {
        val code = startPin()
        service.openConnection(sender).pinPair(PinPairingClient(pin = code, identity = identity))
        val again = service.openConnection(sender)
        assertPairingError(470, false, again.pairSetupPin(PinPairingClient(pin = code).step1()))
    }

    @Test
    fun `step 1 needs a live code for the same address`() {
        val client = PinPairingClient(pin = "0000")
        assertPairingError(470, false, service.openConnection(sender).pairSetupPin(client.step1()), "no code")
        startPin(sender)
        assertPairingError(470, false, service.openConnection(Addresses.other).pairSetupPin(client.step1()), "other IP")
        assertIs<PairingReply.Ok>(service.openConnection(sender).pairSetupPin(client.step1()))
    }

    @Test
    fun `step 1 checks method and username length`() {
        startPin()
        val connection = service.openConnection(sender)

        fun step1(
            method: String,
            user: String,
        ) = connection.pairSetupPin(
            BinaryPlist.encode(
                buildPlistDict {
                    put("method", method)
                    put("user", user)
                },
            ),
        )
        assertPairingError(470, false, step1("pin2", "AA"))
        assertPairingError(470, false, step1("PIN", "AA"))
        assertPairingError(470, false, step1("pin", "x".repeat(65)))
        assertPairingError(470, false, step1("pin", "é".repeat(33)), "66 UTF-8 bytes")
        assertIs<PairingReply.Ok>(step1("pin", "x".repeat(64)))
    }

    @Test
    fun `steps must come in order`() {
        val code = startPin()
        val connection = service.openConnection(sender)
        val client = PinPairingClient(pin = code, identity = identity)
        val helper = PinPairingClient(pin = code, identity = identity)

        // Step 2 and step 3 without what comes before them.
        val other = service.openConnection(sender)
        val step1 = assertIs<PairingReply.Ok>(other.pairSetupPin(helper.step1()))
        val step2Request = helper.step2(step1.body)
        assertPairingError(470, false, connection.pairSetupPin(step2Request), "step 2 first")
        val step3Like =
            BinaryPlist.encode(
                buildPlistDict {
                    put("epk", ByteArray(32))
                    put("authTag", ByteArray(16))
                },
            )
        assertPairingError(470, false, connection.pairSetupPin(step3Like), "step 3 first")

        // The proper order still works afterwards on the same connection, and step 3 cannot repeat.
        val receiverKey = connection.pinPair(client)
        assertContentEquals(service.identityPublicKey, receiverKey)
        assertPairingError(470, false, connection.pairSetupPin(step3Like), "step 3 again")
        assertEquals(0, display.results.count { it == PinResult.REJECTED })
    }

    @Test
    fun `a wrong PIN closes the connection and the third uses the code up`() {
        val code = startPin()
        repeat(2) {
            val connection = service.openConnection(sender)
            val client = PinPairingClient(pin = wrongPin(code))
            val step1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1()))
            assertPairingError(470, true, connection.pairSetupPin(client.step2(step1.body)), "attempt ${it + 1}")
            assertEquals(emptyList(), display.results, "the code stays on screen")
        }
        // The right PIN still works after two misses...
        val good = service.openConnection(sender)
        val ok = PinPairingClient(pin = code)
        val step1 = assertIs<PairingReply.Ok>(good.pairSetupPin(ok.step1()))
        assertIs<PairingReply.Ok>(good.pairSetupPin(ok.step2(step1.body)))

        // ...but on a fresh code, the third miss ends it.
        fixture.clock.advanceSeconds(6)
        val fresh = startPin()
        repeat(3) {
            val connection = service.openConnection(sender)
            val client = PinPairingClient(pin = wrongPin(fresh))
            val s1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1()))
            assertPairingError(470, true, connection.pairSetupPin(client.step2(s1.body)))
        }
        assertEquals(listOf(PinResult.CANCELLED, PinResult.REJECTED), display.results)
        assertPairingError(
            470,
            false,
            service.openConnection(sender).pairSetupPin(PinPairingClient(pin = fresh).step1()),
        )
    }

    @Test
    fun `parallel SRP exchanges cannot outlive the code`() {
        val code = startPin()
        val clients = List(4) { PinPairingClient(pin = wrongPin(code)) }
        val connections = List(4) { service.openConnection(sender) }
        val step2s =
            clients.zip(connections).map { (client, connection) ->
                client.step2(assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1())).body)
            }
        for (i in 0 until 3) assertPairingError(470, true, connections[i].pairSetupPin(step2s[i]))
        assertEquals(listOf(PinResult.REJECTED), display.results)
        // The fourth exchange was started against the same code, which is gone: refused without a check.
        assertPairingError(470, true, connections[3].pairSetupPin(step2s[3]))
        assertEquals(listOf(PinResult.REJECTED), display.results)
    }

    @Test
    fun `a code has three proof checks in all, right or wrong`() {
        val code = startPin()
        val clients = List(4) { PinPairingClient(pin = code) }
        val connections = List(4) { service.openConnection(sender) }
        val step2s =
            clients.zip(connections).map { (client, connection) ->
                client.step2(assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1())).body)
            }
        // Each check is reserved before the SRP proof is looked at, so the fourth is refused unchecked even
        // though it is right: parallel connections cannot get more guesses at a code than it allows.
        for (i in 0 until 3) assertIs<PairingReply.Ok>(connections[i].pairSetupPin(step2s[i]))
        assertPairingError(470, true, connections[3].pairSetupPin(step2s[3]))
        assertEquals(emptyList(), display.results, "no failure was counted")
    }

    @Test
    fun `step 3 is refused when the code was superseded after step 2`() {
        val code = startPin()
        val connection = service.openConnection(sender)
        val client = PinPairingClient(pin = code, identity = identity)
        val step1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1()))
        val step2 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step2(step1.body)))
        fixture.clock.advanceSeconds(5)
        startPin()
        assertPairingError(470, true, connection.pairSetupPin(client.step3(step2.body)))
        assertEquals(listOf(PinResult.CANCELLED), display.results, "nothing is ACCEPTED")
        assertFalse(service.isTrusted(identity.publicKey))
        assertPairingError(470, true, connection.pairVerify(PairVerifyClient(identity).step1()))
    }

    @Test
    fun `step 3 is refused when the code expired after step 2`() {
        val code = startPin()
        val connection = service.openConnection(sender)
        val client = PinPairingClient(pin = code, identity = identity)
        val step1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1()))
        val step2 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step2(step1.body)))
        fixture.clock.advanceSeconds(120)
        assertPairingError(470, true, connection.pairSetupPin(client.step3(step2.body)))
        assertEquals(listOf(PinResult.CANCELLED), display.results)
        assertFalse(service.isTrusted(identity.publicKey))
    }

    @Test
    fun `step 2 checks the proof and key sizes`() {
        val code = startPin()

        fun step2(
            pk: ByteArray,
            proof: ByteArray,
        ): PairingReply {
            val connection = service.openConnection(sender)
            assertIs<PairingReply.Ok>(connection.pairSetupPin(PinPairingClient(pin = code).step1()))
            return connection.pairSetupPin(
                BinaryPlist.encode(
                    buildPlistDict {
                        put("pk", pk)
                        put("proof", proof)
                    },
                ),
            )
        }
        assertPairingError(470, false, step2(ByteArray(256) { 1 }, ByteArray(19)))
        assertPairingError(470, false, step2(ByteArray(256) { 1 }, ByteArray(21)))
        assertPairingError(470, false, step2(ByteArray(257) { 1 }, ByteArray(20)))
        assertPairingError(470, false, step2(ByteArray(0), ByteArray(20)))
        assertEquals(emptyList(), display.results, "malformed proofs are not counted against the code")
    }

    @Test
    fun `a bad step 3 tag closes the connection`() {
        val code = startPin()
        val connection = service.openConnection(sender)
        val client = PinPairingClient(pin = code, identity = identity)
        val step1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step1()))
        val step2 = assertIs<PairingReply.Ok>(connection.pairSetupPin(client.step2(step1.body)))
        val good = client.step3(step2.body)
        val decoded = BinaryPlist.decode(good) as ai.aneesiqbal.rozan.airplay.plist.PlistDict
        val tampered =
            BinaryPlist.encode(
                buildPlistDict {
                    put("epk", assertIs<ByteArray>(decoded.data("epk")).also { it[0] = (it[0] + 1).toByte() })
                    put("authTag", assertIs<ByteArray>(decoded.data("authTag")))
                },
            )
        assertPairingError(470, true, connection.pairSetupPin(tampered))
        assertFalse(service.isTrusted(identity.publicKey))
    }

    @Test
    fun `malformed bodies get 400 or 470 and never throw`() {
        startPin()
        val connection = service.openConnection(sender)
        assertPairingError(400, false, connection.pairSetupPin(ByteArray(0)), "empty")
        assertPairingError(400, false, connection.pairSetupPin("bplist00".toByteArray()), "truncated header")
        val valid = PinPairingClient(pin = "0000").step1()
        for (cut in listOf(1, 8, valid.size / 2, valid.size - 1)) {
            assertPairingError(400, false, connection.pairSetupPin(valid.copyOf(cut)), "cut at $cut")
        }
        assertPairingError(400, false, connection.pairSetupPin(ByteArray(5000)), "oversized")
        val array = BinaryPlist.encode(PlistArray(listOf(PlistString("pin"))))
        assertPairingError(400, false, connection.pairSetupPin(array), "not a dict")
        val wrongTypes =
            BinaryPlist.encode(
                buildPlistDict {
                    put("method", ByteArray(3))
                    put("user", "AA")
                },
            )
        assertPairingError(470, false, connection.pairSetupPin(wrongTypes), "method is data")
        assertPairingError(470, false, connection.pairSetupPin(BinaryPlist.encode(buildPlistDict { put("x", 1L) })))

        val random = java.util.Random(7)
        repeat(500) {
            val junk = ByteArray(random.nextInt(300)).also(random::nextBytes)
            if (it % 2 == 0) "bplist00".toByteArray().copyInto(junk, endIndex = minOf(8, junk.size))
            assertIs<PairingReply.Error>(connection.pairSetupPin(junk))
        }
    }

    @Test
    fun `codes expire after 120 s`() {
        val code = startPin()
        fixture.clock.advanceSeconds(119)
        service.tick(sessionActive = false)
        assertEquals(emptyList(), display.results)
        fixture.clock.advanceSeconds(1)
        service.tick(sessionActive = false)
        assertEquals(listOf(PinResult.CANCELLED), display.results)
        assertPairingError(
            470,
            false,
            service.openConnection(sender).pairSetupPin(PinPairingClient(pin = code).step1()),
        )
    }

    @Test
    fun `an expired code is noticed without a tick`() {
        val code = startPin()
        fixture.clock.advanceSeconds(121)
        assertPairingError(
            470,
            false,
            service.openConnection(sender).pairSetupPin(PinPairingClient(pin = code).step1()),
        )
        assertEquals(listOf(PinResult.CANCELLED), display.results)
    }

    @Test
    fun `a new pair-pin-start supersedes the code, but not within 5 s`() {
        val first = startPin()
        fixture.clock.advanceSeconds(4)
        assertIs<PairingReply.Ok>(service.pinStart(sender, null))
        assertEquals(listOf(first), display.shownCodes, "the code on screen stays")
        fixture.clock.advanceSeconds(1)
        assertIs<PairingReply.Ok>(service.pinStart(sender, null))
        assertEquals(2, display.shownCodes.size)
        assertEquals(listOf(PinResult.CANCELLED), display.results)
        // The superseded code no longer works.
        val connection = service.openConnection(sender)
        val stale = PinPairingClient(pin = first)
        val step1 = assertIs<PairingReply.Ok>(connection.pairSetupPin(stale.step1()))
        if (display.lastCode != first) assertPairingError(470, true, connection.pairSetupPin(stale.step2(step1.body)))
    }

    @Test
    fun `codes are 4 digits with leading zeros and spread over the whole range`() {
        assertEquals("0042", newCode(ScriptedRandom(byteArrayOf(0, 42))))
        assertEquals("0000", newCode(ScriptedRandom(byteArrayOf(0x27, 0x10))), "10000 maps to 0000")
        // 60000 and above are rejected so every code is equally likely: 0xEA60 = 60000.
        assertEquals("0007", newCode(ScriptedRandom(byteArrayOf(0xEA.toByte(), 0x60), byteArrayOf(0, 7))))
        assertEquals("9999", newCode(ScriptedRandom(byteArrayOf(0xEA.toByte(), 0x5F))), "59999 is accepted")
        val seen = HashSet<String>()
        val random = java.security.SecureRandom()
        repeat(20_000) { seen += newCode(random) }
        assertTrue(seen.size > 8_000, "saw ${seen.size} distinct codes")
        assertTrue(seen.all { it.length == 4 && it.all(Char::isDigit) })
    }

    @Test
    fun `a closed connection refuses everything`() {
        val code = startPin()
        val connection = service.openConnection(sender)
        connection.close()
        connection.close()
        assertPairingError(470, true, connection.pairSetupPin(PinPairingClient(pin = code).step1()))
        assertPairingError(470, true, connection.pairVerify(PairVerifyClient(identity).step1()))
        assertPairingError(470, true, connection.pairSetup(identity.publicKey))
        val refused = assertIs<SetupAuthorization.Refused>(connection.authorizeSetup("SETUP", "u", null, null, null))
        assertTrue(refused.close)
        assertFalse(connection.isAuthenticated)
    }
}
