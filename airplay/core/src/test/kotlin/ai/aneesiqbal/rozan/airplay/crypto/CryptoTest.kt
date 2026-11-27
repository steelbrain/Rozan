package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.ProtocolException
import ai.aneesiqbal.rozan.airplay.toHex
import java.io.IOException
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun `constant-time equals compares content and length`() {
        assertTrue(Crypto.constantTimeEquals(ByteArray(0), ByteArray(0)))
        assertTrue(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(0, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(-128), byteArrayOf(127)))
    }

    @Test
    fun `random bytes have the requested size and differ`() {
        assertEquals(0, Crypto.randomBytes(0).size)
        val a = Crypto.randomBytes(32)
        val b = Crypto.randomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
        assertFailsWith<IllegalArgumentException> { Crypto.randomBytes(-1) }
    }

    @Test
    fun `digests hash the concatenation of their parts`() {
        // FIPS 180-2 "abc" examples.
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Crypto.sha512("a".toByteArray(), "bc".toByteArray()).toHex(),
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            Crypto.digest("SHA-1", "ab".toByteArray(), "c".toByteArray()).toHex(),
        )
        assertContentEquals(Crypto.sha512(), Crypto.digest("SHA-512"))
        assertFailsWith<IllegalArgumentException> { Crypto.digest("NOPE-1") }
    }

    @Test
    fun `unsigned bytes drop the sign byte only`() {
        assertContentEquals(byteArrayOf(0), BigInteger.ZERO.toUnsignedBytes())
        assertContentEquals(byteArrayOf(0x7f), BigInteger.valueOf(0x7f).toUnsignedBytes())
        assertContentEquals(byteArrayOf(0x80.toByte()), BigInteger.valueOf(0x80).toUnsignedBytes())
        assertContentEquals(byteArrayOf(1, 0), BigInteger.valueOf(256).toUnsignedBytes())
    }

    @Test
    fun `crypto errors are protocol errors and IO errors`() {
        val e = CryptoException("bad tag")
        assertIs<ProtocolException>(e)
        assertIs<IOException>(e)
    }
}
