package ai.aneesiqbal.rozan.airplay.fairplay

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `fairplay-sessions.json`: the section 1.6 session rules, step by step, through the public API. */
class FairPlaySessionScenariosTest {
    @Test
    fun `the file has 18 scenarios and 80 steps`() {
        assertEquals(18, Vectors.scenarios.size)
        assertEquals(80, Vectors.scenarios.sumOf { it.list("steps").size })
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    fun `scenario step by step`(name: String) {
        val scenario = Vectors.scenarios.single { it.string("name") == name }
        // Each v2 setup step names the receiver SAP the session must use; the session itself fixes bytes 0 and 1.
        var nextSap: ByteArray? = null
        val session =
            FairPlaySession { out ->
                val sap = checkNotNull(nextSap) { "the session drew a receiver SAP outside a v2 setup step" }
                sap.copyInto(out, 0, 2, 128)
                nextSap = null
            }
        for ((index, step) in scenario.list("steps").map { it.asObject() }.withIndex()) {
            val label = "$name step $index (${step.string("op")}): ${step["note"] ?: ""}"
            nextSap =
                step["inject_receiver_sap"]?.let { hexToBytes(it as String) }?.also {
                    assertEquals("0001", it.copyOf(2).toHex(), label)
                }
            val input = step.hex("input")
            val call: () -> ByteArray =
                when (val op = step.string("op")) {
                    "setup" -> { -> session.setup(input) }
                    "handshake" -> { -> session.handshake(input) }
                    "decrypt" -> { -> session.decryptKey(input) }
                    else -> error("unknown op $op")
                }
            if (step["expect_ok"] as Boolean) {
                assertContentEquals(step.hex("expect_output"), call(), label)
            } else {
                assertFailsWith<FairPlayException>(label) { call() }
            }
            assertTrue(nextSap == null || step["expect_ok"] == false, "$label: injected SAP not used")
        }
    }

    companion object {
        @JvmStatic
        fun scenarios(): List<String> = Vectors.scenarios.map { it.string("name") }
    }
}
