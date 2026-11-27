package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Srp6aServerTest {
    // RFC 5054 Appendix B.
    private val rfcUser = "alice".toByteArray()
    private val rfcPassword = "password123".toByteArray()
    private val rfcSalt = hex("BEB25379 D1A8581E B5A72767 3A2441EE")
    private val rfcA = BigInteger("60975527035CF2AD1989806F0407210BC81EDC04E2762A56AFD529DDDA2D4393", 16)
    private val rfcB = BigInteger("E487CB59D31AC550471E81F00F6928E01DDA08E974A004F49E61F5D105284D20", 16)
    private val rfcVerifier =
        hex(
            """
            7E273DE8 696FFC4F 4E337D05 B4B375BE B0DDE156 9E8FA00A 9886D812
            9BADA1F1 822223CA 1A605B53 0E379BA4 729FDC59 F105B478 7E5186F5
            C671085A 1447B52A 48CF1970 B4FB6F84 00BBF4CE BFBB1681 52E08AB5
            EA53D15C 1AFF87B2 B9DA6E04 E058AD51 CC72BFC9 033B564E 26480D78
            E955A5E2 9E7AB245 DB2BE315 E2099AFB
            """,
        )
    private val rfcPublicA =
        hex(
            """
            61D5E490 F6F1B795 47B0704C 436F523D D0E560F0 C64115BB 72557EC4
            4352E890 3211C046 92272D8B 2D1A5358 A2CF1B6E 0BFCF99F 921530EC
            8E393561 79EAE45E 42BA92AE ACED8251 71E1E8B9 AF6D9C03 E1327F44
            BE087EF0 6530E69F 66615261 EEF54073 CA11CF58 58F0EDFD FE15EFEA
            B349EF5D 76988A36 72FAC47B 0769447B
            """,
        )
    private val rfcPublicB =
        hex(
            """
            BD0C6151 2C692C0C B6D041FA 01BB152D 4916A1E7 7AF46AE1 05393011
            BAF38964 DC46A067 0DD125B9 5A981652 236F99D9 B681CBF8 7837EC99
            6C6DA044 53728610 D0C6DDB5 8B318885 D7D82C7F 8DEB75CE 7BD4FBAA
            37089E6F 9C6059F3 88838E7A 00030B33 1EB76840 910440B1 B27AAEAE
            EB4012B7 D7665238 A8E3FB00 4B117B58
            """,
        )
    private val rfcPremaster =
        hex(
            """
            B0DC82BA BCF30674 AE450C02 87745E79 90A3381F 63B387AA F271A10D
            233861E3 59B48220 F7C4693C 9AE12B0A 6F67809F 0876E2D0 13800D6C
            41BB59B6 D5979B5C 00A172B4 A2A5903A 0BDCAF8A 709585EB 2AFAFA8F
            3499B200 210DCC1F 10EB3394 3CD67FC8 8A2F39A4 BE5BEC4E C0A3212D
            C346D7E4 74B29EDE 8A469FFE CA686E5A
            """,
        )
    private val rfcParameters = SrpParameters(SrpGroup.RFC5054_1024, "SHA-1")

    private fun rfcServer(parameters: SrpParameters = rfcParameters) =
        Srp6aServer(parameters, rfcUser, rfcSalt, BigInteger(1, rfcVerifier), rfcB)

    @Test
    fun `k, x and v match RFC 5054 appendix B`() {
        assertEquals("7556aa045aef2cdd07abaf0f665c3e818913186f", Srp6a.multiplier(rfcParameters).toString(16))
        assertEquals(
            "94b7555aabe9127cc58ccf4993db6cf84d16c124",
            Srp6a.privateKey(rfcParameters, rfcUser, rfcPassword, rfcSalt).toString(16),
        )
        assertContentEquals(rfcVerifier, Srp6a.verifier(rfcParameters, rfcUser, rfcPassword, rfcSalt).toUnsignedBytes())
    }

    @Test
    fun `B, u and the premaster secret match RFC 5054 appendix B`() {
        assertContentEquals(rfcPublicB, rfcServer().publicKey)
        val client = SrpTestClient(rfcParameters, rfcUser, rfcPassword, rfcA)
        assertContentEquals(rfcPublicA, client.publicKey().toUnsignedBytes())
        assertEquals(
            "ce38b9593487da98554ed47d70a7ae5f462ef019",
            Srp6a.scrambler(rfcParameters, BigInteger(1, rfcPublicA), BigInteger(1, rfcPublicB)).toString(16),
        )

        var serverPremaster = ByteArray(0)
        val capturing =
            SrpParameters(SrpGroup.RFC5054_1024, "SHA-1", { p, s ->
                serverPremaster = s
                p.hash(s)
            })
        val server = rfcServer(capturing)
        val m1 = SrpTestClient(capturing, rfcUser, rfcPassword, rfcA).clientProof(rfcSalt, server.publicKey)
        server.verifyClient(rfcPublicA, m1)
        assertContentEquals(rfcPremaster, serverPremaster)
    }

    @Test
    fun `interleaved K and RFC 2945 proofs match an independent computation`() {
        // Computed with Python hashlib from the RFC 2945 formulas over the RFC 5054 appendix B exchange.
        val parameters = SrpParameters(SrpGroup.RFC5054_1024, "SHA-1", SrpKeyDerivation.INTERLEAVED, SrpProofs.RFC2945)
        val server = rfcServer(parameters)
        val m2 = server.verifyClient(rfcPublicA, hex("8b5fdb7db0346e353689d2edfacec647a813e6d0"))
        assertEquals("e8149a44a9d5bf552a4cc9120c545301a537f227", m2.toHex())
        assertEquals(
            "2b8cabcede81b9765a37fc68fbde512326a156512bc0dac5fd64d2c7c3bf857a56b0c0a8ceed18c0",
            server.sessionKey.toHex(),
        )
    }

    @Test
    fun `interleave drops leading zeros and an odd first byte`() {
        val s = hex("0000070102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324252627")
        assertEquals(
            "c27e81bfdf0ed7376ece1c2a7cdf521d80fd2318653c26ab72f471876c1d9b9a896493ba6cbb11c5",
            SrpKeyDerivation.INTERLEAVED.deriveKey(rfcParameters, s).toHex(),
        )
    }

    @Test
    fun `HomeKit preset matches an independent SHA-512 3072-bit computation`() {
        val vector = vectorFile("srp-sha512-3072.txt")
        val user = "Pair-Setup".toByteArray()
        val salt = hex(vector.getValue("salt"))
        val verifier = Srp6a.verifier(SrpParameters.HOMEKIT, user, "123-45-678".toByteArray(), salt)
        assertEquals(vector.getValue("v"), verifier.toUnsignedBytes().toHex())
        val server = Srp6aServer(SrpParameters.HOMEKIT, user, salt, verifier, BigInteger(vector.getValue("b"), 16))
        assertEquals(vector.getValue("B"), server.publicKey.toHex())
        val m2 = server.verifyClient(hex(vector.getValue("A")), hex(vector.getValue("M1")))
        assertEquals(vector.getValue("M2"), m2.toHex())
        assertEquals(vector.getValue("K"), server.sessionKey.toHex())
    }

    @Test
    fun `legacy PIN preset matches the pairing spec vector`() {
        // docs/protocol/02-pairing.md section 10.1.
        val vector = vectorFile("srp-legacy-pin.txt")
        val parameters = SrpParameters.LEGACY_PIN
        val user = "AA:BB:CC:DD:EE:FF".toByteArray()
        val pin = "1234".toByteArray()
        val salt = hex(vector.getValue("s"))
        assertEquals(vector.getValue("x"), Srp6a.privateKey(parameters, user, pin, salt).toUnsignedBytes().toHex())
        val verifier = Srp6a.verifier(parameters, user, pin, salt)
        assertEquals(vector.getValue("v"), verifier.toUnsignedBytes().toHex())
        assertEquals(vector.getValue("k"), Srp6a.multiplier(parameters).toUnsignedBytes().toHex())

        val server = Srp6aServer(parameters, user, salt, verifier, BigInteger(vector.getValue("b"), 16))
        assertEquals(vector.getValue("B"), server.publicKey.toHex())
        val client = SrpTestClient(parameters, user, pin, BigInteger(vector.getValue("a"), 16))
        assertEquals(vector.getValue("A"), client.publicKey().toUnsignedBytes().toHex())
        assertEquals(
            vector.getValue("u"),
            Srp6a.scrambler(parameters, client.publicKey(), BigInteger(1, server.publicKey)).toUnsignedBytes().toHex(),
        )
        assertEquals(
            vector.getValue("K"),
            SrpKeyDerivation.HASH_WITH_COUNTER.deriveKey(parameters, hex(vector.getValue("S"))).toHex(),
        )

        val m1 = client.clientProof(server.salt, server.publicKey)
        assertEquals(vector.getValue("M1"), m1.toHex())
        val m2 = server.verifyClient(hex(vector.getValue("A")), m1)
        assertEquals(vector.getValue("M2"), m2.toHex())
        assertEquals(vector.getValue("K"), server.sessionKey.toHex())
        assertEquals(40, server.sessionKey.size)
    }

    @Test
    fun `round trip with a client on the 2048 and 3072-bit groups`() {
        val cases =
            listOf(
                SrpParameters.LEGACY_PIN,
                SrpParameters(SrpGroup.RFC5054_2048, "SHA-256"),
                SrpParameters(SrpGroup.RFC5054_2048, "SHA-1", SrpKeyDerivation.INTERLEAVED),
                SrpParameters.HOMEKIT,
            )
        for (parameters in cases) {
            val user = "Pair-Setup".toByteArray()
            val pin = "3939".toByteArray()
            val server = Srp6aServer.fromPassword(parameters, user, pin)
            assertEquals(Srp6aServer.DEFAULT_SALT_SIZE, server.salt.size)
            val client = SrpTestClient(parameters, user, pin)
            val m1 = client.clientProof(server.salt, server.publicKey)
            val m2 = server.verifyClient(client.publicKey().toUnsignedBytes(), m1)
            assertTrue(client.verifyServer(m1, m2))
            assertContentEquals(client.sessionKey(), server.sessionKey)
        }
    }

    @Test
    fun `generated salts and public keys have a single encoding`() {
        // docs/protocol/02-pairing.md section 5.2: no salt starting with 0x00, and B always full length.
        repeat(300) {
            val server = Srp6aServer.fromPassword(SrpParameters.LEGACY_PIN, rfcUser, "0042".toByteArray())
            assertTrue(server.salt[0] != 0.toByte())
            assertEquals(SrpGroup.RFC5054_2048.byteLength, server.publicKey.size)
        }
    }

    @Test
    fun `server built from a stored verifier accepts the right password`() {
        val salt = Crypto.randomBytes(16)
        val verifier = Srp6a.verifier(SrpParameters.HOMEKIT, rfcUser, rfcPassword, salt).toUnsignedBytes()
        val server = Srp6aServer.fromVerifier(SrpParameters.HOMEKIT, rfcUser, salt, verifier)
        val client = SrpTestClient(SrpParameters.HOMEKIT, rfcUser, rfcPassword)
        val m1 = client.clientProof(server.salt, server.publicKey)
        assertTrue(client.verifyServer(m1, server.verifyClient(client.publicKey().toUnsignedBytes(), m1)))
    }

    @Test
    fun `padded client public key is accepted`() {
        val server = Srp6aServer.fromPassword(rfcParameters, rfcUser, rfcPassword)
        val client = SrpTestClient(rfcParameters, rfcUser, rfcPassword)
        val m1 = client.clientProof(server.salt, server.publicKey)
        val padded = SrpGroup.RFC5054_1024.pad(client.publicKey().toUnsignedBytes())
        assertEquals(SrpGroup.RFC5054_1024.byteLength, padded.size)
        assertTrue(client.verifyServer(m1, server.verifyClient(padded, m1)))
    }

    @Test
    fun `wrong password is rejected and the server cannot be retried`() {
        val server = Srp6aServer.fromPassword(SrpParameters.LEGACY_PIN, rfcUser, "1234".toByteArray())
        val wrong = SrpTestClient(SrpParameters.LEGACY_PIN, rfcUser, "1235".toByteArray())
        val m1 = wrong.clientProof(server.salt, server.publicKey)
        assertFailsWith<CryptoException> { server.verifyClient(wrong.publicKey().toUnsignedBytes(), m1) }
        assertFailsWith<IllegalStateException> { server.sessionKey }

        val right = SrpTestClient(SrpParameters.LEGACY_PIN, rfcUser, "1234".toByteArray())
        val goodProof = right.clientProof(server.salt, server.publicKey)
        assertFailsWith<CryptoException> { server.verifyClient(right.publicKey().toUnsignedBytes(), goodProof) }
    }

    @Test
    fun `a used server rejects a second verification even after success`() {
        val server = Srp6aServer.fromPassword(rfcParameters, rfcUser, rfcPassword)
        val client = SrpTestClient(rfcParameters, rfcUser, rfcPassword)
        val m1 = client.clientProof(server.salt, server.publicKey)
        server.verifyClient(client.publicKey().toUnsignedBytes(), m1)
        assertFailsWith<CryptoException> { server.verifyClient(client.publicKey().toUnsignedBytes(), m1) }
    }

    @Test
    fun `invalid client public keys are rejected`() {
        val group = SrpGroup.RFC5054_1024
        val n = group.prime
        val invalid =
            listOf(
                ByteArray(0),
                ByteArray(1),
                ByteArray(group.byteLength),
                n.toUnsignedBytes(),
                n.add(BigInteger.ONE).toUnsignedBytes(),
                ByteArray(group.byteLength) { 0xff.toByte() },
                n.shiftLeft(1).toUnsignedBytes(),
                ByteArray(group.byteLength + 1).also { it[it.size - 1] = 1 },
                ByteArray(1_000_000) { 1 },
            )
        for (a in invalid) {
            val server = Srp6aServer.fromPassword(rfcParameters, rfcUser, rfcPassword)
            assertFailsWith<CryptoException>("A of ${a.size} bytes") { server.verifyClient(a, ByteArray(20)) }
        }
    }

    @Test
    fun `garbage proofs are rejected`() {
        for (proof in listOf(ByteArray(0), ByteArray(19), ByteArray(20), ByteArray(21), ByteArray(4096) { 7 })) {
            val server = Srp6aServer.fromPassword(rfcParameters, rfcUser, rfcPassword)
            val client = SrpTestClient(rfcParameters, rfcUser, rfcPassword)
            client.clientProof(server.salt, server.publicKey)
            assertFailsWith<CryptoException> { server.verifyClient(client.publicKey().toUnsignedBytes(), proof) }
        }
    }

    @Test
    fun `custom proof strategy is used on both sides`() {
        val keyed =
            object : SrpProofs {
                override fun clientProof(transcript: SrpTranscript) =
                    transcript.hash("client".toByteArray(), transcript.sessionKey, transcript.serverPublicKey)

                override fun serverProof(
                    transcript: SrpTranscript,
                    clientProof: ByteArray,
                ) = transcript.hash("server".toByteArray(), transcript.sessionKey, clientProof)
            }
        val parameters = SrpParameters(SrpGroup.RFC5054_2048, "SHA-512", SrpKeyDerivation.HASH, keyed)
        val server = Srp6aServer.fromPassword(parameters, rfcUser, rfcPassword)
        val client = SrpTestClient(parameters, rfcUser, rfcPassword)
        val m1 = client.clientProof(server.salt, server.publicKey)
        assertContentEquals(parameters.hash("client".toByteArray(), client.sessionKey(), server.publicKey), m1)
        val m2 = server.verifyClient(client.publicKey().toUnsignedBytes(), m1)
        assertTrue(client.verifyServer(m1, m2))
        val rfc2945 =
            SrpTranscript(
                SrpParameters(SrpGroup.RFC5054_2048, "SHA-512"),
                rfcUser,
                server.salt,
                client.publicKey().toUnsignedBytes(),
                server.publicKey,
                client.sessionKey(),
            )
        assertFalse(m1.contentEquals(SrpProofs.RFC2945.clientProof(rfc2945)))
    }

    @Test
    fun `group primes are safe primes of the documented sizes`() {
        val expected =
            mapOf(
                SrpGroup.RFC5054_1024 to Pair(1024, 2L),
                SrpGroup.RFC5054_2048 to Pair(2048, 2L),
                SrpGroup.RFC5054_3072 to Pair(3072, 5L),
            )
        for ((group, sizeAndGenerator) in expected) {
            assertEquals(sizeAndGenerator.first, group.prime.bitLength())
            assertEquals(sizeAndGenerator.first / 8, group.byteLength)
            assertEquals(BigInteger.valueOf(sizeAndGenerator.second), group.generator)
            assertTrue(group.prime.isProbablePrime(32))
            assertTrue(group.prime.shiftRight(1).isProbablePrime(32))
        }
    }

    @Test
    fun `pad left-pads and rejects oversized values`() {
        val group = SrpGroup.RFC5054_1024
        val padded = group.pad(byteArrayOf(0, 0, 1, 2))
        assertEquals(128, padded.size)
        assertContentEquals(byteArrayOf(1, 2), padded.copyOfRange(126, 128))
        assertTrue(padded.copyOfRange(0, 126).all { it == 0.toByte() })
        assertFailsWith<IllegalArgumentException> { group.pad(ByteArray(129) { 1 }) }
    }

    @Test
    fun `invalid configuration is rejected up front`() {
        assertFailsWith<IllegalArgumentException> { SrpParameters(SrpGroup.RFC5054_1024, "NO-SUCH-HASH") }
        assertFailsWith<IllegalArgumentException> { SrpGroup(BigInteger.valueOf(23), BigInteger.TEN) }
        assertFailsWith<IllegalArgumentException> {
            SrpGroup(SrpGroup.RFC5054_1024.prime, SrpGroup.RFC5054_1024.prime)
        }
        assertFailsWith<IllegalArgumentException> {
            Srp6aServer.fromVerifier(rfcParameters, rfcUser, rfcSalt, ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> {
            Srp6aServer.fromVerifier(rfcParameters, rfcUser, rfcSalt, SrpGroup.RFC5054_1024.prime.toUnsignedBytes())
        }
    }
}
