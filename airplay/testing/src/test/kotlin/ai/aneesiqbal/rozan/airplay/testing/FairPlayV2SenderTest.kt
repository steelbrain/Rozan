package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.fairplay.FairPlaySession
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The sender side of FairPlay v2 against docs/protocol/vectors/fairplay-v2.json (one case per mode, so
 * every mode's constants are exercised) and against the receiver's real `FairPlaySession`.
 */
class FairPlayV2SenderTest {
    @Test
    fun `reproduces the vector handshakes and key blobs of every mode`() {
        val cases = vectorCases()
        assertEquals(setOf(0, 1, 2, 3), cases.map { it.getValue("mode").toInt() }.toSet())
        for (case in cases) {
            val mode = case.getValue("mode").toInt()
            val sender = FairPlayV2Sender(mode, ScriptedRandom(hex(case.getValue("sender_sap"))))
            assertContentEquals(hex(case.getValue("setup_request")), sender.setupRequest(), "mode $mode setup")
            assertContentEquals(
                hex(case.getValue("handshake_request")),
                sender.handshakeRequest(hex(case.getValue("setup_reply"))),
                "mode $mode handshake",
            )
            sender.checkHandshakeReply(hex(case.getValue("handshake_reply")))
            assertContentEquals(
                hex(case.getValue("ekey")),
                sender.keyBlob(hex(case.getValue("output_key")), hex(case.getValue("ekey_blind_off16"))),
                "mode $mode key blob",
            )
        }
    }

    @Test
    fun `the receiver's FairPlaySession recovers every key the sender wraps`() {
        val random = Random(7)
        for (mode in 0 until 4) {
            val receiver = FairPlaySession()
            val sender = FairPlayV2Sender(mode)
            val reply = receiver.handshake(sender.handshakeRequest(receiver.setup(sender.setupRequest())))
            sender.checkHandshakeReply(reply)
            repeat(3) {
                val key = random.nextBytes(16)
                assertContentEquals(key, receiver.decryptKey(sender.keyBlob(key)), "mode $mode")
            }
        }
    }

    @Test
    fun `a setup reply of another mode or version is refused`() {
        val reply = FairPlaySession().setup(FairPlayV2Sender(1).setupRequest())
        assertFailsWith<SenderException> { FairPlayV2Sender(0).handshakeRequest(reply) }
        assertFailsWith<SenderException> { FairPlayV2Sender(1).handshakeRequest(reply.copyOf().also { it[4] = 3 }) }
        val sender = FairPlayV2Sender(1)
        sender.handshakeRequest(reply)
        assertFailsWith<SenderException> { sender.checkHandshakeReply(ByteArray(32)) }
    }

    /** Serves [bytes] for the first `nextBytes`, then real randomness. */
    private class ScriptedRandom(
        private val bytes: ByteArray,
    ) : SecureRandom() {
        private var used = false

        override fun nextBytes(out: ByteArray) {
            if (!used && out.size == bytes.size) {
                used = true
                bytes.copyInto(out)
            } else {
                super.nextBytes(out)
            }
        }
    }

    private companion object {
        /** The first case of each mode, as flat string fields (the vector file's own layout). */
        fun vectorCases(): List<Map<String, String>> {
            val text = File(System.getProperty("rozan.testing.fairPlayVectors")).readText()
            val field = Regex("\"(\\w+)\": (?:\"([0-9a-f]*)\"|(\\d+))")
            return text
                .split("\"name\": \"")
                .drop(1)
                .map { block ->
                    field.findAll(block).associate { m ->
                        m.groupValues[1] to
                            m.groupValues[2].ifEmpty { m.groupValues[3] }
                    }
                }.groupBy { it.getValue("mode") }
                .map { (_, cases) -> cases.first() }
        }
    }
}
