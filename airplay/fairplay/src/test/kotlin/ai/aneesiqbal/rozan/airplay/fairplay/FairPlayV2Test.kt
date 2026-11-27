package ai.aneesiqbal.rozan.airplay.fairplay

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** FairPlay v2 stage by stage against `fairplay-v2.json` (spec section 3). */
class FairPlayV2Test {
    @Test
    fun `constants match the vector file`() {
        val v2 = Vectors.v2
        for (mode in 0 until 4) {
            val key = mode.toString()
            assertContentEquals(v2.obj("round_keys_per_mode").hex(key), FairPlayV2.roundKeys(mode))
            assertContentEquals(v2.obj("input_masks").hex(key), FairPlayV2.inputMask(mode))
            assertContentEquals(v2.obj("output_masks").hex(key), FairPlayV2.outputMask(mode))
            assertContentEquals(v2.obj("chain_masks").hex(key), FairPlayV2.chainMask(mode))
        }
        assertContentEquals(v2.hex("mac_prefix"), FairPlayV2.MAC_PREFIX)
        assertContentEquals(v2.hex("mac_suffix"), FairPlayV2.MAC_SUFFIX)
        assertContentEquals(v2.hex("wrapping_prefix"), FairPlayV2.WRAPPING_PREFIX)
        assertContentEquals(v2.hex("wrapping_suffix"), FairPlayV2.WRAPPING_SUFFIX)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `setup encrypts the receiver SAP`(name: String) {
        val case = Vectors.v2Case(name)
        val i = case.obj("intermediates")
        val body = FairPlayV2.sapEncrypt(case.int("mode"), i.hex("receiver_sap"))
        assertContentEquals(i.hex("encrypted_receiver_sap_setup_reply_body"), body)
        assertContentEquals(case.hex("setup_reply").copyOfRange(14, 142), body)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `handshake decrypts the sender SAP and derives the keys`(name: String) {
        val case = Vectors.v2Case(name)
        val i = case.obj("intermediates")
        val mode = case.int("mode")
        val request = case.hex("handshake_request")
        assertContentEquals(i.hex("encrypted_sender_sap_handshake_body"), request.copyOfRange(16, 144))
        val senderSap = FairPlayV2.sapDecrypt(mode, request, 16)
        assertContentEquals(i.hex("sender_sap"), senderSap)
        assertContentEquals(request.copyOfRange(16, 144), FairPlayV2.sapEncrypt(mode, senderSap))
        val receiverSap = i.hex("receiver_sap")
        val macKey = FairPlayV2.macKey(senderSap, receiverSap)
        assertContentEquals(i.hex("mac_key_sha1"), macKey)
        assertContentEquals(i.hex("wrapping_key_md5"), FairPlayV2.wrappingKey(senderSap, receiverSap))
        val confirmation = FairPlayV2.confirmation(macKey)
        assertContentEquals(i.hex("confirmation_hmac"), confirmation)
        assertContentEquals(request.copyOfRange(144, 164), confirmation)
        assertContentEquals(case.hex("handshake_reply").copyOfRange(12, 32), confirmation)
        assertContentEquals(request, TestCrypto.v2HandshakeRequest(mode, senderSap, receiverSap))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `key blob unwraps to the recovered key`(name: String) {
        val case = Vectors.v2Case(name)
        val i = case.obj("intermediates")
        val ekey = case.hex("ekey")
        assertContentEquals(i.hex("ekey_blind_off16"), ekey.copyOfRange(16, 32))
        assertContentEquals(i.hex("ekey_hmac_tag_off36"), ekey.copyOfRange(36, 56))
        assertContentEquals(i.hex("ekey_wrapped_block_off56"), ekey.copyOfRange(56, 72))
        val unwrapped = ByteArray(16)
        Aes.ecbDecrypt(i.hex("wrapping_key_md5"), ekey, 56, unwrapped, 0)
        assertContentEquals(i.hex("aes_ecb_decrypt_of_wrapped"), unwrapped)
        val recovered = TestCrypto.xor(unwrapped, ekey.copyOfRange(16, 32))
        assertContentEquals(i.hex("recovered_key"), recovered)
        assertContentEquals(case.hex("output_key"), recovered)
        val authenticated = ekey.copyOfRange(0, 36) + recovered
        val tag = FairPlayV2.hmacSha1(i.hex("mac_key_sha1"), authenticated, 0, authenticated.size)
        assertContentEquals(ekey.copyOfRange(36, 56), tag)
        val senderSap = i.hex("sender_sap")
        val receiverSap = i.hex("receiver_sap")
        assertContentEquals(ekey, TestCrypto.v2KeyBlob(senderSap, receiverSap, recovered, ekey.copyOfRange(16, 32)))
    }

    @Test
    fun `SAP cipher round-trips in every mode`() {
        val random = kotlin.random.Random(2)
        for (mode in 0 until 4) {
            repeat(20) {
                val plain = random.nextBytes(128)
                val cipher = FairPlayV2.sapEncrypt(mode, plain)
                assertContentEquals(plain, FairPlayV2.sapDecrypt(mode, byteArrayOf(1, 2, 3) + cipher, 3))
            }
        }
    }

    @Test
    fun `ECB step is ordinary AES-128`() {
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val cipher = hexToBytes("69c4e0d86a7b0430d8cdb78070b4c55a")
        val out = ByteArray(16)
        Aes.ecbDecrypt(key, cipher, 0, out, 0)
        assertEquals("00112233445566778899aabbccddeeff", out.toHex())
        assertContentEquals(TestCrypto.aesEcb(Cipher.DECRYPT_MODE, key, cipher), out)
    }

    companion object {
        @JvmStatic
        fun cases(): List<String> = Vectors.v2CaseNames()
    }
}
