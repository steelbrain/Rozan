package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistInteger
import ai.aneesiqbal.rozan.airplay.plist.PlistString
import ai.aneesiqbal.rozan.airplay.plist.PlistValue
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SetupRequestTest {
    private fun parse(value: PlistValue) = SetupRequest.parse(BinaryPlist.encode(value))

    private fun status(body: ByteArray) = assertFailsWith<RtspException> { SetupRequest.parse(body) }.status

    private fun streams(vararg streams: PlistDict) = buildPlistDict { put("streams", PlistArray(streams.toList())) }

    private fun audio(block: PlistDict.Builder.() -> Unit) =
        buildPlistDict {
            put("type", 96)
            block()
        }

    @Test
    fun `session part reads the 03 section 1_2 keys`() {
        val request =
            parse(
                buildPlistDict {
                    put("ekey", ByteArray(72) { 1 })
                    put("eiv", ByteArray(16) { 2 })
                    put("timingProtocol", "NTP")
                    put("timingPort", 60373)
                    put("deviceID", "DC:0C:5C:B7:D6:DA")
                    put("name", "Phone")
                    put("model", "iPhone16,2")
                    put("isScreenMirroringSession", true)
                    put("unknownKey", "ignored")
                },
            )
        val session = assertNotNull(request.session)
        assertContentEquals(ByteArray(72) { 1 }, session.ekey)
        assertContentEquals(ByteArray(16) { 2 }, session.eiv)
        assertEquals(60373, session.timingPort)
        assertEquals("DC:0C:5C:B7:D6:DA", session.deviceId)
        assertEquals("Phone", session.name)
        assertEquals("iPhone16,2", session.model)
        assertEquals(true, session.screenMirroring)
        assertNull(request.streams)
    }

    @Test
    fun `timing port outside 1-65535 or with PTP is treated as absent`() {
        fun timing(block: PlistDict.Builder.() -> Unit): Int =
            parse(
                buildPlistDict {
                    put("ekey", ByteArray(72))
                    put("eiv", ByteArray(16))
                    block()
                },
            ).session!!.timingPort
        assertEquals(0, timing {})
        assertEquals(0, timing { put("timingPort", 0) })
        assertEquals(0, timing { put("timingPort", 65536) })
        assertEquals(0, timing { put("timingPort", -1) })
        assertEquals(0, timing { put("timingPort", "123") })
        assertEquals(
            0,
            timing {
                put("timingPort", 5000)
                put("timingProtocol", "PTP")
            },
        )
        assertEquals(5000, timing { put("timingPort", 5000) })
    }

    @Test
    fun `ekey and eiv sizes are left to the handler after access control`() {
        val session =
            parse(
                buildPlistDict {
                    put("ekey", ByteArray(3))
                    put("eiv", ByteArray(99))
                },
            ).session
        assertEquals(3, session!!.ekey.size)
    }

    @Test
    fun `stream elements in request order`() {
        val request =
            parse(
                streams(
                    buildPlistDict {
                        put("type", 110)
                        put("streamConnectionID", PlistInteger.ofUnsigned(0xC0FFEE1234567890UL))
                    },
                    buildPlistDict { put("type", 103) },
                    audio {
                        put("ct", 8)
                        put("spf", 480)
                        put("controlPort", 63658)
                        put("latencyMin", 3750)
                        put("latencyMax", 999_999)
                        put("usingScreen", true)
                    },
                ),
            )
        assertNull(request.session)
        val streams = request.streams!!
        val mirroring = assertIs<StreamSetup.Mirroring>(streams[0])
        assertEquals(
            "C0FFEE1234567890",
            java.lang.Long
                .toHexString(mirroring.streamConnectionId)
                .uppercase(),
        )
        assertEquals(103L, assertIs<StreamSetup.Unsupported>(streams[1]).type)
        val audio = assertIs<StreamSetup.Audio>(streams[2]).request
        assertEquals(8, audio.compressionType)
        assertEquals(480, audio.framesPerPacket)
        assertEquals(44100, audio.sampleRate)
        assertEquals(63658, audio.clientControlPort)
        assertEquals(3750, audio.latencyMinFrames)
        assertEquals(176_400, audio.latencyMaxFrames)
        assertEquals(true, audio.usingScreen)
    }

    @Test
    fun `negative 8-byte streamConnectionID keeps its 64 bits`() {
        val request =
            parse(
                streams(
                    buildPlistDict {
                        put("type", 110)
                        put("streamConnectionID", -4_557_430_888_798_830_399L)
                    },
                ),
            )
        val id = assertIs<StreamSetup.Mirroring>(request.streams!![0]).streamConnectionId
        assertEquals("13889313184910721217", java.lang.Long.toUnsignedString(id))
    }

    @Test
    fun `audio parameters per 03 section 1_3_2`() {
        fun request(block: PlistDict.Builder.() -> Unit) =
            (parse(streams(audio(block))).streams!![0] as StreamSetup.Audio).request
        assertEquals(352, request { put("ct", 2) }.framesPerPacket)
        assertEquals(
            4096,
            request {
                put("ct", 2)
                put("spf", 4096)
            }.framesPerPacket,
        )
        assertEquals(480, request { put("ct", 8) }.framesPerPacket)
        assertEquals(
            0,
            request {
                put("ct", 2)
                put("controlPort", 70000)
            }.clientControlPort,
        )
        assertEquals(
            0,
            request {
                put("ct", 2)
                put("latencyMin", -5)
            }.latencyMinFrames,
        )
        assertNull(request { put("ct", 2) }.latencyMaxFrames)
        assertEquals(
            44100,
            request {
                put("ct", 2)
                put("sr", 44100)
            }.sampleRate,
        )
    }

    @Test
    fun `invalid audio parameters are 400`() {
        val bad =
            listOf<PlistDict.Builder.() -> Unit>(
                {},
                { put("ct", 4) },
                { put("ct", 1) },
                { put("ct", "2") },
                {
                    put("ct", 8)
                    put("spf", 352)
                },
                {
                    put("ct", 2)
                    put("spf", 0)
                },
                {
                    put("ct", 2)
                    put("spf", 4097)
                },
                {
                    put("ct", 2)
                    put("sr", 48000)
                },
            )
        for (block in bad) assertEquals(400, status(BinaryPlist.encode(streams(audio(block)))))
    }

    @Test
    fun `structural errors are 400`() {
        val mirroring =
            buildPlistDict {
                put("type", 110)
                put("streamConnectionID", 1)
            }
        val cases =
            listOf(
                ByteArray(0),
                "not a plist".toByteArray(),
                hex("62706c6973743030"),
                BinaryPlist.encode(PlistString("x")),
                BinaryPlist.encode(PlistDict(emptyMap())),
                BinaryPlist.encode(buildPlistDict { put("streams", "x") }),
                BinaryPlist.encode(buildPlistDict { put("ekey", ByteArray(72)) }),
                BinaryPlist.encode(streams(mirroring, mirroring)),
                BinaryPlist.encode(streams(*Array(5) { buildPlistDict { put("type", 120) } })),
                BinaryPlist.encode(buildPlistDict { put("streams", PlistArray(listOf(PlistString("x")))) }),
                BinaryPlist.encode(streams(buildPlistDict { put("ct", 2) })),
                BinaryPlist.encode(streams(buildPlistDict { put("type", 110) })),
            )
        for (body in cases) assertEquals(400, status(body))
        val valid = BinaryPlist.encode(streams(mirroring))
        for (cut in 1 until valid.size) {
            try {
                SetupRequest.parse(valid.copyOf(cut))
            } catch (e: RtspException) {
                assertEquals(400, e.status)
            }
        }
    }
}
