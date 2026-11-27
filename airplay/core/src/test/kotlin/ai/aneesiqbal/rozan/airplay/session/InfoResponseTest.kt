package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistArray
import ai.aneesiqbal.rozan.airplay.plist.PlistBool
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistInteger
import ai.aneesiqbal.rozan.airplay.plist.PlistReal
import ai.aneesiqbal.rozan.airplay.plist.PlistString
import ai.aneesiqbal.rozan.airplay.plist.buildPlistDict
import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspProtocol
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class InfoResponseTest {
    private val key = ByteArray(32) { it.toByte() }
    private val deviceId = byteArrayOf(2, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val ad = Advertisement("Living Room", deviceId, key, PinMode.DIGEST, "p-i")

    private fun request(
        target: String,
        body: ByteArray = ByteArray(0),
    ) = RtspRequest("GET", target, RtspProtocol.RTSP_1_0, body = body)

    @Test
    fun `full info has every 01 section 6_1 key with the right types`() {
        val info = InfoResponse.full(ad, DisplayInfo(1920, 1080, 60), "d-uuid", -15.0)
        val expectedKeys =
            listOf(
                "deviceID",
                "macAddress",
                "pk",
                "features",
                "name",
                "pi",
                "vv",
                "statusFlags",
                "keepAliveLowPower",
                "sourceVersion",
                "keepAliveSendStatsAsBody",
                "model",
                "initialVolume",
                "audioLatencies",
                "audioFormats",
                "displays",
            )
        assertEquals(expectedKeys, info.entries.keys.toList())
        assertEquals("02:11:22:33:44:55", info.string("deviceID"))
        assertEquals("02:11:22:33:44:55", info.string("macAddress"))
        assertContentEquals(key, info.data("pk"))
        assertEquals(1384120038L, info.long("features"))
        assertEquals("Living Room", info.string("name"))
        assertEquals("p-i", info.string("pi"))
        assertEquals(2L, info.long("vv"))
        assertEquals(68L, info.long("statusFlags"))
        assertEquals(true, info.bool("keepAliveLowPower"))
        assertEquals(true, info.bool("keepAliveSendStatsAsBody"))
        assertEquals("220.68", info.string("sourceVersion"))
        assertEquals("AppleTV3,2", info.string("model"))
        assertIs<PlistReal>(info.entries["initialVolume"])
        assertEquals(-15.0, info.double("initialVolume"))
        val latencies = info.array("audioLatencies")!!.items.map { it as PlistDict }
        assertEquals(listOf(100L, 101L), latencies.map { it.long("type") })
        for (latency in latencies) {
            assertEquals("default", latency.string("audioType"))
            assertEquals(0L, latency.long("inputLatencyMicros"))
            assertEquals(PlistBool(false), latency.entries["outputLatencyMicros"])
        }
        val formats = info.array("audioFormats")!!.items.map { it as PlistDict }
        assertEquals(listOf(100L, 101L), formats.map { it.long("type") })
        for (format in formats) {
            assertEquals(67108860L, format.long("audioInputFormats"))
            assertEquals(67108860L, format.long("audioOutputFormats"))
        }
        val display = info.array("displays")!!.items.single() as PlistDict
        assertEquals("d-uuid", display.string("uuid"))
        val sizes = listOf("width" to 1920L, "height" to 1080L, "widthPixels" to 1920L, "heightPixels" to 1080L)
        for ((name, value) in sizes) {
            assertEquals(value, display.long(name), name)
        }
        assertEquals(0L, display.long("widthPhysical"))
        assertEquals(0L, display.long("heightPhysical"))
        assertEquals(false, display.bool("rotation"))
        assertEquals(1.0 / 60, display.double("refreshRate"))
        assertEquals(30L, display.long("maxFPS"))
        assertEquals(false, display.bool("overscanned"))
        assertEquals(14L, display.long("features"))
        // Round-trips through the binary form senders receive.
        assertEquals(info, BinaryPlist.decode(BinaryPlist.encode(info)))
    }

    @Test
    fun `statusFlags and features follow the mode`() {
        // 01 section 2.5: Mode P adds 0x8 PINRequired to 0x44; Mode D keeps 0x44.
        val pin = Advertisement("Living Room", deviceId, key, PinMode.PIN_PAIRING, "p-i")
        val pinInfo = InfoResponse.full(pin, DisplayInfo(1920, 1080, 60), "d-uuid", -15.0)
        assertEquals(76L, pinInfo.long("statusFlags"))
        assertEquals(1518337766L, pinInfo.long("features"))
        val digestInfo = InfoResponse.full(ad, DisplayInfo(1920, 1080, 60), "d-uuid", -15.0)
        assertEquals(68L, digestInfo.long("statusFlags"))
        assertEquals(1384120038L, digestInfo.long("features"))
        // The qualifier form carries the same per-mode TXT values.
        assertContentEquals(pin.airplayRdata(), InfoResponse.qualified(pin, setOf("txtAirPlay")).data("txtAirPlay"))
    }

    @Test
    fun `display is scaled to fit 1920x1080 keeping its aspect ratio`() {
        assertContentEquals(intArrayOf(1920, 1080), InfoResponse.offeredSize(DisplayInfo(3840, 2160, 60)))
        assertContentEquals(intArrayOf(1280, 720), InfoResponse.offeredSize(DisplayInfo(1280, 720, 60)))
        assertContentEquals(intArrayOf(1440, 1080), InfoResponse.offeredSize(DisplayInfo(2880, 2160, 60)))
        assertContentEquals(intArrayOf(1920, 810), InfoResponse.offeredSize(DisplayInfo(2560, 1080, 60)))
        assertContentEquals(intArrayOf(1920, 1080), InfoResponse.offeredSize(DisplayInfo(0, -1, 60)))
        val display =
            InfoResponse.full(ad, DisplayInfo(1920, 1080, 50), "u", 0.0).array("displays")!!.items[0] as PlistDict
        assertEquals(1.0 / 50, display.double("refreshRate"))
        val weird =
            InfoResponse.full(ad, DisplayInfo(1920, 1080, 1), "u", 0.0).array("displays")!!.items[0] as PlistDict
        assertEquals(1.0 / 60, weird.double("refreshRate"))
    }

    @Test
    fun `qualifiers come from the body or the query`() {
        assertNull(InfoResponse.qualifiers(request("/info")))
        assertEquals(setOf("txtAirPlay", "txtRAOP"), InfoResponse.qualifiers(request("/info?txtAirPlay&txtRAOP")))
        val body =
            BinaryPlist.encode(
                buildPlistDict { put("qualifier", PlistArray(listOf(PlistString("txtAirPlay"), PlistInteger(3)))) },
            )
        assertEquals(setOf("txtAirPlay"), InfoResponse.qualifiers(request("/info", body)))
        val qualified = InfoResponse.qualified(ad, setOf("txtAirPlay", "unknown"))
        assertEquals(listOf("txtAirPlay"), qualified.entries.keys.toList())
        assertContentEquals(ad.airplayRdata(), qualified.data("txtAirPlay"))
        assertContentEquals(ad.raopRdata(), InfoResponse.qualified(ad, setOf("txtRAOP")).data("txtRAOP"))
    }

    @Test
    fun `malformed qualifier bodies are 400`() {
        val notQualifiers = listOf(BinaryPlist.encode(PlistString("x")), BinaryPlist.encode(PlistDict(emptyMap())))
        val bodies = listOf("garbage".toByteArray()) + notQualifiers
        for (body in bodies) {
            assertEquals(400, assertFailsWith<RtspException> { InfoResponse.qualifiers(request("/info", body)) }.status)
        }
        val qualifier = PlistArray(listOf(PlistString("txtAirPlay")))
        val valid = BinaryPlist.encode(buildPlistDict { put("qualifier", qualifier) })
        val truncated = valid.copyOf(valid.size - 3)
        assertFailsWith<RtspException> { InfoResponse.qualifiers(request("/info", truncated)) }
    }
}
