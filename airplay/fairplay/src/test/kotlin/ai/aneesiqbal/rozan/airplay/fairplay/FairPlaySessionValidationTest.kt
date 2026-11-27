package ai.aneesiqbal.rozan.airplay.fairplay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Malformed input, wrong order and authentication failures, and what each does
 * to the session (spec sections 1, 1.6, 3.4, 3.5 and 6g).
 */
class FairPlaySessionValidationTest {
    private val v2 = Vectors.v2Cases[0]
    private val v3 = Vectors.v3Cases[0]

    private fun v2Session() = FairPlaySessionTest.v2Session(v2)

    private fun v2SetUp(): FairPlaySession = v2Session().also { it.setup(v2.hex("setup_request")) }

    private fun v2Handshaken(): FairPlaySession = v2SetUp().also { it.handshake(v2.hex("handshake_request")) }

    private fun v3SetUp(): FairPlaySession = FairPlaySession().also { it.setup(v3.hex("setup_request")) }

    private fun v3Handshaken(): FairPlaySession = v3SetUp().also { it.handshake(v3.hex("handshake_request")) }

    private fun FairPlaySession.assertV2KeyWorks() =
        assertContentEquals(v2.hex("output_key"), decryptKey(v2.hex("ekey")))

    private fun FairPlaySession.assertV3KeyWorks() =
        assertContentEquals(v3.hex("output_key"), decryptKey(v3.hex("ekey")))

    private fun FairPlaySession.assertNoKey() {
        assertFailsWith<FairPlayException> { decryptKey(v2.hex("ekey")) }
        assertFailsWith<FairPlayException> { decryptKey(v3.hex("ekey")) }
    }

    private fun ByteArray.with(
        index: Int,
        value: Int,
    ): ByteArray = copyOf().also { it[index] = value.toByte() }

    private fun ByteArray.flipped(index: Int): ByteArray = with(index, this[index].toInt() xor 0x01)

    private val wrongSizes = listOf(0, 1, 4, 15, 16, 17, 31, 32, 71, 72, 73, 141, 142, 143, 163, 164, 165, 4096)

    @Test
    fun `setup rejects every size but 16 without touching the session`() {
        for (size in wrongSizes - 16) {
            for (version in listOf(2, 3)) {
                val request = v2.hex("setup_request").copyOf(size)
                if (size > 4) request[4] = version.toByte()
                val session = v2Handshaken()
                assertFailsWith<FairPlayException>("size $size") { session.setup(request) }
                session.assertV2KeyWorks()
            }
        }
    }

    @Test
    fun `setup rejects unknown versions without touching the session`() {
        for (version in (0..255) - listOf(2, 3)) {
            val session = v3Handshaken()
            assertFailsWith<FairPlayException> { session.setup(v2.hex("setup_request").with(4, version)) }
            session.assertV3KeyWorks()
        }
    }

    @Test
    fun `setup rejects modes 4 and up in both versions, discarding the session`() {
        for (mode in 4..255) {
            val v2Session = v2Handshaken()
            assertFailsWith<FairPlayException> { v2Session.setup(v2.hex("setup_request").with(14, mode)) }
            v2Session.assertNoKey()
            val v3Session = v3Handshaken()
            assertFailsWith<FairPlayException> { v3Session.setup(v3.hex("setup_request").with(14, mode)) }
            v3Session.assertNoKey()
        }
    }

    @Test
    fun `v2 setup checks every header byte, and a failed setup discards the session`() {
        val request = v2.hex("setup_request")
        for (index in (0 until 16) - listOf(4, 14)) {
            val session = v2Handshaken()
            assertFailsWith<FairPlayException>("byte $index") { session.setup(request.flipped(index)) }
            session.assertNoKey()
            assertFailsWith<FairPlayException> { session.handshake(v2.hex("handshake_request")) }
        }
    }

    @Test
    fun `calls out of order fail`() {
        assertFailsWith<FairPlayException> { FairPlaySession().handshake(v2.hex("handshake_request")) }
        FairPlaySession().assertNoKey()
        v2SetUp().assertNoKey()
        v3SetUp().assertNoKey()
    }

    @Test
    fun `a v3 handshake needs no setup, and any v3 handshake replaces the session`() {
        FairPlaySession().also { it.handshake(v3.hex("handshake_request")) }.assertV3KeyWorks()
        v2SetUp().also { it.handshake(v3.hex("handshake_request")) }.assertV3KeyWorks()
        v2Handshaken().also { it.handshake(v3.hex("handshake_request")) }.assertV3KeyWorks()
        v3Handshaken().also { it.handshake(v3.hex("handshake_request")) }.assertV3KeyWorks()
    }

    @Test
    fun `a v2 handshake on a v3 session fails and discards it`() {
        for (session in listOf(v3SetUp(), v3Handshaken())) {
            assertFailsWith<FairPlayException> { session.handshake(v2.hex("handshake_request")) }
            session.assertNoKey()
        }
    }

    @Test
    fun `a repeated v2 handshake succeeds again`() {
        val session = v2Handshaken()
        assertContentEquals(v2.hex("handshake_reply"), session.handshake(v2.hex("handshake_request")))
        session.assertV2KeyWorks()
    }

