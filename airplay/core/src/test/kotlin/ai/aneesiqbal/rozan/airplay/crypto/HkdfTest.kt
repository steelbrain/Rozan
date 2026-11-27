package ai.aneesiqbal.rozan.airplay.crypto

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HkdfTest {
    private fun sha256(
        ikm: String,
        salt: String,
        info: String,
        length: Int,
    ) = Hkdf.derive("HmacSHA256", hex(ikm), hex(salt), hex(info), length).toHex()

    @Test
    fun `RFC 5869 test case 1`() {
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            sha256(
                "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b",
                "000102030405060708090a0b0c",
                "f0f1f2f3f4f5f6f7f8f9",
                42,
            ),
        )
    }

    @Test
    fun `RFC 5869 test case 2`() {
        val ikm = (0x00..0x4f).joinToString("") { "%02x".format(it) }
        val salt = (0x60..0xaf).joinToString("") { "%02x".format(it) }
        val info = (0xb0..0xff).joinToString("") { "%02x".format(it) }
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            sha256(ikm, salt, info, 82),
        )
    }

    @Test
    fun `RFC 5869 test case 3 with empty salt and info`() {
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            sha256("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b", "", "", 42),
        )
    }

    @Test
    fun `HKDF-SHA512 matches an independent computation`() {
        // Python: hmac/hashlib HKDF over IKM 00..1f with HomeKit-style ASCII salt and info.
        assertEquals(
            "faf9f3558a8ed1e45219bd94fb6d27e5b43a1bc861157fc2a0d291d8e3df410a",
            Hkdf
                .sha512(
                    hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
                    "Pair-Verify-Encrypt-Salt".toByteArray(),
                    "Pair-Verify-Encrypt-Info".toByteArray(),
                    32,
                ).toHex(),
        )
        // Multi-block output with empty salt and info.
        assertEquals(
            "f5fa02b18298a72a8c23898a8703472c6eb179dc204c03425c970e3b164bf90fff22d04836d0e2343bacc4e7cb6045fa" +
                "aa698e0e3b3eb91331306def1db8319e8a699b5ee45ab993847dc4df75bde023692c8c0710a67a55123f10a8b2d832" +
                "7f9eb138da",
            Hkdf.sha512(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 100).toHex(),
        )
    }

    @Test
    fun `output length is bounded`() {
        val ikm = ByteArray(16)
        assertEquals(255 * 64, Hkdf.sha512(ikm, ByteArray(0), ByteArray(0), 255 * 64).size)
        assertFailsWith<IllegalArgumentException> { Hkdf.sha512(ikm, ByteArray(0), ByteArray(0), 255 * 64 + 1) }
        assertFailsWith<IllegalArgumentException> { Hkdf.sha512(ikm, ByteArray(0), ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { Hkdf.sha512(ikm, ByteArray(0), ByteArray(0), -1) }
        assertFailsWith<IllegalArgumentException> { Hkdf.derive("HmacNOPE", ikm, ByteArray(0), ByteArray(0), 16) }
    }
}
