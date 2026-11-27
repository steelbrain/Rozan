package ai.aneesiqbal.rozan.airplay.fairplay

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** `fairplay-v3-coverage.json` (spec revision 2, section 5): mutation-chosen v3 cases, sap_hash units, garble trace. */
class FairPlayV3CoverageTest {
    private val tables = V3Tables.instance

    @Test
    fun `the file has 16 cases per mode and 16 sap_hash cases`() {
        assertEquals(List(4) { 16 }, (0 until 4).map { mode -> Vectors.coverageCases.count { it.int("mode") == mode } })
        assertEquals(16, Vectors.sapHashCases.size)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `coverage case end to end`(name: String) {
        val case = case(name)
        val session = FairPlaySession()
        assertContentEquals(case.hex("setup_reply"), session.setup(case.hex("setup_request")))
        assertContentEquals(case.hex("handshake_reply"), session.handshake(case.hex("handshake_request")))
        assertContentEquals(case.hex("output_key"), session.decryptKey(case.hex("ekey")))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `coverage case stage by stage`(name: String) {
        val case = case(name)
        val i = case.obj("intermediates")
        val message3 = case.hex("handshake_request")
        val dm = FairPlayV3.decryptMessage(message3, tables)
        assertContentEquals(i.hex("decrypted_client_message"), dm)
        val newSap = FairPlayV3.newSap(dm, tables)
        val md5s = i.list("modified_md5_per_round")
        val hashes = i.list("sap_hash_per_round")
        var key = tables.initialSessionKey.copyOf()
        for (round in 0 until 5) {
            val m = ModifiedMd5.compress(newSap, 64 * round, key)
            assertContentEquals(hexToBytes(md5s[round] as String), m, "modified_md5 round $round")
            val h = SapHash.hash(newSap, 64 * round, tables)
            assertContentEquals(hexToBytes(hashes[round] as String), h, "sap_hash round $round")
            key = ByteArray(16).also { out -> for (w in 0 until 4) out.putLe32(4 * w, h.le32(4 * w) + m.le32(4 * w)) }
        }
        val sessionKey = FairPlayV3.sessionKey(message3, tables)
        assertContentEquals(i.hex("session_key"), sessionKey)
        val aesKey = FairPlayV3.aesKey(sessionKey, tables)
        assertContentEquals(i.hex("aes128_key"), aesKey)
        assertContentEquals(i.hex("block_cipher_output"), FairPlayV3.blockOutput(aesKey, case.hex("ekey"), tables))
        assertContentEquals(case.hex("output_key"), FairPlayV3.mediaKey(aesKey, case.hex("ekey"), tables))
    }

    @ParameterizedTest
    @MethodSource("sapHashCases")
    fun `sap_hash unit case`(name: String) {
        val case = Vectors.sapHashCases.single { it.string("name") == name }
        val block = case.hex("block")
        val b1 = SapHash.loadAndScramble(block, 0)
        assertContentEquals(case.hex("after_load_and_scramble_b1"), b1)
        val buffers = SapHash.Buffers.seeded(b1, tables)
        buffers.garble()
        val after = case.obj("after_garble")
        assertBuffers(after, buffers)
        assertContentEquals(case.hex("output"), SapHash.combine(buffers, tables))
        assertContentEquals(case.hex("output"), SapHash.hash(byteArrayOf(1, 2, 3) + block, 3, tables))
    }

    @Test
    fun `garble step trace`() {
        val trace = Vectors.coverage.obj("garble_trace")
        val input = trace.obj("garble_input")
        val b1 = SapHash.loadAndScramble(trace.hex("sap_hash_block"), 0)
        assertContentEquals(input.hex("b1"), b1)
        val seeded = SapHash.Buffers.seeded(b1, tables)
        assertBuffers(input, seeded)

        val steps = trace.list("trace").map { it.asObject() }
        assertEquals((1..173).toList(), steps.map { it.int("op") })
        val written = mutableListOf<Pair<Int, UInt>>()
        seeded.garble { op, value -> written.add(op to value) }
        assertEquals(steps.size, written.size)
        for ((step, actual) in steps.zip(written)) {
            val expected = step.string("value")
            val (op, value) = actual
            assertEquals(step.int("op"), op)
            // Bytes are 2 hex digits, 32-bit scratch variables 8; the first mismatch names the wrong operation.
            assertEquals(
                expected,
                value.toString(16).padStart(expected.length, '0'),
                "op $op (${step.string("target")})",
            )
        }
        assertBuffers(trace.obj("garble_output"), seeded)
    }

    private fun assertBuffers(
        expected: Map<String, Any?>,
        actual: SapHash.Buffers,
    ) {
        assertContentEquals(expected.hex("b0"), actual.b0, "b0")
        assertContentEquals(expected.hex("b1"), actual.b1, "b1")
        assertContentEquals(expected.hex("b2"), actual.b2, "b2")
        assertContentEquals(expected.hex("b3"), actual.b3, "b3")
        assertContentEquals(expected.hex("b4"), actual.b4, "b4")
    }

    private fun case(name: String) = Vectors.coverageCases.single { it.string("name") == name }

    companion object {
        @JvmStatic
        fun cases(): List<String> = Vectors.coverageCases.map { it.string("name") }

        @JvmStatic
        fun sapHashCases(): List<String> = Vectors.sapHashCases.map { it.string("name") }
    }
}
