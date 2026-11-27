package ai.aneesiqbal.rozan.airplay.fairplay

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** FairPlay v3 stage by stage against `fairplay-v3.json` (spec section 4). */
class FairPlayV3Test {
    private val tables = V3Tables.instance

    @Test
    fun `static replies and handshake header match the vector file`() {
        for (mode in 0 until 4) {
            val reply = FairPlayV3.setupReply(mode)
            assertContentEquals(Vectors.v3.obj("static_setup_replies").hex(mode.toString()), reply)
            assertEquals(142, reply.size)
            assertEquals(mode, reply.u8(13))
        }
        assertContentEquals(Vectors.v3.hex("fp_setup_header_reply"), FairPlayV3.HANDSHAKE_REPLY_HEADER)
    }

    @Test
    fun `MD5 constants are floor(2^32 abs(sin(i + 1)))`() {
        for (i in 0 until 64) {
            val expected = (kotlin.math.abs(StrictMath.sin(i + 1.0)) * 4_294_967_296.0).toLong().toInt()
            assertEquals(expected, ModifiedMd5.K[i], "K[$i]")
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `decrypt_message`(name: String) {
        val case = Vectors.v3Case(name)
        val dm = FairPlayV3.decryptMessage(case.hex("handshake_request"), tables)
        assertContentEquals(case.obj("intermediates").hex("decrypted_client_message"), dm)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `session key rounds`(name: String) {
        val case = Vectors.v3Case(name)
        val i = case.obj("intermediates")
        val newSap = FairPlayV3.newSap(i.hex("decrypted_client_message"), tables)
        assertEquals(320, newSap.size)
        var key = tables.initialSessionKey.copyOf()
        val rounds = i.list("session_key_rounds").map { it.asObject() }
        assertEquals(5, rounds.size)
        for ((r, round) in rounds.withIndex()) {
            assertEquals(r, round.int("round"))
            val block = round.hex("input_block")
            assertContentEquals(newSap.copyOfRange(64 * r, 64 * r + 64), block, "input block $r")
            val m = ModifiedMd5.compress(byteArrayOf(7) + block, 1, key)
            assertContentEquals(round.hex("modified_md5"), m, "modified_md5 round $r")
            val h = SapHash.hash(byteArrayOf(7, 7) + block, 2, tables)
            assertContentEquals(round.hex("sap_hash"), h, "sap_hash round $r")
            key = ByteArray(16).also { out -> for (w in 0 until 4) out.putLe32(4 * w, h.le32(4 * w) + m.le32(4 * w)) }
            assertContentEquals(round.hex("accumulated"), key, "accumulated round $r")
        }
        assertContentEquals(i.hex("session_key"), FairPlayV3.sessionKey(case.hex("handshake_request"), tables))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `final AES-128 step`(name: String) {
        val case = Vectors.v3Case(name)
        val i = case.obj("intermediates")
        val ekey = case.hex("ekey")
        assertContentEquals(i.hex("ekey_chunk1_off16"), ekey.copyOfRange(16, 32))
        assertContentEquals(i.hex("ekey_chunk2_off56"), ekey.copyOfRange(56, 72))
        val aesKey = FairPlayV3.aesKey(i.hex("session_key"), tables)
        assertContentEquals(i.hex("aes128_key"), aesKey)
        assertContentEquals(i.hex("aes128_round_keys"), TestCrypto.expandKey(aesKey))
        assertContentEquals(i.hex("block_cipher_output"), FairPlayV3.blockOutput(aesKey, ekey, tables))
        val mediaKey = FairPlayV3.mediaKey(aesKey, ekey, tables)
        assertContentEquals(i.hex("output_key"), mediaKey)
        assertContentEquals(case.hex("output_key"), mediaKey)
    }

    @Test
    fun `decrypt_message data matches the spec text`() {
        val ivs =
            listOf(
                "5752f1b7549d8f870c10485a6088cadb",
                "df7b1563f005587752a90402b9a39295",
                "68b54611fb04de676c968efb8c9db0c9",
                "27078b2123361e7adc9d0b115354690d",
            )
        assertEquals(ivs.joinToString(""), tables.messageIvs.toHex())
        // RK[0] and RK[10] are shared by all four modes; RK[1..9] differ.
        val keys = tables.messageRoundKeys
        for (mode in 1 until 4) {
            assertContentEquals(keys[0].copyOfRange(0, 16), keys[mode].copyOfRange(0, 16))
            assertContentEquals(keys[0].copyOfRange(160, 176), keys[mode].copyOfRange(160, 176))
            assertFalse(keys[0].copyOfRange(16, 160).contentEquals(keys[mode].copyOfRange(16, 160)))
        }
    }

    @Test
    fun `decrypt_message inverts the forward cipher in CBC mode (spec section 5 test tooling)`() {
        val random = Random(7)
        repeat(50) {
            val mode = random.nextInt(4)
            val dm = random.nextBytes(128)
            val message3 = random.nextBytes(164)
            message3[12] = mode.toByte()
            var previous = tables.messageIvs.copyOfRange(16 * mode, 16 * mode + 16)
            for (block in 0 until 8) {
                val c = TestCrypto.xor(dm.copyOfRange(16 * block, 16 * block + 16), previous)
                Aes.encryptBlock(c, 0, tables.messageRoundKeys[mode])
                c.copyInto(message3, 16 + 16 * block)
                previous = c
            }
            assertContentEquals(dm, FairPlayV3.decryptMessage(message3, tables))
        }
    }

    @Test
    fun `final constants match the spec text`() {
        assertEquals("1a64f9606ce301a954481bd4ab81fcc6", tables.zKey.toHex())
        assertEquals("8eba07ccb65af62033cff842e5d55a7d", tables.xKey.toHex())
        assertEquals("94defeacdab9f7896787e3964e54a6bb", tables.delta0.toHex())
        assertEquals("79".repeat(16), tables.keyXor.toHex())
        assertEquals(listOf(18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30), tables.i0Index.map { it.toInt() })
    }

    companion object {
        @JvmStatic
        fun cases(): List<String> = Vectors.v3CaseNames()
    }
}
