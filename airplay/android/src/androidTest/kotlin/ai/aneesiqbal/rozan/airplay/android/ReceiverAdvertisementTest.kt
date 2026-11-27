package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.NsdBrowser.txt
import ai.aneesiqbal.rozan.airplay.android.ReceiverStatus.Advertising
import ai.aneesiqbal.rozan.airplay.android.ReceiverStatus.Failure
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.random.Random

/**
 * The real receiver in the `:airplay` process, seen from outside: what another
 * device on the network discovers, and what the status screens read when the
 * control port is taken.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverAdvertisementTest {
    private val settings = AirPlaySettings(testContext)
    private val status = ReceiverStatus(testContext)
    private val port = ReceiverConfig.DEFAULT_CONTROL_PORT

    @Before
    fun stopReceiver() {
        settings.enabled = false
        awaitStatus { !it.running }
    }

    @After
    fun tearDown() {
        settings.enabled = false
    }

    @Test
    fun theReceiverIsDiscoverableAsAirPlayAndRaop() {
        val name = "Rozan Ad ${Random.nextInt(0x10000).toString(16)}"
        settings.deviceName = name
        settings.enabled = true
        ReceiverService.start(testContext)
        val announced = awaitStatus { it.receiverName == name && it.advertising == Advertising.ADVERTISING }
        val deviceId = requireNotNull(announced.deviceId)
        assertEquals(port, announced.port)

        val airplay = NsdBrowser.discover(AIRPLAY) { it.serviceName == name }
        assertNotNull("$AIRPLAY \"$name\" discovered", airplay)
        val airplayInfo = NsdBrowser.resolve(requireNotNull(airplay))
        assertEquals(port, airplayInfo.port)
        val txt = airplayInfo.txt()
        assertTrue("deviceid ${txt["deviceid"]} is $deviceId", deviceId.equals(txt["deviceid"], ignoreCase = true))
        val features = requireNotNull(txt["features"]) { "features in $txt" }
        assertTrue("features $features", FEATURES.matches(features))
        val pk = requireNotNull(txt["pk"]) { "pk in $txt" }
        assertTrue("pk $pk", PUBLIC_KEY.matches(pk))

        // RAOP instances are named "<device id without colons>@<name>".
        val raopName = deviceId.replace(":", "").uppercase() + "@" + name
        val raop = NsdBrowser.discover(RAOP) { it.serviceName == raopName }
        assertNotNull("$RAOP \"$raopName\" discovered", raop)
        val raopInfo = NsdBrowser.resolve(requireNotNull(raop))
        assertEquals(port, raopInfo.port)
        assertEquals("same features", features, raopInfo.txt()["ft"])
        assertEquals("same identity key", pk, raopInfo.txt()["pk"])
    }

    @Test
    fun aTakenControlPortIsReportedAndRetried() {
        ServerSocket().use { other ->
            other.bind(InetSocketAddress(port))
            settings.enabled = true
            ReceiverService.start(testContext)
            val blocked = awaitStatus { it.failure == Failure.PORT_IN_USE }
            assertEquals(port, blocked.port)
            assertTrue("service still running", blocked.running)
        }
        // The port is free again. The next start, as when a status screen comes up, tries again.
        ReceiverService.start(testContext)
        awaitStatus { it.failure == null && it.advertising == Advertising.ADVERTISING }
    }

    private fun awaitStatus(predicate: (ReceiverStatus.Snapshot) -> Boolean): ReceiverStatus.Snapshot {
        assertTrue("status (${status.current})", eventually(STATUS_TIMEOUT_MILLIS) { predicate(status.current) })
        return status.current
    }

    private companion object {
        const val AIRPLAY = "_airplay._tcp"
        const val RAOP = "_raop._tcp"
        const val STATUS_TIMEOUT_MILLIS = 30_000L
        val FEATURES = Regex("0x[0-9A-F]+,0x[0-9A-F]+")
        val PUBLIC_KEY = Regex("[0-9a-f]{64}")
    }
}
