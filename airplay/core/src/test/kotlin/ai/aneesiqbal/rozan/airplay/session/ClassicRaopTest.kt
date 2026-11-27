package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The classic RAOP parsers (01 §6.17, §6.8.1): the ANNOUNCE session description and the SETUP Transport. */
class ClassicRaopTest {
    private val keyBlob = ByteArray(72) { (0x46 + it).toByte() }
    private val iv = ByteArray(16) { (0x10 + it).toByte() }
    private val key64: String = Base64.getEncoder().encodeToString(keyBlob)
    private val iv64Padded: String = Base64.getEncoder().encodeToString(iv)
    private val iv64: String = Base64.getEncoder().withoutPadding().encodeToString(iv)

    /** The ten lines Music sent (01 §6.17), with test key and IV; [replace] swaps whole lines by prefix. */
    private fun sdpLines(vararg replace: Pair<String, String?>): List<String> {
        val lines =
            mutableListOf(
                "v=0",
                "o=iTunes 3878543842 0 IN IP4 192.168.1.152",
                "s=iTunes",
                "c=IN IP4 192.168.1.77",
                "t=0 0",
                "m=audio 0 RTP/AVP 96",
                "a=rtpmap:96 AppleLossless",
                "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100",
                "a=fpaeskey:$key64",
                "a=aesiv:$iv64",
            )
        for ((prefix, line) in replace) {
            val index = lines.indexOfFirst { it.startsWith(prefix) }
            if (index < 0) {
                if (line != null) lines += line
            } else if (line == null) {
                lines.removeAt(index)
            } else {
                lines[index] = line
            }
        }
        return lines
    }

    private fun sdp(vararg replace: Pair<String, String?>): ByteArray =
        sdpLines(*replace).joinToString("") { "$it\r\n" }.toByteArray(Charsets.ISO_8859_1)

    private fun status(body: ByteArray): Int = assertFailsWith<RtspException> { SessionDescription.parse(body) }.status

    private fun transportStatus(header: String): Int =
        assertFailsWith<RtspException> { ClassicTransport.parse(header) }.status

    @Test
    fun `Music's description is accepted, with the IV unpadded or padded`() {
        val captured = sdp()
        assertEquals(324, captured.size, "the capture's size (01 §6.17)")
        for (body in listOf(captured, sdp("a=aesiv" to "a=aesiv:$iv64Padded"))) {
            val description = SessionDescription.parse(body)
            assertContentEquals(keyBlob, description.fairPlayKeyBlob)
            assertContentEquals(iv, description.audioIv)
            assertEquals(352, description.framesPerPacket)
            assertNull(description.latencyMinFrames)
            assertNull(description.latencyMaxFrames)
            description.destroy()
            assertTrue(description.fairPlayKeyBlob.all { it == 0.toByte() })
            assertTrue(description.audioIv.all { it == 0.toByte() })
        }
    }

    @Test
    fun `LF endings, empty lines, a missing final newline and ignored lines are fine`() {
        val lines = sdpLines("a=rtpmap" to "a=rtpmap:96 applelossless")
        val body =
            (
                listOf("") + lines.take(5) + listOf("", "i=a session", "b=AS:1411", "a=other:thing") +
                    lines.drop(5) + listOf("a=rtpmap:97 L16/44100/2", "a=fmtp:97 anything")
            ).joinToString("\n")
        val description = SessionDescription.parse(body.toByteArray())
        assertEquals(352, description.framesPerPacket)
    }

    @Test
    fun `latencies are read and clamped, malformed ones ignored`() {
        val description =
            SessionDescription.parse(
                sdp("a=min-latency" to "a=min-latency:11025", "a=max-latency" to "a=max-latency:999999999"),
            )
        assertEquals(11025, description.latencyMinFrames)
        assertEquals(SetupRequest.MAX_LATENCY_FRAMES, description.latencyMaxFrames)
        val malformed =
            SessionDescription.parse(
                sdp("a=min-latency" to "a=min-latency:-1", "a=max-latency" to "a=max-latency:12345678901"),
            )
        assertNull(malformed.latencyMinFrames)
        assertNull(malformed.latencyMaxFrames)
    }

    @Test
    fun `the frame length is the stream's spf`() {
        for (frames in listOf(1, 4096)) {
            val fmtp = "a=fmtp:96 $frames 0 16 40 10 14 2 255 0 0 44100"
            assertEquals(frames, SessionDescription.parse(sdp("a=fmtp" to fmtp)).framesPerPacket)
        }
        val anyMaxFrameBytes = "a=fmtp:96 352 0 16 40 10 14 2 255 4294967295 1411200 44100"
        assertEquals(352, SessionDescription.parse(sdp("a=fmtp" to anyMaxFrameBytes)).framesPerPacket)
    }

