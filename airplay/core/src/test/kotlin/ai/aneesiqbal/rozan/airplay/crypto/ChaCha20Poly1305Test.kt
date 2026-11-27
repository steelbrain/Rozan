package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChaCha20Poly1305Test {
    @Test
    fun `encryption matches RFC 8439 section 2_8_2`() {
        val plaintext =
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                "sunscreen would be it."
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("07000000" + "4041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = ChaCha20Poly1305.encrypt(key, nonce, plaintext.toByteArray(), aad)
        val ciphertext =
            """
            d31a8d34648e60db7b86afbc53ef7ec2 a4aded51296e08fea9e2b5a736ee62d6
            3dbea45e8ca9671282fafb69da92728b 1a71de0a9e060b2905d6a5b67ecd3b36
            92ddbd7f2d778b8c9803aee328091b58 fab324e4fad675945585808b4831d7bc
            3ff4def08e4b7a9de576d26586cec64b 6116
            """
        assertEquals(hex(ciphertext).toHex() + "1ae10b594f09e26a7e902ecbd0600691", sealed.toHex())
        assertEquals(plaintext, String(ChaCha20Poly1305.decrypt(key, nonce, sealed, aad)))
    }

    @Test
    fun `decryption matches RFC 8439 appendix A_5`() {
        val key = hex("1c9240a5eb55d38af333888604f6b5f0473917c1402b80099dca5cbc207075c0")
        val nonce = hex("000000000102030405060708")
        val aad = hex("f33388860000000000004e91")
        val ciphertext =
            hex(
                """
                64a0861575861af460f062c79be643bd 5e805cfd345cf389f108670ac76c8cb2
                4c6cfc18755d43eea09ee94e382d26b0 bdb7b73c321b0100d4f03b7f355894cf
                332f830e710b97ce98c8a84abd0b9481 14ad176e008d33bd60f982b1ff37c855
                9797a06ef4f0ef61c186324e2b350638 3606907b6a7c02b0f9f6157b53c867e4
                b9166c767b804d46a59b5216cde7a4e9 9040c5a40433225ee282a1b0a06c523e
                af4534d7f83fa1155b0047718cbc546a 0d072b04b3564eea1b422273f548271a
                0bb2316053fa76991955ebd63159434e cebb4e466dae5a1073a6727627097a10
                49e617d91d361094fa68f0ff77987130 305beaba2eda04df997b714d6c6f2c29
                a6ad5cb4022b02709b
                """,
            )
        val tag = hex("eead9d67890cbb22392336fea1851f38")
        val plaintext = ChaCha20Poly1305.decrypt(key, nonce, ciphertext + tag, aad)
        val text = String(plaintext, Charsets.UTF_8)
        assertTrue(text.startsWith("Internet-Drafts are draft documents valid for a maximum of six months"))
        // The RFC's text ends in "/" + U+201C + "work in progress./" + U+201D.
        assertContentEquals(hex("2fe2809c776f726b20696e2070726f67726573732e2fe2809d"), plaintext.copyOfRange(240, 265))
        assertEquals(265, plaintext.size)
    }

    @Test
    fun `offsets select part of the input`() {
        val key = Crypto.randomBytes(32)
        val nonce = ChaCha20Poly1305.counterNonce(5)
        val framed = byteArrayOf(1, 2) + "payload".toByteArray() + byteArrayOf(3)
        val sealed = ChaCha20Poly1305.encrypt(key, nonce, framed, offset = 2, length = 7)
        val wrapped = byteArrayOf(9) + sealed + byteArrayOf(9, 9)
        assertEquals("payload", String(ChaCha20Poly1305.decrypt(key, nonce, wrapped, offset = 1, length = sealed.size)))
    }

    @Test
    fun `tampering, wrong nonce or AAD, and short input are rejected`() {
        val random = Random(5)
        val key = random.nextBytes(32)
        val nonce = ChaCha20Poly1305.counterNonce(1)
        val aad = byteArrayOf(0x10, 0x00)
        val sealed = ChaCha20Poly1305.encrypt(key, nonce, random.nextBytes(40), aad)
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException> { ChaCha20Poly1305.decrypt(key, nonce, bad, aad) }
        }
        assertFailsWith<CryptoException> {
            ChaCha20Poly1305.decrypt(
                key,
                ChaCha20Poly1305.counterNonce(2),
                sealed,
                aad,
            )
        }
        assertFailsWith<CryptoException> { ChaCha20Poly1305.decrypt(key, nonce, sealed) }
        for (size in 0 until 16) {
            assertFailsWith<CryptoException> { ChaCha20Poly1305.decrypt(key, nonce, ByteArray(size), aad) }
        }
        assertFailsWith<CryptoException> { ChaCha20Poly1305.encrypt(ByteArray(16), nonce, ByteArray(1)) }
        assertFailsWith<CryptoException> { ChaCha20Poly1305.encrypt(key, ByteArray(8), ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> {
            ChaCha20Poly1305.decrypt(key, nonce, sealed, offset = 1, length = sealed.size)
        }
        repeat(200) {
            val garbage = random.nextBytes(random.nextInt(0, 200))
            assertFailsWith<CryptoException> { ChaCha20Poly1305.decrypt(key, nonce, garbage, aad) }
        }
    }

    @Test
    fun `empty plaintext round trips`() {
        val key = Crypto.randomBytes(32)
        val nonce = ChaCha20Poly1305.labelNonce("PS-Msg05")
        val sealed = ChaCha20Poly1305.encrypt(key, nonce, ByteArray(0))
        assertEquals(16, sealed.size)
        assertEquals(0, ChaCha20Poly1305.decrypt(key, nonce, sealed).size)
    }

    @Test
    fun `counter nonce is four zero bytes then the little-endian counter`() {
        assertEquals("000000000000000000000000", ChaCha20Poly1305.counterNonce(0).toHex())
        assertEquals("000000000100000000000000", ChaCha20Poly1305.counterNonce(1).toHex())
        assertEquals("000000000201000000000000", ChaCha20Poly1305.counterNonce(0x102).toHex())
        assertEquals("000000000807060504030201", ChaCha20Poly1305.counterNonce(0x0102030405060708).toHex())
        assertEquals("00000000ffffffffffffffff", ChaCha20Poly1305.counterNonce(-1).toHex())
    }

    @Test
    fun `label nonce is the ASCII label right-aligned in 12 bytes`() {
        assertContentEquals(hex("00000000") + "PV-Msg02".toByteArray(), ChaCha20Poly1305.labelNonce("PV-Msg02"))
        assertContentEquals(ByteArray(12), ChaCha20Poly1305.labelNonce(""))
        assertContentEquals("ABCDEFGHIJKL".toByteArray(), ChaCha20Poly1305.labelNonce("ABCDEFGHIJKL"))
        assertFailsWith<IllegalArgumentException> { ChaCha20Poly1305.labelNonce("ABCDEFGHIJKLM") }
        assertFailsWith<IllegalArgumentException> { ChaCha20Poly1305.labelNonce("PV-Msg0é") }
    }
}
