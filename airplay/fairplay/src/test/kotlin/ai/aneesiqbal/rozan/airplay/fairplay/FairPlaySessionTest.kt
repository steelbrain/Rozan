package ai.aneesiqbal.rozan.airplay.fairplay

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** End-to-end exchanges through the public API. */
class FairPlaySessionTest {
    @ParameterizedTest
    @MethodSource("v2Cases")
    fun `v2 vector end to end`(name: String) {
        val case = Vectors.v2Case(name)
        val session = v2Session(case)
        assertContentEquals(case.hex("setup_reply"), session.setup(case.hex("setup_request")))
        assertContentEquals(case.hex("handshake_reply"), session.handshake(case.hex("handshake_request")))
        assertContentEquals(case.hex("output_key"), session.decryptKey(case.hex("ekey")))
        // The key decrypt can repeat within the session.
        assertContentEquals(case.hex("output_key"), session.decryptKey(case.hex("ekey")))
    }

    @ParameterizedTest
    @MethodSource("v3Cases")
    fun `v3 vector end to end`(name: String) {
        val case = Vectors.v3Case(name)
        val session = FairPlaySession()
        assertContentEquals(case.hex("setup_reply"), session.setup(case.hex("setup_request")))
        assertContentEquals(case.hex("handshake_reply"), session.handshake(case.hex("handshake_request")))
        assertContentEquals(case.hex("output_key"), session.decryptKey(case.hex("ekey")))
        assertContentEquals(case.hex("output_key"), session.decryptKey(case.hex("ekey")))
    }

    @Test
    fun `v2 with a random receiver SAP and a sender built from the spec`() {
        val random = Random(3)
        for (mode in 0 until 4) {
            val session = FairPlaySession()
            val reply = session.setup(hexToBytes("46504c590201010000000004020000bb").also { it[14] = mode.toByte() })
            assertEquals("46504c5902010200000000820" + "2" + "0$mode", reply.copyOfRange(0, 14).toHex())
            // The sender knows the SAP cipher, so it can read the receiver SAP back.
            val receiverSap = FairPlayV2.sapDecrypt(mode, reply, 14)
            assertEquals(0x00, receiverSap.u8(0))
            assertEquals(0x01, receiverSap.u8(1))
            val senderSap = random.nextBytes(128)
            val handshake = TestCrypto.v2HandshakeRequest(mode, senderSap, receiverSap)
            assertContentEquals(
                hexToBytes("46504c590201040000000014") + handshake.copyOfRange(144, 164),
                session.handshake(handshake),
            )
            repeat(3) {
                val mediaKey = random.nextBytes(16)
                val blob = TestCrypto.v2KeyBlob(senderSap, receiverSap, mediaKey, random.nextBytes(16))
                assertContentEquals(mediaKey, session.decryptKey(blob))
            }
        }
    }

    @Test
    fun `each session draws a fresh receiver SAP`() {
        val request = hexToBytes("46504c590201010000000004020000bb")
        val first = FairPlaySession().setup(request)
        val second = FairPlaySession().setup(request)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `setup restarts the exchange, also across versions`() {
        val v2 = Vectors.v2Cases[0]
        val v3 = Vectors.v3Cases[0]
        val session = v2Session(v2)
        session.setup(v3.hex("setup_request"))
        session.handshake(v3.hex("handshake_request"))
        assertContentEquals(v3.hex("output_key"), session.decryptKey(v3.hex("ekey")))
        session.setup(v2.hex("setup_request"))
        session.handshake(v2.hex("handshake_request"))
        assertContentEquals(v2.hex("output_key"), session.decryptKey(v2.hex("ekey")))
        session.setup(v3.hex("setup_request"))
        session.handshake(v3.hex("handshake_request"))
        assertContentEquals(v3.hex("output_key"), session.decryptKey(v3.hex("ekey")))
    }

    @Test
    fun `v3 setup reply depends only on the mode`() {
        val random = Random(4)
        for (mode in 0 until 4) {
            val request = random.nextBytes(16)
            request[4] = 3
            request[14] = mode.toByte()
            val reply = FairPlaySession().setup(request)
            assertContentEquals(Vectors.v3.obj("static_setup_replies").hex(mode.toString()), reply)
        }
    }

    @Test
    fun `v3 handshake checks only the version and mode, and echoes bytes 144 to 163`() {
        val case = Vectors.v3Cases[0]
        val random = Random(5)
        repeat(20) {
            val request = random.nextBytes(164)
            request[4] = 3
            request[12] = random.nextInt(4).toByte()
            val session = FairPlaySession()
            session.setup(case.hex("setup_request"))
            val reply = session.handshake(request)
            assertContentEquals(hexToBytes("46504c590301040000000014") + request.copyOfRange(144, 164), reply)
            assertEquals(16, session.decryptKey(random.nextBytes(72)).size)
        }
    }

    @ParameterizedTest
    @MethodSource("v3Cases")
    fun `v3 key decrypt ignores the blob framing`(name: String) {
        val case = Vectors.v3Case(name)
        val session = FairPlaySession()
        session.setup(case.hex("setup_request"))
        session.handshake(case.hex("handshake_request"))
        val random = Random(name.hashCode())
        val blob = random.nextBytes(72)
        case.hex("ekey").copyInto(blob, 16, 16, 32)
        case.hex("ekey").copyInto(blob, 56, 56, 72)
        assertContentEquals(case.hex("output_key"), session.decryptKey(blob))
    }

    companion object {
        @JvmStatic
        fun v2Cases(): List<String> = Vectors.v2CaseNames()

        @JvmStatic
        fun v3Cases(): List<String> = Vectors.v3CaseNames()

        /** A session whose receiver SAP is the vector's (bytes 0..1 are fixed by the session itself). */
        fun v2Session(case: Map<String, Any?>): FairPlaySession {
            val sap = case.obj("intermediates").hex("receiver_sap")
            check(sap[0] == 0.toByte() && sap[1] == 1.toByte())
            return FairPlaySession { out -> sap.copyInto(out, 0, 2, 128) }
        }
    }
}
