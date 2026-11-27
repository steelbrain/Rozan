package ai.aneesiqbal.rozan.airplay.fairplay

import javax.crypto.Cipher
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AesTest {
    @Test
    fun `S-box matches FIPS-197`() {
        assertEquals(0x63, Aes.SBOX[0x00])
        assertEquals(0x7C, Aes.SBOX[0x01])
        assertEquals(0xED, Aes.SBOX[0x53])
        assertEquals(0x16, Aes.SBOX[0xFF])
        assertEquals((0 until 256).toSet(), Aes.SBOX.toSet())
        for (x in 0 until 256) assertEquals(x, Aes.INV_SBOX[Aes.SBOX[x]])
    }

    @Test
    fun `round function with an expanded key matches FIPS-197 appendix C1`() {
        val roundKeys = TestCrypto.expandKey(hexToBytes("000102030405060708090a0b0c0d0e0f"))
        val block = hexToBytes("00112233445566778899aabbccddeeff")
        Aes.encryptBlock(block, 0, roundKeys)
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", block.toHex())
        Aes.decryptBlock(block, 0, roundKeys)
        assertEquals("00112233445566778899aabbccddeeff", block.toHex())
    }

    @Test
    fun `round function with an expanded key matches the platform cipher`() {
        val random = Random(1)
        repeat(200) {
            val key = random.nextBytes(16)
            val plain = random.nextBytes(16)
            val block = byteArrayOf(9) + plain + byteArrayOf(9)
            Aes.encryptBlock(block, 1, TestCrypto.expandKey(key))
            assertContentEquals(TestCrypto.aesEcb(Cipher.ENCRYPT_MODE, key, plain), block.copyOfRange(1, 17))
            val out = ByteArray(18)
            Aes.ecbDecrypt(key, block, 1, out, 2)
            assertContentEquals(plain, out.copyOfRange(2, 18))
            Aes.decryptBlock(block, 1, TestCrypto.expandKey(key))
            assertContentEquals(plain, block.copyOfRange(1, 17))
        }
    }
}
