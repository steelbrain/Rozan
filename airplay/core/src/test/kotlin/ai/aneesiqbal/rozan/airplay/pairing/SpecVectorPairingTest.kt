package ai.aneesiqbal.rozan.airplay.pairing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.crypto.X25519KeyPair
import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.PinResult
import ai.aneesiqbal.rozan.airplay.toHex
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * docs/protocol/02-pairing.md §10 through the receiver's real request paths, with the vectors' fixed
 * inputs injected as the service's randomness.
 */
class SpecVectorPairingTest {
    private val receiverSeed = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val senderIdentity =
        Ed25519KeyPair.fromSeed(hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"))
    private val salt = hex("0102030405060708090a0b0c0d0e0f10")
    private val b = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
    private val a = BigInteger("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f", 16)
    private val receiverEphemeral = hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf")
    private val senderEphemeral = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")

    private val vectorB =
        "2c526c063c05323b1d745de49fc362e25d87039874521bf4c030d749cfba4382" +
            "80c0b6fff41add825ded8edafd7ebe200608c7cd8b962326516870ac800771bd" +
            "606013954f5940a4a68ad29856f0e08d761944cda4abd7f300a1b43ee4d7eba4" +
            "1fde03c497cee3e61e37fac93f8d397cabd126f0ac504112c7d382d2ae5d42c5" +
            "18ab55eac38dd5cdf37d7f824e8325dd5d36c5413f54ccd75c4f588c266057f8" +
            "67d8f6bd722b75a13527cc61a4470ca6040e6f44b828b398eb65c3c4727f6702" +
            "5111498947ad8e6f6ddb617ecaeec9ca17b1e5ff9848fa025dcae11bb14d748e" +
            "ebad38ea33d8690c71fb657ab1c6d6df7abc4ef0b04c4723abe43bf6649e8659"
    private val vectorA =
        "937c7b0538da06d03f23cdbc517a103be9e9bcc12d10ff4739a6d2ad4bf4659d" +
            "15a34d340b4ebfff7004fbc2eed5572552bd03e4fdfc3be08cbf9156eff56637" +
            "34e1a012a2a155e09259571d4f4541211480155063258c3861a98625b843d775" +
            "206031fa1aa00d919ea734c984ac067654baca3007677125bcb4b187979306a5" +
            "24c6b231a665dec628a7a03cd4cf4078e794a3d76b9f9134523adbf66393ec43" +
            "49b38888d198159b7200abdb1383870fced29e72c8a79f04bab0cd39d5dc1bad" +
            "e5ee28b627f22ef6078f72be3b583e799ac199bb6c7fd411debd53ceb120f5c5" +
            "8c14ec4b58d5b26a6fb2c906d4f10ebc1acbd92793a4c86e341cafbf6bfa41f1"

    private fun dict(body: ByteArray): PlistDict = assertIs<PlistDict>(BinaryPlist.decode(body))

    private fun ok(reply: PairingReply): PairingReply.Ok = assertIs<PairingReply.Ok>(reply)

    @ParameterizedTest
    @EnumSource(PinMode::class)
    fun `PIN pairing and pair-verify reproduce 02 sections 10_1 to 10_3`(mode: PinMode) {
        // Mode D takes the identity from the seed; Mode P generates it, so the seed is its first random read.
        val random = ScriptedRandom()
        if (mode == PinMode.PIN_PAIRING) random.enqueue(receiverSeed)
        random.enqueue(ScriptedRandom.codeBytes("1234"), salt, b, receiverEphemeral)
        val fixture = PairingFixture(mode, random, identitySeed = receiverSeed)
        val service = fixture.service
        assertEquals(
            "cd14b37f956e953194ff7fb73b3d81dcc561d61a7538094b7c3e1a643ee5f3aa",
            service.identityPublicKey.toHex(),
        )

        val sender = Addresses.sender
        assertEquals(0, ok(service.pinStart(sender, "Test iPhone")).body.size)
        assertEquals(listOf(RecordingDisplay.Shown("1234", sender, "Test iPhone")), fixture.display.events)

        // The sender continues on a new connection (02 §5.1).
        val connection = service.openConnection(sender)
        val client =
            PinPairingClient(pin = "1234", user = "AA:BB:CC:DD:EE:FF", identity = senderIdentity, privateKey = a)

        // §10.1 step 1: B and s.
        val step1 = ok(connection.pairSetupPin(client.step1()))
        assertEquals("application/x-apple-binary-plist", step1.contentType)
        assertEquals(vectorB, assertIs<ByteArray>(dict(step1.body).data("pk")).toHex())
        assertContentEquals(salt, dict(step1.body).data("salt"))

        // Step 2: A and M1 in, M2 out.
        val request2 = client.step2(step1.body)
        assertEquals(vectorA, assertIs<ByteArray>(dict(request2).data("pk")).toHex())
        assertEquals(
            "11fa191ea7c999c8029eba1ade3eeeefbe19f11f",
            assertIs<ByteArray>(dict(request2).data("proof")).toHex(),
        )
        val step2 = ok(connection.pairSetupPin(request2))
        assertEquals(
            "d2a84ae2724b7fb5b0447d6ecce62ef74a865337",
            assertIs<ByteArray>(dict(step2.body).data("proof")).toHex(),
        )

        // §10.2 step 3.
        val request3 = client.step3(step2.body)
        assertEquals(
            "d55b8d6406a174139f90b41473460ae2a0f55c2c753d8b2a7da31acca9420de28d2f7a5a43b447e4",
            client.sessionKey?.toHex(),
        )
        assertEquals(
            "ea4fb875944de961bc74b3beb6337a5b5222dc13af795213989529587758b6b6",
            dict(request3).data("epk")?.toHex(),
        )
        assertEquals("2a74d8217486c694ab0674f529e9835b", dict(request3).data("authTag")?.toHex())
        val step3 = ok(connection.pairSetupPin(request3))
        assertEquals(
            "0d71689aeee69d453899fbf0883339825c4a291eaaef7fde146c1db8aee46851",
            dict(step3.body).data("epk")?.toHex(),
        )
        assertEquals("ce15bf83b46484febee3d98af0747f56", dict(step3.body).data("authTag")?.toHex())
        assertContentEquals(service.identityPublicKey, client.receiverKey(step3.body))
        assertEquals(PinResult.ACCEPTED, fixture.display.results.single())

        assertPairVerifyVectors(connection, service.identityPublicKey)
        assertTrue(random.drained, "every vector input was used")
    }

    /** §10.3 pair-verify on [connection], which PIN-paired the sender key of §10.2. */
    private fun assertPairVerifyVectors(
        connection: ConnectionPairing,
        receiverKey: ByteArray,
    ) {
        val verifier = PairVerifyClient(senderIdentity, X25519KeyPair.fromPrivateKey(senderEphemeral))
        val verify1 = verifier.step1()
        assertEquals(
            "01000000" +
                "605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c" +
                "174553b456dddfc6908ecab1c101fe6ab21e2baa0617795b7d43a63482993fd5",
            verify1.toHex(),
        )
        val response1 = ok(connection.pairVerify(verify1))
        assertEquals("application/octet-stream", response1.contentType)
        assertEquals(
            "dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44" +
                "cd6aa83e570869d7247b7e30f5f7a1af53b5cd73a3275b6eb9bb0772c4002e79" +
                "41d3b5876514c5eb385bb45daec98c1dcb86d5378ceb418f94544e748f0ec389",
            response1.body.toHex(),
        )
        val verify2 = verifier.step2(response1.body, receiverKey)
        assertEquals(
            "00000000" +
                "dc4f6eeab56146ae0338dd729f58437d66b9f4e14011b28afcbdaaf856a941d1" +
                "53b6901aab5499b42e1db996b58287b663ee651c2fe986ed4c7a05ebb0ad9d20",
            verify2.toHex(),
        )
        val response2 = ok(connection.pairVerify(verify2))
        assertEquals(0, response2.body.size)
        assertEquals(
            "3b87c5035cce13678dd93d2dbc3a3d42039ebec86444a1ff82cd61b84c6fe968",
            connection.verifiedSharedSecret?.toHex(),
        )
        assertTrue(connection.isAuthenticated)
        assertSame(SetupAuthorization.Authorized, connection.authorizeSetup("SETUP", "rtsp://x/1", null, null, null))
    }

    @Test
    fun `Digest without qop reproduces 02 section 10_4`() {
        val nonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val random = ScriptedRandom(ScriptedRandom.codeBytes("4821"), hex(nonce))
        val fixture = PairingFixture(PinMode.DIGEST, random)
        val connection = fixture.service.openConnection(Addresses.sender)
        val uri = "rtsp://192.168.1.20/2934859221"
        val deviceId = "AA:BB:CC:DD:EE:FF"

        val challenge =
            assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("SETUP", uri, null, deviceId, "Den"))
        assertEquals("Digest realm=\"raop\", nonce=\"$nonce\"", challenge.wwwAuthenticate)
        assertEquals(listOf(RecordingDisplay.Shown("4821", Addresses.sender, "Den")), fixture.display.events)

        assertEquals("09cbff77416aa1298b86ca6a5a24019e", Digest.md5Hex("AirPlay:raop:4821"))
        assertEquals("7c0a6dca01983f72a20cbf7ac62bb002", Digest.md5Hex("SETUP:$uri"))
        val response = "8ff0d9e4586ff1fe0bdf00259c3078d6"
        assertEquals(response, DigestClient.response("4821", nonce, uri))
        val header =
            "Digest username=\"AirPlay\", realm=\"raop\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
        assertSame(SetupAuthorization.Authorized, connection.authorizeSetup("SETUP", uri, header, deviceId, "Den"))
        assertEquals(listOf(PinResult.ACCEPTED), fixture.display.results)
        assertTrue(connection.isAuthenticated)
        assertTrue(random.drained)
    }

