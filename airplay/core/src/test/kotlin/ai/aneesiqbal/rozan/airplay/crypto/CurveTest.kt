package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurveTest {
    @Test
    fun `X25519 function matches RFC 7748 section 5_2 vectors`() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            X25519KeyPair
                .scalarMult(
                    hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                    hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
                ).toHex(),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            X25519KeyPair
                .scalarMult(
                    hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                    hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
                ).toHex(),
        )
    }

    @Test
    fun `X25519 iterated vectors after 1 and 1000 rounds match RFC 7748`() {
        var k = hex("0900000000000000000000000000000000000000000000000000000000000000")
        var u = k.copyOf()
        for (round in 1..1000) {
            val next = X25519KeyPair.scalarMult(k, u)
            u = k
            k = next
            if (round == 1) assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", k.toHex())
        }
        assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", k.toHex())
    }

    @Test
    fun `X25519 Diffie-Hellman matches RFC 7748 section 6_1`() {
        val alice =
            X25519KeyPair.fromPrivateKey(
                hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
            )
        val bob = X25519KeyPair.fromPrivateKey(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alice.publicKey.toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bob.publicKey.toHex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, alice.sharedSecret(bob.publicKey).toHex())
        assertEquals(shared, bob.sharedSecret(alice.publicKey).toHex())
    }

    @Test
    fun `X25519 generated pairs agree`() {
        val a = X25519KeyPair.generate()
        val b = X25519KeyPair.generate()
        assertContentEquals(a.sharedSecret(b.publicKey), b.sharedSecret(a.publicKey))
        assertFalse(a.publicKey.contentEquals(b.publicKey))
    }

    @Test
    fun `X25519 rejects small-order points that give an all-zero secret`() {
        val pair = X25519KeyPair.generate()
        val smallOrder =
            listOf(
                ByteArray(32),
                hex("0100000000000000000000000000000000000000000000000000000000000000"),
                // The two points of order 8, then p - 1, p and p + 1 (u-coordinates are reduced mod p).
                hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
                hex("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
                hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
                hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
                hex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            )
        for (point in smallOrder) {
            assertFailsWith<CryptoException>(point.toHex()) { pair.sharedSecret(point) }
        }
    }

    @Test
    fun `X25519 rejects keys of the wrong size`() {
        val pair = X25519KeyPair.generate()
        for (size in listOf(0, 31, 33, 64)) {
            assertFailsWith<CryptoException> { pair.sharedSecret(ByteArray(size) { 9 }) }
            assertFailsWith<CryptoException> { X25519KeyPair.fromPrivateKey(ByteArray(size)) }
        }
    }

    @Test
    fun `X25519 public key is a copy`() {
        val pair = X25519KeyPair.generate()
        val key = pair.publicKey
        key.fill(0)
        assertFalse(pair.publicKey.contentEquals(key))
    }

    private class Ed25519Vector(
        val seed: String,
        val publicKey: String,
        val message: String,
        val signature: String,
    )

    // RFC 8032 section 7.1, TEST 1 to 3.
    private val ed25519Vectors =
        listOf(
            Ed25519Vector(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Ed25519Vector(
                "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                    "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
            Ed25519Vector(
                "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                    "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            ),
        )

    private fun allEd25519Vectors(): List<Ed25519Vector> {
        val long = vectorFile("rfc8032-test1024.txt")
        return ed25519Vectors +
            Ed25519Vector(
                long.getValue("secret"),
                long.getValue("public"),
                long.getValue("message"),
                long.getValue("signature"),
            )
    }

    @Test
    fun `Ed25519 keys and signatures match RFC 8032 section 7_1`() {
        for (vector in allEd25519Vectors()) {
            val pair = Ed25519KeyPair.fromSeed(hex(vector.seed))
            assertEquals(vector.publicKey, pair.publicKey.toHex())
            assertEquals(vector.signature, pair.sign(hex(vector.message)).toHex())
            assertTrue(Ed25519.verify(hex(vector.publicKey), hex(vector.message), hex(vector.signature)))
        }
    }

    @Test
    fun `Ed25519 verify rejects tampered messages, signatures and keys`() {
        for (vector in allEd25519Vectors()) {
            val publicKey = hex(vector.publicKey)
            val message = hex(vector.message)
            val signature = hex(vector.signature)
            assertFalse(Ed25519.verify(publicKey, message + byteArrayOf(0), signature))
            for (i in signature.indices step 7) {
                val bad = signature.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
                assertFalse(Ed25519.verify(publicKey, message, bad), "signature byte $i")
            }
            val otherKey = Ed25519KeyPair.generate().publicKey
            assertFalse(Ed25519.verify(otherKey, message, signature))
        }
    }

    @Test
    fun `Ed25519 verify returns false for garbage without throwing`() {
        val random = kotlin.random.Random(1)
        val message = "hello".toByteArray()
        val valid = Ed25519KeyPair.generate()
        val signature = valid.sign(message)
        for (size in listOf(0, 31, 33, 63, 65)) {
            assertFalse(Ed25519.verify(ByteArray(size), message, signature))
            assertFalse(Ed25519.verify(valid.publicKey, message, ByteArray(size)))
        }
        repeat(500) {
            assertFalse(Ed25519.verify(random.nextBytes(32), message, random.nextBytes(64)))
            assertFalse(Ed25519.verify(valid.publicKey, message, random.nextBytes(64)))
        }
        // All-0xff key (not a valid encoding) and a non-canonical S (S = L + small, here all ones).
        assertFalse(Ed25519.verify(ByteArray(32) { 0xff.toByte() }, message, signature))
        val nonCanonical = signature.copyOf().also { it.fill(0xff.toByte(), 32, 64) }
        assertFalse(Ed25519.verify(valid.publicKey, message, nonCanonical))
    }

    @Test
    fun `Ed25519 generated keys sign and verify`() {
        val pair = Ed25519KeyPair.generate()
        val message = Crypto.randomBytes(100)
        assertTrue(Ed25519.verify(pair.publicKey, message, pair.sign(message)))
        assertFailsWith<CryptoException> { Ed25519KeyPair.fromSeed(ByteArray(31)) }
        assertFailsWith<CryptoException> { Ed25519KeyPair.fromSeed(ByteArray(64)) }
    }
}