    @Test
    fun `streams this receiver does not play are 415`() {
        val cases =
            listOf(
                sdp("a=rsaaeskey" to "a=rsaaeskey:AAAA"),
                sdp("a=fpaeskey" to null),
                sdp("a=fpaeskey" to null, "a=aesiv" to null),
                sdp("a=rtpmap" to "a=rtpmap:96 mpeg4-generic/44100/2"),
                sdp("a=rtpmap" to "a=rtpmap:96 L16/44100/2"),
                sdp("m=" to "m=video 0 RTP/AVP 96"),
                sdp("m=" to "m=audio 0 RTP/SAVP 96"),
                sdp("m=" to "m=audio 0 RTP/AVP 96 97"),
                sdp("m=" to "m=audio 0 RTP/AVP 97"),
                sdp("a=fmtp" to "a=fmtp:96 0 0 16 40 10 14 2 255 0 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 4097 0 16 40 10 14 2 255 0 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 1 16 40 10 14 2 255 0 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 24 40 10 14 2 255 0 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 1 255 0 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 10 2 255 0 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 48000"),
            )
        for ((index, body) in cases.withIndex()) assertEquals(415, status(body), "case $index")
    }

    @Test
    fun `malformed descriptions are 400`() {
        val longLine = "a=x:" + "y".repeat(SessionDescription.MAX_LINE_BYTES)
        val cases =
            listOf(
                ByteArray(0),
                "\r\n\r\n".toByteArray(),
                sdp("v=" to null),
                sdp("v=" to "v=1"),
                sdp("o=" to "v=0"),
                sdp("m=" to null),
                sdp("m=" to "m=audio 0 RTP/AVP"),
                sdp("a=rtpmap" to null),
                sdp("a=fmtp" to null),
                sdp("a=aesiv" to null),
                sdp("o=" to "a=rtpmap:96 AppleLossless"),
                sdp("o=" to "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100"),
                sdp("o=" to "a=fpaeskey:$key64"),
                sdp("o=" to "a=aesiv:$iv64"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 2 255 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100 0"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 4410x"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 -44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 2 255 4294967296 0 44100"),
                sdp("a=fmtp" to "a=fmtp:96 352 0 16 40 10 14 2 255 00000000000 0 44100"),
                sdp("a=fpaeskey" to "a=fpaeskey:" + key64.dropLast(4)),
                sdp("a=fpaeskey" to "a=fpaeskey:" + key64 + "AAAA"),
                sdp("a=fpaeskey" to "a=fpaeskey:" + "A".repeat(132)),
                sdp("a=fpaeskey" to "a=fpaeskey:" + key64.replaceRange(0, 1, "*")),
                sdp("a=fpaeskey" to "a=fpaeskey:"),
                sdp("a=aesiv" to "a=aesiv:" + iv64.dropLast(2)),
                sdp("a=aesiv" to "a=aesiv:" + Base64.getEncoder().encodeToString(ByteArray(17))),
                sdp("a=aesiv" to "a=aesiv:" + "A".repeat(36)),
                sdp("a=aesiv" to "a=aesiv:" + iv64 + "="),
                sdp("o=" to "V=0"),
                sdp("o=" to "o"),
                sdp("o=" to "=0"),
                sdp("o=" to "o=iTunes\u0000"),
                sdp("o=" to "o=iTunes\u007f"),
                sdp("o=" to longLine),
                (sdpLines().joinToString("\r\n") + "\r\n" + "a=x\r\n".repeat(SessionDescription.MAX_LINES))
                    .toByteArray(),
                ByteArray(SessionDescription.MAX_BYTES + 1) { 'a'.code.toByte() },
            )
        for ((index, body) in cases.withIndex()) assertEquals(400, status(body), "case $index")
    }

    @Test
    fun `repeated lines are 400`() {
        val repeated =
            listOf(
                "a=rtpmap:96 AppleLossless",
                "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100",
                "a=fpaeskey:$key64",
                "a=aesiv:$iv64",
                "v=0",
            )
        for (line in repeated) {
            val body = (sdpLines() + line).joinToString("") { "$it\r\n" }.toByteArray()
            assertEquals(400, status(body), line)
        }
        val twoMedia = (sdpLines() + "m=audio 0 RTP/AVP 96").joinToString("") { "$it\r\n" }.toByteArray()
        assertEquals(415, status(twoMedia), "a second stream is a stream this receiver does not play")
    }

    @Test
    fun `every truncation of the description is refused or parses`() {
        val valid = sdp()
        for (cut in 0 until valid.size) {
            try {
                SessionDescription.parse(valid.copyOf(cut))
                // Only a cut inside the last line's CRLF still describes the whole stream.
                assertTrue(cut >= valid.size - 2, "cut at $cut parsed")
            } catch (e: RtspException) {
                assertTrue(e.status == 400 || e.status == 415, "cut at $cut: ${e.status}")
            }
        }
    }

    @Test
    fun `random garbage and mutations are 400 or 415, never another exception`() {
        val random = Random(0x5D9)
        repeat(2_000) {
            val size = random.nextInt(0, SessionDescription.MAX_BYTES + 64)
            val body = random.nextBytes(size)
            val e = assertFailsWith<RtspException> { SessionDescription.parse(body) }
            assertTrue(e.status == 400 || e.status == 415, "status ${e.status}")
        }
        val valid = sdp()
        repeat(5_000) {
            val body = valid.copyOf()
            repeat(random.nextInt(1, 4)) { body[random.nextInt(body.size)] = random.nextInt(256).toByte() }
            try {
                SessionDescription.parse(body).destroy()
            } catch (e: RtspException) {
                assertTrue(e.status == 400 || e.status == 415, "status ${e.status}")
            }
        }
    }

    @Test
    fun `Music's Transport gives the sender's control and timing ports`() {
        val transport =
            ClassicTransport.parse("RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002")
        assertEquals(6001, transport.clientControlPort)
        assertEquals(6002, transport.clientTimingPort)
    }

    @Test
    fun `Transport variants that still mean UDP unicast record are accepted`() {
        val accepted =
            listOf(
                "RTP/AVP;unicast;mode=record;control_port=6001;timing_port=6002",
                "rtp/avp/udp; Unicast ; MODE=\"RECORD\" ;Control_Port=6001;timing_port=6002",
                "RTP/AVP/UDP;unicast;control_port=6001;timing_port=6002;client_port=6000-6001",
                "RTP/AVP/UDP;unicast;mode=record;control_port=6001;timing_port=6002,RTP/AVP/TCP;interleaved=0-1",
            )
        for (header in accepted) {
            val transport = ClassicTransport.parse(header)
            assertEquals(6001, transport.clientControlPort, header)
            assertEquals(6002, transport.clientTimingPort, header)
        }
    }

    @Test
    fun `ports that are absent or out of range are 0`() {
        val bad = listOf("", "0", "65536", "-1", "6001x", "123456", "+600", " ")
        for (value in bad) {
            val transport = ClassicTransport.parse("RTP/AVP/UDP;unicast;mode=record;control_port=$value;timing_port")
            assertEquals(0, transport.clientControlPort, "control_port=$value")
            assertEquals(0, transport.clientTimingPort)
        }
        assertEquals(65535, ClassicTransport.parse("RTP/AVP/UDP;control_port=65535").clientControlPort)
        assertEquals(0, ClassicTransport.parse("RTP/AVP/UDP").clientTimingPort)
    }

    @Test
    fun `other transports are 461, malformed ones 400`() {
        val unsupported =
            listOf(
                "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record",
                "RTP/SAVP;unicast;mode=record",
                "RAW/RAW/UDP;unicast;mode=record",
                "RTP/AVP/UDP;multicast;mode=record",
                "RTP/AVP/UDP;unicast;mode=play",
                "RTP/AVP/UDP;unicast;mode",
            )
        for (header in unsupported) assertEquals(461, transportStatus(header), header)
        val malformed =
            listOf(
                "",
                " ;mode=record",
                "RTP/AVP/UDP;;mode=record",
                "RTP/AVP/UDP;unicast;=6001",
                "RTP/AVP/UDP;control_port=6001;control_port=6002",
                "RTP/AVP/UDP;mode=record;MODE=record",
                "RTP/AVP/UDP" + ";x".repeat(ClassicTransport.MAX_PARAMETERS + 1),
            )
        for (header in malformed) assertEquals(400, transportStatus(header), header)
        ClassicTransport.parse("RTP/AVP/UDP" + (1..ClassicTransport.MAX_PARAMETERS).joinToString("") { ";p$it" })
    }

    @Test
    fun `random Transport headers are parsed or refused with 400 or 461`() {
        val random = Random(0x461)
        val alphabet = "RTP/AVUDrecodmplatsu_=;,\"0123456789 -"
        repeat(5_000) {
            val header = String(CharArray(random.nextInt(0, 200)) { alphabet[random.nextInt(alphabet.length)] })
            try {
                val transport = ClassicTransport.parse(header)
                assertTrue(transport.clientControlPort in 0..65535 && transport.clientTimingPort in 0..65535)
            } catch (e: RtspException) {
                assertTrue(e.status == 400 || e.status == 461, "status ${e.status} for $header")
            }
        }
    }

    @Test
    fun `the reply names the three local ports`() {
        assertEquals(
            "RTP/AVP/UDP;unicast;mode=record;server_port=50003;control_port=50004;timing_port=50001",
            ClassicTransport.reply(50003, 50004, 50001),
        )
    }
}
