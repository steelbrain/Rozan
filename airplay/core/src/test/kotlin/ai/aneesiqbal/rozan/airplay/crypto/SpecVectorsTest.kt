package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.params.KeyParameter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The primitives combined as docs/protocol/02-pairing.md section 10 and docs/protocol/03d-media-test-vectors.md
 * sections A to C use them. The session layer has its own protocol-level tests; these pin the crypto glue.
 */
class SpecVectorsTest {
    private fun sha512Prefix(
        label: String,
        secret: ByteArray,
    ): ByteArray = Crypto.sha512(label.toByteArray(Charsets.US_ASCII), secret).copyOf(16)

    // 02 section 10.1.
    private val pinK = hex("d55b8d6406a174139f90b41473460ae2a0f55c2c753d8b2a7da31acca9420de28d2f7a5a43b447e4")
    private val senderIdentity =
        Ed25519KeyPair.fromSeed(hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"))
    private val receiverIdentity =
        Ed25519KeyPair.fromSeed(hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"))

    @Test
    fun `pair-setup-pin step 3 GCM with 16-byte IVs matches 02 section 10_2`() {
        val gcmKey = sha512Prefix("Pair-Setup-AES-Key", pinK)
        val iv0 = sha512Prefix("Pair-Setup-AES-IV", pinK)
        assertEquals("1123f1b97cba2fb15eeca8c3474a992d", gcmKey.toHex())
        assertEquals("e5c2fd58ee8df195da8e5758af1f50c9", iv0.toHex())
        val ivClient = iv0.copyOf().also { it[15] = (it[15] + 1).toByte() }
        val ivReceiver = iv0.copyOf().also { it[15] = (it[15] + 2).toByte() }
        assertEquals("e5c2fd58ee8df195da8e5758af1f50ca", ivClient.toHex())
        assertEquals("e5c2fd58ee8df195da8e5758af1f50cb", ivReceiver.toHex())

        val senderPublic = "174553b456dddfc6908ecab1c101fe6ab21e2baa0617795b7d43a63482993fd5"
        val receiverPublic = "cd14b37f956e953194ff7fb73b3d81dcc561d61a7538094b7c3e1a643ee5f3aa"
        assertEquals(senderPublic, senderIdentity.publicKey.toHex())
        assertEquals(receiverPublic, receiverIdentity.publicKey.toHex())

        val request =
            hex("ea4fb875944de961bc74b3beb6337a5b5222dc13af795213989529587758b6b6" + "2a74d8217486c694ab0674f529e9835b")
        assertContentEquals(request, AesGcm.encrypt(gcmKey, ivClient, senderIdentity.publicKey))
        assertContentEquals(senderIdentity.publicKey, AesGcm.decrypt(gcmKey, ivClient, request))

        val response = AesGcm.encrypt(gcmKey, ivReceiver, receiverIdentity.publicKey)
        assertEquals(
            "0d71689aeee69d453899fbf0883339825c4a291eaaef7fde146c1db8aee46851" + "ce15bf83b46484febee3d98af0747f56",
            response.toHex(),
        )
    }

    @Test
    fun `legacy pair-verify matches 02 section 10_3`() {
        val senderEph = X25519KeyPair.fromPrivateKey(ByteArray(32) { (0xa0 + it).toByte() })
        val receiverEph = X25519KeyPair.fromPrivateKey(ByteArray(32) { (0xc0 + it).toByte() })
        assertEquals("605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c", senderEph.publicKey.toHex())
        assertEquals("dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44", receiverEph.publicKey.toHex())

        val shared = receiverEph.sharedSecret(senderEph.publicKey)
        assertEquals("3b87c5035cce13678dd93d2dbc3a3d42039ebec86444a1ff82cd61b84c6fe968", shared.toHex())
        val aesKey = sha512Prefix("Pair-Verify-AES-Key", shared)
        val aesIv = sha512Prefix("Pair-Verify-AES-IV", shared)
        assertEquals("17aea8a8febc8464dd79dcb9b66979c9", aesKey.toHex())
        assertEquals("29699cd358d299706e694ffac817b6ba", aesIv.toHex())

        val sigR = receiverIdentity.sign(receiverEph.publicKey + senderEph.publicKey)
        assertEquals(
            "3a728c2c17b9cfb8d1348f603b451aa3d2dd1e1d65fcd1927634e2b75857069f" +
                "054713161795a8cb1904b371aa2572553e62e386ef55b779daa2ccea19922c0b",
            sigR.toHex(),
        )
        // One keystream for both directions: bytes 0..63 encrypt sigR, bytes 64..127 decrypt sigS.
        val ctr = AesCtr(aesKey, aesIv)
        ctr.process(sigR)
        assertEquals(
            "dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44" +
                "cd6aa83e570869d7247b7e30f5f7a1af53b5cd73a3275b6eb9bb0772c4002e79" +
                "41d3b5876514c5eb385bb45daec98c1dcb86d5378ceb418f94544e748f0ec389",
            (receiverEph.publicKey + sigR).toHex(),
        )
        val request2 =
            hex(
                "00000000" +
                    "dc4f6eeab56146ae0338dd729f58437d66b9f4e14011b28afcbdaaf856a941d1" +
                    "53b6901aab5499b42e1db996b58287b663ee651c2fe986ed4c7a05ebb0ad9d20",
            )
        ctr.process(request2, 4, 64)
        val sigS = request2.copyOfRange(4, 68)
        assertEquals(
            "42859a8b0f3aa818657baa0a630ed77ae1eeb9a3a72a03327b4a1976c27ca31a" +
                "1a5d1d9f4a261e4091e77f3ef786e14fd663050d32cb3467b48194f68a1dff0a",
            sigS.toHex(),
        )
        assertTrue(Ed25519.verify(senderIdentity.publicKey, senderEph.publicKey + receiverEph.publicKey, sigS))
    }

    // 03d section A.
    private val fairplayKey = hex("00112233445566778899aabbccddeeff")
    private val ecdhSecret = ByteArray(32) { (0x40 + it).toByte() }
    private val sessionKey = Crypto.sha512(fairplayKey, ecdhSecret).copyOf(16)

    @Test
    fun `media key derivation matches 03d section A`() {
        assertEquals("43cc4f5b5ca43586ad422154f65b0e10", sessionKey.toHex())
        val id = java.lang.Long.toUnsignedString(0xC0FFEE1234567890UL.toLong())
        assertEquals("13907095936298285200", id)
        assertEquals("8bb49b485fce49813f8f44aea781cc0c", sha512Prefix("AirPlayStreamKey$id", sessionKey).toHex())
        assertEquals("29a8a98d0a98fff567f7493e8a363eab", sha512Prefix("AirPlayStreamIV$id", sessionKey).toHex())
        assertEquals("25196fd897a9542649376f356c82f61f", sha512Prefix("AirPlayStreamKey$id", fairplayKey).toHex())
        assertEquals("c0a42d423b234d9d6004147074258124", sha512Prefix("AirPlayStreamIV$id", fairplayKey).toHex())
    }

    private val videoKey = hex("8bb49b485fce49813f8f44aea781cc0c")
    private val videoIv = hex("29a8a98d0a98fff567f7493e8a363eab")
    private val packet1 = hex("00 00 00 11 65 88 84 00 21 01 02 03 04 05 06 07 08 09 0a 0b 0c")
    private val packet1Cipher = hex("36 f4 bf 15 60 a6 65 79 7e f8 0b 78 25 e3 7f fa f7 fa 84 cc 7e")
    private val packet2 =
        hex(
            """
            00 00 00 24 41 9a 22 44 80 81 82 83 84 85 86 87
            88 89 8a 8b 8c 8d 8e 8f 90 91 92 93 94 95 96 97
            98 99 9a 9b 9c 9d 9e 9f
            """,
        )
    private val packet2Cipher =
        hex(
            """
            8a 24 6a 95 91 8b 6e 0a 7f 74 69 a7 15 69 04 05
            bc 80 f8 17 80 1a 99 98 89 65 89 ca 48 59 7c df
            55 2e af 03 92 78 e7 c0
            """,
        )

    @Test
    fun `mirroring CTR keystream continues across packets as in 03d section B`() {
        val ctr = AesCtr(videoKey, videoIv)
        val buffer = packet1Cipher + packet2Cipher
        ctr.process(buffer, 0, 21)
        ctr.process(buffer, 21, 40)
        assertContentEquals(packet1 + packet2, buffer)

        val encrypt = AesCtr(videoKey, videoIv)
        val sealed = packet1 + packet2
        encrypt.process(sealed, 0, 21)
        encrypt.process(sealed, 21, 40)
        assertContentEquals(packet1Cipher + packet2Cipher, sealed)

        // The broken-implementation symptom from 03d: restarting the keystream for packet 2.
        val restarted = packet2Cipher.copyOf()
        AesCtr(videoKey, videoIv).process(restarted)
        assertEquals("bcd0d59194a58f73", restarted.copyOf(8).toHex())
    }

    @Test
    fun `mirroring CTR keystream blocks and carry match 03d section B`() {
        val keystream = ByteArray(48)
        val ctr = AesCtr(videoKey, videoIv)
        ctr.process(keystream, 0, 5)
        ctr.process(keystream, 5, 43)
        assertEquals(
            "36f4bf04052ee1795ff9097b21e679fd" + "fff38ec7728a246ab1d0114c4efff5eb" +
                "2491ec82823409729c0c97171719f41b",
            keystream.toHex(),
        )

        val carry = ByteArray(32)
        AesCtr(videoKey, hex("000102030405060708090a0bffffffff")).process(carry)
        val block = AESEngine.newInstance().apply { init(true, KeyParameter(videoKey)) }
        val expected = ByteArray(16)
        block.processBlock(hex("000102030405060708090a0c00000000"), 0, expected, 0)
        assertContentEquals(expected, carry.copyOfRange(16, 32))
    }

    @Test
    fun `audio CBC per packet with a clear tail matches 03d section C`() {
        val eiv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val cbc = AesCbc(sessionKey)
        val rtpHeader = ByteArray(12) { 0x55 }
        val packetA =
            rtpHeader +
                hex(
                    """
                    54 d8 c4 b5 8c 36 4a 8b 8f 68 35 0b 94 6b 66 16 3c 7b 7c c5 c4 af 09 3b
                    ca e3 a7 a6 25 49 96 03 20 21 22 23 24
                    """,
                )
        assertEquals(32, cbc.decryptWholeBlocks(eiv, packetA, 12, packetA.size - 12))
        assertContentEquals(rtpHeader, packetA.copyOf(12))
        assertContentEquals(ByteArray(37) { if (it == 0) 0x20 else it.toByte() }, packetA.copyOfRange(12, packetA.size))

        val packetB = hex("af 95 40 95 a8 3a 9c d1 26 f2 51 da f3 12 db 18")
        assertEquals(16, cbc.decryptWholeBlocks(eiv, packetB, 0, 16))
        assertEquals("8d101112131415161718191a1b1c1d1e", packetB.toHex())

        val noData = hex("00 68 34 00")
        assertEquals(0, cbc.decryptWholeBlocks(eiv, noData, 0, 4))
        assertEquals("00683400", noData.toHex())
    }
}