    @Test
    fun `handshake rejects every size but 164 without touching the session`() {
        for (size in wrongSizes - 164) {
            val v2Session = v2Handshaken()
            val v2Request = v2.hex("handshake_request").copyOf(size)
            assertFailsWith<FairPlayException> { v2Session.handshake(v2Request) }
            v2Session.assertV2KeyWorks()
            val v3Session = v3Handshaken()
            assertFailsWith<FairPlayException> { v3Session.handshake(v3.hex("handshake_request").copyOf(size)) }
            v3Session.assertV3KeyWorks()
        }
    }

    @Test
    fun `v2 handshake checks its header, mode and fixed bytes, and keeps the setup on failure`() {
        val request = v2.hex("handshake_request")
        // Byte 4 is the version: flipping 02 to 03 makes a valid v3 handshake.
        for (index in (0 until 16) - 4) {
            val session = v2Handshaken()
            assertFailsWith<FairPlayException>("byte $index") { session.handshake(request.flipped(index)) }
            session.assertNoKey()
            session.handshake(request)
            session.assertV2KeyWorks()
        }
    }

    @Test
    fun `v2 handshake mode must equal the setup mode`() {
        val case = Vectors.v2Cases.first { it.int("mode") == 1 }
        val request = case.hex("handshake_request")
        for (mode in listOf(0, 2, 3)) {
            val session = FairPlaySessionTest.v2Session(case).also { it.setup(case.hex("setup_request")) }
            assertFailsWith<FairPlayException> { session.handshake(request.with(12, mode)) }
            session.handshake(request)
            assertContentEquals(case.hex("output_key"), session.decryptKey(case.hex("ekey")))
        }
    }

    @Test
    fun `v2 handshake fails on a wrong confirmation or a tampered SAP, and the client may retry`() {
        val request = v2.hex("handshake_request")
        for (index in listOf(16, 80, 143, 144, 150, 163)) {
            val session = v2SetUp()
            assertFailsWith<FairPlayException>("byte $index") { session.handshake(request.flipped(index)) }
            session.assertNoKey()
            assertContentEquals(v2.hex("handshake_reply"), session.handshake(request))
            session.assertV2KeyWorks()
        }
    }

    @Test
    fun `v3 handshake with an unknown version leaves the session, a mode of 4 and up discards it`() {
        val request = v3.hex("handshake_request")
        for (version in (0..255) - listOf(2, 3)) {
            val session = v3Handshaken()
            assertFailsWith<FairPlayException> { session.handshake(request.with(4, version)) }
            session.assertV3KeyWorks()
        }
        for (mode in 4..255) {
            val session = v3Handshaken()
            assertFailsWith<FairPlayException> { session.handshake(request.with(12, mode)) }
            session.assertNoKey()
        }
    }

    @Test
    fun `decryptKey rejects every size but 72 and leaves the session usable`() {
        for (size in wrongSizes - 72) {
            val v2Session = v2Handshaken()
            assertFailsWith<FairPlayException> { v2Session.decryptKey(v2.hex("ekey").copyOf(size)) }
            v2Session.assertV2KeyWorks()
            val v3Session = v3Handshaken()
            assertFailsWith<FairPlayException> { v3Session.decryptKey(v3.hex("ekey").copyOf(size)) }
            v3Session.assertV3KeyWorks()
        }
    }

    @Test
    fun `v2 decryptKey checks the framing, failing only that call`() {
        val ekey = v2.hex("ekey")
        val session = v2Handshaken()
        for (index in (0 until 16) + (32 until 36)) {
            assertFailsWith<FairPlayException>("byte $index") { session.decryptKey(ekey.flipped(index)) }
            session.assertV2KeyWorks()
        }
    }

    @Test
    fun `v2 decryptKey authenticates the blind, tag and wrapped key, failing only that call`() {
        val ekey = v2.hex("ekey")
        val session = v2Handshaken()
        for (index in (16 until 72) - (32 until 36)) {
            assertFailsWith<FairPlayException>("byte $index") { session.decryptKey(ekey.flipped(index)) }
            session.assertV2KeyWorks()
        }
    }

    @Test
    fun `random garbage only ever raises FairPlayException`() {
        val random = Random(6)
        repeat(3000) {
            val session = sessionInRandomState(random)
            val input = randomInput(random)
            val step = random.nextInt(3)
            try {
                when (step) {
                    0 -> session.setup(input)
                    1 -> session.handshake(input)
                    else -> session.decryptKey(input)
                }
            } catch (expected: FairPlayException) {
                assertTrue(expected.message.orEmpty().isNotEmpty())
            } catch (unexpected: Throwable) {
                fail("unexpected ${unexpected::class.simpleName} in step $step for ${input.size} bytes", unexpected)
            }
        }
    }

    private fun sessionInRandomState(random: Random): FairPlaySession =
        when (random.nextInt(5)) {
            0 -> FairPlaySession()
            1 -> v2SetUp()
            2 -> v2Handshaken()
            3 -> v3SetUp()
            else -> v3Handshaken()
        }

    /** Random bytes, often of a size the API expects and with a plausible version and mode. */
    private fun randomInput(random: Random): ByteArray {
        val sizes = listOf(0, 1, 15, 16, 17, 72, 163, 164, 165)
        val size = if (random.nextBoolean()) sizes.random(random) else random.nextInt(300)
        val input = random.nextBytes(size)
        if (size > 4 && random.nextBoolean()) input[4] = (2 + random.nextInt(2)).toByte()
        if (size > 14 && random.nextBoolean()) input[14] = random.nextInt(4).toByte()
        if (size > 12 && random.nextBoolean()) input[12] = random.nextInt(4).toByte()
        return input
    }
}
