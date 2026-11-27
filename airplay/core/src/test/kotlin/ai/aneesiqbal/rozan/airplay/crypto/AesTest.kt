package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AesTest {
    // NIST SP 800-38A appendix F.
    private val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
    private val plaintext =
        hex(
            """
            6bc1bee22e409f96e93d7e117393172a ae2d8a571e03ac9c9eb76fac45af8e51
            30c81c46a35ce411e5fbc1191a0a52ef f69f2445df4f9b17ad2b417be66c3710
            """,
        )
    private val ctrCounter = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    private val ctrCiphertext =
        hex(
            """
            874d6191b620e3261bef6864990db6ce 9806f66b7970fdff8617187bb9fffdff
            5ae4df3edbd5d35e5b4f09020db03eab 1e031dda2fbe03d1792170a0f3009cee
            """,
        )
    private val cbcIv = hex("000102030405060708090a0b0c0d0e0f")
    private val cbcCiphertext =
        hex(
            """
            7649abac8119b246cee98e9b12e9197d 5086cb9b507219ee95db113a917678b2
            73bed6b8e3c1743b7116e69e22229516 3ff1caa1681fac09120eca307586e1a7
            """,
        )

    @Test
    fun `CTR matches NIST SP 800-38A F_5_1 and F_5_2`() {
        val buffer = plaintext.copyOf()
        AesCtr(key, ctrCounter).process(buffer)
        assertContentEquals(ctrCiphertext, buffer)
        AesCtr(key, ctrCounter).process(buffer)
        assertContentEquals(plaintext, buffer)
    }

    @Test
    fun `CTR keeps its keystream position across calls of any size`() {
        val random = Random(7)
        val message = random.nextBytes(5000)
        val whole = message.copyOf().also { AesCtr(key, ctrCounter).process(it) }
        repeat(50) {
            val ctr = AesCtr(key, ctrCounter)
            val inPlace = message.copyOf()
            val separate = ByteArray(message.size + 3)
            var position = 0
            while (position < message.size) {
                val n = minOf(random.nextInt(0, 70), message.size - position)
                if (random.nextBoolean()) {
                    ctr.process(inPlace, position, n)
                    inPlace.copyInto(separate, position + 3, position, position + n)
                } else {
                    ctr.process(message, position, n, separate, position + 3)
                    separate.copyInto(inPlace, position, position + 3, position + 3 + n)
                }
                position += n
            }
            assertContentEquals(whole, inPlace)
            assertContentEquals(whole, separate.copyOfRange(3, separate.size))
        }
    }

    @Test
    fun `CTR counter wraps as a 128-bit integer`() {
        // openssl enc -aes-128-ctr, IV all ones: blocks are AES(ff..ff), AES(00..00), AES(00..01).
        val buffer = ByteArray(48)
        val ctr = AesCtr(key, ByteArray(16) { 0xff.toByte() })
        ctr.process(buffer, 0, 20)
        ctr.process(buffer, 20, 28)
        assertEquals(
            "8af2860142f786f409307c1a3f7eaaac7df76b0c1ab899b33e42f047b91b546f57127d4034b1bebfaef466b9c7726fc6",
            buffer.toHex(),
        )
    }

    @Test
    fun `CTR rejects bad sizes and ranges`() {
        assertFailsWith<CryptoException> { AesCtr(ByteArray(15), ctrCounter) }
        assertFailsWith<CryptoException> { AesCtr(ByteArray(0), ctrCounter) }
        assertFailsWith<CryptoException> { AesCtr(key, ByteArray(12)) }
        val ctr = AesCtr(key, ctrCounter)
        assertFailsWith<IllegalArgumentException> { ctr.process(ByteArray(10), 5, 6) }
        assertFailsWith<IllegalArgumentException> { ctr.process(ByteArray(10), -1, 2) }
        assertFailsWith<IllegalArgumentException> { ctr.process(ByteArray(10), 0, 10, ByteArray(9), 0) }
        AesCtr(ByteArray(32), ctrCounter).process(ByteArray(0))
    }

    @Test
    fun `CBC matches NIST SP 800-38A F_2_1 and F_2_2`() {
        val cbc = AesCbc(key)
        assertContentEquals(cbcCiphertext, cbc.encrypt(cbcIv, plaintext))
        assertContentEquals(plaintext, cbc.decrypt(cbcIv, cbcCiphertext))
        // Decrypting from the second block needs the first ciphertext block as the IV.
        val middle = cbc.decrypt(cbcCiphertext.copyOfRange(0, 16), cbcCiphertext, 16, 32)
        assertContentEquals(plaintext.copyOfRange(16, 48), middle)
    }

    @Test
    fun `CBC whole-block decryption leaves a trailing partial block untouched`() {
        val cbc = AesCbc(key)
        val tail = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = byteArrayOf(9, 9) + cbcCiphertext + tail
        assertEquals(64, cbc.decryptWholeBlocks(cbcIv, buffer, 2, cbcCiphertext.size + tail.size))
        assertContentEquals(byteArrayOf(9, 9) + plaintext + tail, buffer)

        val short = byteArrayOf(1, 2, 3)
        assertEquals(0, cbc.decryptWholeBlocks(cbcIv, short, 0, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), short)
    }

    @Test
    fun `CBC whole-block decryption matches JCA across chunk boundaries and calls`() {
        val random = Random(11)
        val cbc = AesCbc(key)
        for (length in listOf(0, 15, 16, 17, 31, 32, 4095, 4096, 4097, 4111, 4112, 8192, 10_000, 20_007)) {
            val iv = random.nextBytes(16)
            val data = random.nextBytes(length)
            val whole = length - length % 16
            val jca =
                jcaCipher("AES/CBC/NoPadding", Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val expected = jca.doFinal(data, 0, whole) + data.copyOfRange(whole, length)
            val buffer = data.copyOf()
            assertEquals(whole, cbc.decryptWholeBlocks(iv, buffer, 0, length))
            assertContentEquals(expected, buffer, "length $length")
        }
    }

    @Test
    fun `CBC rejects bad sizes`() {
        assertFailsWith<CryptoException> { AesCbc(ByteArray(17)) }
        val cbc = AesCbc(key)
        assertFailsWith<CryptoException> { cbc.encrypt(cbcIv, ByteArray(15)) }
        assertFailsWith<CryptoException> { cbc.decrypt(cbcIv, ByteArray(17)) }
        assertFailsWith<CryptoException> { cbc.decrypt(ByteArray(8), ByteArray(16)) }
        assertFailsWith<CryptoException> { cbc.decryptWholeBlocks(ByteArray(15), ByteArray(16), 0, 16) }
        assertFailsWith<IllegalArgumentException> { cbc.decryptWholeBlocks(cbcIv, ByteArray(16), 1, 16) }
    }

    @Test
    fun `GCM matches the GCM specification test cases`() {
        // McGrew and Viega, "The Galois/Counter Mode of Operation", test cases 2, 3 and 4.
        assertEquals(
            "0388dace60b6a392f328c2b971b2fe78" + "ab6e47d42cec13bdf53a67b21257bddf",
            AesGcm.encrypt(ByteArray(16), ByteArray(12), ByteArray(16)).toHex(),
        )
        val key = hex("feffe9928665731c6d6a8f9467308308")
        val nonce = hex("cafebabefacedbaddecaf888")
        val p3 =
            hex(
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                    "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
            )
        val c3 =
            "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
                "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091473f5985"
        assertEquals(c3 + "4d5c2af327cd64a62cf35abd2ba6fab4", AesGcm.encrypt(key, nonce, p3).toHex())
        val p4 = p3.copyOf(60)
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val sealed = AesGcm.encrypt(key, nonce, p4, aad)
        assertEquals(c3.substring(0, 120) + "5bc94fbc3221a5db94fae95ae7121a47", sealed.toHex())
        assertContentEquals(p4, AesGcm.decrypt(key, nonce, sealed, aad))
    }

    @Test
    fun `GCM agrees with the JDK provider for 12 and 16-byte nonces`() {
        val random = Random(3)
        repeat(40) {
            val key = random.nextBytes(listOf(16, 24, 32)[it % 3])
            val nonce = random.nextBytes(if (it % 2 == 0) 12 else 16)
            val message = random.nextBytes(random.nextInt(0, 300))
            val aad = random.nextBytes(random.nextInt(0, 40))
            val jdk =
                jcaCipher(
                    "AES/GCM/NoPadding",
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce),
                )
            jdk.updateAAD(aad)
            val expected = jdk.doFinal(message)
            assertContentEquals(expected, AesGcm.encrypt(key, nonce, message, aad))
            assertContentEquals(message, AesGcm.decrypt(key, nonce, expected, aad))
        }
    }

    @Test
    fun `GCM rejects tampering and bad sizes`() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { 1 }
        val sealed = AesGcm.encrypt(key, nonce, "secret".toByteArray(), "aad".toByteArray())
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() }
            assertFailsWith<CryptoException> { AesGcm.decrypt(key, nonce, bad, "aad".toByteArray()) }
        }
        assertFailsWith<CryptoException> { AesGcm.decrypt(key, nonce, sealed, "aae".toByteArray()) }
        assertFailsWith<CryptoException> { AesGcm.decrypt(key, nonce, sealed.copyOf(15)) }
        assertFailsWith<CryptoException> { AesGcm.decrypt(key, nonce, ByteArray(0)) }
        assertFailsWith<CryptoException> { AesGcm.encrypt(key, ByteArray(8), ByteArray(1)) }
        assertFailsWith<CryptoException> { AesGcm.encrypt(key, ByteArray(13), ByteArray(1)) }
        assertFailsWith<CryptoException> { AesGcm.encrypt(ByteArray(10), nonce, ByteArray(1)) }
    }
}