    @Test
    fun `Digest on a classic ANNOUNCE reproduces 02 section 10_4`() {
        val nonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val random = ScriptedRandom(ScriptedRandom.codeBytes("4821"), hex(nonce))
        val fixture = PairingFixture(PinMode.DIGEST, random)
        val connection = fixture.service.openConnection(Addresses.sender)
        val uri = "rtsp://192.168.1.10/3413821438"
        val dacpId = "F7C2CDC26FC71581"

        val challenge =
            assertIs<SetupAuthorization.Challenge>(connection.authorizeSetup("ANNOUNCE", uri, null, dacpId, null))
        assertEquals("Digest realm=\"raop\", nonce=\"$nonce\"", challenge.wwwAuthenticate)
        assertEquals(listOf(RecordingDisplay.Shown("4821", Addresses.sender, null)), fixture.display.events)

        assertEquals("ecf01412782e979164b4ebe1b9c0445a", Digest.md5Hex("iTunes:raop:4821"))
        assertEquals("5bc41bef58ac2ac9c639975eaecfdd39", Digest.md5Hex("ANNOUNCE:$uri"))
        val response = "6ad5bb05dc6cc7c658eea3845eef02da"
        assertEquals(response, DigestClient.response("4821", nonce, uri, username = "iTunes", method = "ANNOUNCE"))
        val header =
            "Digest username=\"iTunes\", realm=\"raop\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
        assertSame(SetupAuthorization.Authorized, connection.authorizeSetup("ANNOUNCE", uri, header, dacpId, null))
        assertEquals(listOf(PinResult.ACCEPTED), fixture.display.results)
        assertTrue(connection.isAuthenticated)
        assertTrue(random.drained)
    }

