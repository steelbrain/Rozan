package ai.aneesiqbal.rozan.airplay.rtsp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Random and mutated input must only ever produce [RtspException] (or valid messages), whatever the chunk
 * boundaries, and never another exception type or unbounded buffering.
 */
class RtspFuzzTest {
    private val limits =
        RtspLimits(maxStartLineBytes = 256, maxHeaderBytes = 512, maxHeaderCount = 8, maxBodyBytes = 64)

    private val requests =
        latin1(
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/690.7.1\r\n\r\n" +
                "POST /pair-setup HTTP/1.1\r\nContent-Length: 6\r\nContent-Type: application/octet-stream\r\n\r\n" +
                "\u0000\u0001\u0006\u0001\u0001\u0000" +
                "\r\nGET /info HTTP/1.1\r\nX-Apple-ProtocolVersion: 1\r\n\r\n",
        )

    private val responses =
        latin1(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 3\r\n\r\nabc" +
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: PTTH/1.0\r\n\r\n" +
                "HTTP/1.1 404\r\n\r\n",
        )

    @Test
    fun `valid streams parse fully under random chunking`() {
        val random = Random(1)
        repeat(500) {
            val requestParser = RtspRequestParser(limits)
            assertTrue(feedInChunks(requestParser::feed, requests, random) == 3)
            requestParser.finish()
            val responseParser = RtspResponseParser(limits)
            assertTrue(feedInChunks(responseParser::feed, responses, random) == 3)
            responseParser.finish()
        }
    }

    @Test
    fun `mutated requests only fail with RtspException`() {
        val random = Random(2)
        repeat(20_000) { round ->
            val input = mutate(requests, random)
            val parser = RtspRequestParser(limits)
            expectOnlyRtspException(round, input) {
                feedInChunks(parser::feed, input, random)
                parser.finish()
            }
        }
    }

    @Test
    fun `mutated responses only fail with RtspException`() {
        val random = Random(3)
        repeat(20_000) { round ->
            val input = mutate(responses, random)
            val parser = RtspResponseParser(limits)
            expectOnlyRtspException(round, input) {
                feedInChunks(parser::feed, input, random)
                parser.finish()
            }
        }
    }

    @Test
    fun `random bytes only fail with RtspException`() {
        val random = Random(4)
        val alphabet = latin1("GET POST RTSP/1.0 HTTP/1.1 Content-Length: 0123456789 \r\n\t:\u0000\u00ff")
        repeat(20_000) { round ->
            val size = random.nextInt(0, 600)
            val input =
                if (round % 2 == 0) {
                    random.nextBytes(size)
                } else {
                    ByteArray(size) { alphabet[random.nextInt(alphabet.size)] }
                }
            val requestParser = RtspRequestParser(limits)
            expectOnlyRtspException(round, input) {
                feedInChunks(requestParser::feed, input, random)
                requestParser.finish()
            }
            val responseParser = RtspResponseParser(limits)
            expectOnlyRtspException(round, input) {
                feedInChunks(responseParser::feed, input, random)
                responseParser.finish()
            }
        }
    }

    @Test
    fun `endless input without line breaks fails at the limit instead of buffering`() {
        val parser = RtspRequestParser()
        val chunk = ByteArray(1024) { 'A'.code.toByte() }
        var fed = 0
        val failure =
            runCatching {
                while (fed < 1_000_000) {
                    parser.feed(chunk)
                    fed += chunk.size
                }
            }.exceptionOrNull()
        assertTrue(failure is RtspException && failure.status == 414)
        assertTrue(fed <= RtspLimits.DEFAULT_START_LINE_BYTES)
    }

    private fun feedInChunks(
        feed: (ByteArray, Int, Int) -> List<RtspMessage>,
        input: ByteArray,
        random: Random,
    ): Int {
        var position = 0
        var messages = 0
        while (position < input.size) {
            val length = random.nextInt(1, input.size - position + 1).coerceAtMost(random.nextInt(1, 64))
            messages += feed(input, position, length).size
            position += length
        }
        return messages
    }

    private fun mutate(
        source: ByteArray,
        random: Random,
    ): ByteArray {
        val bytes = source.toMutableList()
        repeat(random.nextInt(1, 6)) {
            val index = random.nextInt(bytes.size + 1)
            when (random.nextInt(4)) {
                0 -> if (index < bytes.size) bytes[index] = random.nextInt(256).toByte()
                1 -> bytes.add(index, random.nextInt(256).toByte())
                2 -> if (index < bytes.size) bytes.removeAt(index)
                else -> if (index < bytes.size) bytes[index] = INTERESTING[random.nextInt(INTERESTING.size)]
            }
        }
        val result = bytes.toByteArray()
        return if (random.nextInt(4) == 0) result.copyOf(random.nextInt(result.size + 1)) else result
    }

    private fun expectOnlyRtspException(
        round: Int,
        input: ByteArray,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (expected: RtspException) {
            assertTrue(expected.status in 400..599)
        } catch (other: Throwable) {
            fail("round $round: ${other::class.simpleName} for input ${input.toString(Charsets.ISO_8859_1)}", other)
        }
    }

    private companion object {
        val INTERESTING = latin1("\r\n :\t0\u0000\u007f\u0080")
    }
}
