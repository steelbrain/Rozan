package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair
import ai.aneesiqbal.rozan.airplay.hex
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdvertisementTest {
    private val deviceId = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val seedKey = Ed25519KeyPair.fromSeed(ByteArray(32) { (0x80 + it).toByte() }).publicKey

    /** The `pi` of the 01 section 2.6 vectors. */
    private val vectorPi = "2e388006-13ba-4041-9a67-25dd4a43d536"

    private fun advertisement(
        mode: PinMode = PinMode.PIN_PAIRING,
        name: String = "Living Room",
    ) = Advertisement(name, deviceId, seedKey, mode, vectorPi)

    @Test
    fun `airplay rdata matches the 01 section 2_6 Mode P vector`() {
        assertEquals("cd14b37f956e953194ff7fb73b3d81dcc561d61a7538094b7c3e1a643ee5f3aa", seedKey.toHex())
        val expected =
            hex(
                "1a64657669636569643d30323a31313a32323a33333a34343a35351766656174" +
                    "757265733d307835413746464545362c3078300870773d66616c736509666c61" +
                    "67733d307863106d6f64656c3d4170706c655456332c3243706b3d6364313462" +
                    "3337663935366539353331393466663766623733623364383164636335363164" +
                    "3631613735333830393462376333653161363433656535663361612770693d32" +
                    "653338383030362d313362612d343034312d396136372d323564643461343364" +
                    "3533360e737263766572733d3232302e36380476763d32",
            )
        val rdata = advertisement(PinMode.PIN_PAIRING).airplayRdata()
        assertEquals(215, rdata.size)
        assertEquals(expected.toHex(), rdata.toHex())
    }

    @Test
    fun `Mode P puts PINRequired and no password on _airplay and keeps pw=true on RAOP`() {
        // 01 section 2.5: on _airplay, pw=false with 0x8 PINRequired gets the on-screen code entry on macOS 27,
        // with RAOP still at pw=true and sf=0x8c (02 section 8.6 test 6).
        val pin = advertisement(PinMode.PIN_PAIRING)
        val airplay = pin.airplayTxt.toMap()
        val raop = pin.raopTxt.toMap()
        assertEquals("0x5A7FFEE6,0x0", airplay["features"])
        assertEquals(1518337766L, pin.features)
        assertEquals("false", airplay["pw"])
        assertEquals("0xc", airplay["flags"])
        assertEquals("true", raop["pw"])
        assertEquals("0x8c", raop["sf"])
        assertEquals(airplay["features"], raop["ft"])
    }

    @Test
    fun `Mode D advertises a password on both records`() {
        val digest = advertisement(PinMode.DIGEST)
        val airplay = digest.airplayTxt.toMap()
        val raop = digest.raopTxt.toMap()
        assertEquals("0x527FFEE6,0x0", airplay["features"])
        assertEquals(1384120038L, digest.features)
        assertEquals("true", airplay["pw"])
        assertEquals("0x4", airplay["flags"])
        assertEquals("true", raop["pw"])
        assertEquals("0x84", raop["sf"])
        assertEquals(airplay["features"], raop["ft"])
    }

    @Test
    fun `airplay record has the 01 section 2_2 keys in order`() {
        val txt = advertisement(PinMode.DIGEST).airplayTxt
        assertEquals(
            listOf(
                "deviceid" to "02:11:22:33:44:55",
                "features" to "0x527FFEE6,0x0",
                "pw" to "true",
                "flags" to "0x4",
                "model" to "AppleTV3,2",
                "pk" to seedKey.toHex(),
                "pi" to vectorPi,
                "srcvers" to "220.68",
                "vv" to "2",
            ),
            txt,
        )
    }

    @Test
    fun `raop record has the 01 section 2_3 keys in order`() {
        val txt = advertisement(PinMode.DIGEST).raopTxt
        assertEquals(
            listOf(
                "ch" to "2",
                "cn" to "0,1,2,3",
                "da" to "true",
                "et" to "0,3,5",
                "vv" to "2",
                "ft" to "0x527FFEE6,0x0",
                "am" to "AppleTV3,2",
                "md" to "0,1,2",
                "rhd" to "5.6.0.0",
                "pw" to "true",
                "sf" to "0x84",
                "sr" to "44100",
                "ss" to "16",
                "sv" to "false",
                "tp" to "UDP",
                "txtvers" to "1",
                "vs" to "220.68",
                "vn" to "65537",
                "pk" to seedKey.toHex(),
            ),
            txt,
        )
        assertEquals(
            advertisement(PinMode.DIGEST).raopRdata().size,
            txt.sumOf { 1 + "${it.first}=${it.second}".length },
        )
    }

    @Test
    fun `records use the same port and the RAOP instance prefix`() {
        val records = advertisement(name = "Living Room").records(7000)
        assertEquals(2, records.size)
        assertEquals("Living Room", records[0].instanceName)
        assertEquals("_airplay._tcp", records[0].serviceType)
        assertEquals("021122334455@Living Room", records[1].instanceName)
        assertEquals("_raop._tcp", records[1].serviceType)
        assertTrue(records.all { it.port == 7000 })
    }

    @Test
    fun `names are cut at local and to 50 UTF-8 bytes on a code point boundary`() {
        assertEquals("Den", IdentityText.advertisedName("Den.local"))
        assertEquals("Den", IdentityText.advertisedName("  Den.local.extra "))
        assertEquals("a".repeat(50), IdentityText.advertisedName("a".repeat(80)))
        // 25 two-byte characters fill exactly 50 bytes; the 26th does not fit.
        assertEquals("é".repeat(25), IdentityText.advertisedName("é".repeat(30)))
        // A four-byte emoji at byte 48 does not fit, so the name stops before it.
        val name = "a".repeat(48) + "😀"
        assertEquals("a".repeat(48), IdentityText.advertisedName(name))
        assertEquals("AirPlay", IdentityText.advertisedName(".local"))
        assertEquals(63, "021122334455@".length + IdentityText.advertisedName("x".repeat(99)).length)
    }

    @Test
    fun `identity text forms`() {
        assertEquals("02:11:22:33:44:55", IdentityText.deviceId(deviceId))
        assertEquals("021122334455", IdentityText.raopPrefix(deviceId))
        val ff = ByteArray(6) { 0xFF.toByte() }
        assertEquals("ff:ff:ff:ff:ff:ff", IdentityText.deviceId(ff))
        assertEquals("FFFFFFFFFFFF", IdentityText.raopPrefix(ff))
    }

    @Test
    fun `derived UUIDs are stable, distinct per label and version 4`() {
        val pi = IdentityText.derivedUuid(deviceId, "pi")
        assertEquals(pi, IdentityText.derivedUuid(deviceId.copyOf(), "pi"))
        assertNotEquals(pi, IdentityText.derivedUuid(deviceId, "display"))
        assertNotEquals(pi, IdentityText.derivedUuid(ByteArray(6), "pi"))
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(pi), pi)
    }

    @Test
    fun `TXT entries over 255 bytes are refused`() {
        assertEquals(256, TxtRdata.encode(listOf("k" to "v".repeat(253))).size)
        assertFailsWith<IllegalArgumentException> { TxtRdata.encode(listOf("k" to "v".repeat(254))) }
    }
}