    @Test
    fun `Digest with qop matches the RFC 2617 section 3_5 example`() {
        val header =
            """
            Digest username="Mufasa",
                 realm="testrealm@host.com",
                 nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093",
                 uri="/dir/index.html",
                 qop=auth,
                 nc=00000001,
                 cnonce="0a4f113b",
                 response="6629fae49393a05397450978507c4ef1",
                 opaque="5ccc069c403ebaf9f0171e9517f40e41"
            """.trimIndent().replace("\n", "")
        val parameters = assertIs<Map<String, String>>(Digest.parse(header))
        assertEquals("6629fae49393a05397450978507c4ef1", Digest.expectedResponse(parameters, "Circle Of Life", "GET"))
        assertTrue(Digest.matches(parameters, "Circle Of Life", "GET"))
        assertEquals(
            "6629fae49393a05397450978507c4ef1",
            DigestClient.response(
                "Circle Of Life",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093",
                "/dir/index.html",
                username = "Mufasa",
                method = "GET",
                realm = "testrealm@host.com",
                qop = "auth",
            ),
        )
    }

    @Test
    fun `step 3 IVs add to byte 15 without carrying into byte 14`() {
        // This a (found by search) gives IV0 = ...e54cff: IVc and IVr wrap to ...4c00 and ...4c01. A receiver
        // that carried would use ...4d00 and fail the sender's tag.
        val forcing = BigInteger("7ca9993347f3d26d78af5d8b932df5d2a7b38a90d41c895d414da25b42a22262", 16)
        val random = ScriptedRandom(ScriptedRandom.codeBytes("1234"), salt, b)
        val fixture = PairingFixture(PinMode.DIGEST, random)
        fixture.service.pinStart(Addresses.sender, null)
        val connection = fixture.service.openConnection(Addresses.sender)
        val client = PinPairingClient(pin = "1234", identity = senderIdentity, privateKey = forcing)
        val step1 = ok(connection.pairSetupPin(client.step1()))
        val step2 = ok(connection.pairSetupPin(client.step2(step1.body)))
        val step3 = ok(connection.pairSetupPin(client.step3(step2.body)))
        assertEquals("42f1375681078dd9a4a396e99ee54cff", client.initialIv?.toHex())
        assertEquals(
            "42f1375681078dd9a4a396e99ee54c00",
            PinPairingClient.pinIv(hex("42f1375681078dd9a4a396e99ee54cff"), 1).toHex(),
        )
        assertContentEquals(fixture.service.identityPublicKey, client.receiverKey(step3.body))
        assertEquals(
            "42f1375681078dd9a4a396e99ee54cff",
            Crypto.sha512("Pair-Setup-AES-IV".toByteArray(), assertIs<ByteArray>(client.sessionKey)).copyOf(16).toHex(),
        )
    }
}
